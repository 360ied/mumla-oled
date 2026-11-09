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

package se.lublin.humla.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Platform port behind the route policy.
 *
 * Slice 3 of the HumlaService decomposition: wraps exactly the AudioManager
 * surface the route code touches so the controller is testable on the plain
 * JVM with fakes (plus TestScope for timers — no Robolectric, no shadows).
 * The S+ vs legacy split stays inside the production implementation; callers
 * keep their version guards, which become redundant but harmless.
 */
interface AudioPlatform {
    /** False before onCreate wires the real manager, or when lookup failed. */
    val hasManager: Boolean

    /** AudioManager mode get/set; no-op set and MODE_NORMAL get when absent. */
    var mode: Int

    /** GET_DEVICES_OUTPUTS snapshot; null when absent (mirrors the query). */
    fun getOutputDevices(): Array<AudioDeviceInfo>?

    /** No-op when absent (mirrors the former safe-call). */
    fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler)

    /** No-op when absent (mirrors the former safe-call). */
    fun unregisterDeviceCallback(callback: AudioDeviceCallback)

    /** S+ communication device; null on pre-S or when absent. */
    fun getCommunicationDevice(): AudioDeviceInfo?

    /** Legacy SCO flag; false when absent. */
    var bluetoothScoOn: Boolean

    /** S+ route clear; no-op on pre-S or when absent. */
    fun clearCommunicationDevice()

    /** Legacy SCO stop; no-op when absent. */
    fun stopBluetoothSco()
}

/**
 * Production [AudioPlatform] over a nullable AudioManager.
 *
 * Null manager mirrors the old safe-calls: queries return absent values,
 * mutations are no-ops, and mode reads fall back to MODE_NORMAL (callers
 * check [hasManager] before touching mode, so the fallback never fires).
 */
class AndroidAudioPlatform(private val manager: AudioManager?) : AudioPlatform {
    override val hasManager: Boolean
        get() = manager != null

    override var mode: Int
        get() = manager?.mode ?: AudioManager.MODE_NORMAL
        set(value) {
            manager?.mode = value
        }

    override fun getOutputDevices(): Array<AudioDeviceInfo>? {
        return manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    }

    override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) {
        manager?.registerAudioDeviceCallback(callback, handler)
    }

    override fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
        manager?.unregisterAudioDeviceCallback(callback)
    }

    override fun getCommunicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }
        return manager?.communicationDevice
    }

    override var bluetoothScoOn: Boolean
        get() = manager?.isBluetoothScoOn == true
        set(value) {
            manager?.isBluetoothScoOn = value
        }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        manager?.clearCommunicationDevice()
    }

    override fun stopBluetoothSco() {
        manager?.stopBluetoothSco()
    }
}
