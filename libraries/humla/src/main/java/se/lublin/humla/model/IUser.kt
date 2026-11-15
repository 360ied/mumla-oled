/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.model

/**
 * Live user handle view. Read-only: [talkState] is voice-owned and read
 * live from TalkActivity (absent means passive). All session writes are
 * Session intents; navigation returns interfaces.
 */
interface IUser {
    // Talk state is voice-owned and read live from TalkActivity (absent =
    // passive); see UserHandle. No transition memory lives on the handle.
    val session: Int

    val channel: IChannel?

    val userId: Int

    val name: String?

    val comment: String?

    val commentHash: ByteArray?

    val texture: ByteArray?

    val textureHash: ByteArray?

    fun hasTexture(): Boolean

    val textureCacheKey: Int

    val hash: String?

    val isMuted: Boolean

    val isDeafened: Boolean

    val isSuppressed: Boolean

    val isSelfMuted: Boolean

    val isSelfDeafened: Boolean

    val isPrioritySpeaker: Boolean

    val isRecording: Boolean

    val isLocalMuted: Boolean

    val isLocalIgnored: Boolean

    val talkState: TalkState

    val listeningChannels: Set<Int>

    fun isListeningTo(channelId: Int): Boolean
}
