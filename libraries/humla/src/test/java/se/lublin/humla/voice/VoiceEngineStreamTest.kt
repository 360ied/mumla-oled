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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.StreamProfile

/**
 * Facade tests for step 5b's disconnect and failure paths: one reopen per
 * disconnect, the three skip conditions, a failed reopen within 5 s, and
 * failed opens/starts that leave voice without streams but retry later.
 */
class VoiceEngineStreamTest {
    private class Harness(
        val fake: VoiceEngineTest.FakeEngineNative = VoiceEngineTest.FakeEngineNative(),
    ) {
        val routes: MutableStateFlow<RouteState> = MutableStateFlow(RouteState.Idle)
        val prefs: MutableStateFlow<RoutePreferences> = MutableStateFlow(
            RoutePreferences(bluetoothSco = false, earpiece = false),
        )
        val events = mutableListOf<VoiceEvent>()
        val engine = VoiceEngine(
            talkPublisher = TalkActivityPublisher(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { fake },
            context = null,
            routeStates = routes,
            prefs = prefs,
        )

        init {
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
                engine.events.collect { events.add(it) }
            }
        }

        fun start() {
            engine.startVoice(
                "example.org", 64738, UdpFormat.PROTOBUF, null,
            )
        }

        fun ready(
            route: AppliedRoute = AppliedRoute.VOICE_HANDSET,
            profile: StreamProfile = StreamProfile.VOICE,
        ) {
            routes.value = RouteState.Ready(route, profile)
        }

        fun idle() {
            routes.value = RouteState.Idle
        }

        fun pipelineErrors(): List<VoiceEvent.PipelineError> =
            events.filterIsInstance<VoiceEvent.PipelineError>()
    }

    @Test
    fun `disconnect skips when voice is not running`() {
        val h = Harness()
        h.start()
        h.ready()
        assertEquals(1, h.fake.streamOpens.size)
        h.engine.stopVoice()
        h.engine.onStreamDisconnect(1L)
        assertEquals(1, h.fake.streamOpens.size)
    }

    @Test
    fun `disconnect skips when no streams are open`() {
        val h = Harness()
        h.start()
        h.engine.onStreamDisconnect(1L)
        assertTrue(h.fake.streamOpens.isEmpty())
        assertEquals(0, h.fake.streamStarts)
    }

    @Test
    fun `disconnect skips an older generation`() {
        val h = Harness()
        h.start()
        h.ready()
        // nativeReset's generation 0 and a generation from a stream pair a
        // route change already replaced must not trigger a reopen.
        h.engine.onStreamDisconnect(0L)
        assertEquals(1, h.fake.streamOpens.size)
    }

    @Test
    fun `disconnect reopens both streams once`() {
        val h = Harness()
        h.start()
        h.ready()
        val first = h.engine.pipelineForTests()
        assertNotNull(first)
        h.engine.onStreamDisconnect(first!!.generation)
        val second = h.engine.pipelineForTests()
        assertNotNull(second)
        assertTrue(first !== second)
        assertEquals(2L, second!!.generation)
        assertEquals(2, h.fake.streamOpens.size)
        assertEquals(2, h.fake.streamStarts)
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
        assertTrue(h.pipelineErrors().isEmpty())
    }

    @Test
    fun `a second disconnect within five seconds is a failed reopen`() {
        val h = Harness()
        h.start()
        h.ready()
        h.engine.onStreamDisconnect(1L)
        val reopened = h.engine.pipelineForTests()
        assertNotNull(reopened)
        h.engine.onStreamDisconnect(reopened!!.generation)
        // No third open: the failed reopen stops and closes what is open,
        // clears the record, and reports PipelineError.
        assertNull(h.engine.pipelineForTests())
        assertEquals(2, h.fake.streamOpens.size)
        assertEquals(2, h.fake.streamStops)
        assertEquals(2, h.fake.streamCloses)
        assertTrue(h.pipelineErrors().any { it.message == "stream reopen failed" })
    }

