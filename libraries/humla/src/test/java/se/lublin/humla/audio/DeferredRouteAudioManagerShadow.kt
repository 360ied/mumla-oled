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

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.Executor

/**
 * [ShadowAudioManager] with a real platform's asynchronous routing: a
 * communication-device request is accepted without switching the current
 * device, so the link only comes up when the test calls [connect]. The
 * stock shadow switches immediately, which always takes the manager's
 * already-on-SCO fast path and never exercises the timeout.
 *
 * Also counts bring-up requests and tracks live listener registrations,
 * which is how a duplicate bring-up shows up.
 */
@Implements(AudioManager::class)
class DeferredRouteAudioManagerShadow : ShadowAudioManager() {
    var setRequests = 0
        private set
    var legacyStarts = 0
        private set
    var legacyStops = 0
        private set
    private var current: AudioDeviceInfo? = null
    private val live = mutableSetOf<AudioManager.OnCommunicationDeviceChangedListener>()

    /** Live device-listener registrations (S+). */
    val listenerCount: Int get() = live.size

    /** The device the platform currently routes communication audio to. */
    val currentDevice: AudioDeviceInfo? get() = current

    /** The platform switches to [device] and notifies listeners through their executors. */
    fun connect(device: AudioDeviceInfo?) {
        current = device
        callOnCommunicationDeviceChangedListeners(device)
    }

    /** Preselects a route without notifying, as when SCO is already up. */
    fun preselect(device: AudioDeviceInfo?) {
        current = device
    }

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        setRequests++
        return true
    }

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun getCommunicationDevice(): AudioDeviceInfo? = current

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun clearCommunicationDevice() {
        current = null
    }

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun addOnCommunicationDeviceChangedListener(
        executor: Executor,
        listener: AudioManager.OnCommunicationDeviceChangedListener,
    ) {
        live += listener
        super.addOnCommunicationDeviceChangedListener(executor, listener)
    }

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun removeOnCommunicationDeviceChangedListener(
        listener: AudioManager.OnCommunicationDeviceChangedListener,
    ) {
        live -= listener
        super.removeOnCommunicationDeviceChangedListener(listener)
    }

    @Implementation
    protected fun startBluetoothSco() {
        legacyStarts++
    }

    @Implementation
    protected fun stopBluetoothSco() {
        legacyStops++
    }
}
