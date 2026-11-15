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
    private class FakeEngineNative : EngineNative {
        var formatUdp = -1
        var formatLegacy = -1
        var muted: IntArray? = null
        var known: IntArray? = null
        var keys: Triple<ByteArray, ByteArray, ByteArray>? = null
        var decryptIv: ByteArray? = null
        var encryptIvOut: ByteArray? = ByteArray(16) { it.toByte() }
        val started = mutableListOf<Pair<String, Int>>()
        var stopCalls = 0
        var resetCalls = 0
        val sent = mutableListOf<ByteArray>()
        val tunnelled = mutableListOf<ByteArray>()
        val removed = mutableListOf<Int>()
        var cryptStats = intArrayOf(1, 2, 3, 4)
        var destroyed = false

        override fun setFormat(udpFormat: Int, legacyVersion: Int) {
            formatUdp = udpFormat
            formatLegacy = legacyVersion
        }

        override fun setPolicy(muted: IntArray?, known: IntArray?) {
            this.muted = muted
            this.known = known
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
            return true
        }

        override fun stop() {
            stopCalls++
        }

        override fun reset() {
            resetCalls++
        }

        override fun sendDatagram(plain: ByteArray?, length: Int): Boolean {
            sent.add(plain!!.copyOf(length))
            return true
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
        override fun awaitWork(timeoutMs: Int): Boolean = false
        override fun lastHasActiveVoices(): Boolean = false
        override fun wakeForStop() = Unit
        override fun lastDroppedInbound(): Long = 0
        override fun lastDroppedTunnel(): Long = 0
        override fun lastDroppedJitter(): Long = 0
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
    fun `sendVoice tunnels when forced TCP and uses native when UDP`() {
        val fake = FakeEngineNative()
        val sunk = mutableListOf<ByteArray>()
        val engine = newEngine(fake, sunk)
        engine.startVoice("example.org", 64738, protobufFormat(), keys())
        engine.sendVoice(byteArrayOf(1, 2, 3), 3)
        assertEquals(1, sunk.size)
        assertTrue(fake.sent.isEmpty())
        engine.submit(VoiceCommand.SetVoiceRoute(true))
        engine.sendVoice(byteArrayOf(4, 5), 2)
        assertEquals(1, sunk.size)
        assertEquals(1, fake.sent.size)
        assertArrayEquals(byteArrayOf(4, 5), fake.sent[0])
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
        )
        val users = publisher.talkActivity.value.users
        assertEquals(TalkState.TALKING, users[1])
        assertEquals(TalkState.SHOUTING, users[2])
        assertEquals(TalkState.WHISPERING, users[3])
        // PASSIVE (2) and unknown ordinals never insert: absence is passive.
        assertFalse(users.containsKey(4))
        assertFalse(users.containsKey(5))
        engine.onTalkSnapshot(intArrayOf(), intArrayOf(), 0L, 0L, 0L)
        assertTrue(publisher.talkActivity.value.users.isEmpty())
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
    fun `native load failure throws in debug builds`() {
        val engine = VoiceEngine(
            talkPublisher = TalkActivityPublisher(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            engineFactory = { throw RuntimeException("no lib") },
        )
        try {
            engine.startVoice("example.org", 64738, protobufFormat(), keys())
            fail("expected IllegalStateException in debug builds")
        } catch (e: IllegalStateException) {
            // expected: the failure mode that killed the first 5a attempt,
            // loud instead of dead voice.
        }
    }

    @Test
    fun `startVoiceWithoutUdp fixes format without opening UDP`() {
        val fake = FakeEngineNative()
        val engine = newEngine(fake)
        engine.startVoiceWithoutUdp(protobufFormat())
        assertTrue(engine.status.value.running)
        assertTrue(fake.started.isEmpty())
        assertEquals(1, fake.formatUdp)
        // Session start drains stale state like Start (notifier restart).
        assertEquals(1, fake.resetCalls)
    }
}
