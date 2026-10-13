/*
 * Copyright (C) 2014 Andrew Comminos
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
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet

// Final by intent (pre-image Java was non-final, but no subclass exists
// in-tree): documents sealing; re-open if extensibility is ever required.
class User : IUser, Comparable<User> {

    override val session: Int
    override var userId: Int = -1
    override var name: String? = null
    override var comment: String? = null
    private var commentHashValue: ByteString? = null
    private var textureValue: ByteString? = null
    private var textureHashValue: ByteString? = null
    override var hash: String? = null

    @Volatile
    override var isMuted: Boolean = false
        set(muted) {
            field = muted
            // Desktop parity (ClientUser::setMute): keep the admin pair coherent
            // the same way murmur coerces its broadcast.
            if (!muted) {
                isDeafened = false
            }
        }

    @Volatile
    override var isDeafened: Boolean = false
        set(deafened) {
            field = deafened
            // Desktop parity (ClientUser::setDeaf): deaf implies mute.
            if (deafened) {
                isMuted = true
            }
        }

    @Volatile
    override var isSuppressed: Boolean = false

    // Volatile: the optimistic self-mute path writes these on the main thread
    // while the server-echo path writes them on the TCP reader thread, and
    // UI readers observe them lock-free (mirrors isLocalMuted below).
    @Volatile
    override var isSelfMuted: Boolean = false
        set(muted) {
            field = muted
            // Desktop parity (ClientUser::setSelfMute): unmute implies undeafen.
            // murmur coerces the same way server-side; mirroring it here keeps
            // single-field packets (e.g. a deaf-only initial-state broadcast,
            // which omits self_mute) from leaving an incoherent mute=false,
            // deaf=true pair behind.
            if (!muted) {
                isSelfDeafened = false
            }
        }

    @Volatile
    override var isSelfDeafened: Boolean = false
        set(deafened) {
            field = deafened
            // Desktop parity (ClientUser::setSelfDeaf): deaf implies mute.
            if (deafened) {
                isSelfMuted = true
            }
        }

    override var isPrioritySpeaker: Boolean = false
    override var isRecording: Boolean = false

    private var channelField: Channel? = null

    @Volatile
    override var talkState: TalkState = TalkState.PASSIVE

    private val listeningChannelSet = CopyOnWriteArraySet<Int>()

    // Local state (both written on the TCP thread in ModelHandler and read
    // on UI; volatile for symmetry/visibility).
    @Volatile
    override var isLocalMuted: Boolean = false
    @Volatile
    override var isLocalIgnored: Boolean = false

    constructor() {
        session = 0
    }

    constructor(session: Int, name: String?) {
        this.session = session
        this.name = name
    }

    override var channel: Channel?
        get() = channelField
        set(channel) {
            channelField?.removeUser(this)
            channelField = channel
            channelField?.addUser(this)
        }

    override val commentHash: ByteArray?
        get() = commentHashValue?.toByteArray()

    override fun setCommentHash(commentHash: ByteString?) {
        commentHashValue = commentHash
    }

    override val texture: ByteArray?
        get() = textureValue?.toByteArray()

    override fun setTexture(texture: ByteString?) {
        textureValue = texture
    }

    override val textureHash: ByteArray?
        get() = textureHashValue?.toByteArray()

    override fun setTextureHash(textureHash: ByteString?) {
        textureHashValue = textureHash
    }

    override fun hasTexture(): Boolean {
        val texture = textureValue
        return texture != null && !texture.isEmpty
    }

    override val textureCacheKey: Int
        get() {
            val texture = textureValue
            if (texture != null && !texture.isEmpty) {
                val h = texture.hashCode()
                return if (h != 0) h else 1
            }
            val textureHash = textureHashValue
            if (textureHash != null && !textureHash.isEmpty) {
                val h = textureHash.hashCode()
                return if (h != 0) h else 1
            }
            return 0
        }

    override val listeningChannels: Set<Int>
        get() = Collections.unmodifiableSet(listeningChannelSet)

    fun addListeningChannel(channelId: Int) {
        listeningChannelSet.add(channelId)
    }

    fun removeListeningChannel(channelId: Int) {
        listeningChannelSet.remove(channelId)
    }

    override fun isListeningTo(channelId: Int): Boolean {
        return listeningChannelSet.contains(channelId)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        other as User

        if (session != other.session) return false

        return true
    }

    override fun hashCode(): Int {
        return session
    }

    override fun compareTo(other: User): Int {
        val thisName = name
        val otherName = other.name
        if (thisName == null && otherName == null) {
            return Integer.compare(session, other.session)
        }
        if (thisName == null) {
            return -1
        }
        if (otherName == null) {
            return 1
        }
        val nameCmp = thisName.compareTo(otherName, ignoreCase = true)
        if (nameCmp != 0) {
            return nameCmp
        }
        return Integer.compare(session, other.session)
    }
}
