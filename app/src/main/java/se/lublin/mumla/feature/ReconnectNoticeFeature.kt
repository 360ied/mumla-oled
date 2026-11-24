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
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.R

/** Warns when a reconnect-only setting changes while connected. */
class ReconnectNoticeFeature(
    private val clientState: StateFlow<ClientState>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
) {
    private var connected = false
    private var lastCertificate: Long? = null
    private var lastForceTcp: Boolean? = null

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            clientState.collect { connected = it is ClientState.Connected }
        }
        child.launch {
            settings.collect { snap ->
                val previousCertificate = lastCertificate
                val previousForceTcp = lastForceTcp
                lastCertificate = snap.certificateId
                lastForceTcp = snap.forceTcp
                if (previousCertificate != null &&
                    (previousCertificate != snap.certificateId || previousForceTcp != snap.forceTcp) &&
                    connected
                ) {
                    platform.toast(R.string.change_requires_reconnect)
                }
            }
        }
    }
}
