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

package se.lublin.mumla.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.mumla.data.model.ChatMessageUiState
import se.lublin.mumla.data.model.ChatTargetUiState
import se.lublin.mumla.data.model.ServerCardUiState
import se.lublin.mumla.data.model.SessionUiState
import se.lublin.mumla.data.model.TreeItem
import se.lublin.mumla.data.repository.ChatRepository
import se.lublin.mumla.data.repository.ServerRepository
import se.lublin.mumla.data.repository.SessionRepository
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.service.IMumlaService

class MumlaMainViewModel(
    private val database: MumlaDatabase
) : ViewModel() {

    val serverRepository = ServerRepository(database)
    val sessionRepository = SessionRepository(viewModelScope)
    val chatRepository = ChatRepository(viewModelScope)

    val servers: StateFlow<List<ServerCardUiState>> = serverRepository.servers
    val sessionState: StateFlow<SessionUiState> = sessionRepository.sessionState
    val treeItems: StateFlow<List<TreeItem>> = sessionRepository.treeItems
    val messages: StateFlow<List<ChatMessageUiState>> = chatRepository.messages
    val chatTarget: StateFlow<ChatTargetUiState> = chatRepository.chatTarget

    init {
        viewModelScope.launch {
            serverRepository.refreshServers()
        }
    }

    fun onServiceBound(service: IMumlaService?) {
        sessionRepository.attachService(service)
        chatRepository.attachService(service)
        viewModelScope.launch {
            val host = service?.targetServer?.host
            val port = service?.targetServer?.port ?: -1
            serverRepository.refreshServers(host, port)
        }
    }

    fun onServiceUnbound() {
        sessionRepository.detachService()
        chatRepository.detachService()
    }

    override fun onCleared() {
        super.onCleared()
        sessionRepository.detachService()
        chatRepository.detachService()
    }

    fun refreshServers() {
        viewModelScope.launch {
            serverRepository.refreshServers()
        }
    }

    fun addOrUpdateServer(server: Server) {
        viewModelScope.launch {
            if (server.id > 0) {
                serverRepository.updateServer(server)
            } else {
                serverRepository.addServer(server)
            }
        }
    }

    fun deleteServer(server: Server) {
        viewModelScope.launch {
            serverRepository.deleteServer(server)
        }
    }

    fun toggleChannelExpanded(channelId: Int) {
        sessionRepository.toggleChannelExpansion(channelId)
    }

    fun joinChannel(channelId: Int) {
        sessionRepository.joinChannel(channelId)
    }

    fun toggleSelfMute() {
        sessionRepository.toggleSelfMute()
    }

    fun toggleSelfDeafen() {
        sessionRepository.toggleSelfDeafen()
    }

    fun onTalkKeyDown() {
        sessionRepository.onTalkKeyDown()
    }

    fun onTalkKeyUp() {
        sessionRepository.onTalkKeyUp()
    }

    fun sendMessage(textHtml: String) {
        chatRepository.sendMessage(textHtml)
    }

    fun setChatTarget(target: ChatTargetUiState) {
        chatRepository.setChatTarget(target)
    }
}
