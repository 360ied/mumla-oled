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
 * <p>All listener callbacks fire on the main thread. The manager holds no UI;
 * user-visible fallback reporting is the owner's job.
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

    public interface Listener {
        /** The SCO link is up; the audio pipeline may now select the voice-call route. */
        void onScoConnected();
        /** A live link dropped; the pipeline should fall back to the previous route. */
        void onScoDisconnected();
        /** The link never came up (no device, no permission, timeout, refusal). */
        void onScoFailed();
    }

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private int mState = STATE_IDLE;
    private int mAttempts;
    private int mSavedMode = AudioManager.MODE_NORMAL;
    private AudioDeviceInfo mSelectedDevice;

    private BroadcastReceiver mLegacyReceiver;
    private AudioManager.OnCommunicationDeviceChangedListener mDeviceListener;

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onConnectTimeout();
        }
    };

    public BluetoothScoManager(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Begins SCO bring-up. No-op while starting or active. Reports
     * {@link Listener#onScoFailed} (never {@code onScoDisconnected}) when the
     * link cannot be established.
     */
    public synchronized void start() {
        if (mState != STATE_IDLE) {
            return;
        }
        if (mAudioManager == null) {
            fail("No AudioManager");
            return;
        }
        if (!hasConnectPermission()) {
            fail("BLUETOOTH_CONNECT not granted");
            return;
        }
        mSavedMode = mAudioManager.getMode();
        mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        mAttempts = 0;
        mState = STATE_STARTING;
        attemptBringUp();
    }

    /**
     * Tears down the link and restores the previous audio mode. Idempotent;
     * safe to call when idle. Never fires listener callbacks.
     */
    public synchronized void stop() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        teardownObservers();
        if (mState == STATE_IDLE) {
            return;
        }
        mState = STATE_IDLE;
        mSelectedDevice = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mAudioManager.clearCommunicationDevice();
            } else {
                mAudioManager.stopBluetoothSco();
            }
        } catch (Exception e) {
            Log.w(TAG, "SCO teardown threw", e);
        }
        restoreMode();
    }

    /** True only once the link has reported connected and not since dropped. */
    public synchronized boolean isActive() {
        return mState == STATE_ACTIVE;
    }

    private void attemptBringUp() {
        mAttempts++;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bringUpModern();
        } else {
            bringUpLegacy();
        }
    }

    private void bringUpModern() {
        AudioDeviceInfo sco = findScoDevice();
        if (sco == null) {
            fail("No Bluetooth SCO communication device available");
            return;
        }
        mSelectedDevice = sco;
        registerDeviceListener();
        boolean accepted;
        try {
            accepted = mAudioManager.setCommunicationDevice(sco);
        } catch (Exception e) {
            Log.w(TAG, "setCommunicationDevice threw", e);
            accepted = false;
        }
        if (!accepted) {
            fail("setCommunicationDevice refused the SCO device");
            return;
        }
        armTimeout();
    }

    private void bringUpLegacy() {
        registerLegacyReceiver();
        try {
            mAudioManager.startBluetoothSco();
        } catch (Exception e) {
            Log.w(TAG, "startBluetoothSco threw", e);
            fail("startBluetoothSco threw: " + e.getMessage());
            return;
        }
        armTimeout();
    }

    private AudioDeviceInfo findScoDevice() {
        List<AudioDeviceInfo> devices;
        try {
            devices = mAudioManager.getAvailableCommunicationDevices();
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
        synchronized (this) {
            if (mState != STATE_STARTING) {
                return;
            }
            if (mAttempts < MAX_ATTEMPTS) {
                Log.i(TAG, "SCO bring-up attempt " + mAttempts + " timed out, retrying");
                teardownObservers();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    try {
                        mAudioManager.stopBluetoothSco();
                    } catch (Exception ignored) {
                    }
                }
                attemptBringUp();
                return;
            }
        }
        fail("SCO bring-up timed out after " + MAX_ATTEMPTS + " attempts");
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
            mSelectedDevice = null;
        }
        restoreMode();
        Log.i(TAG, "Bluetooth SCO disconnected");
        mListener.onScoDisconnected();
    }

    private void fail(String reason) {
        synchronized (this) {
            if (mState == STATE_IDLE) {
                return;
            }
            mState = STATE_IDLE;
            mHandler.removeCallbacks(mTimeoutRunnable);
            teardownObservers();
            mSelectedDevice = null;
        }
        restoreMode();
        Log.w(TAG, "Bluetooth SCO failed: " + reason);
        mListener.onScoFailed();
    }

    private void restoreMode() {
        try {
            // Only touch the mode if SCO setup changed it; a phone call or
            // another app may have legitimately moved it since.
            if (mAudioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                mAudioManager.setMode(mSavedMode);
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio mode restore threw", e);
        }
    }

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

    private void registerDeviceListener() {
        if (mDeviceListener != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        mDeviceListener = this::onModernDeviceChanged;
        try {
            mAudioManager.addOnCommunicationDeviceChangedListener(
                    mContext.getMainExecutor(), mDeviceListener);
        } catch (Exception e) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener threw", e);
            mDeviceListener = null;
        }
    }

    private void registerLegacyReceiver() {
        if (mLegacyReceiver != null) {
            return;
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
        }
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
