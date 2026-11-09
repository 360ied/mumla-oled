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
 * Replaces five service fields with one. Main-confined like `apply()`;
 * no volatility needed.
 */
class InputModes(
    val activity: ActivityInputMode =
        ActivityInputMode(ActivityInputMode.DEFAULT_VAD_MAX),
    val toggle: ToggleInputMode = ToggleInputMode(),
    val continuous: ContinuousInputMode = ContinuousInputMode(),
    var transmitMode: Int = Constants.TRANSMIT_VOICE_ACTIVITY,
    var halfDuplex: Boolean = false,
)

/**
 * Applies the builder-knob and input-mode mapping beside
 * [AudioHandler.Builder]: threshold, boost, transmit mode, quality, frames
 * per packet, half duplex, and leveler. Validates first via
 * [validateAudioDelta] so a bad delta is all-or-nothing and never
 * partially mutates the holder.
 */
fun InputModes.validateAudioDelta(delta: ConfigDelta) {
    val transmitMode = delta.transmitMode
    if (transmitMode != null) {
        when (transmitMode) {
            Constants.TRANSMIT_PUSH_TO_TALK,
            Constants.TRANSMIT_CONTINUOUS,
            Constants.TRANSMIT_VOICE_ACTIVITY -> {}
            else ->
                throw IllegalArgumentException("Unknown transmit mode: $transmitMode")
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
        // only assigned once the branch resolves, so a bad value can never
        // poison it for a later half-duplex-only delta.
        val inputMode: IInputMode
        when (transmitMode) {
            Constants.TRANSMIT_PUSH_TO_TALK ->
                inputMode = toggle
            Constants.TRANSMIT_CONTINUOUS ->
                inputMode = continuous
            Constants.TRANSMIT_VOICE_ACTIVITY ->
                inputMode = activity
            else ->
                throw IllegalArgumentException("Unknown transmit mode: $transmitMode")
        }
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
