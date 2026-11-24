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

import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.collections.immutable.toPersistentSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.lublin.humla.interop.NoUdpVoice
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.CryptKeys
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceFormat

/**
 * Facade tests for step 5a.3b with an injected fake engine (no native
 * library): lifecycle ordering, route following, send routing, policy
 * push, removal delegation, talk mapping, and status.
 */
class VoiceEngineTest {
    internal class FakeEngineNative : EngineNative {
        var formatUdp = -1
        var formatLegacy = -1
        var muted: IntArray? = null
        var known: IntArray? = null
        var gateTransmit = false
        var gatePtt = false
        var gateTarget = -1
        var gateBitrate = -1
        var gateFpp = -1
        var settingsMode = -1
        var settingsHalfDuplex = false
        var voiceRouteUdp = false
        var rnnoiseModel: ByteArray? = null
        var enqueuedPings = mutableListOf<Long>()
        var capturedFrames = 0
        var keys: Triple<ByteArray, ByteArray, ByteArray>? = null
        var decryptIv: ByteArray? = null
        var encryptIvOut: ByteArray? = ByteArray(16) { it.toByte() }
        val started = mutableListOf<Pair<String, Int>>()
        var startOk = true
        var stopCalls = 0
        var resetCalls = 0
        val tunnelled = mutableListOf<ByteArray>()
        val removed = mutableListOf<Int>()
        var cryptStats = intArrayOf(1, 2, 3, 4)
        var droppedOutbound = 0L
        var destroyed = false
        // Step 5b stream calls, shared with the route-following tests.
        val streamOpens = mutableListOf<Pair<Int, Int>>()
        var openStreamsOk = true
        var startStreamsOk = true
        var sessionId = -1L
        var nextGeneration = 1L
        var streamStarts = 0
        var streamStops = 0
        var streamCloses = 0

        override fun setFormat(udpFormat: Int, legacyVersion: Int) {
            formatUdp = udpFormat
            formatLegacy = legacyVersion
        }

        override fun setPolicy(muted: IntArray?, known: IntArray?, transmitAllowed: Boolean, pttPressed: Boolean, voiceTarget: Int, bitrate: Int, framesPerPacket: Int) {
            this.muted = muted
            this.known = known
            gateTransmit = transmitAllowed
            gatePtt = pttPressed
            gateTarget = voiceTarget
            gateBitrate = bitrate
            gateFpp = framesPerPacket
        }

        override fun setSettings(inputMode: Int, vadMax: Float, vadMin: Float, amplitudeBoost: Float, adaptiveLeveler: Boolean, halfDuplex: Boolean) {
            settingsMode = inputMode
            settingsHalfDuplex = halfDuplex
        }

        override fun setVoiceRoute(udp: Boolean) {
            voiceRouteUdp = udp
        }

        override fun setRnnoiseModel(model: ByteArray?): Boolean {
            rnnoiseModel = model
            return true
        }

        override fun enqueuePing(timestampMicros: Long): Boolean {
            enqueuedPings.add(timestampMicros)
            return true
        }

        override fun captureFrame(pcm: ShortArray?, offset: Int, length: Int) {
            capturedFrames++
        }

        override fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean {
            keys = Triple(key!!, eiv!!, div!!)
            return true
        }

        override fun setDecryptIv(div: ByteArray?): Boolean {
            decryptIv = div
            return true
        }

        override fun getEncryptIv(): ByteArray? = encryptIvOut

        override fun start(host: String, port: Int): Boolean {
            started.add(host to port)
            return startOk
        }

        override fun stop() {
            stopCalls++
        }

        override fun reset() {
            resetCalls++
        }

        override fun queueTunnelled(packet: ByteArray?): Boolean {
            tunnelled.add(packet!!.copyOf())
            return true
        }

        override fun pushRemoveUser(session: Int): Boolean {
            removed.add(session)
            return true
        }

        override fun render(out: ShortArray?, offset: Int, length: Int): Int = 0
        override fun openStreams(inputPreset: Int, usage: Int): LongArray? {
            streamOpens.add(inputPreset to usage)
            // Native consumes a generation on every attempt, including a
            // failed open, so a dead pair's generation is never reused.
            val generation = nextGeneration++
            return if (openStreamsOk) longArrayOf(sessionId, generation) else null
        }

        override fun startStreams(): Boolean {
            streamStarts++
            return startStreamsOk
        }

        override fun stopStreams() {
            streamStops++
        }

