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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.protocol.StateEventLogger
import se.lublin.humla.util.HumlaLogger

/**
 * Pins the strings recorded from StateEventLogger in :app, where Robolectric
 * loads the merged resources. The chat log feature must reproduce every line
 * (see testing.md#chat-log-goldens); a changed string is a failing test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateEventLoggerGoldensTest {

    private class RecordingLogger : HumlaLogger {
        val lines = ArrayList<ChatLogGoldens.Line>()
        override fun logInfo(message: String?) {
            lines.add(ChatLogGoldens.Line(false, message ?: "<null>"))
        }
        override fun logWarning(message: String?) {
            lines.add(ChatLogGoldens.Line(true, message ?: "<null>"))
        }
        override fun logError(message: String?) {
            lines.add(ChatLogGoldens.Line(true, message ?: "<null>"))
        }
    }

    @Test
    fun recordedEventStringsMatchTheAdapter() {
        val context = RuntimeEnvironment.getApplication()
        for (case in goldenCases) {
            val logger = RecordingLogger()
            StateEventLogger(context, logger).log(listOf(case.event), case.state)
            assertEquals(case.name, ChatLogGoldens.events[case.name], logger.lines)
        }
    }

    @Test
    fun recordedDenyStringsMatchTheAdapter() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = StateEventLogger(context, RecordingLogger())
        for ((name, event) in goldenDenies) {
            assertEquals(name, ChatLogGoldens.denies[name], adapter.denyReason(event))
        }
    }
}
