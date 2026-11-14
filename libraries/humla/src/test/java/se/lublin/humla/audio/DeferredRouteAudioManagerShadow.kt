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
 * The stock [ShadowAudioManager] with asynchronous communication-device
 * routing, as on a real device: a request is accepted without switching the
 * current device, and the switch happens only when the test calls
 * [connect]. The stock shadow switches inside the request, which always
 * takes the SCO manager's already-routed fast path and never reaches its
 * connect timeout.
 *
 * Overrides only the request. Device state, listener registration, and
 * listener delivery (through the executor each listener was registered
 * with) stay the stock shadow's. Listener add and remove are observed on
 * their way through, because a leaked registration is invisible in the
 * manager's behavior (its epoch guard drops what the listener delivers)
 * and the stock shadow has no public view of its listeners.
 */
@Implements(AudioManager::class)
class DeferredRouteAudioManagerShadow : ShadowAudioManager() {
    /** Communication-device requests the platform has received. */
    var requests = 0
        private set

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        requests++
        return true
    }

    /** Live communication-device listener registrations. */
    var listeners = 0
        private set

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun addOnCommunicationDeviceChangedListener(
        executor: Executor,
        listener: AudioManager.OnCommunicationDeviceChangedListener,
    ) {
        listeners++
        super.addOnCommunicationDeviceChangedListener(executor, listener)
    }

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun removeOnCommunicationDeviceChangedListener(
        listener: AudioManager.OnCommunicationDeviceChangedListener,
    ) {
        listeners--
        super.removeOnCommunicationDeviceChangedListener(listener)
    }

    /** The platform switches to [device], then notifies listeners. */
    fun connect(device: AudioDeviceInfo) {
        super.setCommunicationDevice(device)
        callOnCommunicationDeviceChangedListeners(device)
    }

    /** The platform is already routed to [device], with no notification. */
    fun preselect(device: AudioDeviceInfo) {
        super.setCommunicationDevice(device)
    }
}
