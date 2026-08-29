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
import se.lublin.humla.model.IMessage
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.data.model.ChatMessageUiState
import se.lublin.mumla.data.model.ChatTargetMode
import se.lublin.mumla.data.model.ChatTargetUiState
import se.lublin.mumla.data.model.LogMessageType
import se.lublin.mumla.service.IMumlaService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatRepository(
    private val scope: CoroutineScope
) {
    private var service: IMumlaService? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val _messages = MutableStateFlow<List<ChatMessageUiState>>(emptyList())
    val messages: StateFlow<List<ChatMessageUiState>> = _messages.asStateFlow()

    private val _chatTarget = MutableStateFlow(ChatTargetUiState(mode = ChatTargetMode.CURRENT_CHANNEL, targetName = "Current Channel"))
    val chatTarget: StateFlow<ChatTargetUiState> = _chatTarget.asStateFlow()

    private val observer = object : HumlaObserver() {
        override fun onMessageLogged(message: IMessage?) {
            if (message == null) return
            val selfSession = service?.HumlaSession()?.sessionId ?: -1
            val isSelf = message.actor == selfSession
            val senderName = if (isSelf) "You" else (message.actorName ?: "User")
            val isChannel = !message.targetChannels.isNullOrEmpty() || !message.targetTrees.isNullOrEmpty()
            val targetName = if (isChannel) "Channel" else (if (isSelf) "Private" else "Direct Message")

            addMessage(
                ChatMessageUiState(
                    id = UUID.randomUUID().toString(),
                    senderName = senderName,
                    timestamp = System.currentTimeMillis(),
                    formattedTime = timeFormat.format(Date()),
                    contentHtml = message.message ?: "",
                    messageType = LogMessageType.TEXT,
                    isPrivate = !isChannel,
                    targetName = targetName,
                    isSelf = isSelf
                )
            )
        }

        override fun onLogInfo(message: String?) {
            if (message == null) return
            addSystemLog(message, LogMessageType.INFO)
        }

        override fun onLogWarning(message: String?) {
            if (message == null) return
            addSystemLog(message, LogMessageType.WARNING)
        }

        override fun onLogError(message: String?) {
            if (message == null) return
            addSystemLog(message, LogMessageType.ERROR)
        }
    }

    fun attachService(mumlaService: IMumlaService?) {
        service?.unregisterObserver(observer)
        service = mumlaService
        service?.registerObserver(observer)
    }

    fun detachService() {
        service?.unregisterObserver(observer)
        service = null
    }

    fun setChatTarget(target: ChatTargetUiState) {
        _chatTarget.value = target
    }

    fun sendMessage(textHtml: String) {
        if (textHtml.isBlank()) return
        val svc = service ?: return
        val session = svc.HumlaSession() ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val target = _chatTarget.value
                when (target.mode) {
                    ChatTargetMode.CURRENT_CHANNEL -> {
                        val currentChan = session.sessionUser?.channel
                        if (currentChan != null) {
                            session.sendChannelTextMessage(currentChan.id, textHtml, false)
                        }
                    }
                    ChatTargetMode.SUBCHANNEL_TREE -> {
                        val currentChan = session.sessionUser?.channel
                        if (currentChan != null) {
                            session.sendChannelTextMessage(currentChan.id, textHtml, true)
                        }
                    }
                    ChatTargetMode.PRIVATE_USER -> {
                        if (target.targetId >= 0) {
                            session.sendUserTextMessage(target.targetId, textHtml)
                        }
                    }
                }
            } catch (e: Exception) {
                addSystemLog("Failed to send message: ${e.message}", LogMessageType.ERROR)
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    private fun addMessage(message: ChatMessageUiState) {
        val current = _messages.value.toMutableList()
        current.add(0, message) // Prepend so latest is index 0 for reverseLayout LazyColumn
        _messages.value = current
    }

    private fun addSystemLog(logText: String, type: LogMessageType) {
        addMessage(
            ChatMessageUiState(
                id = UUID.randomUUID().toString(),
                senderName = "System",
                timestamp = System.currentTimeMillis(),
                formattedTime = timeFormat.format(Date()),
                contentHtml = logText,
                messageType = type,
                isPrivate = false
            )
        )
    }
}
