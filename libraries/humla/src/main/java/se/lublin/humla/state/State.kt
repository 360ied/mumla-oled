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

import com.google.protobuf.ByteString
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf

/**
 * Immutable session state. See
 * docs/target-architecture/state.md.
 *
 * One immutable value per session, held in a MutableStateFlow owned by the
 * Session. Every change is `state.update { ... }` with a pure transition.
 * Android-free: no android.* imports.
 */
data class SessionState(
    val server: ServerState,
    val local: LocalState,
)

enum class SyncPhase { HANDSHAKING, SYNCING, SYNCED }

data class ServerState(
    val phase: SyncPhase,
    val info: ServerInfo,
    val selfSession: Int?,
    val rootPermissions: Int,
    val channels: PersistentMap<Int, ChannelInfo>,
    val users: PersistentMap<Int, UserInfo>,
) {
    // Derived, not part of the value: excluded from the immutability walk
    // (walk follows primary-constructor properties only) and computed lazily.
    val tree: ChannelTree by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ChannelTree.build(channels, users)
    }
    val self: UserInfo? get() = selfSession?.let(users::get)
}

data class ServerInfo(
    val versionV2: Long?,
    val release: String?,
    val os: String?,
    val osVersion: String?,
    val opus: Boolean,
    val maxBandwidth: Int?,
    val welcomeText: String?,
    val settings: ServerSettingsInfo?,
)

data class ServerSettingsInfo(
    val allowHtml: Boolean,
    val messageLength: Int,
    val imageMessageLength: Int,
    val maxUsers: Int,
)

data class ChannelInfo(
    val id: Int,
    val parentId: Int?,
    val name: String?,
    val position: Int,
    val temporary: Boolean,
    val description: String?,
    val descriptionHash: ByteString?,
    val links: PersistentSet<Int>,
    val maxUsers: Int,
    val enterRestricted: Boolean,
    val canEnter: Boolean,
    val permissions: Int,
)

data class UserInfo(
    val session: Int,
    val userId: Int?,
    val name: String?,
    val channelId: Int?,
    val certHash: String?,
    val comment: String?,
    val commentHash: ByteString?,
    val texture: ByteString?,
    val textureHash: ByteString?,
    val muted: Boolean,
    val deafened: Boolean,
    val suppressed: Boolean,
    val selfMuted: Boolean,
    val selfDeafened: Boolean,
    val prioritySpeaker: Boolean,
    val recording: Boolean,
    val listeningChannels: PersistentSet<Int>,
    val localMuted: Boolean,
    val localIgnored: Boolean,
)

data class LocalState(
    val selfMuteDesired: Boolean?,
    val selfDeafDesired: Boolean?,
    val pttPressed: Boolean,
    val voiceTargetId: Int,
    val whisperTargets: PersistentMap<Int, WhisperTarget>,
    val listeningVolumes: PersistentMap<Int, Float>,
)

data class WhisperTarget(
    val channelId: Int,
    val includeLinked: Boolean,
    val includeSubchannels: Boolean,
    val group: String?,
)

enum class VoiceTargetMode {
    NORMAL,
    WHISPER,
    SERVER_LOOPBACK;

    companion object {
        @JvmStatic
        fun fromId(targetId: Byte): VoiceTargetMode {
            val id = targetId.toInt() and 0xFF
            if (id == 0) {
                return NORMAL
            } else if (id in 1..30) {
                return WHISPER
            } else if (id == 31) {
                return SERVER_LOOPBACK
            } else {
                throw IllegalArgumentException("Unknown voice target mode id: $id")
            }
        }

        @JvmStatic
        fun fromInt(targetId: Int): VoiceTargetMode = fromId(targetId.toByte())
    }
}

/** Empty starting value: HANDSHAKING with no channels or users. */
fun emptySessionState(): SessionState = SessionState(
    server = ServerState(
        phase = SyncPhase.HANDSHAKING,
        info = ServerInfo(
            versionV2 = null,
            release = null,
            os = null,
            osVersion = null,
            opus = false,
            maxBandwidth = null,
            welcomeText = null,
            settings = null,
        ),
        selfSession = null,
        rootPermissions = 0,
        channels = persistentMapOf(),
        users = persistentMapOf(),
    ),
    local = LocalState(
        selfMuteDesired = null,
        selfDeafDesired = null,
        pttPressed = false,
        voiceTargetId = 0,
        whisperTargets = persistentMapOf(),
        listeningVolumes = persistentMapOf(),
    ),
)

/** Today's getVoiceTargetMode equivalent: derive the mode from local state. */
fun LocalState.voiceTargetMode(): VoiceTargetMode =
    VoiceTargetMode.fromInt(voiceTargetId)

/** Active whisper target for ids 1..30, null otherwise. */
fun SessionState.activeWhisperTarget(): WhisperTarget? =
    if (local.voiceTargetId in 1..30) local.whisperTargets[local.voiceTargetId] else null

fun UserInfo.hasTexture(): Boolean = texture != null && !texture.isEmpty

/** Self-mute gate. Desired value drives the gate; echo updates the model. */
fun effectiveSelfMute(s: SessionState): Boolean {
    val self = s.server.self ?: return true
    val desired = s.local.selfMuteDesired ?: self.selfMuted
    val deafDesired = s.local.selfDeafDesired ?: self.selfDeafened
    return desired || deafDesired || self.selfDeafened
}

// Channel-view helpers mirroring today's Channel/User derived reads.
fun ChannelInfo.isListening(state: SessionState): Boolean {
    val self = state.server.self ?: return false
    return self.listeningChannels.contains(id)
}

fun ChannelInfo.listeningVolume(state: SessionState): Float =
    state.local.listeningVolumes[id] ?: 1.0f

/** Display-name lookup for a whisper target id (channel name in state). */
fun SessionState.whisperTargetName(id: Int): String? {
    val target = local.whisperTargets[id] ?: return null
    return server.channels[target.channelId]?.name
}
