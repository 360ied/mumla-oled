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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * Owns the Bluetooth SCO (HFP) audio route for a voice session.
 *
 * <p>SCO link bring-up is asynchronous and unreliable: {@code startBluetoothSco()}
 * returns before the link exists, establishment takes seconds, and failure is
 * silent without a state broadcast. This manager hides that behind a small state
 * machine with a connect timeout, one retry, and automatic fallback reporting.
 *
 * <p>Two platform backends sit behind one interface, selected by API level:
 * <ul>
 *   <li>API 31+: {@code setCommunicationDevice()} with a {@code TYPE_BLUETOOTH_SCO}
 *       device from {@code getAvailableCommunicationDevices()}, observed via
 *       {@code OnCommunicationDeviceChangedListener}.</li>
 *   <li>Below 31: legacy {@code startBluetoothSco()} / {@code stopBluetoothSco()},
 *       observed via the {@code ACTION_SCO_AUDIO_STATE_UPDATED} broadcast
 *       (available since API 14, so no intent fallback is needed).</li>
 * </ul>
 *
 * <p>Locking: {@code mState}, {@code mAttempts}, {@code mSavedMode},
 * {@code mModeOwned}, {@code mGeneration}, {@code mRouteHeld},
 * {@code mLegacyReceiver}, and {@code mDeviceListener} are guarded by the
 * manager monitor. Bring-up runs outside the monitor and re-validates by
 * session epoch after each IPC step, so a concurrent {@code stop()} aborts
 * the sequence instead of interleaving with it. Observer register/teardown
 * take the monitor themselves; their IPC is idempotent and fast. Route
 * release and mode restore always run outside the monitor. All listener
 * callbacks run outside the monitor: {@code onScoConnected} and
 * {@code onScoDisconnected} originate on main-thread platform callbacks;
 * {@code onScoFailed} is always posted to the main handler and dropped when
 * its session generation is stale, so {@code stop()} truly never callbacks.
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
    private int mSavedMode = AudioManager.MODE_NORMAL;
    private boolean mModeOwned;
    private int mGeneration;
    private boolean mRouteHeld;

    private BroadcastReceiver mLegacyReceiver;
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
            ++mGeneration;
        }
        if (!hasConnectPermission()) {
            fail(REASON_PERMISSION);
            return;
        }
        final int currentMode;
        try {
            currentMode = mAudioManager.getMode();
        } catch (Exception e) {
            Log.w(TAG, "getMode threw", e);
            fail(REASON_ERROR);
            return;
        }
        synchronized (this) {
            if (mState != STATE_STARTING) {
                return; // stopped concurrently; nothing was changed yet.
            }
            mSavedMode = currentMode;
        }
        try {
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            Log.w(TAG, "setMode threw", e);
            fail(REASON_ERROR);
            return;
        }
        final boolean stopped;
        synchronized (this) {
            stopped = mState != STATE_STARTING;
            if (!stopped) {
                mModeOwned = true;
            }
        }
        if (stopped) {
            // Stopped while setMode was in flight: undo the mode change,
            // which stop() could not know about. Outside the monitor: IPC.
            try {
                if (mAudioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                    mAudioManager.setMode(currentMode);
                }
            } catch (Exception e) {
                Log.w(TAG, "setMode undo threw", e);
            }
            return;
        }
        // Outside the monitor: bring-up IPC must never pin the lock that
        // main-thread callbacks and stop() need.
        attemptBringUp();
    }

    /**
     * Tears down the link and restores the previous audio mode. Idempotent and
     * safe to call when idle; a failed bring-up may still hold a requested
     * route, so release is guarded by the hold flag. Never fires listener callbacks:
     * the generation bump drops any already-posted failure on delivery.
     */
    public void stop() {
        final int savedMode;
        final boolean owned;
        synchronized (this) {
            // One atomic unit with the flip: callback removal, observer
            // teardown, and the generation bump cannot interleave with a
            // concurrent bring-up's register/arm, matching fail() and
            // dropActiveLink(). Unregister calls are idempotent and fast;
            // route release and mode restore stay outside the monitor.
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            mState = STATE_IDLE;
            savedMode = mSavedMode;
            owned = mModeOwned;
            mModeOwned = false;
            ++mGeneration;
        }
        // Guarded release: a stale hold must never clear another app's
        // routing selection.
        releaseRouteIfHeld();
        if (owned) {
            restoreMode(savedMode);
        }
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
        String failure;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            failure = bringUpModern(epoch);
        } else {
            failure = bringUpLegacy(epoch);
        }
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
            // before the request existed. The generation guard drops racing
            // callbacks.
            releaseRouteIfHeld();
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
                    setActive();
                }
            });
            return null;
        }
        armTimeout();
        return null;
    }

    // Returns a REASON_* on failure, null on success or concurrent-stop abort.
    private String bringUpLegacy(int epoch) {
        if (!isStartingEpoch(epoch)) {
            return null;
        }
        if (!isHeadsetConnected()) {
            return REASON_NO_DEVICE;
        }
        try {
            if (!registerLegacyReceiver()) {
                return REASON_ERROR;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Legacy SCO receiver denied", e);
            return REASON_PERMISSION;
        }
        if (!isStartingEpoch(epoch)) {
            teardownObservers();
            return null;
        }
        try {
            mAudioManager.startBluetoothSco();
        } catch (SecurityException e) {
            Log.w(TAG, "startBluetoothSco denied", e);
            return REASON_PERMISSION;
        } catch (Exception e) {
            Log.w(TAG, "startBluetoothSco threw", e);
            return REASON_ERROR;
        }
        markRouteHeld();
        if (!isStartingEpoch(epoch)) {
            releaseRouteIfHeld();
            return null;
        }
        if (isScoActiveNow()) {
            // See modern fast-path above.
            armTimeout();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    setActive();
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo current = mAudioManager.getCommunicationDevice();
                return current != null
                        && current.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
            } else {
                return isBluetoothScoOnLegacy();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // Legacy path runs below API 31 only, where this query is not deprecated.
    @SuppressWarnings("deprecation")
    private boolean isBluetoothScoOnLegacy() {
        return mAudioManager.isBluetoothScoOn();
    }

    // Fail-open HFP presence check: any doubt preserves the timeout fallback.
    private boolean isHeadsetConnected() {
        try {
            BluetoothManager manager =
                    (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            return adapter != null
                    && adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                            == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            Log.w(TAG, "Headset presence check threw", e);
            return true;
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

    private void onModernDeviceChanged(AudioDeviceInfo device) {
        final boolean scoNow;
        final int state;
        synchronized (this) {
            state = mState;
            scoNow = device != null && device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
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

    private void onLegacyStateChanged(int audioState) {
        final int state;
        final int attempts;
        synchronized (this) {
            state = mState;
            attempts = mAttempts;
        }
        if (audioState == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            if (state == STATE_STARTING) {
                setActive();
            }
            return;
        }
        if (audioState != AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                && audioState != AudioManager.SCO_AUDIO_STATE_ERROR) {
            return;
        }
        if (state == STATE_STARTING) {
            // Only an explicit stack error acts here: stale DISCONNECTED
            // broadcasts are benign link chatter and must not burn the retry
            // budget (the modern path ignores mid-bring-up moves likewise).
            if (audioState == AudioManager.SCO_AUDIO_STATE_ERROR) {
                if (!shouldRetryBringUp(attempts, mMaxAttempts)) {
                    fail(REASON_ERROR);
                    return;
                }
                // Otherwise retry or give up on the timeout path's terms by
                // re-arming immediately.
                mHandler.removeCallbacks(mTimeoutRunnable);
                mHandler.post(mTimeoutRunnable);
            }
        } else if (state == STATE_ACTIVE) {
            dropActiveLink();
        }
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
        final int savedMode;
        final boolean owned;
        synchronized (this) {
            if (mState != STATE_ACTIVE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            savedMode = mSavedMode;
            owned = mModeOwned;
            mModeOwned = false;
        }
        releaseRouteIfHeld();
        if (owned) {
            restoreMode(savedMode);
        }
        Log.i(TAG, "Bluetooth SCO disconnected");
        // Main-thread origin; see setActive.
        mListener.onScoDisconnected();
    }

    private void fail(String reason) {
        final int savedMode;
        final boolean owned;
        final int generation;
        synchronized (this) {
            if (mState == STATE_IDLE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            savedMode = mSavedMode;
            owned = mModeOwned;
            mModeOwned = false;
            generation = mGeneration;
        }
        releaseRouteIfHeld();
        if (owned) {
            restoreMode(savedMode);
        }
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
        }
        if (held) {
            releaseRoute();
        }
    }

    private void markRouteHeld() {
        synchronized (this) {
            mRouteHeld = true;
        }
    }

    private void releaseRoute() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mAudioManager.clearCommunicationDevice();
            } else {
                mAudioManager.stopBluetoothSco();
            }
        } catch (Exception e) {
            Log.w(TAG, "SCO route release threw", e);
        }
    }

    private void restoreMode(int savedMode) {
        try {
            // Only touch the mode if SCO setup changed it; a phone call or
            // another app may have legitimately moved it since.
            if (mAudioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                mAudioManager.setMode(savedMode);
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio mode restore threw", e);
        }
    }

    private void armTimeout() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        mHandler.postDelayed(mTimeoutRunnable, mConnectTimeoutMs);
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
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
        mDeviceListener = this::onModernDeviceChanged;
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

    // Self-locking; see registerDeviceListener. The legacy path runs below
    // API 31 only, so no export-flag branch is needed here.
    private synchronized boolean registerLegacyReceiver() {
        if (mLegacyReceiver != null) {
            return true;
        }
        mLegacyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(intent.getAction())) {
                    return;
                }
                // Unknown states are ignored, never errors: a malformed
                // broadcast must not fail a healthy bring-up.
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == -1) {
                    return;
                }
                onLegacyStateChanged(state);
            }
        };
        IntentFilter filter =
                new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        try {
            mContext.registerReceiver(mLegacyReceiver, filter);
        } catch (SecurityException e) {
            Log.w(TAG, "Legacy SCO receiver denied", e);
            mLegacyReceiver = null;
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Legacy SCO receiver registration threw", e);
            mLegacyReceiver = null;
            return false;
        }
        return true;
    }

    private synchronized void teardownObservers() {
        if (mLegacyReceiver != null) {
            try {
                mContext.unregisterReceiver(mLegacyReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered; teardown is idempotent by design.
            }
            mLegacyReceiver = null;
        }
        if (mDeviceListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                mAudioManager.removeOnCommunicationDeviceChangedListener(mDeviceListener);
            } catch (Exception e) {
                Log.w(TAG, "Device listener removal threw", e);
            }
            mDeviceListener = null;
        }
    }
}