    @Test
    fun `failed open reports PipelineError and retries on next route event`() {
        val h = Harness()
        h.fake.openStreamsOk = false
        h.start()
        h.ready()
        assertNull(h.engine.pipelineForTests())
        assertEquals(0, h.fake.streamStarts)
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
        assertTrue(h.pipelineErrors().any { it.message == "capture open failed" })
        // The cleared record means an unchanged route event rebuilds too.
        h.fake.openStreamsOk = true
        h.engine.onStreamDisconnect(1L) // no streams open: skipped
        assertEquals(1, h.fake.streamOpens.size)
        h.idle()
        h.ready()
        assertNotNull(h.engine.pipelineForTests())
        assertEquals(2, h.fake.streamOpens.size)
    }

    @Test
    fun `failed start reports PipelineError and leaves no streams`() {
        val h = Harness()
        h.fake.startStreamsOk = false
        h.start()
        h.ready()
        assertNull(h.engine.pipelineForTests())
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
        assertTrue(h.pipelineErrors().any { it.message == "playback open failed" })
        assertTrue(h.engine.status.value.running) // session stays up
    }

    @Test
    fun `disconnect skips a future generation`() {
        val h = Harness()
        h.start()
        h.ready()
        val first = h.engine.pipelineForTests()!!
        h.engine.onStreamDisconnect(first.generation + 1)
        assertEquals(1, h.fake.streamOpens.size)
        assertEquals(1, h.fake.streamStarts)
        assertEquals(first, h.engine.pipelineForTests())
    }

    @Test
    fun `failed open consumes its generation`() {
        val h = Harness()
        h.fake.openStreamsOk = false
        h.start()
        h.ready()
        h.fake.openStreamsOk = true
        h.idle()
        h.ready()
        val built = h.engine.pipelineForTests()!!
        assertEquals(2L, built.generation)
        // Generation 1 belonged to the failed attempt and must be ignored.
        h.engine.onStreamDisconnect(1L)
        assertEquals(2, h.fake.streamOpens.size)
        assertEquals(1, h.fake.streamStarts)
    }

    @Test
    fun `disconnect after route rebuild is not a failed reopen`() {
        val h = Harness()
        h.start()
        h.ready()
        val first = h.engine.pipelineForTests()!!
        h.engine.onStreamDisconnect(first.generation)
        assertNotNull(h.engine.pipelineForTests())
        // A later route rebuild is not the reopened pair, so its 5 s window
        // must not carry over to this disconnect.
        h.ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        val rebuilt = h.engine.pipelineForTests()!!
        h.engine.onStreamDisconnect(rebuilt.generation)
        assertNotNull(h.engine.pipelineForTests())
        assertEquals(4, h.fake.streamOpens.size)
        assertEquals(4, h.fake.streamStarts)
        assertTrue(h.pipelineErrors().none { it.message == "stream reopen failed" })
    }

    @Test
    fun `close suppresses later route rebuilds`() {
        val h = Harness()
        h.start()
        h.ready()
        assertEquals(1, h.fake.streamOpens.size)
        runBlocking { h.engine.close().join() }
        h.idle()
        h.ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        assertEquals(1, h.fake.streamOpens.size)
        assertEquals(1, h.fake.streamCloses)
        assertNull(h.engine.pipelineForTests())
    }

    @Test
    fun `disconnect after window reopens instead of failing`() {
        val h = Harness()
        var now = 0L
        h.engine.clock = { now }
        h.start()
        h.ready()
        h.engine.onStreamDisconnect(1L)
        val reopened = h.engine.pipelineForTests()
        assertNotNull(reopened)
        // Past the 5 s window the same generation is a fresh disconnect,
        // not a failed reopen: it rebuilds instead of reporting an error.
        now = 6_000L
        h.engine.onStreamDisconnect(reopened!!.generation)
        assertNotNull(h.engine.pipelineForTests())
        assertEquals(3, h.fake.streamOpens.size)
        assertEquals(3, h.fake.streamStarts)
        assertTrue(h.pipelineErrors().isEmpty())
    }
}
