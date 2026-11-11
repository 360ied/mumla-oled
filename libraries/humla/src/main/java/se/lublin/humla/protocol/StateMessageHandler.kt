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
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserHandle
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
        val had = state.value.server.channels.containsKey(id)
        state.update { current -> applyChannelState(current, msg).state }
        val handle = ChannelHandle(id, state)
        // Touch to cache in case a later remove needs scalars.
        handle.name
        if (!had) observer.onChannelAdded(handle) else observer.onChannelStateUpdated(handle)
    }

    override fun messageChannelRemove(msg: Mumble.ChannelRemove) {
        val id = msg.channelId
        if (id == 0 || !state.value.server.channels.containsKey(id)) return
        // Cache scalars before the update detaches them.
        val ordered = collectSubtree(id)
        val handles = ordered.map { ChannelHandle(it, state).also { h -> h.name } }
        state.update { current -> applyChannelRemove(current, msg).state }
        for (handle in handles.asReversed()) observer.onChannelRemoved(handle)
    }

    private fun collectSubtree(root: Int): List<Int> {
        val channels = state.value.server.channels
        if (!channels.containsKey(root)) return emptyList()
        val out = ArrayList<Int>()
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!visited.add(node)) continue
            if (!channels.containsKey(node)) continue
            out.add(node)
            for ((childId, child) in channels) {
                if (child.parentId == node && childId != node && !visited.contains(childId)) {
                    stack.add(childId)
                }
            }
        }
        return out
    }

    override fun messageUserState(msg: Mumble.UserState) {
        val session = if (msg.hasSession()) msg.session else return
        // Cache names for the joined-channel callback before the update.
        val oldUser = state.value.server.users[session]
        val oldChannelId = oldUser?.channelId
        val isNew = oldUser == null
        val result = applyUserState(state.value, msg, mutedUserIds, ignoredUserIds)
        state.value = result.state
        events.log(result.events, result.state)
        val handle = UserHandle(session, state)
        handle.name
        if (isNew) {
            observer.onUserConnected(handle)
        } else {
            observer.onUserStateUpdated(handle)
            val newChannelId = result.state.server.users[session]?.channelId
            if (newChannelId != oldChannelId) {
                val newChannel = if (newChannelId != null) ChannelHandle(newChannelId, state) else null
                val oldChannel = if (oldChannelId != null) ChannelHandle(oldChannelId, state) else null
                observer.onUserJoinedChannel(handle, newChannel, oldChannel)
            }
        }
    }

    override fun messageUserRemove(msg: Mumble.UserRemove) {
        val session = msg.session
        val selfSession = state.value.server.selfSession
        if (session == selfSession) {
            state.update { current -> applyUserRemove(current, msg).state }
            return
        }
        val known = state.value.server.users.containsKey(session)
        val userName = state.value.server.users[session]?.name
        val actorName = if (msg.hasActor()) state.value.server.users[msg.actor]?.name else null
        val result = applyUserRemove(state.value, msg, userName, actorName)
        state.value = result.state
        events.log(result.events, result.state)
        if (known) {
            val handle = UserHandle(session, state)
            handle.name
            val reason = result.events.filterIsInstance<SessionEvent.UserLeft>().firstOrNull()?.reason
            observer.onUserRemoved(handle, reason)
        } else {
            val reason = result.events.filterIsInstance<SessionEvent.UserLeft>().firstOrNull()?.reason
            observer.onUserRemoved(null, reason)
        }
    }

    override fun messagePermissionQuery(msg: Mumble.PermissionQuery) {
        state.update { current -> applyPermissionQuery(current, msg).state }
        if (state.value.server.channels.containsKey(msg.channelId)) {
            observer.onChannelPermissionsUpdated(ChannelHandle(msg.channelId, state))
        }
    }

    override fun messageServerSync(msg: Mumble.ServerSync) {
        val result = applyServerSync(state.value, msg)
        state.value = result.state
        events.log(result.events, result.state)
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
        val result = applyTextMessage(state.value, msg)
        state.value = result.state
        for (event in result.events) {
            if (event is SessionEvent.TextMessageReceived) {
                observer.onMessageLogged(events.toMessage(event, state))
            }
        }
    }

    override fun messagePermissionDenied(msg: Mumble.PermissionDenied) {
        val result = applyPermissionDenied(state.value, msg)
        val event = result.events.filterIsInstance<SessionEvent.PermissionDenied>().firstOrNull()
            ?: return
        observer.onPermissionDenied(events.denyReason(event))
    }
}
