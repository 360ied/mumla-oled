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

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.humla.route.RouteEvent
import se.lublin.mumla.R

/** SCO failure toast, with the same reason bucketing as the old service. */
class ScoFailureFeature(
    private val routeEvents: SharedFlow<RouteEvent>,
    private val platform: FeaturePlatform,
) {
    fun start(scope: CoroutineScope) {
        scope.featureChild().launch {
            routeEvents.collect { event ->
                if (event is RouteEvent.ScoFailed) {
                    platform.toast(scoFailureMessage(event.reason))
                }
            }
        }
    }

    companion object {
        @StringRes
        @JvmStatic
        fun scoFailureMessage(reason: String?): Int =
            if (reason == BluetoothScoManager.REASON_PERMISSION) {
                R.string.bluetooth_sco_permission
            } else {
                R.string.bluetooth_sco_failed
            }
    }
}
