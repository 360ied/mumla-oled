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
import se.lublin.humla.audio.InputPipeline
import se.lublin.humla.audio.OutputPipeline
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceFormat
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.StreamProfile

/**
 * Route-following pipeline tests (issue 03 lesson): `VoiceEngine` starts a
 * session (running) and drives `RouteState` through every transition with a
 * fake native layer and fake AudioRecord/Track, asserting build, rebuild,
 * and teardown. Fast JVM tests, no hardware.
 */
class VoiceEnginePipelineTest {
    private class FakeInput : InputPipeline {
        var started = false
        var shut = false
        override fun startRecording() { started = true }
        override fun shutdown() { shut = true }
    }

    private class FakeOutput : OutputPipeline {
        var startedStream: Int? = null
        var stopped = false
        override fun startPlaying(audioStream: Int) { startedStream = audioStream }
        override fun stopPlaying() { stopped = true }
    }

    private fun protobufFormat() = VoiceFormat(UdpFormat.PROTOBUF, 0x10500)

    private class Harness(
        val routeStates: MutableStateFlow<RouteState> = MutableStateFlow(RouteState.Idle),
        val prefs: MutableStateFlow<RoutePreferences> = MutableStateFlow(
            RoutePreferences(bluetoothSco = false, handsetStream = AudioManager.STREAM_MUSIC),
        ),
        val inputBuilds: MutableList<FakeInput> = mutableListOf(),
        val outputBuilds: MutableList<FakeOutput> = mutableListOf(),
        var failInput: Boolean = false,
    ) {
        val engine = VoiceEngine(
            talkPublisher = TalkActivityPublisher(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { NativeVoiceEngine(0) },
            context = null,
            routeStates = routeStates,
            prefs = prefs,
            inputFactory = { _, _ ->
                if (failInput) throw RuntimeException("no mic")
                FakeInput().also { inputBuilds.add(it) }
            },
            outputFactory = { _, _ ->
                FakeOutput().also { outputBuilds.add(it) }
            },
        )

        fun start() {
            engine.startVoiceWithoutUdp(
                VoiceFormat(UdpFormat.PROTOBUF, 0x10500)
            )
        }

        fun stop() {
            engine.stopVoice()
        }
    }

    @Test
    fun `no pipeline while idle even when started`() {
        val h = Harness()
        h.start()
        // Route Idle (initial): no build despite running.
        assertNull(h.engine.pipelineForTests())
        assertTrue(h.inputBuilds.isEmpty())
        assertTrue(h.outputBuilds.isEmpty())
    }

    @Test
    fun `builds once on Ready and tears down on Idle`() {
        val h = Harness()
        h.start()
        h.routeStates.value = RouteState.Ready(AppliedRoute.VOICE_HANDSET, StreamProfile.VOICE)
        val built = h.engine.pipelineForTests()
        assertNotNull(built)
        assertEquals(1, h.inputBuilds.size)
        assertEquals(1, h.outputBuilds.size)
        assertTrue(h.inputBuilds[0].started)
        assertEquals(AudioManager.STREAM_MUSIC, h.outputBuilds[0].startedStream)
        // Settling keeps streams (spec): no teardown.
        h.routeStates.value = RouteState.Settling(AppliedRoute.MEDIA)
        assertEquals(built, h.engine.pipelineForTests())
        // Idle stops streams.
        h.routeStates.value = RouteState.Idle
        assertNull(h.engine.pipelineForTests())
        assertTrue(h.inputBuilds[0].shut)
        assertTrue(h.outputBuilds[0].stopped)
    }

    @Test
    fun `rebuilds on route change but not on same route`() {
        val h = Harness()
        h.start()
        h.routeStates.value = RouteState.Ready(AppliedRoute.VOICE_HANDSET, StreamProfile.VOICE)
        val first = h.engine.pipelineForTests()
        assertNotNull(first)
        // Same route/profile: no rebuild (same instance).
        h.engine.setSettings(
            VoiceSettings(
                inputMode = InputMode.VOICE_ACTIVITY,
                vadMax = 0.5f, vadMin = 0.35f, amplitudeBoost = 2.0f,
                targetBitrate = 32000, targetFramesPerPacket = 4,
                halfDuplex = false, adaptiveLeveler = false,
            )
        )
        assertEquals(first, h.engine.pipelineForTests())
        assertEquals(1, h.inputBuilds.size)
        // Different route: rebuild (old joined, new live).
        h.routeStates.value = RouteState.Ready(AppliedRoute.VOICE_SCO, StreamProfile.VOICE)
        val second = h.engine.pipelineForTests()
        assertNotNull(second)
        assertTrue(first !== second)
        assertEquals(2, h.inputBuilds.size)
        assertEquals(2, h.outputBuilds.size)
        assertTrue(h.inputBuilds[0].shut)
        assertTrue(h.outputBuilds[0].stopped)
        assertTrue(h.inputBuilds[1].started)
    }

    @Test
    fun `stop tears down even with Ready route`() {
        val h = Harness()
        h.start()
        h.routeStates.value = RouteState.Ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        assertNotNull(h.engine.pipelineForTests())
        h.stop()
        assertNull(h.engine.pipelineForTests())
        assertTrue(h.inputBuilds[0].shut)
        assertTrue(h.outputBuilds[0].stopped)
    }

    @Test
    fun `build failure retries on next route event`() {
        val h = Harness()
        h.start()
        h.failInput = true
        h.routeStates.value = RouteState.Ready(AppliedRoute.VOICE_HANDSET, StreamProfile.VOICE)
        // Build threw: no pipeline, no crash.
        assertNull(h.engine.pipelineForTests())
        assertTrue(h.inputBuilds.isEmpty())
        // Next event with a working factory builds.
        h.failInput = false
        h.routeStates.value = RouteState.Ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        assertNotNull(h.engine.pipelineForTests())
        assertEquals(1, h.inputBuilds.size)
    }
}
