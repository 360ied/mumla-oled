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

import se.lublin.humla.Constants
import se.lublin.humla.protocol.MAX_AMPLITUDE_BOOST
import se.lublin.humla.protocol.MAX_INPUT_BITRATE
import se.lublin.humla.protocol.MIN_AMPLITUDE_BOOST
import se.lublin.humla.protocol.MIN_INPUT_BITRATE

/**
 * Maps user and server knobs to [VoiceSettings]. Reuses the conversion
 * rules from InputModes and ActivityInputMode without depending on them.
 */
object VoiceSettingsMapper {
    fun inputModeFor(transmitMode: Int): InputMode = when (transmitMode) {
        Constants.TRANSMIT_PUSH_TO_TALK -> InputMode.PUSH_TO_TALK
        Constants.TRANSMIT_CONTINUOUS -> InputMode.CONTINUOUS
        Constants.TRANSMIT_VOICE_ACTIVITY -> InputMode.VOICE_ACTIVITY
        else -> throw IllegalArgumentException("Unknown transmit mode: $transmitMode")
    }

    fun vadFor(threshold: Float): Pair<Float, Float> {
        require(threshold.isFinite()) { "detectionThreshold must be finite" }
        val max = threshold.coerceIn(0.0f, 1.0f)
        val min = max * 0.7f
        return max to min
    }

    /**
     * Raw half-duplex preference (Settings.isHalfDuplex). Gating to the
     * push-to-talk mode happens at pipeline build (legacy InputModes
     * parity: the holder keeps the raw flag, the builder gates), so the
     * preference survives transmit-mode round-trips; the spec effective
     * value is `halfDuplex && inputMode == PUSH_TO_TALK`.
     */
    fun halfDuplexFor(transmitMode: Int, halfDuplex: Boolean): Boolean {
        return halfDuplex && transmitMode == Constants.TRANSMIT_PUSH_TO_TALK
    }

    fun initialFromDefaults(
        transmitMode: Int = Constants.TRANSMIT_VOICE_ACTIVITY,
        detectionThreshold: Float = 1.0f,
        amplitudeBoost: Float = 1.0f,
        inputQuality: Int = 40000,
        framesPerPacket: Int = Constants.DEFAULT_FRAMES_PER_PACKET,
        halfDuplex: Boolean = false,
        adaptiveLeveler: Boolean = true,
    ): VoiceSettings {
        require(detectionThreshold.isFinite()) { "detectionThreshold must be finite" }
        require(amplitudeBoost.isFinite()) { "amplitudeBoost must be finite" }
        require(amplitudeBoost in MIN_AMPLITUDE_BOOST..MAX_AMPLITUDE_BOOST) {
            "amplitudeBoost must be in $MIN_AMPLITUDE_BOOST..$MAX_AMPLITUDE_BOOST"
        }
        require(inputQuality in MIN_INPUT_BITRATE..MAX_INPUT_BITRATE) {
            "inputQuality must be in $MIN_INPUT_BITRATE..$MAX_INPUT_BITRATE"
        }
        val (vadMax, vadMin) = vadFor(detectionThreshold)
        return VoiceSettings(
            inputMode = inputModeFor(transmitMode),
            vadMax = vadMax,
            vadMin = vadMin,
            amplitudeBoost = amplitudeBoost,
            targetBitrate = inputQuality,
            targetFramesPerPacket = Constants.sanitizeFramesPerPacket(framesPerPacket),
            halfDuplex = halfDuplex,
            adaptiveLeveler = adaptiveLeveler,
        )
    }

    fun update(
        current: VoiceSettings,
        transmitMode: Int? = null,
        detectionThreshold: Float? = null,
        amplitudeBoost: Float? = null,
        inputQuality: Int? = null,
        framesPerPacket: Int? = null,
        halfDuplex: Boolean? = null,
        adaptiveLeveler: Boolean? = null,
    ): VoiceSettings {
        var next = current
        if (transmitMode != null) {
            next = next.copy(inputMode = inputModeFor(transmitMode))
        }
        if (detectionThreshold != null) {
            val (vadMax, vadMin) = vadFor(detectionThreshold)
            next = next.copy(vadMax = vadMax, vadMin = vadMin)
        }
        if (amplitudeBoost != null) {
            require(amplitudeBoost.isFinite()) { "amplitudeBoost must be finite" }
            require(amplitudeBoost in MIN_AMPLITUDE_BOOST..MAX_AMPLITUDE_BOOST) {
                "amplitudeBoost must be in $MIN_AMPLITUDE_BOOST..$MAX_AMPLITUDE_BOOST"
            }
            next = next.copy(amplitudeBoost = amplitudeBoost)
        }
        if (inputQuality != null) {
            require(inputQuality in MIN_INPUT_BITRATE..MAX_INPUT_BITRATE) {
                "inputQuality must be in $MIN_INPUT_BITRATE..$MAX_INPUT_BITRATE"
            }
            next = next.copy(targetBitrate = inputQuality)
        }
        if (framesPerPacket != null) {
            next = next.copy(targetFramesPerPacket = Constants.sanitizeFramesPerPacket(framesPerPacket))
        }
        // Raw preference stored; the builder gates to push-to-talk (legacy
        // holder parity), so mode-only switches preserve the PTT+half
        // preference across a VAD round-trip.
        if (halfDuplex != null) {
            next = next.copy(halfDuplex = halfDuplex)
        }
        if (adaptiveLeveler != null) {
            next = next.copy(adaptiveLeveler = adaptiveLeveler)
        }
        return next
    }
}
