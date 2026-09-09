/*
 * Copyright (C) 2026 Mumla Developers
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * Unified Audio Device and Hardware Routing Manager.
 *
 * Serves as the single authority managing Android's {@link AudioManager} state,
 * including communication mode, audio focus, Bluetooth SCO / Android 12+ communication
 * device routing, handset (earpiece) mode, and hardware state callbacks.
 */
public class AudioDeviceManager implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "AudioDeviceManager";

    public interface Listener {
        void onBluetoothScoConnected();
        void onBluetoothScoDisconnected();
        void onBluetoothRouteChanged(boolean active);
    }

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final Listener mListener;

    private boolean mBluetoothRequested;
    private boolean mBluetoothConnected;
    private boolean mHandsetMode;
    private boolean mInCommunication;
    private boolean mHasAudioFocus;

    private AudioFocusRequest mFocusRequest;
    private Object mCommDeviceListener; // AudioManager.OnCommunicationDeviceChangedListener on API 31+
    private BroadcastReceiver mLegacyScoReceiver;

    public AudioDeviceManager(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        initListeners();
    }

    private void initListeners() {
        if (mAudioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener commListener =
                    new AudioManager.OnCommunicationDeviceChangedListener() {
                        @Override
                        public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                            handleCommunicationDeviceChanged(device);
                        }
                    };
            mCommDeviceListener = commListener;
            mAudioManager.addOnCommunicationDeviceChangedListener(
                    mContext.getMainExecutor(),
                    commListener
            );
        } else {
            mLegacyScoReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR);
                    switch (state) {
                        case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                            handleLegacyScoConnected();
                            break;
                        case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                        case AudioManager.SCO_AUDIO_STATE_ERROR:
                            handleLegacyScoDisconnected();
                            break;
                    }
                }
            };
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mContext.registerReceiver(mLegacyScoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                mContext.registerReceiver(mLegacyScoReceiver, filter);
            }
        }
    }

    /**
     * Activates or deactivates telephony communication mode (MODE_IN_COMMUNICATION).
     * Must be active during voice sessions to permit VoIP device routing.
     */
    public synchronized void setCommunicationMode(boolean enable) {
        if (mAudioManager == null) return;

        if (enable) {
            if (!mInCommunication) {
                mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                mInCommunication = true;
                requestAudioFocus();
                applyRouting();
            }
        } else {
            if (mInCommunication) {
                clearBluetoothRoute();
                mAudioManager.setMode(AudioManager.MODE_NORMAL);
                mInCommunication = false;
                abandonAudioFocus();
            }
        }
    }

    public synchronized boolean isCommunicationMode() {
        return mInCommunication;
    }

    /**
     * Requests or disables routing through Bluetooth SCO / communication device.
     * @param enable true to route via Bluetooth, false to return to device audio.
     * @return true if the route was successfully applied or initiated; false if unavailable.
     */
    public synchronized boolean setBluetoothRoutingEnabled(boolean enable) {
        if (mAudioManager == null) return false;

        mBluetoothRequested = enable;

        if (enable) {
            // Ensure audio mode is MODE_IN_COMMUNICATION before requesting routing
            if (!mInCommunication) {
                setCommunicationMode(true);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo target = findBluetoothCommunicationDevice();
                if (target != null) {
                    boolean success = mAudioManager.setCommunicationDevice(target);
                    Log.i(TAG, "setCommunicationDevice(" + target.getProductName() + ") success: " + success);
                    if (success) {
                        // Routing requested successfully; wait for listener callback or update state
                        mBluetoothConnected = true;
                        if (mListener != null) {
                            mListener.onBluetoothScoConnected();
                            mListener.onBluetoothRouteChanged(true);
                        }
                        return true;
                    } else {
                        mBluetoothRequested = false;
                        return false;
                    }
                } else {
                    Log.w(TAG, "No Bluetooth communication device found");
                    mBluetoothRequested = false;
                    return false;
                }
            } else {
                try {
                    mAudioManager.startBluetoothSco();
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "startBluetoothSco failed: " + e.getMessage());
                    mBluetoothRequested = false;
                    return false;
                }
            }
        } else {
            clearBluetoothRoute();
            return true;
        }
    }

    public synchronized boolean isBluetoothRoutingEnabled() {
        return mBluetoothRequested;
    }

    public synchronized boolean isBluetoothConnected() {
        return mBluetoothConnected;
    }

    /**
     * Checks if a Bluetooth audio headset or communication device is currently available.
     */
    public boolean isBluetoothAvailable() {
        if (mAudioManager == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return findBluetoothCommunicationDevice() != null;
        } else {
            return mAudioManager.isBluetoothScoAvailableOffCall();
        }
    }

    private AudioDeviceInfo findBluetoothCommunicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || mAudioManager == null) {
            return null;
        }
        List<AudioDeviceInfo> devices = mAudioManager.getAvailableCommunicationDevices();
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_HEARING_AID) {
                return device;
            }
        }
        return null;
    }

    private synchronized void clearBluetoothRoute() {
        if (mAudioManager == null) return;

        mBluetoothRequested = false;
        boolean wasConnected = mBluetoothConnected;
        mBluetoothConnected = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mAudioManager.clearCommunicationDevice();
        }
        try {
            mAudioManager.stopBluetoothSco();
        } catch (Exception ignored) {
        }

        if (wasConnected && mListener != null) {
            mListener.onBluetoothScoDisconnected();
            mListener.onBluetoothRouteChanged(false);
        }

        applyRouting();
    }

    private synchronized void handleCommunicationDeviceChanged(AudioDeviceInfo device) {
        boolean isBluetooth = false;
        if (device != null) {
            int type = device.getType();
            isBluetooth = (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                           type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                           type == AudioDeviceInfo.TYPE_HEARING_AID);
        }

        Log.i(TAG, "onCommunicationDeviceChanged: " + (device != null ? device.getProductName() : "none") +
                " (isBluetooth=" + isBluetooth + ")");

        if (isBluetooth) {
            if (!mBluetoothConnected) {
                mBluetoothConnected = true;
                if (mListener != null) {
                    mListener.onBluetoothScoConnected();
                    mListener.onBluetoothRouteChanged(true);
                }
            }
        } else {
            if (mBluetoothConnected) {
                mBluetoothConnected = false;
                mBluetoothRequested = false;
                if (mListener != null) {
                    mListener.onBluetoothScoDisconnected();
                    mListener.onBluetoothRouteChanged(false);
                }
                applyRouting();
            }
        }
    }

    private synchronized void handleLegacyScoConnected() {
        Log.i(TAG, "Legacy SCO connected");
        mBluetoothConnected = true;
        if (mListener != null) {
            mListener.onBluetoothScoConnected();
            mListener.onBluetoothRouteChanged(true);
        }
    }

    private synchronized void handleLegacyScoDisconnected() {
        Log.i(TAG, "Legacy SCO disconnected");
        boolean wasConnected = mBluetoothConnected;
        mBluetoothConnected = false;
        mBluetoothRequested = false;
        try {
            mAudioManager.stopBluetoothSco();
        } catch (Exception ignored) {
        }
        if (wasConnected && mListener != null) {
            mListener.onBluetoothScoDisconnected();
            mListener.onBluetoothRouteChanged(false);
        }
        applyRouting();
    }

    /**
     * Configures Handset (earpiece) mode.
     */
    public synchronized void setHandsetModeEnabled(boolean enabled) {
        mHandsetMode = enabled;
        applyRouting();
    }

    public synchronized boolean isHandsetModeEnabled() {
        return mHandsetMode;
    }

    /**
     * Resolves and applies speakerphone state based on Bluetooth and Handset mode priority.
     */
    private synchronized void applyRouting() {
        if (mAudioManager == null || !mInCommunication) return;

        if (mBluetoothConnected) {
            // Bluetooth headset takes precedence; leave speakerphone off
            try {
                mAudioManager.setSpeakerphoneOn(false);
            } catch (Exception ignored) {
            }
        } else if (mHandsetMode) {
            // Handset mode forces earpiece
            try {
                mAudioManager.setSpeakerphoneOn(false);
            } catch (Exception ignored) {
            }
        } else {
            // Default VoIP routing on loudspeaker
            try {
                mAudioManager.setSpeakerphoneOn(true);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns the recommended audio stream type for output based on routing state.
     */
    public synchronized int getActiveAudioStream() {
        if (mBluetoothConnected || mHandsetMode) {
            return AudioManager.STREAM_VOICE_CALL;
        }
        return AudioManager.STREAM_MUSIC;
    }

    public synchronized void requestAudioFocus() {
        if (mAudioManager == null || mHasAudioFocus) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();

                mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(this)
                        .build();

                int result = mAudioManager.requestAudioFocus(mFocusRequest);
                mHasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            } else {
                int result = mAudioManager.requestAudioFocus(this,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN);
                mHasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to request audio focus: " + e.getMessage());
        }
    }

    public synchronized void abandonAudioFocus() {
        if (mAudioManager == null || !mHasAudioFocus) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mFocusRequest != null) {
                mAudioManager.abandonAudioFocusRequest(mFocusRequest);
            } else {
                mAudioManager.abandonAudioFocus(this);
            }
        } catch (Exception ignored) {
        }
        mHasAudioFocus = false;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        Log.i(TAG, "onAudioFocusChange: " + focusChange);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                mHasAudioFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mHasAudioFocus = true;
                applyRouting();
                break;
        }
    }

    /**
     * Tears down listeners and restores system audio mode.
     */
    public synchronized void shutdown() {
        clearBluetoothRoute();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mCommDeviceListener != null) {
            try {
                mAudioManager.removeOnCommunicationDeviceChangedListener(
                        (AudioManager.OnCommunicationDeviceChangedListener) mCommDeviceListener);
            } catch (Exception ignored) {
            }
            mCommDeviceListener = null;
        }

        if (mLegacyScoReceiver != null) {
            try {
                mContext.unregisterReceiver(mLegacyScoReceiver);
            } catch (Exception ignored) {
            }
            mLegacyScoReceiver = null;
        }

        setCommunicationMode(false);
    }
}
