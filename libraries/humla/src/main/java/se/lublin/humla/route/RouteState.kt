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

package se.lublin.humla.route

/**
 * Target-architecture route state. See
 * docs/tech-debt-whales/target-architecture/route.md#routestate.
 *
 * Android-free: no android.* imports so the decision and state stay
 * runnable in plain JVM tests. The controller (RouteControl) is the only
 * Android-touching member of this package.
 */
enum class AppliedRoute {
    VOICE_SCO,
    VOICE_HANDSET,
    MEDIA,
}

enum class StreamProfile {
    VOICE,
    MEDIA,
}

sealed interface RouteState {
    data object Idle : RouteState
    data class Settling(val target: AppliedRoute) : RouteState
    data class Ready(val route: AppliedRoute, val profile: StreamProfile) : RouteState
    data class Fallback(
        val route: AppliedRoute,
        val profile: StreamProfile,
        val reason: String,
    ) : RouteState
}

sealed interface RouteCommand {
    data object SessionStarted : RouteCommand
    data object SessionEnded : RouteCommand
    data object RetrySco : RouteCommand
}

data class RoutePreferences(
    val bluetoothSco: Boolean,
    val handsetStream: Int,
)

sealed interface RouteEvent {
    data class ScoFailed(val reason: String?) : RouteEvent
    data object RouteChanged : RouteEvent
}
