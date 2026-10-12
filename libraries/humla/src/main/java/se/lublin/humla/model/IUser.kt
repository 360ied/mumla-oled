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

import com.google.protobuf.ByteString

/**
 * Created by andrew on 15/10/15.
 */
interface IUser {
    val session: Int

    var channel: Channel?

    var userId: Int

    var name: String?

    var comment: String?

    val commentHash: ByteArray?

    fun setCommentHash(commentHash: ByteString?)

    val texture: ByteArray?

    fun setTexture(texture: ByteString?)

    val textureHash: ByteArray?

    fun setTextureHash(textureHash: ByteString?)

    fun hasTexture(): Boolean

    val textureCacheKey: Int

    var hash: String?

    var isMuted: Boolean

    var isDeafened: Boolean

    var isSuppressed: Boolean

    var isSelfMuted: Boolean

    var isSelfDeafened: Boolean

    var isPrioritySpeaker: Boolean

    var isRecording: Boolean

    var isLocalMuted: Boolean

    var isLocalIgnored: Boolean

    var talkState: TalkState

    val listeningChannels: Set<Int>

    fun isListeningTo(channelId: Int): Boolean
}
