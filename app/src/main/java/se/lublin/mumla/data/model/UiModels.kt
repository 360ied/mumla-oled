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

package se.lublin.mumla.data.model

import androidx.compose.runtime.Immutable
import se.lublin.humla.model.TalkState

@Immutable
data class ServerCardUiState(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val pingMs: Long = -1,
    val userCount: Int = -1,
    val maxUsers: Int = -1,
    val isConnected: Boolean = false
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCHRONIZING,
    SYNCHRONIZED,
    RECONNECTING
}

@Immutable
data class SessionUiState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val serverName: String = "",
    val serverHost: String = "",
    val serverRelease: String = "",
    val pingTcp: Long = -1,
    val pingUdp: Long = -1,
    val currentBandwidthBps: Int = -1,
    val maxBandwidthBps: Int = -1,
    val selfSessionId: Int = -1,
    val selfChannelId: Int = -1,
    val isSelfMuted: Boolean = false,
    val isSelfDeafened: Boolean = false,
    val isTransmitting: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
sealed interface TreeItem {
    val id: Long
    val depth: Int

    @Immutable
    data class ChannelItem(
        override val id: Long,
        override val depth: Int,
        val channelId: Int,
        val name: String,
        val userCount: Int,
        val isExpanded: Boolean,
        val isLinked: Boolean,
        val description: String? = null,
        val isCurrentChannel: Boolean = false
    ) : TreeItem

    @Immutable
    data class UserItem(
        override val id: Long,
        override val depth: Int,
        val session: Int,
        val userId: Int,
        val name: String,
        val channelId: Int,
        val talkState: TalkState = TalkState.PASSIVE,
        val isMuted: Boolean = false,
        val isDeafened: Boolean = false,
        val isSelfMuted: Boolean = false,
        val isSelfDeafened: Boolean = false,
        val isSuppressed: Boolean = false,
        val isPrioritySpeaker: Boolean = false,
        val comment: String? = null,
        val isLocalMuted: Boolean = false,
        val isSelf: Boolean = false
    ) : TreeItem
}

enum class LogMessageType {
    TEXT,
    INFO,
    WARNING,
    ERROR
}

@Immutable
data class ChatMessageUiState(
    val id: String,
    val senderName: String,
    val timestamp: Long,
    val formattedTime: String,
    val contentHtml: String,
    val messageType: LogMessageType = LogMessageType.TEXT,
    val isPrivate: Boolean = false,
    val targetName: String? = null,
    val isSelf: Boolean = false,
    val imageBase64: String? = null
)

enum class ChatTargetMode {
    CURRENT_CHANNEL,
    SUBCHANNEL_TREE,
    PRIVATE_USER
}

@Immutable
data class ChatTargetUiState(
    val mode: ChatTargetMode = ChatTargetMode.CURRENT_CHANNEL,
    val targetName: String = "",
    val targetId: Int = -1
)
