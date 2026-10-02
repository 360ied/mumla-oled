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
 * <p>Locking: {@code mState}, {@code mAttempts}, and {@code mSavedMode} are guarded
 * by the manager monitor. Private bring-up/teardown helpers require the monitor
 * unless noted. AudioManager IPC and all listener callbacks run outside the
 * monitor: callbacks originate on the main thread (broadcast receiver, device
 * listener, and timeout all run there) except {@code onScoFailed}, which is
 * always posted to the main handler because bring-up failures can surface on
 * the {@code start()} caller's thread. Callbacks may race a concurrent
 * {@code stop()}; owners must treat them as hints and re-check pipeline state.
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

    /** Failure reasons delivered to {@link Listener#onScoFailed}. */
    public static final String REASON_PERMISSION = "permission";
    public static final String REASON_NO_DEVICE = "no-device";
    public static final String REASON_REFUSED = "refused";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_ERROR = "error";

    public interface Listener {
        /** The SCO link is up; the audio pipeline may now select the voice-call route. */
        void onScoConnected();
        /** A live link dropped; the pipeline should fall back to the previous route. */
        void onScoDisconnected();
        /**
         * The link never came up.
         * @param reason one of {@code REASON_*};
         *               {@code REASON_PERMISSION} means the grant is missing.
         */
        void onScoFailed(String reason);
    }

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private int mState = STATE_IDLE;
    private int mAttempts;
    private int mSavedMode = AudioManager.MODE_NORMAL;

    private BroadcastReceiver mLegacyReceiver;
    private AudioManager.OnCommunicationDeviceChangedListener mDeviceListener;

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onConnectTimeout();
        }
    };

    public BluetoothScoManager(Context context, Listener listener) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(listener, "listener");
        mContext = context.getApplicationContext();
        mListener = listener;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Begins SCO bring-up. No-op while starting or active. A posted
     * {@link Listener#onScoFailed} (never {@code onScoDisconnected}) reports
     * every failure, including pre-flight checks, so callers must not assume
     * the link is up when this returns.
     */
    public synchronized void start() {
        if (mState != STATE_IDLE) {
            return;
        }
        // Flip first: pre-flight failures below report through fail(), which
        // only notifies when leaving a non-IDLE state.
        mState = STATE_STARTING;
        mAttempts = 0;
        if (!hasConnectPermission()) {
            fail(REASON_PERMISSION);
            return;
        }
        mSavedMode = mAudioManager.getMode();
        try {
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            Log.w(TAG, "setMode threw", e);
            fail(REASON_ERROR);
            return;
        }
        attemptBringUp();
    }

    /**
     * Tears down the link and restores the previous audio mode. Idempotent and
     * safe to call when idle; a failed bring-up may still hold a requested
     * route, so release is unconditional. Never fires listener callbacks.
     */
    public void stop() {
        final int savedMode;
        synchronized (this) {
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            mState = STATE_IDLE;
            savedMode = mSavedMode;
        }
        releaseRoute();
        restoreMode(savedMode);
    }

    /** True only once the link has reported connected and not since dropped. */
    public synchronized boolean isActive() {
        return mState == STATE_ACTIVE;
    }

    // Call with monitor held.
    private void attemptBringUp() {
        mAttempts++;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bringUpModern();
        } else {
            bringUpLegacy();
        }
    }

    // Call with monitor held.
    private void bringUpModern() {
        AudioDeviceInfo sco = findScoDevice();
        if (sco == null) {
            fail(REASON_NO_DEVICE);
            return;
        }
        if (!registerDeviceListener()) {
            fail(REASON_ERROR);
            return;
        }
        boolean accepted;
        try {
            accepted = mAudioManager.setCommunicationDevice(sco);
        } catch (SecurityException e) {
            Log.w(TAG, "setCommunicationDevice denied", e);
            fail(REASON_PERMISSION);
            return;
        } catch (Exception e) {
            Log.w(TAG, "setCommunicationDevice threw", e);
            fail(REASON_ERROR);
            return;
        }
        if (!accepted) {
            fail(REASON_REFUSED);
            return;
        }
        armTimeout();
    }

    // Call with monitor held.
    private void bringUpLegacy() {
        if (!registerLegacyReceiver()) {
            fail(REASON_ERROR);
            return;
        }
        try {
            mAudioManager.startBluetoothSco();
        } catch (SecurityException e) {
            Log.w(TAG, "startBluetoothSco denied", e);
            fail(REASON_PERMISSION);
            return;
        } catch (Exception e) {
            Log.w(TAG, "startBluetoothSco threw", e);
            fail(REASON_ERROR);
            return;
        }
        armTimeout();
    }

    private AudioDeviceInfo findScoDevice() {
        List<AudioDeviceInfo> devices;
        try {
            devices = mAudioManager.getAvailableCommunicationDevices();
        } catch (SecurityException e) {
            Log.w(TAG, "getAvailableCommunicationDevices denied", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "getAvailableCommunicationDevices threw", e);
            return null;
        }
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
        synchronized (this) {
            state = mState;
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
            // Bring-up attempt failed inside the stack; retry or give up on
            // the timeout path's terms by re-arming immediately.
            mHandler.removeCallbacks(mTimeoutRunnable);
            mHandler.post(mTimeoutRunnable);
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
            retry = mAttempts < MAX_ATTEMPTS;
        }
        if (!retry) {
            fail(REASON_TIMEOUT);
            return;
        }
        Log.i(TAG, "SCO bring-up attempt timed out, retrying");
        // Clear observers and any half-requested route before re-attempting.
        // Outside the monitor: unregister/release are Binder IPC.
        teardownObservers();
        releaseRoute();
        synchronized (this) {
            if (mState != STATE_STARTING) {
                return;
            }
            attemptBringUp();
        }
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
        synchronized (this) {
            if (mState != STATE_ACTIVE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            savedMode = mSavedMode;
        }
        releaseRoute();
        restoreMode(savedMode);
        Log.i(TAG, "Bluetooth SCO disconnected");
        // Main-thread origin; see setActive.
        mListener.onScoDisconnected();
    }

    private void fail(String reason) {
        final int savedMode;
        synchronized (this) {
            if (mState == STATE_IDLE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            savedMode = mSavedMode;
        }
        releaseRoute();
        restoreMode(savedMode);
        Log.w(TAG, "Bluetooth SCO failed: " + reason);
        // Always posted: pre-flight and re-entrant failures can surface on
        // the start() caller's thread, and callbacks must stay main-thread.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onScoFailed(reason);
            }
        });
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

    // Call with monitor held.
    private void armTimeout() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        mHandler.postDelayed(mTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Call with monitor held. Returns false when registration threw, in which
    // case bring-up must fail fast: without an observer nothing can ever
    // report the link connected.
    private boolean registerDeviceListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        if (mDeviceListener != null) {
            return true;
        }
        mDeviceListener = this::onModernDeviceChanged;
        try {
            mAudioManager.addOnCommunicationDeviceChangedListener(
                    mContext.getMainExecutor(), mDeviceListener);
        } catch (Exception e) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener threw", e);
            mDeviceListener = null;
            return false;
        }
        return true;
    }

    // Call with monitor held. Returns false when registration threw; see
    // registerDeviceListener.
    private boolean registerLegacyReceiver() {
        if (mLegacyReceiver != null) {
            return true;
        }
        mLegacyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(intent.getAction())) {
                    return;
                }
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR);
                onLegacyStateChanged(state);
            }
        };
        IntentFilter filter =
                new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mContext.registerReceiver(mLegacyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                mContext.registerReceiver(mLegacyReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "Legacy SCO receiver registration threw", e);
            mLegacyReceiver = null;
            return false;
        }
        return true;
    }

    private void teardownObservers() {
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
            } catch (Exception ignored) {
            }
            mDeviceListener = null;
        }
    }
}
