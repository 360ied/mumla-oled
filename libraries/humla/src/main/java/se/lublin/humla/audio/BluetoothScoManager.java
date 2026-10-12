/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * Owns the Bluetooth SCO (HFP) audio route for a voice session.
 *
 * <p>SCO link bring-up is asynchronous and unreliable: the platform call
 * returns before the link exists, establishment takes seconds, and failure is
 * silent without a state callback. This manager hides that behind a small state
 * machine with a connect timeout, one retry, and automatic fallback reporting.
 *
 * <p>SCO link bring-up runs on the single platform backend:
 * {@code setCommunicationDevice()} with a {@code TYPE_BLUETOOTH_SCO} device
 * from {@code getAvailableCommunicationDevices()}, observed via
 * {@code OnCommunicationDeviceChangedListener}.
 *
 * <p>Locking: {@code mState}, {@code mAttempts}, {@code mGeneration}, {@code mRouteHeld},
 * {@code mRouteHeldEpoch}, and {@code mDeviceListener} are guarded by the
 * manager monitor. Bring-up runs outside the monitor and re-validates by
 * session epoch after each IPC step, so a concurrent {@code stop()} aborts
 * the sequence instead of interleaving with it. Observer register/teardown
 * take the monitor themselves; their IPC is idempotent and fast. Route
 * release always runs outside the monitor. All listener
 * callbacks run outside the monitor: {@code onScoConnected} and
 * {@code onScoDisconnected} originate on main-thread platform callbacks;
 * {@code onScoFailed} is always posted to the main handler and dropped when
 * its session generation is stale, so {@code stop()} truly never callbacks.
 *
 * <p>AudioManager mode is deliberately NOT owned here. The service route
 * policy ({@code HumlaService}) is the single owner of
 * {@code MODE_IN_COMMUNICATION}: it holds the mode for the whole voice-route
 * duration, which covers confirmed SCO sessions. Setting it here as well
 * made the service classify our own hold as externally owned and give up
 * its claim, stranding the mode on later media switches — so this manager
 * only selects and releases the SCO route, never the mode.
 */
public class BluetoothScoManager {
    private static final String TAG = "BluetoothScoManager";

    /** Per-attempt budget for the link to report connected before retry/fail. */
    private static final long CONNECT_TIMEOUT_MS = 8000;
    /** Total bring-up attempts (initial + one retry) before reporting failure. */
    private static final int MAX_ATTEMPTS = 2;

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_ACTIVE = 2;

    /** The grant is missing or was revoked mid-bring-up; surface a permission hint. */
    public static final String REASON_PERMISSION = "permission";
    /** No SCO communication device is available (none paired/connected). */
    public static final String REASON_NO_DEVICE = "no-device";
    /** The platform refused the device selection. */
    public static final String REASON_REFUSED = "refused";
    /** The link did not report connected within the attempt budget. */
    public static final String REASON_TIMEOUT = "timeout";
    /** Any other platform failure. */
    public static final String REASON_ERROR = "error";

    public interface Listener {
        /** The SCO link is up; the audio pipeline may now select the voice-call route. */
        void onScoConnected();
        /** A live link dropped; the pipeline should fall back to the previous route. */
        void onScoDisconnected();
        /**
         * The link never came up.
         * @param reason one of {@code REASON_*}.
         */
        void onScoFailed(String reason);
    }

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Injected budgets (ODD-23): production uses the static defaults above;
    // a future device harness can narrow the timeout without touching the machine.
    private final long mConnectTimeoutMs;
    private final int mMaxAttempts;

    private int mState = STATE_IDLE;
    private int mAttempts;
    private int mGeneration;
    private boolean mRouteHeld;
    // Generation that holds the route request (see markRouteHeld): stale
    // abort paths only release their own epoch so a newer session's route
    // is never cleared by an older session's teardown.
    private int mRouteHeldEpoch = -1;

