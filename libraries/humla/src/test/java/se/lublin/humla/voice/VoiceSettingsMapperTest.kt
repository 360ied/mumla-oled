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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.Constants
import se.lublin.humla.state.emptySessionState

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSettingsMapperTest {
    @Test
    fun inputModeMappingMatchesConstants() {
        assertEquals(InputMode.PUSH_TO_TALK, VoiceSettingsMapper.inputModeFor(Constants.TRANSMIT_PUSH_TO_TALK))
        assertEquals(InputMode.CONTINUOUS, VoiceSettingsMapper.inputModeFor(Constants.TRANSMIT_CONTINUOUS))
        assertEquals(InputMode.VOICE_ACTIVITY, VoiceSettingsMapper.inputModeFor(Constants.TRANSMIT_VOICE_ACTIVITY))
    }

    @Test
    fun vadMappingFollowsActivityRule() {
        val (max, min) = VoiceSettingsMapper.vadFor(1.0f)
        assertEquals(1.0f, max)
        assertEquals(0.7f, min, 0.001f)
    }

    @Test
    fun halfDuplexOnlyInPtt() {
        assertTrue(VoiceSettingsMapper.halfDuplexFor(Constants.TRANSMIT_PUSH_TO_TALK, true))
        assertFalse(VoiceSettingsMapper.halfDuplexFor(Constants.TRANSMIT_VOICE_ACTIVITY, true))
        assertFalse(VoiceSettingsMapper.halfDuplexFor(Constants.TRANSMIT_PUSH_TO_TALK, false))
    }

    @Test
    fun updateAppliesDeltaFields() {
        val base = VoiceSettings.initial()
        val next = VoiceSettingsMapper.update(
            base,
            transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
            inputQuality = 24000,
            framesPerPacket = 4,
            halfDuplex = true,
        )
        assertEquals(InputMode.PUSH_TO_TALK, next.inputMode)
        assertEquals(24000, next.targetBitrate)
        assertEquals(4, next.targetFramesPerPacket)
        assertTrue(next.halfDuplex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRejectsUnknownTransmitMode() {
        VoiceSettingsMapper.update(VoiceSettings.initial(), transmitMode = 99)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRejectsNonFiniteThreshold() {
        VoiceSettingsMapper.update(VoiceSettings.initial(), detectionThreshold = Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRejectsNonFiniteBoost() {
        VoiceSettingsMapper.update(VoiceSettings.initial(), amplitudeBoost = Float.POSITIVE_INFINITY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRejectsOutOfRangeBitrate() {
        VoiceSettingsMapper.update(VoiceSettings.initial(), inputQuality = 1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRejectsOutOfRangeBoost() {
        VoiceSettingsMapper.update(VoiceSettings.initial(), amplitudeBoost = 9.0f)
    }

    @Test
    fun updateSanitizesFramesPerPacket() {
        val next = VoiceSettingsMapper.update(VoiceSettings.initial(), framesPerPacket = 3)
        assertEquals(2, next.targetFramesPerPacket)
    }

    @Test
    fun halfDuplexPreferenceSurvivesModeRoundTrip() {
        val ptt = VoiceSettingsMapper.initialFromDefaults(
            transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
            halfDuplex = true,
        )
        assertTrue(ptt.halfDuplex)
        // Mode-only switch keeps the raw preference (builder gates).
        val vad = VoiceSettingsMapper.update(ptt, transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY)
        assertEquals(InputMode.VOICE_ACTIVITY, vad.inputMode)
        assertTrue(vad.halfDuplex)
        val back = VoiceSettingsMapper.update(vad, transmitMode = Constants.TRANSMIT_PUSH_TO_TALK)
        assertTrue(back.halfDuplex)
        // Effective value in non-PTT modes is still false at the gate.
        assertFalse(VoiceSettingsMapper.halfDuplexFor(Constants.TRANSMIT_VOICE_ACTIVITY, vad.halfDuplex))
        assertTrue(VoiceSettingsMapper.halfDuplexFor(Constants.TRANSMIT_PUSH_TO_TALK, back.halfDuplex))
    }
}
