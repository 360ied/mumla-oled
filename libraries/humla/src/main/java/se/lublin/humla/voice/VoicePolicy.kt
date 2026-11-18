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

package se.lublin.humla.voice

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import se.lublin.humla.Constants
import se.lublin.humla.net.ProtobufMessages
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.effectiveSelfMute

/**
 * Target-architecture voice policy and settings. See
 * docs/target-architecture/voice.md#voicepolicy and
 * #policy-and-settings-handoff.
 *
 * Android-free: pure Kotlin plus persistent collections so the mapping
 * runs in plain JVM tests with virtual time.
 */
enum class InputMode {
    VOICE_ACTIVITY,
    PUSH_TO_TALK,
    CONTINUOUS,
}

data class VoiceSettings(
    val inputMode: InputMode,
    val vadMax: Float,
    val vadMin: Float,
    val amplitudeBoost: Float,
    val targetBitrate: Int,
    val targetFramesPerPacket: Int,
    val halfDuplex: Boolean,
    val adaptiveLeveler: Boolean,
) {
    companion object {
        fun initial(): VoiceSettings = VoiceSettings(
            inputMode = InputMode.VOICE_ACTIVITY,
            vadMax = 1.0f,
            vadMin = 0.7f,
            amplitudeBoost = 1.0f,
            targetBitrate = 40000,
            targetFramesPerPacket = 2,
            halfDuplex = false,
            adaptiveLeveler = true,
        )
    }
}

data class VoicePolicy(
    val transmitAllowed: Boolean,
    val pttPressed: Boolean,
    val voiceTarget: Int,
    val bitrate: Int,
    val framesPerPacket: Int,
    /**
     * Output-side local mutes for the native sender filter. Render-side
     * muting stays native; no Kotlin model lookup remains.
     */
    val locallyMuted: PersistentSet<Int>,
    /**
     * Senders the native filter accepts (5a.3b). Beside locallyMuted
     * through the same policy sequence lock; overflow fails open (see
     * voice.md sender filter).
     */
    val knownSessions: PersistentSet<Int>,
) {
    companion object {
        fun mutedFallback(settings: VoiceSettings): VoicePolicy = VoicePolicy(
            transmitAllowed = false,
            pttPressed = false,
            voiceTarget = 0,
            bitrate = settings.targetBitrate,
            framesPerPacket = settings.targetFramesPerPacket,
            locallyMuted = persistentSetOf(),
            knownSessions = persistentSetOf(),
        )
    }
}

fun voicePolicy(s: SessionState, v: VoiceSettings): VoicePolicy {
    val self = s.server.self
    val transmit = s.server.phase == SyncPhase.SYNCED && self != null &&
        !effectiveSelfMute(s) && !self.muted && !self.deafened && !self.suppressed
    val encoder = computeEffectiveConfig(
        v.targetBitrate,
        v.targetFramesPerPacket,
        s.server.info.maxBandwidth ?: -1,
    )
    return VoicePolicy(
        transmitAllowed = transmit,
        pttPressed = s.local.pttPressed,
        voiceTarget = s.local.voiceTargetId,
        bitrate = encoder.bitrate,
        framesPerPacket = encoder.framesPerPacket,
        locallyMuted = s.server.users.values.filter { it.localMuted }
            .map { it.session }.toPersistentSet(),
        knownSessions = s.server.users.keys.toPersistentSet(),
    )
}

private fun sanitizeFramesPerPacket(fpp: Int): Int {
    return Constants.sanitizeFramesPerPacket(fpp)
}

/**
 * Pure bandwidth-degradation decision, moved from AudioHandler in 5a.4:
 * does not call Android APIs, so JVM tests cover it. Do not add logging,
 * native calls, or context access here.
 */
fun computeEffectiveConfig(bitrate: Int, framesPerPacket: Int, maxBandwidth: Int): EffectiveAudioConfig {
    if (maxBandwidth == -1) {
        return EffectiveAudioConfig(bitrate, sanitizeFramesPerPacket(framesPerPacket))
    }
    var degradedBitrate = bitrate
    var degradedFpp = framesPerPacket

    // fpp==6 is already the largest packet: no growth branch applies by
    // design, so only the bitrate loop below degrades (pinned by test).
    if (ProtobufMessages.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth) {
        if (degradedFpp <= 4 && maxBandwidth <= 32000) {
            degradedFpp = 4
        } else if (degradedFpp == 1 && maxBandwidth <= 64000) {
            degradedFpp = 2
        } else if (degradedFpp == 2 && maxBandwidth <= 48000) {
            degradedFpp = 4
        }
        while (ProtobufMessages.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth && degradedBitrate > 8000) {
            degradedBitrate -= 1000
        }
    }
    degradedBitrate = Math.max(8000, degradedBitrate)
    degradedFpp = sanitizeFramesPerPacket(degradedFpp)
    return EffectiveAudioConfig(degradedBitrate, degradedFpp)
}

/** Degraded effective audio config produced by bandwidth adaptation. Pure value type. */
class EffectiveAudioConfig(
    @JvmField val bitrate: Int,
    @JvmField val framesPerPacket: Int
)
