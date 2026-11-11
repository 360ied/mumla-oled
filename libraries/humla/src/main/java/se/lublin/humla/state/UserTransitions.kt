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

import se.lublin.humla.protobuf.Mumble

private fun stubChannelForUsers(id: Int): ChannelInfo = ChannelInfo(
    id = id,
    parentId = null,
    name = null,
    position = 0,
    temporary = false,
    description = null,
    descriptionHash = null,
    links = kotlinx.collections.immutable.persistentSetOf(),
    maxUsers = 0,
    enterRestricted = false,
    canEnter = true,
    permissions = 0,
)

private fun newUserInfo(session: Int, name: String): UserInfo = UserInfo(
    session = session,
    userId = null,
    name = name,
    channelId = 0,
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
    listeningChannels = kotlinx.collections.immutable.persistentSetOf(),
    localMuted = false,
    localIgnored = false,
)

/**
 * UserState. Ported from ModelHandler.messageUserState.
 *
 * @param mutedUserIds registered user IDs locally muted (LocalFacts).
 * @param ignoredUserIds registered user IDs locally ignored.
 */
fun applyUserState(
    state: SessionState,
    msg: Mumble.UserState,
    mutedUserIds: Set<Int> = emptySet(),
    ignoredUserIds: Set<Int> = emptySet(),
): TransitionResult {
    var server = state.server
    // First channel/user message leaves HANDSHAKING for SYNCING.
    if (server.phase == SyncPhase.HANDSHAKING) {
        server = server.copy(phase = SyncPhase.SYNCING)
    }
    val synced = state.server.phase == SyncPhase.SYNCED
    var channels = server.channels
    var users = server.users
    val events = ArrayList<SessionEvent>()

    var user = users[msg.session]
    var isNew = false
    if (user == null) {
        if (!msg.hasName()) return TransitionResult(state.copy(server = server))
        if (!channels.containsKey(0)) {
            channels = channels.putting(0, stubChannelForUsers(0))
        }
        user = newUserInfo(msg.session, msg.name)
        users = users.putting(msg.session, user)
        isNew = true
        if (synced) {
            events.add(SessionEvent.UserJoinedServer(msg.session, msg.name))
        }
    }
    var u = users[msg.session] ?: user
    val self = server.selfSession?.let(users::get)

    if (msg.hasUserId()) {
        u = u.copy(userId = msg.userId)
        if (mutedUserIds.contains(msg.userId)) u = u.copy(localMuted = true)
        if (ignoredUserIds.contains(msg.userId)) u = u.copy(localIgnored = true)
    }
    if (msg.hasHash()) {
        u = u.copy(certHash = msg.hash)
    }

    // Self-mute echo: desktop parity, the toggle site owns the self line.
    // Other users' transitions in the self channel still notify.
    if (msg.hasSelfMute() || msg.hasSelfDeaf()) {
        val otherChanged = (msg.hasSelfMute() && u.selfMuted != msg.selfMute) ||
            (msg.hasSelfDeaf() && u.selfDeafened != msg.selfDeaf)
        var sm = u.selfMuted
        var sd = u.selfDeafened
        if (msg.hasSelfMute()) {
            sm = msg.selfMute
            if (!sm) sd = false
        }
        if (msg.hasSelfDeaf()) {
            sd = msg.selfDeaf
            if (sd) sm = true
        }
        u = u.copy(selfMuted = sm, selfDeafened = sd)
        if (otherChanged && synced && self != null) {
            val userChan = u.channelId
            val selfChan = self.channelId
            if (u.session != self.session && userChan != null && userChan == selfChan) {
                events.add(
                    SessionEvent.UserSelfMuteChanged(
                        session = u.session,
                        name = u.name,
                        muted = sm,
                        deafened = sd,
                    ),
                )
            }
        }
    }

    if (msg.hasRecording()) {
        if (u.recording != msg.recording) {
            u = u.copy(recording = msg.recording)
            if (synced) {
                events.add(SessionEvent.RecordingChanged(u.session, msg.recording))
            }
        } else {
            u = u.copy(recording = msg.recording)
        }
    }

    if (msg.hasMute() || msg.hasDeaf() || msg.hasSuppress() || msg.hasPrioritySpeaker()) {
        var m = u.muted
        var d = u.deafened
        if (msg.hasMute()) {
            m = msg.mute
            if (!m) d = false
        }
        if (msg.hasDeaf()) {
            d = msg.deaf
            if (d) m = true
        }
        var s = u.suppressed
        var p = u.prioritySpeaker
        if (msg.hasSuppress()) s = msg.suppress
        if (msg.hasPrioritySpeaker()) p = msg.prioritySpeaker
        u = u.copy(muted = m, deafened = d, suppressed = s, prioritySpeaker = p)
    }

    if (msg.hasChannelId()) {
        var channel = channels[msg.channelId]
        if (channel == null) {
            channel = channels[0]
        }
        val newChannelId: Int? = channel?.id
        val oldChannelId: Int? = u.channelId
        if (newChannelId != oldChannelId) {
            u = u.copy(channelId = newChannelId)
            if (!isNew && synced && newChannelId != null) {
                events.add(SessionEvent.UserMoved(u.session, oldChannelId, newChannelId, if (msg.hasActor()) msg.actor else null))
            } else if (!isNew && synced && newChannelId == null) {
                // Unplaced (no root yet): record the loss without a target.
                // Server will move the user once the root arrives.
            }
        } else {
            u = u.copy(channelId = newChannelId)
        }
    }

    if (msg.hasName()) u = u.copy(name = msg.name)

    if (msg.hasTextureHash()) {
        u = u.copy(textureHash = msg.textureHash, texture = null)
    }
    if (msg.hasTexture()) {
        u = u.copy(texture = msg.texture, textureHash = null)
    }
    if (msg.hasCommentHash()) {
        u = u.copy(commentHash = msg.commentHash, comment = null)
    }
    if (msg.hasComment()) {
        u = u.copy(comment = msg.comment, commentHash = null)
    }

    if (msg.listeningChannelAddCount > 0) {
        var listening = u.listeningChannels
        for (channelId in msg.listeningChannelAddList) {
            if (!listening.contains(channelId)) listening = listening.adding(channelId)
        }
        u = u.copy(listeningChannels = listening)
    }
    if (msg.listeningChannelRemoveCount > 0) {
        var listening = u.listeningChannels
        for (channelId in msg.listeningChannelRemoveList) {
            if (listening.contains(channelId)) listening = listening.removing(channelId)
        }
        u = u.copy(listeningChannels = listening)
    }

    var local = state.local
    if (msg.listeningVolumeAdjustmentCount > 0) {
        // Only the self user's packet drives local volume (upstream parity).
        if (u.session == server.selfSession) {
            var volumes = local.listeningVolumes
            for (adj in msg.listeningVolumeAdjustmentList) {
                if (adj.hasListeningChannel() && adj.hasVolumeAdjustment()) {
                    volumes = volumes.putting(adj.listeningChannel, adj.volumeAdjustment)
                }
            }
            local = local.copy(listeningVolumes = volumes)
        }
    }

    users = users.putting(u.session, u)
    server = server.copy(channels = channels, users = users)
    return TransitionResult(state.copy(server = server, local = local), events)
}

