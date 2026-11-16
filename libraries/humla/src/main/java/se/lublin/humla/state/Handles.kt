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

package se.lublin.humla.state

import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.humla.voice.TalkActivity

/**
 * Live compatibility handles. See
 * docs/target-architecture/state.md#compatibility-handles-migration-only.
 *
 * Adapters keep references and re-read after observer callbacks. Snapshots
 * would compile and silently freeze screens, so these read the current
 * value on every access. Each handle caches the last value it saw; when
 * the user or channel is gone from state, reads return that cached value,
 * as a departed model object does today. Navigation returns handles.
 * Equal when IDs are equal. Read-only: [talkState] reads the live talk
 * levels (absent means passive), unless [talkStateOverride] pins it — the
 * service uses the override for the local user's repaint hint. The live
 * read itself is self-aware: the local session never appears in the
 * remote [TalkActivity.users] table, so a handle whose session is the
 * current self session reports TALKING while
 * [TalkActivity.selfTalking] is true. Without this, freshly bound rows
 * (channel list, overlay) always read the self user as passive until the
 * next talk-activity hint arrives.
 */
class UserHandle(
    private val sessionId: Int,
    private val state: StateFlow<SessionState>,
    private val talkActivity: StateFlow<TalkActivity>? = null,
    seed: UserInfo? = null,
    private val talkStateOverride: TalkState? = null,
) : IUser {
    override val session: Int get() = sessionId

    // Voice-owned, read live on every access: remote sessions read the
    // live table (absent means passive, which is also what a missing
    // publisher means, so call sites without voice stay compiling and show
    // passive, as fresh handles always did). The local session never
    // appears in the remote table — its level lives in
    // TalkActivity.selfTalking — so a handle for the current self session
    // reports TALKING while selfTalking is true. This gives freshly
    // created indicators (channel list rows, overlay rows, talk button)
    // the correct initial state without waiting for the next repaint hint.
    override val talkState: TalkState
        get() {
            talkStateOverride?.let { return it }
            val talk = talkActivity?.value ?: return TalkState.PASSIVE
            if (talk.selfTalking && sessionId == state.value.server.selfSession) {
                return TalkState.TALKING
            }
            return talk.users[sessionId] ?: TalkState.PASSIVE
        }

    private var cached: UserInfo? = seed

    private fun current(): UserInfo? {
        val found = state.value.server.users[sessionId]
        if (found != null) cached = found
        return found ?: cached
    }

    private fun live(): UserInfo? = state.value.server.users[sessionId]

    override val channel: IChannel?
        get() {
            // Detached (removed) users read null, as today.
            val id = live()?.channelId ?: return null
            return ChannelHandle(id, state, talkActivity)
        }

    override val userId: Int get() = current()?.userId ?: -1
    override val name: String? get() = current()?.name
    override val comment: String? get() = current()?.comment
    override val commentHash: ByteArray? get() = current()?.commentHash?.toByteArray()
    override val texture: ByteArray? get() = current()?.texture?.toByteArray()
    override val textureHash: ByteArray? get() = current()?.textureHash?.toByteArray()

    override fun hasTexture(): Boolean {
        val t = current()?.texture
        return t != null && !t.isEmpty
    }

    override val textureCacheKey: Int
        get() {
            val info = current() ?: return 0
            val texture = info.texture
            if (texture != null && !texture.isEmpty) {
                val h = texture.hashCode()
                return if (h != 0) h else 1
            }
            val hash = info.textureHash
            if (hash != null && !hash.isEmpty) {
                val h = hash.hashCode()
                return if (h != 0) h else 1
            }
            return 0
        }

    override val hash: String? get() = current()?.certHash
    override val isMuted: Boolean get() = current()?.muted ?: false
    override val isDeafened: Boolean get() = current()?.deafened ?: false
    override val isSuppressed: Boolean get() = current()?.suppressed ?: false
    override val isSelfMuted: Boolean get() = current()?.selfMuted ?: false
    override val isSelfDeafened: Boolean get() = current()?.selfDeafened ?: false
    override val isPrioritySpeaker: Boolean get() = current()?.prioritySpeaker ?: false
    override val isRecording: Boolean get() = current()?.recording ?: false
    override val isLocalMuted: Boolean get() = current()?.localMuted ?: false
    override val isLocalIgnored: Boolean get() = current()?.localIgnored ?: false

    override val listeningChannels: Set<Int>
        get() = current()?.listeningChannels ?: emptySet()

    override fun isListeningTo(channelId: Int): Boolean =
        current()?.listeningChannels?.contains(channelId) ?: false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserHandle) return false
        return sessionId == other.sessionId
    }

    override fun hashCode(): Int = sessionId
}

class ChannelHandle(
    private val channelId: Int,
    private val state: StateFlow<SessionState>,
    private val talkActivity: StateFlow<TalkActivity>? = null,
    seed: ChannelInfo? = null,
) : IChannel {
    override val id: Int get() = channelId

    private var cached: ChannelInfo? = seed

    private fun current(): ChannelInfo? {
        val found = state.value.server.channels[channelId]
        if (found != null) cached = found
        return found ?: cached
    }

    private fun live(): ChannelInfo? = state.value.server.channels[channelId]

    override val position: Int get() = current()?.position ?: 0
    override val isTemporary: Boolean get() = current()?.temporary ?: false
    override val parent: IChannel?
        get() {
            // Detached subtree nodes read null, as today (cleared on remove).
            val info = live() ?: return null
            val parentId = info.parentId ?: return null
            return ChannelHandle(parentId, state, talkActivity)
        }
    override val name: String? get() = current()?.name
    override val description: String? get() = current()?.description
    // toByteArray() already returns a fresh copy; no extra clone needed.
    override val descriptionHash: ByteArray? get() = current()?.descriptionHash?.toByteArray()

    override val users: List<IUser>
        get() {
            if (live() == null) return emptyList()
            return state.value.server.tree.users(channelId).map { UserHandle(it.session, state, talkActivity) }
        }

    override val subchannels: List<IChannel>
        get() {
            if (live() == null) return emptyList()
            return state.value.server.tree.children(channelId).map { ChannelHandle(it.id, state, talkActivity) }
        }

    override val subchannelUserCount: Int
        get() {
            if (live() == null) return 0
            return state.value.server.tree.userCountInSubtree(channelId)
        }

    override val links: List<IChannel>
        get() {
            if (live() == null) return emptyList()
            val ids = live()?.links ?: return emptyList()
            val out = ArrayList<IChannel>(ids.size)
            for (linkId in ids) {
                if (state.value.server.channels.containsKey(linkId)) {
                    out.add(ChannelHandle(linkId, state, talkActivity))
                }
            }
            return out
        }

    override val permissions: Int get() = current()?.permissions ?: 0
    override val maxUsers: Int get() = current()?.maxUsers ?: 0
    override val isEnterRestricted: Boolean get() = current()?.enterRestricted ?: false
    override fun canEnter(): Boolean = current()?.canEnter ?: true

    override val isListening: Boolean
        get() {
            val s = state.value.server
            val self = s.self ?: return false
            return self.listeningChannels.contains(channelId)
        }

    override val listeningVolume: Float
        get() = state.value.local.listeningVolumes[channelId] ?: 1.0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelHandle) return false
        return channelId == other.channelId
    }

    override fun hashCode(): Int = channelId
}
