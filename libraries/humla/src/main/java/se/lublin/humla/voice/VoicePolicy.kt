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
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.effectiveSelfMute

/**
 * Target-architecture voice policy and settings. See
 * docs/tech-debt-whales/target-architecture/voice.md#voicepolicy and
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
     * Output-side local mutes, published for step-5a output gating. The
     * interim capture path ([AudioHandler.applyInterimPolicy]) consumes
     * only transmitAllowed/pttPressed/bitrate/framesPerPacket/voiceTarget;
     * render-side muting stays with the legacy AudioOutput model lookup.
     */
    val locallyMuted: PersistentSet<Int>,
) {
    companion object {
        fun mutedFallback(settings: VoiceSettings): VoicePolicy = VoicePolicy(
            transmitAllowed = false,
            pttPressed = false,
            voiceTarget = 0,
            bitrate = settings.targetBitrate,
            framesPerPacket = settings.targetFramesPerPacket,
            locallyMuted = persistentSetOf(),
        )
    }
}

fun voicePolicy(s: SessionState, v: VoiceSettings): VoicePolicy {
    val self = s.server.self
    val transmit = s.server.phase == SyncPhase.SYNCED && self != null &&
        !effectiveSelfMute(s) && !self.muted && !self.deafened && !self.suppressed
    val encoder = AudioHandler.computeEffectiveConfig(
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
    )
}
