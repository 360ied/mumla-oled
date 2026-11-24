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

package se.lublin.mumla.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.state.SessionEventRecord

/**
 * Replays the strings recorded from StateEventLogger against the feature's
 * formatter. A changed string is a failing test, never a re-recorded fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatLogGoldensTest {

    private val formatter = ChatEventFormatter(RuntimeEnvironment.getApplication())

    @Test
    fun eventStringsAreUnchanged() {
        for (case in goldenCases) {
            val expected = ChatLogGoldens.events.getValue(case.name).map {
                ChatLine(it.text, it.warning)
            }
            assertEquals(case.name, expected, formatter.lines(SessionEventRecord(case.event, case.state)))
        }
    }

    @Test
    fun denyStringsAreUnchanged() {
        for ((name, event) in goldenDenies) {
            assertEquals(name, ChatLogGoldens.denies[name], formatter.denyReason(event))
        }
    }

    @Test
    fun encoderLimitedUsesTheMovedBandwidthString() {
        val line = formatter.voiceLine(VoiceEvent.EncoderLimited(32000, 24000, 6))
        assertEquals(
            "Maximum server network bandwidth is only 32 kbit/s. " +
                "Adjusting audio quality to 24 kbit/s (60 ms).",
            line?.text,
        )
        assertTrue(line?.warning == true)
    }

    @Test
    fun pipelineErrorIsLoggedAsAWarning() {
        val line = formatter.voiceLine(VoiceEvent.PipelineError("capture failed"))
        assertEquals("Voice pipeline error: capture failed", line?.text)
        assertTrue(line?.warning == true)
    }
}
