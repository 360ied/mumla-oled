/*
 * Copyright (C) 2026 Mumla Contributors
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

package se.lublin.mumla.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.data.model.ConnectionStatus
import se.lublin.mumla.data.model.SessionUiState
import se.lublin.mumla.data.model.TreeItem
import se.lublin.mumla.service.IMumlaService

class SessionRepository(
    private val scope: CoroutineScope
) {
    private var service: IMumlaService? = null
    private val expandedChannels = mutableMapOf<Int, Boolean>()

    private val _sessionState = MutableStateFlow(SessionUiState())
    val sessionState: StateFlow<SessionUiState> = _sessionState.asStateFlow()

    private val _treeItems = MutableStateFlow<List<TreeItem>>(emptyList())
    val treeItems: StateFlow<List<TreeItem>> = _treeItems.asStateFlow()

    private val observer = object : HumlaObserver() {
        override fun onConnecting() {
            _sessionState.value = _sessionState.value.copy(
                status = ConnectionStatus.CONNECTING,
                errorMessage = null
            )
        }

        override fun onConnected() {
            updateSessionState()
            rebuildTree()
        }

        override fun onDisconnected(e: HumlaException?) {
            _sessionState.value = SessionUiState(
                status = ConnectionStatus.DISCONNECTED,
                errorMessage = e?.message
            )
            _treeItems.value = emptyList()
        }

        override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) {
            rebuildTree()
        }

        override fun onChannelAdded(channel: IChannel?) {
            rebuildTree()
        }

        override fun onChannelRemoved(channel: IChannel?) {
            rebuildTree()
        }

        override fun onChannelStateUpdated(channel: IChannel?) {
            rebuildTree()
        }

        override fun onUserConnected(user: IUser?) {
            rebuildTree()
        }

        override fun onUserRemoved(user: IUser?, reason: String?) {
            rebuildTree()
        }

        override fun onUserStateUpdated(user: IUser?) {
            updateSessionState()
            rebuildTree()
        }

        override fun onUserTalkStateUpdated(user: IUser?) {
            rebuildTree()
        }
    }

    fun attachService(mumlaService: IMumlaService?) {
        service?.unregisterObserver(observer)
        service = mumlaService
        service?.registerObserver(observer)
        updateSessionState()
        rebuildTree()
    }

    fun detachService() {
        service?.unregisterObserver(observer)
        service = null
    }

    fun toggleChannelExpansion(channelId: Int) {
        val current = expandedChannels[channelId] ?: true
        expandedChannels[channelId] = !current
        rebuildTree()
    }

    fun joinChannel(channelId: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                service?.HumlaSession()?.joinChannel(channelId)
            } catch (e: Exception) {
                // Ignore disconnect races
            }
        }
    }

    fun toggleSelfMute() {
        val session = service?.HumlaSession() ?: return
        val user = session.sessionUser ?: return
        val newMute = !user.isSelfMuted
        session.setSelfMuteDeafState(newMute, user.isSelfDeafened)
        _sessionState.value = _sessionState.value.copy(isSelfMuted = newMute)
    }

    fun toggleSelfDeafen() {
        val session = service?.HumlaSession() ?: return
        val user = session.sessionUser ?: return
        val newDeaf = !user.isSelfDeafened
        session.setSelfMuteDeafState(if (newDeaf) true else user.isSelfMuted, newDeaf)
        _sessionState.value = _sessionState.value.copy(
            isSelfDeafened = newDeaf,
            isSelfMuted = if (newDeaf) true else _sessionState.value.isSelfMuted
        )
    }

    fun onTalkKeyDown() {
        service?.onTalkKeyDown()
        _sessionState.value = _sessionState.value.copy(isTransmitting = true)
    }

    fun onTalkKeyUp() {
        service?.onTalkKeyUp()
        _sessionState.value = _sessionState.value.copy(isTransmitting = false)
    }

    fun updateSessionState() {
        val svc = service ?: return
        if (!svc.isConnected) {
            _sessionState.value = _sessionState.value.copy(status = ConnectionStatus.DISCONNECTED)
            return
        }

        try {
            val session: IHumlaSession? = svc.HumlaSession()
            if (session != null) {
                val selfUser = session.sessionUser
                val selfChannel = selfUser?.channel
                _sessionState.value = _sessionState.value.copy(
                    status = ConnectionStatus.SYNCHRONIZED,
                    serverName = svc.targetServer?.name ?: svc.targetServer?.host ?: "Mumble Server",
                    serverHost = svc.targetServer?.host ?: "",
                    serverRelease = session.serverRelease ?: "",
                    pingTcp = session.tcpLatency,
                    pingUdp = session.udpLatency,
                    currentBandwidthBps = session.currentBandwidth,
                    maxBandwidthBps = session.maxBandwidth,
                    selfSessionId = session.sessionId,
                    selfChannelId = selfChannel?.id ?: -1,
                    isSelfMuted = selfUser?.isSelfMuted ?: false,
                    isSelfDeafened = selfUser?.isSelfDeafened ?: false
                )
            }
        } catch (e: HumlaDisconnectedException) {
            _sessionState.value = _sessionState.value.copy(status = ConnectionStatus.DISCONNECTED)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun rebuildTree() {
        val svc = service ?: return
        if (!svc.isConnected) {
            _treeItems.value = emptyList()
            return
        }

        try {
            val session: IHumlaSession = svc.HumlaSession() ?: return
            val rootChannel: IChannel = session.rootChannel ?: return
            val selfSession = session.sessionId
            val currentChannelId = session.sessionUser?.channel?.id ?: -1

            val items = mutableListOf<TreeItem>()
            flattenChannelTree(rootChannel, depth = 0, selfSession, currentChannelId, items)
            _treeItems.value = items
        } catch (e: Exception) {
            // Service might be disconnected during tree traversal
        }
    }

    private fun flattenChannelTree(
        channel: IChannel,
        depth: Int,
        selfSession: Int,
        currentChannelId: Int,
        result: MutableList<TreeItem>
    ) {
        val isExpanded = expandedChannels[channel.id] ?: true
        val channelItemId = (channel.id.toLong() and 0xFFFFFFFFL) or (1L shl 32)

        result.add(
            TreeItem.ChannelItem(
                id = channelItemId,
                depth = depth,
                channelId = channel.id,
                name = channel.name ?: "Root",
                userCount = channel.subchannelUserCount + channel.users.size,
                isExpanded = isExpanded,
                isLinked = channel.links.isNotEmpty(),
                description = channel.description,
                isCurrentChannel = channel.id == currentChannelId
            )
        )

        if (isExpanded) {
            // Add users in this channel
            for (u in channel.users) {
                val userItemId = (u.session.toLong() and 0xFFFFFFFFL) or (1L shl 33)
                val isSelf = u.session == selfSession
                result.add(
                    TreeItem.UserItem(
                        id = userItemId,
                        depth = depth + 1,
                        session = u.session,
                        userId = u.userId,
                        name = u.name ?: "User",
                        channelId = channel.id,
                        talkState = u.talkState ?: TalkState.PASSIVE,
                        isMuted = u.isMuted,
                        isDeafened = u.isDeafened,
                        isSelfMuted = u.isSelfMuted,
                        isSelfDeafened = u.isSelfDeafened,
                        isSuppressed = u.isSuppressed,
                        isPrioritySpeaker = u.isPrioritySpeaker,
                        comment = u.comment,
                        isLocalMuted = u.isLocalMuted,
                        isSelf = isSelf
                    )
                )
            }

            // Recurse into subchannels
            for (sub in channel.subchannels) {
                flattenChannelTree(sub, depth + 1, selfSession, currentChannelId, result)
            }
        }
    }
}
