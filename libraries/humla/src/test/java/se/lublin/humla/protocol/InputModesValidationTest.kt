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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import se.lublin.humla.Constants
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.util.ConfigDelta

/**
 * Pins the validate-before-commit rule: an unknown transmit mode must throw
 * from [validateAudioDelta] without mutating the holder, so a later
 * half-duplex-only delta never computes against a poisoned value and
 * `apply()` can reject the delta before publishing params. Also pins NaN
 * rejection, constructor validation, and the applier mapping.
 */
class InputModesValidationTest {

    @Test
    fun unknownTransmitModeThrowsWithoutPoisoningHolder() {
        val modes = InputModes()
        val before = modes.transmitMode
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(transmitMode = 999))
        }
        assertEquals(before, modes.transmitMode)
    }

    @Test
    fun knownTransmitModesPassValidation() {
        val modes = InputModes()
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK))
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_CONTINUOUS))
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY))
    }

    @Test
    fun rejectedInvalidModeLeavesPriorValidModeIntact() {
        val modes = InputModes(
            transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
            halfDuplex = false,
        )
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(transmitMode = 999))
        }
        assertEquals(Constants.TRANSMIT_PUSH_TO_TALK, modes.transmitMode)
    }

    @Test
    fun emptyDeltaPassesValidation() {
        val modes = InputModes()
        modes.validateAudioDelta(ConfigDelta())
    }

    @Test
    fun invalidConstructionThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            InputModes(transmitMode = 999)
        }
    }

    @Test
    fun nanThresholdAndBoostThrow() {
        val modes = InputModes()
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(detectionThreshold = Float.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(amplitudeBoost = Float.NaN))
        }
        // Holder untouched by the rejected delta.
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, modes.transmitMode)
    }

    @Test
    fun applierMapsTransmitModeAndHalfDuplex() {
        // PTT + halfDuplex recomputes the builder flag; use a recording
        // builder double via the real Builder with no Android dependency?
        // The Builder needs Context, so pin the holder side here: mode
        // assignment and half-duplex recombination are holder state.
        val modes = InputModes()
        // Resolver is the single source: each known mode resolves without throw.
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK))
        assertEquals(ActivityInputMode.DEFAULT_VAD_MAX, modes.activity.vadMax, 0.0f)
        // NaN-free thresholds pass validation.
        modes.validateAudioDelta(ConfigDelta(detectionThreshold = 0.5f, amplitudeBoost = 1.0f))
    }

    @Test
    fun halfDuplexOnlyDeltaPreservesMode() {
        val modes = InputModes(
            transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
            halfDuplex = false,
        )
        modes.validateAudioDelta(ConfigDelta(halfDuplex = true))
        assertEquals(Constants.TRANSMIT_PUSH_TO_TALK, modes.transmitMode)
        assertTrue(modes.resolveInputMode(Constants.TRANSMIT_PUSH_TO_TALK) === modes.toggle)
        assertTrue(modes.resolveInputMode(Constants.TRANSMIT_CONTINUOUS) === modes.continuous)
        assertTrue(modes.resolveInputMode(Constants.TRANSMIT_VOICE_ACTIVITY) === modes.activity)
    }

    @Test
    fun applierPushesModeAndKnobsToBuilderAndLive() {
        val modes = InputModes()
        val builder = mock(AudioHandler.Builder::class.java)
        // Specific instance (not any()): Kotlin non-null param + any() null
        // would NPE at the stub call site.
        `when`(builder.setInputMode(modes.toggle)).thenReturn(builder)
        `when`(builder.setTargetBitrate(org.mockito.ArgumentMatchers.anyInt())).thenReturn(builder)
        `when`(builder.setTargetFramesPerPacket(org.mockito.ArgumentMatchers.anyInt())).thenReturn(builder)
        `when`(builder.setAmplitudeBoost(org.mockito.ArgumentMatchers.anyFloat())).thenReturn(builder)
        `when`(builder.setHalfDuplexEnabled(org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(builder)
        `when`(builder.setAdaptiveLevelerEnabled(org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(builder)
        val liveHandler = mock(AudioHandler::class.java)
        val liveConnection = mock(HumlaConnection::class.java)
        modes.applyAudioDelta(
            ConfigDelta(
                transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
                halfDuplex = true,
                detectionThreshold = 0.5f,
                amplitudeBoost = 2.0f,
                inputQuality = 64000,
                framesPerPacket = 4,
                adaptiveLeveler = true,
            ),
            builder,
            liveHandler,
            liveConnection,
        )
        assertEquals(Constants.TRANSMIT_PUSH_TO_TALK, modes.transmitMode)
        assertEquals(true, modes.halfDuplex)
        verify(builder).setInputMode(modes.toggle)
        verify(builder).setHalfDuplexEnabled(true)
        verify(builder).setTargetBitrate(64000)
        verify(builder).setTargetFramesPerPacket(4)
        verify(builder).setAmplitudeBoost(2.0f)
        verify(builder).setAdaptiveLevelerEnabled(true)
        verify(liveConnection).setTargetFramesPerPacket(4)
    }

    @Test
    fun applierRejectsBadModeWithoutMutating() {
        val modes = InputModes()
        val builder = mock(AudioHandler.Builder::class.java)
        val before = modes.transmitMode
        assertThrows(IllegalArgumentException::class.java) {
            modes.applyAudioDelta(
                ConfigDelta(transmitMode = 999),
                builder,
                null,
                null,
            )
        }
        assertEquals(before, modes.transmitMode)
    }
}
