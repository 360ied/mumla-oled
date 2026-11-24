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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.R

/** Reverts the Bluetooth headset preference when the runtime grant is absent. */
class BluetoothPermissionGuardFeature(
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
) {
    private var seenInitial = false

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            settings.map { it.bluetoothHeadset }.distinctUntilChanged().collect { enabled ->
                if (!seenInitial) {
                    seenInitial = true
                    return@collect
                }
                if (enabled && !platform.hasBluetoothConnectGrant()) {
                    // The write-back re-enters once with false and terminates.
                    platform.setBluetoothHeadset(false)
                    platform.toast(R.string.grant_perm_bluetooth)
                }
            }
        }
    }
}
