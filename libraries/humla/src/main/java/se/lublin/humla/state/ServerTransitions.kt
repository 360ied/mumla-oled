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

import se.lublin.humla.Constants
import se.lublin.humla.protobuf.Mumble

/** PermissionQuery. Ported from ModelHandler.messagePermissionQuery. */
fun applyPermissionQuery(state: SessionState, msg: Mumble.PermissionQuery): TransitionResult {
    var channels = state.server.channels
    if (msg.flush) {
        for ((id, ch) in channels) {
            if (ch.permissions != 0) channels = channels.putting(id, ch.copy(permissions = 0))
        }
    }
    val channel = channels[msg.channelId] ?: return TransitionResult(
        state.copy(server = state.server.copy(channels = channels)),
    )
    var rootPermissions = state.server.rootPermissions
    val updated = channel.copy(permissions = msg.permissions)
    channels = channels.putting(msg.channelId, updated)
    if (msg.channelId == 0) {
        rootPermissions = msg.permissions
    }
    return TransitionResult(
        state.copy(server = state.server.copy(channels = channels, rootPermissions = rootPermissions)),
    )
}

/** ServerSync. Sets SYNCED, publishes self, permissions, bandwidth, welcome. */
fun applyServerSync(state: SessionState, msg: Mumble.ServerSync): TransitionResult {
    var info = state.server.info
    if (msg.hasMaxBandwidth()) info = info.copy(maxBandwidth = msg.maxBandwidth)
    if (msg.hasWelcomeText()) info = info.copy(welcomeText = msg.welcomeText)
    var server = state.server.copy(
        phase = SyncPhase.SYNCED,
        selfSession = msg.session,
        info = info,
    )
    if (msg.hasPermissions()) {
        server = server.copy(rootPermissions = msg.permissions.toInt())
    }
    val events = ArrayList<SessionEvent>()
    if (msg.hasWelcomeText()) {
        events.add(SessionEvent.WelcomeText(msg.welcomeText))
    }
    return TransitionResult(state.copy(server = server), events)
}

/** ServerConfig. Settings, welcome text, and bandwidth cap.
 *
 * Field reads are unguarded: murmur always sends the full config, so a
 * partial message is a protocol violation we do not defend against.
 */
fun applyServerConfig(state: SessionState, msg: Mumble.ServerConfig): TransitionResult {
    var info = state.server.info
    info = info.copy(
        settings = ServerSettingsInfo(
            allowHtml = msg.allowHtml,
            messageLength = msg.messageLength,
            imageMessageLength = msg.imageMessageLength,
            maxUsers = msg.maxUsers,
        ),
    )
    if (msg.hasWelcomeText()) info = info.copy(welcomeText = msg.welcomeText)
    if (msg.hasMaxBandwidth()) info = info.copy(maxBandwidth = msg.maxBandwidth)
    return TransitionResult(state.copy(server = state.server.copy(info = info)))
}

/** Version. Derives the v2 version from whichever field the server sent. */
fun applyVersion(state: SessionState, msg: Mumble.Version): TransitionResult {
    var info = state.server.info
    if (msg.hasVersionV2()) {
        info = info.copy(versionV2 = msg.versionV2)
    } else if (msg.hasVersionV1()) {
        info = info.copy(versionV2 = Constants.toVersionV2FromLegacy(msg.versionV1))
    }
    if (msg.hasRelease()) info = info.copy(release = msg.release)
    if (msg.hasOs()) info = info.copy(os = msg.os)
    if (msg.hasOsVersion()) info = info.copy(osVersion = msg.osVersion)
    return TransitionResult(state.copy(server = state.server.copy(info = info)))
}

/** CodecVersion. Opus-only; the legacy CELT fallback is not tracked. */
fun applyCodecVersion(state: SessionState, msg: Mumble.CodecVersion): TransitionResult {
    if (!msg.hasOpus()) return TransitionResult(state)
    return TransitionResult(
        state.copy(server = state.server.copy(info = state.server.info.copy(opus = msg.opus))),
    )
}

/** TextMessage. Drops ignored senders; history is an app feature, not state.
 *
 * [nowMillis] is a required parameter (no default clock) so transitions
 * stay pure and tests stay deterministic; callers pass wall-clock time.
 */
fun applyTextMessage(
    state: SessionState,
    msg: Mumble.TextMessage,
    nowMillis: Long,
): TransitionResult {
    val sender = if (msg.hasActor()) state.server.users[msg.actor] else null
    if (sender != null && sender.localIgnored) return TransitionResult(state)
    val channels = msg.channelIdList.filter { state.server.channels.containsKey(it) }
    val trees = msg.treeIdList.filter { state.server.channels.containsKey(it) }
    val users = msg.sessionList.filter { state.server.users.containsKey(it) }
    val event = SessionEvent.TextMessageReceived(
        actor = if (msg.hasActor()) msg.actor else null,
        actorName = sender?.name,
        channels = channels,
        trees = trees,
        users = users,
        message = msg.message,
        receivedAtMillis = nowMillis,
    )
    return TransitionResult(state, listOf(event))
}

/** PermissionDenied. No state change; the app formats the reason. */
fun applyPermissionDenied(state: SessionState, msg: Mumble.PermissionDenied): TransitionResult {
    val event = SessionEvent.PermissionDenied(
        type = msg.type.number,
        permission = if (msg.hasPermission()) msg.permission else null,
        channel = if (msg.hasChannelId()) msg.channelId else null,
        session = if (msg.hasSession()) msg.session else null,
        reason = if (msg.hasReason()) msg.reason else null,
        name = if (msg.hasName()) msg.name else null,
    )
    return TransitionResult(state, listOf(event))
}
