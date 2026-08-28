/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.lublin.humla.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * Modern Bluetooth Audio Communication Manager.
 *
 * Supports modern Android 12+ (API 31+) AudioManager.setCommunicationDevice()
 * with graceful fallback to legacy Bluetooth SCO routing on older versions.
 */
public class BluetoothScoReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothScoReceiver";

    private final Listener mListener;
    private final AudioManager mAudioManager;
    private boolean mBluetoothScoOn;

    public BluetoothScoReceiver(Context context, Listener listener) {
        mListener = listener;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int audioState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
        switch (audioState) {
            case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                mBluetoothScoOn = true;
                if (mListener != null) {
                    mListener.onBluetoothScoConnected();
                }
                break;
            case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
            case AudioManager.SCO_AUDIO_STATE_ERROR:
                stopBluetoothSco();
                mBluetoothScoOn = false;
                if (mListener != null) {
                    mListener.onBluetoothScoDisconnected();
                }
                break;
        }
    }

    public void startBluetoothSco() {
        if (mAudioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> devices = mAudioManager.getAvailableCommunicationDevices();
            AudioDeviceInfo target = null;
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    target = device;
                    break;
                }
            }
            if (target != null) {
                boolean success = mAudioManager.setCommunicationDevice(target);
                Log.i(TAG, "setCommunicationDevice Bluetooth success: " + success);
                mBluetoothScoOn = success;
                if (mBluetoothScoOn && mListener != null) {
                    mListener.onBluetoothScoConnected();
                }
                return;
            }
        }

        try {
            mAudioManager.startBluetoothSco();
        } catch (Exception e) {
            Log.w(TAG, "Legacy startBluetoothSco failed: " + e.getMessage());
        }
    }

    public void stopBluetoothSco() {
        if (mAudioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mAudioManager.clearCommunicationDevice();
        }
        try {
            mAudioManager.stopBluetoothSco();
        } catch (Exception ignored) {
        }
        mBluetoothScoOn = false;
    }

    public boolean isBluetoothScoOn() {
        return mBluetoothScoOn;
    }

    public interface Listener {
        void onBluetoothScoConnected();
        void onBluetoothScoDisconnected();
    }
}