/**
 * UserRemove. Ported from ModelHandler.messageUserRemove.
 *
 * Self removal sets [TransitionResult.selfRemoved]; the session fails with
 * USER_REMOVE (no reconnect) instead of emitting a leave event.
 */
fun applyUserRemove(
    state: SessionState,
    msg: Mumble.UserRemove,
    userName: String? = null,
    actorName: String? = null,
): TransitionResult {
    val server = state.server
    val synced = server.phase == SyncPhase.SYNCED
    if (msg.session == server.selfSession) {
        return TransitionResult(state, emptyList(), selfRemoved = true)
    }
    val users = server.users
    val user = users[msg.session]
    val events = ArrayList<SessionEvent>()
    // Kicks and bans warn in every phase; quiet leaves only after SYNCED.
    val hasActor = msg.hasActor()
    if (hasActor || synced) {
        events.add(
            SessionEvent.UserLeft(
                session = msg.session,
                name = user?.name ?: userName,
                actor = if (hasActor) msg.actor else null,
                actorName = actorName,
                reason = if (msg.hasReason()) msg.reason else null,
                ban = msg.ban,
            ),
        )
    }
    if (user == null) return TransitionResult(state, events)
    return TransitionResult(
        state.copy(server = server.copy(users = users.removing(msg.session))),
        events,
    )
}
