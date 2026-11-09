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

package se.lublin.humla.protocol

import se.lublin.humla.Constants
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.util.ConfigDelta

/**
 * Audio-knob holder (config track of the HumlaService decomposition): the
 * three input-mode instances plus the transmit mode and half-duplex flag.
 * Replaces five service fields with one. Writes are main-confined like
 * `apply()`; `transmitMode`/`halfDuplex` are additionally read off-main via
 * `HumlaService.getTransmitMode`, so they are volatile for visibility
 * (single-writer, no atomic group transitions).
 *
 * The holder starts with voice-activity defaults while
 * `AudioHandler.Builder.mInputMode` stays null until the first
 * mode-carrying delta runs: `MumlaService.buildConnectDelta` always sets
 * `transmitMode`, so the first `apply()` carries it in practice. A delta
 * without `transmitMode` leaves the previous holder value in place.
 */
class InputModes(
    val activity: ActivityInputMode =
        ActivityInputMode(ActivityInputMode.DEFAULT_VAD_MAX),
    val toggle: ToggleInputMode = ToggleInputMode(),
    val continuous: ContinuousInputMode = ContinuousInputMode(),
    @Volatile
    var transmitMode: Int = Constants.TRANSMIT_VOICE_ACTIVITY,
    @Volatile
    var halfDuplex: Boolean = false,
) {
    init {
        require(
            transmitMode == Constants.TRANSMIT_PUSH_TO_TALK ||
                transmitMode == Constants.TRANSMIT_CONTINUOUS ||
                transmitMode == Constants.TRANSMIT_VOICE_ACTIVITY
        ) {
            "Unknown transmit mode: $transmitMode"
        }
    }

    internal fun resolveInputMode(mode: Int): IInputMode {
        return when (mode) {
            Constants.TRANSMIT_PUSH_TO_TALK -> toggle
            Constants.TRANSMIT_CONTINUOUS -> continuous
            Constants.TRANSMIT_VOICE_ACTIVITY -> activity
            else ->
                throw IllegalArgumentException("Unknown transmit mode: $mode")
        }
    }
}

/**
 * Validates the audio half of [ConfigDelta]. Only `transmitMode` (via the
 * shared resolver) and NaN float knobs throw; other ranges (bitrate, FPP)
 * pass through to downstream sanitizers (FPP silent-coerce, VAD clamp).
 * Strict-reject (mode) vs silent-coerce (FPP) is intentional: mode selects
 * the input pipeline, FPP only tunes packetization.
 */
fun InputModes.validateAudioDelta(delta: ConfigDelta) {
    val transmitMode = delta.transmitMode
    if (transmitMode != null) {
        resolveInputMode(transmitMode)
    }
    val detectionThreshold = delta.detectionThreshold
    if (detectionThreshold != null) {
        require(!detectionThreshold.isNaN()) {
            "detectionThreshold must not be NaN"
        }
    }
    val amplitudeBoost = delta.amplitudeBoost
    if (amplitudeBoost != null) {
        require(!amplitudeBoost.isNaN()) {
            "amplitudeBoost must not be NaN"
        }
    }
}

fun InputModes.applyAudioDelta(
    delta: ConfigDelta,
    builder: AudioHandler.Builder,
    liveHandler: AudioHandler?,
    liveConnection: HumlaConnection?,
) {
    validateAudioDelta(delta)
    val detectionThreshold = delta.detectionThreshold
    if (detectionThreshold != null) {
        activity.setThreshold(detectionThreshold)
        if (liveHandler != null) {
            liveHandler.setVadThresholds(activity.vadMax, activity.vadMin)
        }
    }
    val amplitudeBoost = delta.amplitudeBoost
    if (amplitudeBoost != null) {
        builder.setAmplitudeBoost(amplitudeBoost)
    }
    val transmitMode = delta.transmitMode
    if (transmitMode != null) {
        // Validated by validateAudioDelta before any commit; the holder is
        // only assigned once the resolver returns, so a bad value can never
        // poison it for a later half-duplex-only delta.
        val inputMode = resolveInputMode(transmitMode)
        this.transmitMode = transmitMode
        builder.setInputMode(inputMode)
    }
    val inputQuality = delta.inputQuality
    if (inputQuality != null) {
        builder.setTargetBitrate(inputQuality)
    }
    val framesPerPacket = delta.framesPerPacket
    if (framesPerPacket != null) {
        builder.setTargetFramesPerPacket(framesPerPacket)
        liveConnection?.setTargetFramesPerPacket(framesPerPacket)
    }
    val halfDuplex = delta.halfDuplex
    if (halfDuplex != null) {
        this.halfDuplex = halfDuplex
    }
    if (delta.halfDuplex != null || delta.transmitMode != null) {
        builder.setHalfDuplexEnabled(
            this.transmitMode == Constants.TRANSMIT_PUSH_TO_TALK && this.halfDuplex
        )
    }
    val adaptiveLeveler = delta.adaptiveLeveler
    if (adaptiveLeveler != null) {
        builder.setAdaptiveLevelerEnabled(adaptiveLeveler)
    }
}
