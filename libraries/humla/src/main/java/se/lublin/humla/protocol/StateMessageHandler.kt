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

package se.lublin.humla.protocol

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.ChannelHandle
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.applyChannelRemove
import se.lublin.humla.state.applyChannelState
import se.lublin.humla.state.applyCodecVersion
import se.lublin.humla.state.applyPermissionDenied
import se.lublin.humla.state.applyPermissionQuery
import se.lublin.humla.state.applyServerConfig
import se.lublin.humla.state.applyServerSync
import se.lublin.humla.state.applyTextMessage
import se.lublin.humla.state.applyUserRemove
import se.lublin.humla.state.applyUserState
import se.lublin.humla.state.applyVersion
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver

/**
 * Step-2 model handler: applies pure transitions to the session state flow,
 * logs semantic events through the temporary adapter, and fans observer
 * callbacks out with live handles.
 */
class StateMessageHandler(
    context: Context,
    private val state: MutableStateFlow<SessionState>,
    private val observer: IHumlaObserver,
    logger: HumlaLogger,
    private val mutedUserIds: Set<Int> = emptySet(),
    private val ignoredUserIds: Set<Int> = emptySet(),
) : HumlaTCPMessageListener.Stub() {
    private val events = StateEventLogger(context, logger)
    val flow = state

    override fun messageChannelState(msg: Mumble.ChannelState) {
        if (!msg.hasChannelId()) return
        val id = msg.channelId
        // Decide inside update: a concurrent intent write between a read
        // and a plain assign would be clobbered. A refused transition
        // (self-parent/cycle) leaves the channel absent, so fan out from
        // the post-image, never the pre-image alone.
        var added = false
        state.update { current ->
            val next = applyChannelState(current, msg).state
            added = !current.server.channels.containsKey(id) &&
                next.server.channels.containsKey(id)
            next
        }
        val handle = ChannelHandle(id, state)
        // Touch to cache in case a later remove needs scalars.
        handle.name
        if (added) observer.onChannelAdded(handle) else observer.onChannelStateUpdated(handle)
    }

    override fun messageChannelRemove(msg: Mumble.ChannelRemove) {
        val id = msg.channelId
        // Snapshot the detached infos inside the same atomic update that
        // removes them, then seed handles so callbacks read last values.
        // Sorted children-first (depth descending): iteration order alone
        // does not guarantee the old detached-subtree sequence.
        var removedInfos: List<ChannelInfo> = emptyList()
        var known = false
        state.update { current ->
            known = current.server.channels.containsKey(id)
            if (id == 0 || !known) return@update current
            val next = applyChannelRemove(current, msg).state
            val removedIds = current.server.channels.keys - next.server.channels.keys
            fun depthOf(start: Int): Int {
                var depth = 0
                var node: Int? = start
                val seen = HashSet<Int>()
                while (node != null && removedIds.contains(node) && seen.add(node)) {
                    node = current.server.channels[node]?.parentId
                    depth++
                }
                return depth
            }
            removedInfos = removedIds.mapNotNull { current.server.channels[it] }
                .sortedByDescending { depthOf(it.id) }
            next
        }
        if (id == 0 || !known) return
        for (info in removedInfos) {
            observer.onChannelRemoved(ChannelHandle(info.id, state, seed = info))
        }
    }

    override fun messageUserState(msg: Mumble.UserState) {
        val session = if (msg.hasSession()) msg.session else return
        // Everything decided inside one atomic update; callbacks use the
        // captured outcome, never a re-read that raced an intent.
        var isNew = false
        var oldChannelId: Int? = null
        var newChannelId: Int? = null
        var outcomeEvents: List<SessionEvent> = emptyList()
        lateinit var snap: SessionState
        state.update { current ->
            val oldUser = current.server.users[session]
            isNew = oldUser == null
            oldChannelId = oldUser?.channelId
            val result = applyUserState(current, msg, mutedUserIds, ignoredUserIds)
            outcomeEvents = result.events
            newChannelId = result.state.server.users[session]?.channelId
            snap = result.state
            snap
        }
        // Gate the already-decided events against the transition snapshot,
        // not a fresh read that a concurrent intent may have moved on.
        events.log(outcomeEvents, snap)
        val handle = UserHandle(session, state)
        handle.name
        if (isNew) {
            observer.onUserConnected(handle)
        } else {
            observer.onUserStateUpdated(handle)
            if (newChannelId != oldChannelId) {
                val newChannel = if (newChannelId != null) ChannelHandle(newChannelId, state) else null
                val oldChannel = if (oldChannelId != null) ChannelHandle(oldChannelId, state) else null
                observer.onUserJoinedChannel(handle, newChannel, oldChannel)
            }
        }
    }

    override fun messageUserRemove(msg: Mumble.UserRemove) {
        val session = msg.session
        // Self removal is owned by the connection layer (HumlaConnection
        // fails the session with no reconnect); state needs no change.
        var selfRemoved = false
        var oldInfo: UserInfo? = null
        var outcomeEvents: List<SessionEvent> = emptyList()
        lateinit var snap: SessionState
        state.update { current ->
            if (session == current.server.selfSession) {
                selfRemoved = true
                return@update applyUserRemove(current, msg).state
            }
            oldInfo = current.server.users[session]
            val actorName = if (msg.hasActor()) current.server.users[msg.actor]?.name else null
            val result = applyUserRemove(current, msg, oldInfo?.name, actorName)
            outcomeEvents = result.events
            snap = result.state
            snap
        }
        if (selfRemoved) return
        events.log(outcomeEvents, snap)
        val reason = outcomeEvents.filterIsInstance<SessionEvent.UserLeft>().firstOrNull()?.reason
        if (oldInfo != null) {
            observer.onUserRemoved(UserHandle(session, state, seed = oldInfo), reason)
        } else {
            observer.onUserRemoved(null, reason)
        }
    }

    override fun messagePermissionQuery(msg: Mumble.PermissionQuery) {
        var known = false
        state.update { current ->
            known = current.server.channels.containsKey(msg.channelId)
            applyPermissionQuery(current, msg).state
        }
        if (known) {
            observer.onChannelPermissionsUpdated(ChannelHandle(msg.channelId, state))
        }
    }

    override fun messageServerSync(msg: Mumble.ServerSync) {
        var outcomeEvents: List<SessionEvent> = emptyList()
        lateinit var snap: SessionState
        state.update { current ->
            val result = applyServerSync(current, msg)
            outcomeEvents = result.events
            snap = result.state
            snap
        }
        events.log(outcomeEvents, snap)
    }

    override fun messageServerConfig(msg: Mumble.ServerConfig) {
        state.update { current -> applyServerConfig(current, msg).state }
    }

    override fun messageVersion(msg: Mumble.Version) {
        state.update { current -> applyVersion(current, msg).state }
    }

    override fun messageCodecVersion(msg: Mumble.CodecVersion) {
        state.update { current -> applyCodecVersion(current, msg).state }
    }

    override fun messageTextMessage(msg: Mumble.TextMessage) {
        var received: SessionEvent.TextMessageReceived? = null
        state.update { current ->
            val result = applyTextMessage(current, msg, System.currentTimeMillis())
            received = result.events.filterIsInstance<SessionEvent.TextMessageReceived>().firstOrNull()
            result.state
        }
        val event = received ?: return
        observer.onMessageLogged(events.toMessage(event, state))
    }

    override fun messagePermissionDenied(msg: Mumble.PermissionDenied) {
        var denied: SessionEvent.PermissionDenied? = null
        state.update { current ->
            val result = applyPermissionDenied(current, msg)
            denied = result.events.filterIsInstance<SessionEvent.PermissionDenied>().firstOrNull()
            result.state
        }
        val event = denied ?: return
        observer.onPermissionDenied(events.denyReason(event))
    }
}