        override fun closeStreams() {
            streamCloses++
        }
        override fun lastDroppedInbound(): Long = 0
        override fun lastDroppedTunnel(): Long = 0
        override fun lastDroppedJitter(): Long = 0
        override fun lastDroppedOutbound(): Long = droppedOutbound
        override fun getStats(): IntArray = cryptStats
        override fun destroy() {
            destroyed = true
        }
    }

    private fun newEngine(fake: FakeEngineNative = FakeEngineNative(), sunk: MutableList<ByteArray> = mutableListOf()): VoiceEngine {
        return VoiceEngine(
            tunnelSink = { sunk.add(it) },
            talkPublisher = TalkActivityPublisher(),
            // Unconfined: command launches complete synchronously, so the
            // assertions below observe submission order without waiting.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { fake },
        )
    }

    private fun keys(): CryptKeys = CryptKeys(
        ByteString.copyFrom(ByteArray(16) { 1 }),
        ByteString.copyFrom(ByteArray(16) { 2 }),
        ByteString.copyFrom(ByteArray(16) { 3 }),
    )

    private fun protobufFormat() = VoiceFormat(UdpFormat.PROTOBUF, 0x10500)

    @Test
    fun `start resets installs keys and opens socket when routed`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.submit(VoiceCommand.SetVoiceRoute(true))
        // No endpoint yet: route alone opens nothing.
        assertTrue(fake.started.isEmpty())
        engine.submit(VoiceCommand.Start("example.org", 64738, keys(), protobufFormat()))
        assertEquals(1, fake.formatUdp)
        assertEquals(0x10500, fake.formatLegacy)
        assertEquals(1, fake.resetCalls)
        assertEquals(listOf("example.org" to 64738), fake.started)
        assertTrue(engine.status.value.running)
    }

    @Test
    fun `stop resets route and closes socket`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.submit(VoiceCommand.SetVoiceRoute(true))
        assertEquals(1, fake.started.size)
        engine.stopVoice()
        assertEquals(1, fake.stopCalls)
        assertFalse(engine.status.value.running)
        // A late route push after stop opens nothing.
        engine.submit(VoiceCommand.SetVoiceRoute(true))
        assertEquals(1, fake.started.size)
    }

    @Test
    fun `queueTunnelled and removeUser delegate native`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.queueTunnelled(byteArrayOf(9, 9, 9))
        engine.removeUser(42)
        assertEquals(1, fake.tunnelled.size)
        assertEquals(listOf(42), fake.removed)
    }

    @Test
    fun `sendPing enqueues native ping when UDP`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.submit(VoiceCommand.SetVoiceRoute(true))
        engine.submit(VoiceCommand.SendPing(12345L))
        assertEquals(listOf(12345L), fake.enqueuedPings)
    }

    @Test
    fun `setPolicy pushes muted and known lists`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.setPolicy(
            VoicePolicy(
                transmitAllowed = true,
                pttPressed = false,
                voiceTarget = 0,
                bitrate = 40000,
                framesPerPacket = 2,
                locallyMuted = setOf(7).toPersistentSet(),
                knownSessions = setOf(7, 8).toPersistentSet(),
            )
        )
        assertArrayEquals(intArrayOf(7), fake.muted)
        assertArrayEquals(intArrayOf(7, 8), fake.known!!.sortedArray())
        assertTrue(fake.gateTransmit)
        assertEquals(40000, fake.gateBitrate)
        assertEquals(2, fake.gateFpp)
    }

    @Test
    fun `setSettings pushes native capture settings`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.setSettings(
            VoiceSettings(
                inputMode = InputMode.PUSH_TO_TALK,
                vadMax = 0.8f,
                vadMin = 0.56f,
                amplitudeBoost = 1.5f,
                targetBitrate = 32000,
                targetFramesPerPacket = 4,
                halfDuplex = true,
                adaptiveLeveler = false,
            )
        )
        assertEquals(1, fake.settingsMode)
        assertTrue(fake.settingsHalfDuplex)
    }

    @Test
    fun `talk snapshot maps ordinals and drops unknown`() {
        val publisher = TalkActivityPublisher()
        val engine = VoiceEngine(
            talkPublisher = publisher,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { FakeEngineNative() },
        )
        engine.onTalkSnapshot(
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(0, 1, 3, 2, 99),
            0L, 0L, 0L,
            false,
        )
        val users = publisher.talkActivity.value.users
        assertEquals(TalkState.TALKING, users[1])
        assertEquals(TalkState.SHOUTING, users[2])
        assertEquals(TalkState.WHISPERING, users[3])
        // PASSIVE (2) and unknown ordinals never insert: absence is passive.
        assertFalse(users.containsKey(4))
        assertFalse(users.containsKey(5))
        engine.onTalkSnapshot(intArrayOf(), intArrayOf(), 0L, 0L, 0L, false)
        assertTrue(publisher.talkActivity.value.users.isEmpty())
        engine.onTalkSnapshot(intArrayOf(), intArrayOf(), 0L, 0L, 0L, true)
        assertTrue(publisher.talkActivity.value.selfTalking)
        engine.onTunnelPacket(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `status carries running and native crypt counts`() {
        val fake = FakeEngineNative()
        fake.cryptStats = intArrayOf(11, 12, 13, 14)
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        val status = engine.status.value
        assertTrue(status.running)
        assertEquals(11, status.localCrypt.good)
        assertEquals(12, status.localCrypt.late)
        assertEquals(13, status.localCrypt.lost)
        assertEquals(14, status.localCrypt.resync)
    }

    @Test
    fun `native creation failure reports PipelineError without dead voice`() {
        val engine = VoiceEngine(
            talkPublisher = TalkActivityPublisher(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { throw RuntimeException("no lib") },
        )
        // Creation runs on the control thread, so there is no synchronous
        // throw (the old inline contract died with caller-thread creation).
        // The failure maps to PipelineError plus status, and the session is
        // left stopped rather than half-started: loud, without dead voice.
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        assertEquals("voice engine unavailable", engine.status.value.lastError)
        assertFalse(engine.status.value.running)
    }

    @Test
    fun `start without UDP route fixes format without opening socket`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.submit(VoiceCommand.SetVoiceRoute(false))
        assertTrue(engine.status.value.running)
        assertTrue(fake.started.isEmpty())
        assertEquals(1, fake.formatUdp)
        // Session start drains stale state like Start (notifier restart).
        assertEquals(1, fake.resetCalls)
    }

    @Test
    fun `duplicate healthy Start is a no-op, changed Start restarts`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        val keys = keys()
        val format = protobufFormat()
        engine.submit(VoiceCommand.Start("example.org", 64738, keys, format))
        assertEquals(1, fake.resetCalls)
        // Identical healthy Start: dropped before reset, capture untouched.
        engine.submit(VoiceCommand.Start("example.org", 64738, keys, format))
        assertEquals(1, fake.resetCalls)
        // Changed endpoint: live state torn down first, then a full Start.
        engine.submit(VoiceCommand.Start("other.example", 64739, keys, format))
        assertEquals(2, fake.resetCalls)
    }

    @Test
    fun `failed socket Start retries on identical resubmit`() {
        val fake = FakeEngineNative().also { it.startOk = false }
        val engine = newEngine(fake)
        val keys = keys()
        val format = protobufFormat()
        engine.submit(VoiceCommand.SetVoiceRoute(true))
        engine.submit(VoiceCommand.Start("example.org", 64738, keys, format))
        assertEquals(1, fake.started.size)
        // Unhealthy session: the identical retry must proceed (tear down
        // and re-drive the socket open), not skip as a duplicate.
        engine.submit(VoiceCommand.Start("example.org", 64738, keys, format))
        assertEquals(2, fake.resetCalls)
        assertEquals(2, fake.started.size)
    }

    @Test
    fun `blocked voice drops switchback to UDP`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        val blocked = NoUdpVoice(engine, reportSocketFailure = false)
        blocked.startVoice("example.org", 64738, protobufFormat(), keys())
        assertTrue(fake.started.isEmpty())
        assertFalse(fake.voiceRouteUdp)
        // A later transport switch-to-UDP must not open the socket.
        blocked.submit(VoiceCommand.SetVoiceRoute(true))
        assertTrue(fake.started.isEmpty())
        assertFalse(fake.voiceRouteUdp)
    }

    @Test
    fun `setPolicy refreshes status from effective policy`() {
        // Bandwidth source pin: status bitrate/FPP follow the effective
        // (server-cap-applied) policy, not the raw targets, so
        // getCurrentBandwidth via stats reports the degraded values.
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.setPolicy(
            VoicePolicy(
                transmitAllowed = false,
                pttPressed = false,
                voiceTarget = 0,
                bitrate = 16000,
                framesPerPacket = 4,
                locallyMuted = emptySet<Int>().toPersistentSet(),
                knownSessions = emptySet<Int>().toPersistentSet(),
            )
        )
        // Unconfined scope: the control-thread publish already ran.
        assertFalse(fake.gateTransmit)
        assertEquals(16000, fake.gateBitrate)
        assertEquals(4, fake.gateFpp)
        assertEquals(16000, engine.status.value.bitrate)
        assertEquals(4, engine.status.value.framesPerPacket)
    }
}
