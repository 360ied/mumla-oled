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

package se.lublin.mumla.feature

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RouteState
import se.lublin.mumla.PrefSnapshot

/** Handset-mode proximity blanking; a live SCO link wins over handset mode. */
class ProximityFeature(
    private val clientState: StateFlow<ClientState>,
    private val routeState: StateFlow<RouteState>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
) {
    private var lock: WakeLockLike? = null

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            combine(clientState, routeState, settings) { state, route, snap ->
                val sco = route is RouteState.Ready && route.route == AppliedRoute.VOICE_SCO
                state is ClientState.Connected && snap.handsetMode && !sco
            }.collect { wanted -> setLock(wanted) }
        }
    }

    private fun setLock(on: Boolean) {
        if (on) {
            if (lock?.isHeld() == true) return
            val next = platform.newProximityLock() ?: return
            lock = next
            try {
                next.acquire()
            } catch (e: RuntimeException) {
                // Sensor-less devices and OEM restrictions can throw here.
                lock = null
            }
        } else {
            val current = lock ?: return
            lock = null
            try {
                current.release()
            } catch (e: RuntimeException) {
                // A released lock must never escape into a disconnect path.
            }
        }
    }
}
