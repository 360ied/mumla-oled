/*
 * Copyright (C) 2014 Andrew Comminos
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
