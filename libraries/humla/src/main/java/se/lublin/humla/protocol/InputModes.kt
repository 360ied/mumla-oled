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
 * `apply()`; `transmitMode` is additionally read off-main via
 * `HumlaService.getTransmitMode`, so it is volatile for visibility
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
    transmitMode: Int = Constants.TRANSMIT_VOICE_ACTIVITY,
    @Volatile
    var halfDuplex: Boolean = false,
) {
    /**
     * Validated at construction and on every applier write (see
     * [applyAudioDelta]); the private setter keeps direct assignment from
     * ever poisoning the holder that a later half-duplex-only delta reads.
     * Volatile: read off-main via `HumlaService.getTransmitMode`.
     */
    @Volatile
    var transmitMode: Int = transmitMode
        private set
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

    /**
     * Applies the audio half of [ConfigDelta]. Validates first (see
     * [validateAudioDelta]), so a bad delta never commits: the holder is
     * only assigned once the resolver returns, and builder knobs only after.
     */
    fun applyAudioDelta(
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
}

/** Floor for [ConfigDelta.inputQuality]: no downstream sanitizer floors the
 * native target (only the bandwidth-degraded copy is floored at 8000), and
 * the settings UI mins at the same value. */
private const val MIN_INPUT_BITRATE = 8000

/**
 * Validates the audio half of [ConfigDelta]. The throwing inputs (unknown
 * transmit mode via the shared resolver, non-finite float knobs, sub-floor
 * bitrate) fail here, so `apply()` can reject the delta before publishing
 * params. FPP passes through to its downstream silent-coerce sanitizer;
 * VAD thresholds clamp at both layers but still reject non-finite here.
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
        // Clamped downstream (holder + native), but non-finite must still
        // fail fast: NaN poisons comparisons, Inf saturates the VAD.
        require(detectionThreshold.isFinite()) {
            "detectionThreshold must be finite"
        }
    }
    val amplitudeBoost = delta.amplitudeBoost
    if (amplitudeBoost != null) {
        // No downstream clamp: Inf gain hits the native limiter raw (silent
        // frames go 0*Inf=NaN through the JNI int cast).
        require(amplitudeBoost.isFinite()) {
            "amplitudeBoost must be finite"
        }
    }
    val inputQuality = delta.inputQuality
    if (inputQuality != null) {
        require(inputQuality >= MIN_INPUT_BITRATE) {
            "inputQuality must be >= $MIN_INPUT_BITRATE"
        }
    }
}

