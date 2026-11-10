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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import se.lublin.humla.Constants
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.util.ConfigDelta

/**
 * Pins the validate-before-commit rule: an unknown transmit mode must throw
 * from [validateAudioDelta] without mutating the holder, so a later
 * half-duplex-only delta never computes against a poisoned value and
 * `apply()` can reject the delta before publishing params. Also pins
 * non-finite rejection, the bitrate floor, constructor validation, and the
 * applier mapping (mode + knobs to builder and live handler).
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
    fun validatorResolvesKnownModesWithoutMutating() {
        val modes = InputModes()
        // The resolver is the single source: each known mode validates
        // without throw, and validation observes only — holder state must
        // not move, so a later half-duplex-only delta computes cleanly.
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK))
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_CONTINUOUS))
        modes.validateAudioDelta(ConfigDelta(transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY))
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, modes.transmitMode)
        assertFalse(modes.halfDuplex)
        assertEquals(ActivityInputMode.DEFAULT_VAD_MAX, modes.activity.vadMax, 1e-6f)
        // Finite thresholds pass validation, still without mutating.
        modes.validateAudioDelta(ConfigDelta(detectionThreshold = 0.5f, amplitudeBoost = 1.0f))
        assertEquals(ActivityInputMode.DEFAULT_VAD_MAX, modes.activity.vadMax, 1e-6f)
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
        // Observes only: holder untouched.
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, modes.transmitMode)
        assertFalse(modes.halfDuplex)
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
    fun infiniteThresholdAndBoostThrow() {
        val modes = InputModes()
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(detectionThreshold = Float.POSITIVE_INFINITY))
        }
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(amplitudeBoost = Float.NEGATIVE_INFINITY))
        }
        // Holder untouched by the rejected deltas.
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, modes.transmitMode)
        assertEquals(ActivityInputMode.DEFAULT_VAD_MAX, modes.activity.vadMax, 1e-6f)
    }

    @Test
    fun subFloorBitrateThrows() {
        val modes = InputModes()
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(inputQuality = 7999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(inputQuality = -1))
        }
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, modes.transmitMode)
    }

    @Test
    fun floorBitratePassesValidation() {
        val modes = InputModes()
        modes.validateAudioDelta(ConfigDelta(inputQuality = 8000))
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, modes.transmitMode)
    }

    @Test
    fun halfDuplexOnlyDeltaPreservesMode() {
        val modes = InputModes(
            transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
            halfDuplex = false,
        )
        modes.validateAudioDelta(ConfigDelta(halfDuplex = true))
        assertEquals(Constants.TRANSMIT_PUSH_TO_TALK, modes.transmitMode)
        // Validation observes only: the flag itself must not flip.
        assertFalse(modes.halfDuplex)
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
        `when`(builder.setTargetBitrate(anyInt())).thenReturn(builder)
        `when`(builder.setTargetFramesPerPacket(anyInt())).thenReturn(builder)
        `when`(builder.setAmplitudeBoost(anyFloat())).thenReturn(builder)
        `when`(builder.setHalfDuplexEnabled(anyBoolean())).thenReturn(builder)
        `when`(builder.setAdaptiveLevelerEnabled(anyBoolean())).thenReturn(builder)
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
        assertTrue(modes.halfDuplex)
        // The live VAD push must ride along: a regression dropping it would
        // leave the running pipeline on stale thresholds.
        assertEquals(0.5f, modes.activity.vadMax, 1e-6f)
        verify(liveHandler).setVadThresholds(eq(modes.activity.vadMax), eq(modes.activity.vadMin))
        verify(builder).setInputMode(modes.toggle)
        verify(builder).setHalfDuplexEnabled(true)
        verify(builder).setTargetBitrate(64000)
        verify(builder).setTargetFramesPerPacket(4)
        verify(builder).setAmplitudeBoost(2.0f)
        verify(builder).setAdaptiveLevelerEnabled(true)
        verify(liveConnection).setTargetFramesPerPacket(4)
    }

    @Test
    fun applierRejectsInfiniteBoostWithoutMutating() {
        val modes = InputModes()
        val builder = mock(AudioHandler.Builder::class.java)
        val before = modes.activity.vadMax
        assertThrows(IllegalArgumentException::class.java) {
            modes.applyAudioDelta(
                ConfigDelta(amplitudeBoost = Float.POSITIVE_INFINITY),
                builder,
                null,
                null,
            )
        }
        // Validate-before-commit covers the mutating entry too: the holder
        // keeps its thresholds and the builder sees no boost.
        assertEquals(before, modes.activity.vadMax, 1e-6f)
        verify(builder, never()).setAmplitudeBoost(anyFloat())
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
        verifyNoInteractions(builder)
    }

    @Test
    fun outOfRangeFiniteThresholdsPassThenClamp() {
        val modes = InputModes()
        // Out-of-range finite thresholds pass validation (clamped downstream),
        // e.g. 2.0 clamps to 1.0, -1.0 clamps to 0.0. Both directions pinned.
        modes.validateAudioDelta(ConfigDelta(detectionThreshold = 2.0f))
        modes.validateAudioDelta(ConfigDelta(detectionThreshold = -1.0f))
        modes.applyAudioDelta(
            ConfigDelta(detectionThreshold = 2.0f),
            mock(AudioHandler.Builder::class.java),
            null,
            null,
        )
        assertEquals(1.0f, modes.activity.vadMax, 1e-6f)
        modes.applyAudioDelta(
            ConfigDelta(detectionThreshold = -1.0f),
            mock(AudioHandler.Builder::class.java),
            null,
            null,
        )
        assertEquals(0.0f, modes.activity.vadMax, 1e-6f)
    }

    @Test
    fun amplitudeBoostRangeEnforced() {
        val modes = InputModes()
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(amplitudeBoost = -1.0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(amplitudeBoost = 1e30f))
        }
        modes.validateAudioDelta(ConfigDelta(amplitudeBoost = 2.0f))
    }

    @Test
    fun inputQualityCeilingEnforced() {
        val modes = InputModes()
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(inputQuality = 1000000))
        }
        // Ceiling is MAX_INPUT_BITRATE (510000): boundary pinned both sides.
        assertThrows(IllegalArgumentException::class.java) {
            modes.validateAudioDelta(ConfigDelta(inputQuality = 510001))
        }
        modes.validateAudioDelta(ConfigDelta(inputQuality = 510000))
        modes.validateAudioDelta(ConfigDelta(inputQuality = 64000))
    }
}
