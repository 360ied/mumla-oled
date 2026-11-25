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

import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.StreamProfile

private fun protobufFormat() = UdpFormat.PROTOBUF

/**
 * Route-following tests (issue 03 lesson): `VoiceEngine` starts a session
 * and drives `RouteState` through every transition with a fake native layer
 * that records AAudio opens, starts, stops, and closes. Fast JVM tests, no
 * hardware. Stream parameters follow route.md#stream-parameters.
 */
class VoiceEnginePipelineTest {
    private class Harness(
        val fake: VoiceEngineTest.FakeEngineNative = VoiceEngineTest.FakeEngineNative(),
    ) {
        val routeStates: MutableStateFlow<RouteState> = MutableStateFlow(RouteState.Idle)
        val prefs: MutableStateFlow<RoutePreferences> = MutableStateFlow(
            RoutePreferences(bluetoothSco = false, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val engine = VoiceEngine(
            talkPublisher = TalkActivityPublisher(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { fake },
            context = null,
            routeStates = routeStates,
            prefs = prefs,
        )

        fun start() {
            engine.startVoice("example.org", 64738, protobufFormat(), null)
        }

        fun stop() {
            engine.stopVoice()
        }

        fun ready(
            route: AppliedRoute = AppliedRoute.VOICE_HANDSET,
            profile: StreamProfile = StreamProfile.VOICE,
        ) {
            routeStates.value = RouteState.Ready(route, profile)
        }
    }

    @Test
    fun `no pipeline while idle even when started`() {
        val h = Harness()
        h.start()
        assertNull(h.engine.pipelineForTests())
        assertTrue(h.fake.streamOpens.isEmpty())
        assertEquals(0, h.fake.streamStarts)
    }

    @Test
    fun `builds once on Ready and tears down on Idle`() {
        val h = Harness()
        h.start()
        h.ready()
        val built = h.engine.pipelineForTests()
        assertNotNull(built)
        assertEquals(1, h.fake.streamOpens.size)
        // AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION, AAUDIO_USAGE_MEDIA.
        assertEquals(7 to 1, h.fake.streamOpens[0])
        assertEquals(1, h.fake.streamStarts)
        assertEquals(1L, built!!.generation)
        assertTrue(built.attachedEffects.isEmpty())
        // Settling keeps streams (spec): no teardown.
        h.routeStates.value = RouteState.Settling(AppliedRoute.MEDIA)
        assertEquals(built, h.engine.pipelineForTests())
        // Idle stops, then closes the streams.
        h.routeStates.value = RouteState.Idle
        assertNull(h.engine.pipelineForTests())
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
    }

    @Test
    fun `rebuilds on route change but not on same route`() {
        val h = Harness()
        h.start()
        h.ready()
        val first = h.engine.pipelineForTests()
        assertNotNull(first)
        // Same route/profile: no rebuild.
        h.engine.setSettings(
            VoiceSettings(
                inputMode = InputMode.VOICE_ACTIVITY,
                vadMax = 0.5f, vadMin = 0.35f, amplitudeBoost = 2.0f,
                targetBitrate = 32000, targetFramesPerPacket = 4,
                halfDuplex = false, adaptiveLeveler = false,
            )
        )
        assertEquals(first, h.engine.pipelineForTests())
        assertEquals(1, h.fake.streamOpens.size)
        // Different route: stop/close the old pair, then open/start a new one.
        h.ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        val second = h.engine.pipelineForTests()
        assertNotNull(second)
        assertTrue(first !== second)
        assertEquals(1, second!!.inputPreset) // AAUDIO_INPUT_PRESET_GENERIC
        assertEquals(1, second.usage) // AAUDIO_USAGE_MEDIA
        assertEquals(2L, second.generation)
        assertEquals(2, h.fake.streamOpens.size)
        assertEquals(2, h.fake.streamStarts)
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
    }

    @Test
    fun `handset stream preference changes the output usage`() {
        val h = Harness()
        h.start()
        h.ready(AppliedRoute.VOICE_HANDSET)
        assertEquals(1, h.engine.pipelineForTests()!!.usage)
        h.prefs.value = RoutePreferences(false, AudioManager.STREAM_VOICE_CALL)
        assertEquals(2, h.engine.pipelineForTests()!!.usage) // VOICE_COMMUNICATION
        assertEquals(2, h.fake.streamOpens.size)
        assertEquals(7 to 2, h.fake.streamOpens[1])
    }

    @Test
    fun `changed Start tears down live pipeline before reset`() {
        val h = Harness()
        h.start()
        h.ready()
        val first = h.engine.pipelineForTests()
        assertNotNull(first)
        // Same session, new endpoint: the live streams are stopped and closed
        // first, then a full Start rebuilds them.
        h.engine.startVoice("other.example", 64739, protobufFormat(), null)
        val second = h.engine.pipelineForTests()
        assertNotNull(second)
        assertTrue(first !== second)
        assertEquals(2, h.fake.streamOpens.size)
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
    }

    @Test
    fun `stop tears down even with Ready route`() {
        val h = Harness()
        h.start()
        h.ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        assertNotNull(h.engine.pipelineForTests())
        h.stop()
        assertNull(h.engine.pipelineForTests())
        assertEquals(1, h.fake.streamStops)
        assertEquals(1, h.fake.streamCloses)
    }

    @Test
    fun `failed open retries on next route event`() {
        val h = Harness()
        h.start()
        h.fake.openStreamsOk = false
        h.ready()
        assertNull(h.engine.pipelineForTests())
        assertEquals(0, h.fake.streamStarts)
        h.fake.openStreamsOk = true
        h.ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        assertNotNull(h.engine.pipelineForTests())
        assertEquals(1, h.fake.streamStarts)
    }
}
