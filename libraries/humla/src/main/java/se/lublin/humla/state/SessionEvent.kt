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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.state

/**
 * Semantic session events. See
 * docs/target-architecture/state.md#events.
 *
 * App formats user-visible strings from these; state code never formats text.
 */
sealed interface SessionEvent {
    data class TextMessageReceived(
        val actor: Int?,
        val actorName: String?,
        val channels: List<Int>,
        val trees: List<Int>,
        val users: List<Int>,
        val message: String,
        val receivedAtMillis: Long,
    ) : SessionEvent

    data class UserJoinedServer(val session: Int, val name: String?) : SessionEvent

    data class UserLeft(
        val session: Int,
        val name: String?,
        val actor: Int?,
        val actorName: String?,
        val reason: String?,
        val ban: Boolean,
    ) : SessionEvent

    data class UserMoved(val session: Int, val from: Int?, val to: Int, val actor: Int?) : SessionEvent

    data class RecordingChanged(val session: Int, val recording: Boolean) : SessionEvent

    data class SelfMuteStateChanged(val muted: Boolean, val deafened: Boolean) : SessionEvent

    data class UserSelfMuteChanged(
        val session: Int,
        val name: String?,
        val muted: Boolean,
        val deafened: Boolean,
    ) : SessionEvent

    data class WelcomeText(val text: String) : SessionEvent

    data class ConnectionWarning(val message: String) : SessionEvent

    data class TextMessageSent(
        val channels: List<Int>,
        val trees: List<Int>,
        val users: List<Int>,
        val message: String,
        val sentAtMillis: Long,
    ) : SessionEvent

    data class PermissionDenied(
        val type: Int,
        val permission: Int?,
        val channel: Int?,
        val session: Int?,
        val reason: String?,
        val name: String?,
    ) : SessionEvent
}
