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

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.emptySessionState
import se.lublin.mumla.AppLogLine
import se.lublin.mumla.SpeechLine

/** TTS formatting, engine lifecycle, deafen rule and backpressure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextToSpeechFeatureTest {

    private class Fixture(speakSink: (suspend (String) -> Unit)? = null) {
        val platform = FakeFeaturePlatform()
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 256)
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val session = MutableStateFlow(emptySessionState())
        val speech = MutableSharedFlow<SpeechLine>(extraBufferCapacity = 8)
        val settings = MutableStateFlow(testSnapshot(useTts = true))
        val appLog = ArrayList<AppLogLine>()
        val feature = TextToSpeechFeature(
            RuntimeEnvironment.getApplication(), events, state, session, speech, settings,
            platform, { appLog.add(it) }, speakSink,
        )
    }

    private fun textRecord(message: String) = SessionEventRecord(
        SessionEvent.TextMessageReceived(
            2, "Alice", emptyList(), emptyList(), emptyList(), message, 0L,
        ),
        goldenState(),
    )

    @Test fun speaksReceivedTextAndHandOffLines() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.session.value = goldenState()
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        f.events.emit(textRecord("hello"))
        kotlinx.coroutines.yield()
        f.speech.emit(SpeechLine("Muted"))
        kotlinx.coroutines.yield()
        assertEquals(2, f.platform.ttsEngines.last().spoken.size)
        assertTrue(f.platform.ttsEngines.last().spoken[0].contains("hello"))
    }

    @Test fun deafenedSelfIsSilent() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        f.events.emit(textRecord("hello"))
        kotlinx.coroutines.yield()
        // sessionState is still empty: no self user, so speech is skipped.
        assertTrue(f.platform.ttsEngines.last().spoken.isEmpty())
    }

    @Test fun longMessagesAreNotSpoken() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.session.value = goldenState()
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        f.events.emit(textRecord("x".repeat(300)))
        kotlinx.coroutines.yield()
        assertTrue(f.platform.ttsEngines.last().spoken.isEmpty())
    }

    @Test fun engineFailureReachesTheAppLog() = runFeature { scope ->
        val f = Fixture()
        f.platform.ttsInitStatus = TextToSpeech.ERROR
        f.feature.start(scope)
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        assertEquals(1, f.appLog.size)
        assertTrue(f.appLog[0].warning)
    }

    @Test fun masterSwitchShutsTheEngineDown() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        val engine = f.platform.ttsEngines.last()
        f.settings.value = testSnapshot(useTts = false)
        assertTrue(engine.shutdown)
    }

    @Test fun slowTtsEngineNeverStallsTheEmitter() = runFeature { scope ->
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(speakSink = { gate.await() })
        f.feature.start(scope)
        f.session.value = goldenState()
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        kotlinx.coroutines.runBlocking {
            withTimeout(2_000) { repeat(300) { f.events.emit(textRecord("hello")) } }
        }
        gate.complete(Unit)
    }
}
