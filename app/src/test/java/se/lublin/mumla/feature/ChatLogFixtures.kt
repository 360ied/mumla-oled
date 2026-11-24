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

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.emptySessionState

/** Every [SessionEvent] case and both sides of the chat log's filters. */
data class GoldenCase(val name: String, val event: SessionEvent, val state: SessionState)

private fun channel(id: Int, name: String, links: Set<Int> = emptySet()) = ChannelInfo(
    id = id,
    parentId = if (id == 0) null else 0,
    name = name,
    position = id,
    temporary = false,
    description = null,
    descriptionHash = null,
    links = persistentSetOf<Int>().addAll(links),
    maxUsers = 0,
    enterRestricted = false,
    canEnter = true,
    permissions = 0,
)

private fun user(session: Int, name: String, channelId: Int) = UserInfo(
    session = session,
    userId = null,
    name = name,
    channelId = channelId,
    certHash = null,
    comment = null,
    commentHash = null,
    texture = null,
    textureHash = null,
    muted = false,
    deafened = false,
    suppressed = false,
    selfMuted = false,
    selfDeafened = false,
    prioritySpeaker = false,
    recording = false,
    listeningChannels = persistentSetOf(),
    localMuted = false,
    localIgnored = false,
)

/** Self is in channel 0; channel 2 is linked to it; user 3 is an actor. */
fun goldenState(): SessionState = emptySessionState().copy(
    server = emptySessionState().server.copy(
        selfSession = 1,
        channels = persistentMapOf(
            0 to channel(0, "Root", setOf(2)),
            1 to channel(1, "Away"),
            2 to channel(2, "Linked", setOf(0)),
        ),
        users = persistentMapOf(
            1 to user(1, "Self", 0),
            2 to user(2, "Alice", 0),
            3 to user(3, "Bob", 2),
            4 to user(4, "Carol", 1),
        ),
    ),
)

private val state = goldenState()

/** All cases that produce a line, plus the two filter branches that drop one. */
val goldenCases: List<GoldenCase> = listOf(
    GoldenCase("joined", SessionEvent.UserJoinedServer(2, "Alice"), state),
    GoldenCase("left", SessionEvent.UserLeft(2, "Alice", null, null, null, false), state),
    GoldenCase("kicked", SessionEvent.UserLeft(2, "Alice", 3, "Bob", "rude", false), state),
    GoldenCase("banned", SessionEvent.UserLeft(2, "Alice", 3, "Bob", "rude", true), state),
    GoldenCase("left_no_name", SessionEvent.UserLeft(2, null, null, null, null, false), state),
    GoldenCase("kicked_no_actor_name", SessionEvent.UserLeft(2, "Alice", 3, null, "rude", false), state),
    GoldenCase("moved_out_self", SessionEvent.UserMoved(2, 0, 1, 2), state),
    GoldenCase("moved_out_other", SessionEvent.UserMoved(2, 0, 1, 3), state),
    GoldenCase("moved_out_server", SessionEvent.UserMoved(2, 0, 1, null), state),
    GoldenCase("moved_in_self", SessionEvent.UserMoved(2, 1, 0, 2), state),
    GoldenCase("moved_in_other", SessionEvent.UserMoved(2, 1, 0, 3), state),
    GoldenCase("moved_in_server", SessionEvent.UserMoved(2, 1, 0, null), state),
    GoldenCase("moved_self", SessionEvent.UserMoved(1, 0, 1, 1), state),
    GoldenCase("moved_unrelated", SessionEvent.UserMoved(4, 1, 2, 4), state),
    GoldenCase("recording_self_on", SessionEvent.RecordingChanged(1, true), state),
    GoldenCase("recording_self_off", SessionEvent.RecordingChanged(1, false), state),
    GoldenCase("recording_other_on", SessionEvent.RecordingChanged(3, true), state),
    GoldenCase("recording_other_off", SessionEvent.RecordingChanged(3, false), state),
    GoldenCase("recording_unlinked_on", SessionEvent.RecordingChanged(4, true), state),
    GoldenCase("self_mute", SessionEvent.SelfMuteStateChanged(true, false), state),
    GoldenCase("self_deafen", SessionEvent.SelfMuteStateChanged(true, true), state),
    GoldenCase("self_unmute", SessionEvent.SelfMuteStateChanged(false, false), state),
    GoldenCase("user_mute", SessionEvent.UserSelfMuteChanged(2, "Alice", true, false), state),
    GoldenCase("user_deafen", SessionEvent.UserSelfMuteChanged(2, "Alice", true, true), state),
    GoldenCase("user_unmute", SessionEvent.UserSelfMuteChanged(2, "Alice", false, false), state),
    GoldenCase("welcome", SessionEvent.WelcomeText("Welcome to the server"), state),
    GoldenCase("warning", SessionEvent.ConnectionWarning("UDP packets lost"), state),
    GoldenCase("text_received", SessionEvent.TextMessageReceived(
        2, "Alice", emptyList(), emptyList(), emptyList(), "hi", 0L,
    ), state),
    GoldenCase("text_sent", SessionEvent.TextMessageSent(
        emptyList(), emptyList(), emptyList(), "hi", 0L,
    ), state),
    GoldenCase("permission_denied", SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.ChannelName.number, null, null, null, null, null,
    ), state),
)

/** The deny formatter's branches, kept beside the event goldens. */
val goldenDenies: List<Pair<String, SessionEvent.PermissionDenied>> = listOf(
    "channel_name" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.ChannelName.number, null, null, null, null, null),
    "text_too_long" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.TextTooLong.number, null, null, null, null, null),
    "temporary_channel" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.TemporaryChannel.number, null, null, null, null, null),
    "missing_certificate" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.MissingCertificate.number, null, null, null, null, null),
    "user_name" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.UserName.number, null, null, null, null, null),
    "channel_full" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.ChannelFull.number, null, null, null, null, null),
    "nesting_limit" to SessionEvent.PermissionDenied(
        Mumble.PermissionDenied.DenyType.NestingLimit.number, null, null, null, null, null),
    "other_reason" to SessionEvent.PermissionDenied(-1, null, null, null, "because", null),
    "other_none" to SessionEvent.PermissionDenied(-1, null, null, null, null, null),
)
