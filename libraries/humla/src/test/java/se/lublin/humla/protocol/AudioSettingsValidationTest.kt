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
import org.junit.Assert.fail
import org.junit.Test
import se.lublin.humla.Constants
import se.lublin.humla.util.ConfigDelta

/**
 * Pins the validate-before-commit rule: an unknown transmit mode must throw
 * from [validateAudioDelta] without mutating the holder, so a later
 * half-duplex-only delta never computes against a poisoned value and
 * `apply()` can reject the delta before publishing params.
 */
class AudioSettingsValidationTest {

    @Test
    fun unknownTransmitModeThrowsWithoutPoisoningHolder() {
        val modes = InputModes()
        val before = modes.transmitMode
        try {
            modes.validateAudioDelta(ConfigDelta(transmitMode = 999))
            fail("expected IllegalArgumentException for unknown transmit mode")
        } catch (e: IllegalArgumentException) {
            // Expected.
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
        try {
            modes.validateAudioDelta(ConfigDelta(transmitMode = 999))
            fail("expected IllegalArgumentException for unknown transmit mode")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        assertEquals(Constants.TRANSMIT_PUSH_TO_TALK, modes.transmitMode)
    }
}