    private AudioManager.OnCommunicationDeviceChangedListener mDeviceListener;

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onConnectTimeout();
        }
    };

    /**
     * Pure retry-budget decision shared by the timeout and stack-error paths
     * (ODD-23): another bring-up attempt remains while fewer than
     * {@code maxAttempts} attempts have run. Refusal and no-device fail
     * unconditionally in their callers — no budget branch exists to drift.
     * Package-visible for the JVM truth-table test; not part of the
     * production API.
     */
    static boolean shouldRetryBringUp(int attempts, int maxAttempts) {
        return attempts < maxAttempts;
    }

    public BluetoothScoManager(Context context, Listener listener) {
        this(context, listener, CONNECT_TIMEOUT_MS, MAX_ATTEMPTS);
    }

    BluetoothScoManager(Context context, Listener listener,
                        long connectTimeoutMs, int maxAttempts) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(listener, "listener");
        if (connectTimeoutMs < 0) {
            throw new IllegalArgumentException("connectTimeoutMs must be >= 0");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        mContext = context.getApplicationContext();
        mListener = listener;
        mConnectTimeoutMs = connectTimeoutMs;
        mMaxAttempts = maxAttempts;
        AudioManager audioManager =
                (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        Objects.requireNonNull(audioManager, "AudioManager");
        mAudioManager = audioManager;
    }

    /**
     * Begins SCO bring-up. No-op while starting or active. Every failure —
     * including pre-flight checks — reports through a posted
     * {@link Listener#onScoFailed} (never {@code onScoDisconnected}), so
     * callers must not assume the link is up when this returns.
     */
    public void start() {
        synchronized (this) {
            if (mState != STATE_IDLE) {
                return;
            }
            // Flip first: pre-flight failures below report through fail(), which
            // only notifies when leaving a non-IDLE state. Bump the generation
            // so callbacks posted by earlier sessions are dropped on delivery.
            // A new session never inherits a stale route hold.
            mState = STATE_STARTING;
            mAttempts = 0;
            mRouteHeld = false;
            mRouteHeldEpoch = -1;
            ++mGeneration;
        }
        if (!hasConnectPermission()) {
            fail(REASON_PERMISSION);
            return;
        }
        // Outside the monitor: bring-up IPC must never pin the lock that
        // main-thread callbacks and stop() need. (No mode work here: the
        // service route policy owns MODE_IN_COMMUNICATION; see class note.)
        attemptBringUp();
    }

    /**
     * Tears down the link. Idempotent and safe to call when idle; a failed
     * bring-up may still hold a requested route, so release is guarded by
     * the hold flag. Never fires listener callbacks: the generation bump
     * drops any already-posted failure on delivery. AudioManager mode is
     * owned by the service route policy, never touched here (see class note).
     */
    public void stop() {
        synchronized (this) {
            // One atomic unit with the flip: callback removal, observer
            // teardown, and the generation bump cannot interleave with a
            // concurrent bring-up's register/arm, matching fail() and
            // dropActiveLink(). Unregister calls are idempotent and fast;
            // route release stays outside the monitor.
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            mState = STATE_IDLE;
            ++mGeneration;
        }
        // Guarded release: a stale hold must never clear another app's
        // routing selection.
        releaseRouteIfHeld();
    }

    /** True when no bring-up is in progress and no link is up. */
    public synchronized boolean isIdle() {
        return mState == STATE_IDLE;
    }

    /** True only once the link has reported connected and not since dropped. */
    public synchronized boolean isActive() {
        return mState == STATE_ACTIVE;
    }

    // Bring-up runs outside the monitor (see class locking note), keyed by
    // session epoch: every step re-checks that its session is still current.
    private void attemptBringUp() {
        final int epoch;
        synchronized (this) {
            mAttempts++;
            epoch = mGeneration;
        }
        String failure = bringUpModern(epoch);
        if (failure != null) {
            fail(failure);
        }
    }

    private boolean isStartingEpoch(int epoch) {
        synchronized (this) {
            return mState == STATE_STARTING && epoch == mGeneration;
        }
    }

    // Returns a REASON_* on failure, null on success or concurrent-stop abort
    // (stop() owns teardown there, so there is nothing to report).
    private String bringUpModern(int epoch) {
        final AudioDeviceInfo sco;
        try {
            sco = findScoDevice();
        } catch (SecurityException e) {
            Log.w(TAG, "getAvailableCommunicationDevices denied", e);
            return REASON_PERMISSION;
        } catch (Exception e) {
            Log.w(TAG, "getAvailableCommunicationDevices threw", e);
            return REASON_ERROR;
        }
        if (!isStartingEpoch(epoch)) {
            return null;
        }
        if (sco == null) {
            return REASON_NO_DEVICE;
        }
        try {
            if (!registerDeviceListener()) {
                return REASON_ERROR;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Device listener denied", e);
            return REASON_PERMISSION;
        }
        if (!isStartingEpoch(epoch)) {
            // Stopped after registration: unregister what was just added.
            teardownObservers();
            return null;
        }
        boolean accepted;
        try {
            accepted = mAudioManager.setCommunicationDevice(sco);
        } catch (SecurityException e) {
            Log.w(TAG, "setCommunicationDevice denied", e);
            return REASON_PERMISSION;
        } catch (Exception e) {
            Log.w(TAG, "setCommunicationDevice threw", e);
            return REASON_ERROR;
        }
        if (!accepted) {
            return REASON_REFUSED;
        }
        markRouteHeld();
        if (!isStartingEpoch(epoch)) {
            // Stopped after the request landed: undo it, since stop() ran
            // before the request existed. Epoch-scoped: a newer session may
            // hold its own route by now, which this stale path must not clear.
            releaseRouteIfHeldForEpoch(epoch);
            return null;
        }
        if (isScoActiveNow()) {
            // Already on SCO (preselected route or idempotent re-request):
            // complete now instead of burning the timeout waiting for a
            // change event that will never come. The timeout stays armed as
            // fallback; setActive (or stop) removes it.
            armTimeout();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isStartingEpoch(epoch)) {
                        setActive();
                    } else {
                        Log.i(TAG, "Dropping stale SCO fast-path activation");
                    }
                }
            });
            return null;
        }
        armTimeout();
        return null;
    }

    // Whether SCO audio is currently routed, independent of the state
    // machine. Fail-closed false: doubt falls through to event-driven setup.
    private boolean isScoActiveNow() {
        try {
            AudioDeviceInfo current = mAudioManager.getCommunicationDevice();
            return current != null
                    && current.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        } catch (Exception e) {
            return false;
        }
    }

    private AudioDeviceInfo findScoDevice() {
        List<AudioDeviceInfo> devices = mAudioManager.getAvailableCommunicationDevices();
        if (devices == null) {
            return null;
        }
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return device;
            }
        }
        return null;
    }

    /**
     * Whether an SCO-capable device is currently available for bring-up,
     * without starting anything. Used by the service attach trigger as a
     * fallback when the added array carries no SCO entry itself (A2DP-first
     * stagger, HALs that surface SCO only in the availability query).
     * Fail-closed false on any error: doubt falls through to event-driven
     * setup, never to a blind bring-up attempt. Public for the service
     * route policy (same library, adjacent package).
     */
    public boolean isScoDeviceAvailable() {
        try {
            return findScoDevice() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void onModernDeviceChanged(AudioDeviceInfo device, int epoch) {
        final boolean scoNow;
        final int state;
        final int currentEpoch;
        synchronized (this) {
            state = mState;
            currentEpoch = mGeneration;
            scoNow = device != null && device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        }
        if (epoch != currentEpoch) {
            // Queued callback from a torn-down session: a newer start() owns
            // the route now and this stale event must not move the machine.
            return;
        }
        if (state == STATE_STARTING && scoNow) {
            setActive();
        } else if (state == STATE_ACTIVE && !scoNow) {
            dropActiveLink();
        }
        // A system move to a non-SCO device mid-bring-up is left to burn the
        // attempt timeout: selections flap transiently during routing, and
        // failing fast here would turn every blip into a fallback.
    }

    private void onConnectTimeout() {
        final boolean retry;
        synchronized (this) {
            if (mState != STATE_STARTING) {
                return;
            }
            retry = shouldRetryBringUp(mAttempts, mMaxAttempts);
        }
        if (!retry) {
            fail(REASON_TIMEOUT);
            return;
        }
        Log.i(TAG, "SCO bring-up attempt timed out, retrying");
        // Clear observers and any half-requested route before re-attempting.
        teardownObservers();
        releaseRouteIfHeld();
        synchronized (this) {
            if (mState != STATE_STARTING) {
                return;
            }
        }
        // Outside the monitor: bring-up IPC must never pin the lock.
        attemptBringUp();
    }

    private void setActive() {
        synchronized (this) {
            if (mState != STATE_STARTING) {
                return;
            }
            mState = STATE_ACTIVE;
            mHandler.removeCallbacks(mTimeoutRunnable);
        }
        Log.i(TAG, "Bluetooth SCO connected");
        // Main-thread origin (broadcast, device listener, or timeout), so a
        // direct call preserves connected-then-callback ordering.
        mListener.onScoConnected();
    }

    private void dropActiveLink() {
        synchronized (this) {
            if (mState != STATE_ACTIVE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
        }
        releaseRouteIfHeld();
        Log.i(TAG, "Bluetooth SCO disconnected");
        // Main-thread origin; see setActive.
        mListener.onScoDisconnected();
    }

    private void fail(String reason) {
        final int generation;
        synchronized (this) {
            if (mState == STATE_IDLE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            generation = mGeneration;
        }
        releaseRouteIfHeld();
        Log.w(TAG, "Bluetooth SCO failed: " + reason);
        // Always posted: failures can surface on the start() caller's thread.
        // The generation check drops delivery when stop() or a newer start()
        // has since moved on, honoring stop()'s never-callbacks contract.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                final boolean current;
                synchronized (BluetoothScoManager.this) {
                    current = generation == mGeneration;
                }
                if (current) {
                    mListener.onScoFailed(reason);
                } else {
                    Log.i(TAG, "Dropping stale SCO failure callback: " + reason);
                }
            }
        });
    }

    // Releases only when this session holds a route request, so a teardown
    // that follows a completed fail()/drop() cannot clear another app's
    // routing selection.
    private void releaseRouteIfHeld() {
        final boolean held;
        synchronized (this) {
            held = mRouteHeld;
            mRouteHeld = false;
            mRouteHeldEpoch = -1;
        }
        if (held) {
            releaseRoute();
        }
    }

    private void markRouteHeld() {
        synchronized (this) {
            mRouteHeld = true;
            mRouteHeldEpoch = mGeneration;
        }
    }

    /**
     * Epoch-scoped release for stale abort paths: only clears the hold when
     * it still belongs to the given session generation. Authoritative
     * teardowns (stop/fail/drop/timeout) use {@link #releaseRouteIfHeld}.
     */
    private void releaseRouteIfHeldForEpoch(int epoch) {
        final boolean release;
        synchronized (this) {
            release = mRouteHeld && mRouteHeldEpoch == epoch;
            if (release) {
                mRouteHeld = false;
                mRouteHeldEpoch = -1;
            }
        }
        if (release) {
            releaseRoute();
        }
    }

    private void releaseRoute() {
        try {
            mAudioManager.clearCommunicationDevice();
        } catch (Exception e) {
            Log.w(TAG, "SCO route release threw", e);
        }
    }

    private void armTimeout() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        mHandler.postDelayed(mTimeoutRunnable, mConnectTimeoutMs);
    }

    private boolean hasConnectPermission() {
        return mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Self-locking: safe from bring-up (outside the monitor) and teardown paths.
    // Returns false when registration threw, in which case bring-up must fail
    // fast: without an observer nothing can ever report the link connected.
    private synchronized boolean registerDeviceListener() {
        if (mDeviceListener != null) {
            return true;
        }
        final int epoch = mGeneration;
        mDeviceListener = new AudioManager.OnCommunicationDeviceChangedListener() {
            @Override
            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                onModernDeviceChanged(device, epoch);
            }
        };
        try {
            mAudioManager.addOnCommunicationDeviceChangedListener(
                    mContext.getMainExecutor(), mDeviceListener);
        } catch (SecurityException e) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener denied", e);
            mDeviceListener = null;
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener threw", e);
            mDeviceListener = null;
            return false;
        }
        return true;
    }

    private synchronized void teardownObservers() {
        if (mDeviceListener != null) {
            try {
                mAudioManager.removeOnCommunicationDeviceChangedListener(mDeviceListener);
            } catch (Exception e) {
                Log.w(TAG, "Device listener removal threw", e);
            }
            mDeviceListener = null;
        }
    }
}
