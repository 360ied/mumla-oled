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

package se.lublin.mumla.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.client.ClientState
import se.lublin.humla.state.SessionState
import se.lublin.mumla.service.IChatMessage

/** The server's message-size limits; 0 means unlimited or not yet known. */
data class ChatLimits(
    val messageLength: Int = 0,
    val imageMessageLength: Int = 0,
)

/** Why a send did or did not reach the session. */
enum class ChatSendResult {
    SENT,
    NOT_CONNECTED,
    NO_TARGET,
    TOO_LARGE,
}

/**
 * The chat screen: the log feature's messages, the parent screen's chat
 * target, and sending. A sent message reaches the log through the session's
 * `TextMessageSent` event, exactly once; the ViewModel never appends it
 * itself.
 */
class ChatViewModel(
    /** The chat log feature's current server log. */
    val messages: StateFlow<List<IChatMessage>>,
    /** The chat target owned by the parent channel screen. */
    val target: StateFlow<ChatTargetDisplay>,
    private val clientState: StateFlow<ClientState>,
    private val sessionState: StateFlow<SessionState>,
    private val sendText: (channels: List<Int>, trees: List<Int>, users: List<Int>, message: String) -> Unit,
    private val clearLog: () -> Unit,
) : ViewModel() {

    /** The server's current limits, for the image compressor's loop. */
    fun limits(): ChatLimits {
        val settings = sessionState.value.server.info.settings
        return ChatLimits(
            messageLength = settings?.messageLength ?: 0,
            imageMessageLength = settings?.imageMessageLength ?: 0,
        )
    }

    /**
     * Sends [message] to the current target, after today's size checks. A
     * message that is not connected or has no resolvable target is refused so
     * the screen keeps the editor's text.
     */
    fun send(message: String, image: Boolean = false): ChatSendResult {
        if (clientState.value !is ClientState.Connected) {
            return ChatSendResult.NOT_CONNECTED
        }
        val limits = limits()
        val limit = if (image) limits.imageMessageLength else limits.messageLength
        if (limit > 0 && message.length > limit) {
            return ChatSendResult.TOO_LARGE
        }
        val resolved = resolveTarget() ?: return ChatSendResult.NO_TARGET
        sendText(resolved.channels, emptyList(), resolved.users, message)
        return ChatSendResult.SENT
    }

    fun clear() = clearLog()

    /** True while a live session exists; the image picker gates on it. */
    fun isConnected(): Boolean = clientState.value is ClientState.Connected

    private data class ResolvedTarget(val channels: List<Int>, val users: List<Int>)

    private fun resolveTarget(): ResolvedTarget? {
        val chatTarget = target.value.target
        return when {
            chatTarget == null -> {
                val selfChannel = sessionState.value.server.self?.channelId
                    ?: return null
                ResolvedTarget(channels = listOf(selfChannel), users = emptyList())
            }
            chatTarget.session != null ->
                ResolvedTarget(channels = emptyList(), users = listOf(chatTarget.session))
            chatTarget.channelId != null ->
                ResolvedTarget(channels = listOf(chatTarget.channelId), users = emptyList())
            else -> null
        }
    }
}
