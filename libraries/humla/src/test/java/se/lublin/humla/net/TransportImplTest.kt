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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.net

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.client.InMemoryLocalFactsStore
import se.lublin.humla.client.LocalFacts
import se.lublin.humla.client.NoopObserver
import se.lublin.humla.client.Session
import se.lublin.humla.client.testTarget
import se.lublin.humla.Constants
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.voice.TalkActivity

/** Server-to-client bytes; close() delivers end of stream to the reader. */
private class QueueInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<Int>()

    fun feed(bytes: ByteArray) {
        for (b in bytes) queue.put(b.toInt() and 0xff)
    }

    override fun read(): Int {
        val b = queue.take()
        if (b == EOF) queue.put(EOF)
        return b
    }

    override fun close() {
        queue.put(EOF)
    }

    companion object {
        const val EOF = -1
    }
}

/** Client-to-server bytes, captured whole. */
private class CapturingOutputStream : OutputStream() {
    private val bytes = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) = bytes.write(b)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)

    @Synchronized
    fun snapshot(): ByteArray = bytes.toByteArray()
}

private class FakeSocket : Closeable {
    val input = QueueInputStream()
    val output = CapturingOutputStream()
    private val closedFlag = AtomicBoolean(false)
    val closed: Boolean get() = closedFlag.get()

    override fun close() {
        closedFlag.set(true)
        input.close()
    }
}

private class RecordingVoice : VoiceLink {
    val commands: MutableList<VoiceCommand> = Collections.synchronizedList(ArrayList())
    val keys: MutableList<CryptKeys> = Collections.synchronizedList(ArrayList())
    val decryptIvs: MutableList<ByteString> = Collections.synchronizedList(ArrayList())
    val tunnelled: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    val clientNonce: ByteString = ByteString.copyFrom(ByteArray(16) { 7 })

    val eventFlow = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = eventFlow.asSharedFlow()
    val statusFlow = MutableStateFlow(VoiceStatus.initial())
    override val status: StateFlow<VoiceStatus> = statusFlow.asStateFlow()

    override fun setKeys(keys: CryptKeys) {
        this.keys.add(keys)
    }

    override fun setDecryptIv(iv: ByteString) {
        decryptIvs.add(iv)
    }

    override fun encryptIv(): ByteString = clientNonce

    override fun submit(command: VoiceCommand) {
        commands.add(command)
    }

    override fun queueTunnelled(packet: ByteArray) {
        tunnelled.add(packet)
    }

    fun setLocalGood(good: Int) {
        statusFlow.value = statusFlow.value.copy(localCrypt = CryptCounts(good, 0, 0, 0))
    }

    fun routes(): List<VoiceCommand.SetVoiceRoute> =
        synchronized(commands) { commands.filterIsInstance<VoiceCommand.SetVoiceRoute>() }

    fun pings(): List<VoiceCommand.SendPing> =
        synchronized(commands) { commands.filterIsInstance<VoiceCommand.SendPing>() }
}

private fun frame(type: HumlaTCPMessageType, payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply {
        writeShort(type.ordinal)
        writeInt(payload.size)
        write(payload)
        flush()
    }
    return out.toByteArray()
}

private fun parseFrames(bytes: ByteArray): List<Pair<HumlaTCPMessageType, ByteArray>> {
    val input = DataInputStream(bytes.inputStream())
    val frames = ArrayList<Pair<HumlaTCPMessageType, ByteArray>>()
    while (input.available() > 0) {
        val type = HumlaTCPMessageType.entries[input.readShort().toInt()]
        val data = ByteArray(input.readInt())
        input.readFully(data)
        frames.add(type to data)
    }
    return frames
}

/**
 * TransportImpl over in-memory streams: writer, keepalive, and voice
 * collector on the test dispatcher with the transport clock on virtual
 * time; the reader stays a real thread, so reader-side effects are awaited
 * with a sentinel frame (frames are handled in order).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(val scope: TestScope, val forceTcp: Boolean = false) {
    val voice = RecordingVoice()
    val socket = FakeSocket()
    val dispatcher = scope.coroutineContext[ContinuationInterceptor] as TestDispatcher
    val transport = TransportImpl(
        host = "127.0.0.1",
        port = 64738,
        certificate = null,
        certificatePassword = null,
        trustStorePath = null,
        trustStorePassword = null,
        trustStoreFormat = null,
        forceTcp = forceTcp,
        voice = voice,
        parentScope = scope.backgroundScope,
        ioDispatcher = dispatcher,
        nanoTime = { scope.testScheduler.currentTime * 1_000_000L },
    )
    private var sentinels = 0

    /** Routes pushed by start(), before any test step; cleared from [voice]. */
    val initialRoutes: List<VoiceCommand.SetVoiceRoute>

    init {
        transport.start(socket.input, socket.output, socket)
        scope.runCurrent()
        initialRoutes = voice.routes()
        voice.commands.clear()
    }

    fun step() = scope.runCurrent()

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    fun serverSends(type: HumlaTCPMessageType, message: MessageLite) =
        socket.input.feed(frame(type, message.toByteArray()))

    fun serverSendsRaw(bytes: ByteArray) = socket.input.feed(bytes)

    fun serverPing(good: Int) = serverSends(
        HumlaTCPMessageType.Ping,
        Mumble.Ping.newBuilder().setTimestamp(0).setGood(good).build(),
    )

    /**
     * Waits until the reader has handled everything fed so far, then runs
     * the dispatcher. Returns the inbound items that arrived before the
     * sentinel.
     */
    fun sync(): List<Inbound> {
        val marker = "sentinel-${sentinels++}"
        serverSends(HumlaTCPMessageType.TextMessage, Mumble.TextMessage.newBuilder().setMessage(marker).build())
        val received = ArrayList<Inbound>()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val item = transport.inbound.tryReceive().getOrNull()
            if (item == null) {
                Thread.sleep(1)
                continue
            }
            if (item is Inbound.Message && item.type == HumlaTCPMessageType.TextMessage &&
                (item.message as Mumble.TextMessage).message == marker
            ) {
                step()
                return received
            }
            received.add(item)
        }
        fail("reader did not reach $marker")
        throw AssertionError()
    }

    fun awaitFailure(): ClientError {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!transport.failure.isCompleted && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("transport did not fail", transport.failure.isCompleted)
        return transport.failure.getCompleted()
    }

    fun sentFrames(): List<Pair<HumlaTCPMessageType, ByteArray>> = parseFrames(socket.output.snapshot())

    fun sentOf(type: HumlaTCPMessageType): List<ByteArray> =
        sentFrames().filter { it.first == type }.map { it.second }

    fun close() = transport.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransportImplTest {
    private fun assertDummyTunnel(payload: ByteArray) {
        val utb = Mumble.UDPTunnel.parseFrom(payload)
        assertEquals(ByteString.copyFrom(ByteArray(3)), utb.packet)
    }

    @Test
    fun keepaliveCadenceBootstrapThenSteady() = runTest {
        val f = Fixture(this)
        try {
            assertTrue(f.sentOf(HumlaTCPMessageType.Ping).isEmpty())
            f.transport.startKeepalive()
            f.step()
            // First round at once: a TCP ping and a UDP ping request.
            assertEquals(1, f.sentOf(HumlaTCPMessageType.Ping).size)
            assertEquals(listOf(VoiceCommand.SendPing(0)), f.voice.pings())
            f.advance(4_999)
            assertEquals(1, f.sentOf(HumlaTCPMessageType.Ping).size)
            f.advance(1)
            assertEquals(2, f.sentOf(HumlaTCPMessageType.Ping).size)
            // Healthy UDP both ways: after the 30 s bootstrap, every 10 s.
            f.voice.setLocalGood(10)
            f.serverPing(good = 10)
            f.sync()
            f.advance(25_000)
            val pings = f.sentOf(HumlaTCPMessageType.Ping).map { Mumble.Ping.parseFrom(it).timestamp }
            assertEquals(listOf(0L, 5L, 10L, 15L, 20L, 25L, 30L).map { it * 1_000_000 }, pings)
            f.advance(9_999)
            assertEquals(7, f.sentOf(HumlaTCPMessageType.Ping).size)
            f.advance(1)
            assertEquals(8, f.sentOf(HumlaTCPMessageType.Ping).size)
            // TCP pings carry the local crypt counts.
            assertEquals(10, Mumble.Ping.parseFrom(f.sentOf(HumlaTCPMessageType.Ping).last()).good)
            // Keepalive starts once.
            f.transport.startKeepalive()
            f.step()
            assertEquals(8, f.sentOf(HumlaTCPMessageType.Ping).size)
        } finally {
            f.close()
        }
    }

    @Test
    fun udpGivesUpOnlyAfter20Seconds() = runTest {
        val f = Fixture(this)
        try {
            f.advance(10_000)
            f.serverPing(good = 0)
            f.sync()
            assertTrue(f.voice.routes().isEmpty())
            assertTrue(f.transport.stats.value.usingUdp)
            // Exactly 20 s is not "more than 20 s".
            f.advance(10_000)
            f.serverPing(good = 0)
            f.sync()
            assertTrue(f.voice.routes().isEmpty())
            f.advance(1)
            f.serverPing(good = 0)
            f.sync()
            assertEquals(listOf(VoiceCommand.SetVoiceRoute(false)), f.voice.routes())
            assertFalse(f.transport.stats.value.usingUdp)
            val tunnels = f.sentOf(HumlaTCPMessageType.UDPTunnel)
            assertEquals(1, tunnels.size)
            assertDummyTunnel(tunnels[0])
            // Already tunnelled: no second switch or dummy.
            f.serverPing(good = 0)
            f.sync()
            assertEquals(1, f.voice.routes().size)
            assertEquals(1, f.sentOf(HumlaTCPMessageType.UDPTunnel).size)
        } finally {
            f.close()
        }
    }

    @Test
    fun udpGivesUpWhenOnlyLocalSideIsDead() = runTest {
        val f = Fixture(this)
        try {
            f.voice.setLocalGood(0)
            f.advance(21_000)
            f.serverPing(good = 50)
            f.sync()
            assertEquals(listOf(VoiceCommand.SetVoiceRoute(false)), f.voice.routes())
        } finally {
            f.close()
        }
    }

    @Test
    fun switchesBackToUdpWhenBothSidesHaveMoreThanThreeGood() = runTest {
        val f = Fixture(this)
        try {
            f.advance(21_000)
            f.serverPing(good = 0)
            f.sync()
            assertEquals(listOf(VoiceCommand.SetVoiceRoute(false)), f.voice.routes())
            // Three good on one side is not enough.
            f.voice.setLocalGood(4)
            f.serverPing(good = 3)
            f.sync()
            assertEquals(1, f.voice.routes().size)
            f.voice.setLocalGood(3)
            f.serverPing(good = 4)
            f.sync()
            assertEquals(1, f.voice.routes().size)
            f.voice.setLocalGood(4)
            f.serverPing(good = 4)
            f.sync()
            assertEquals(
                listOf(VoiceCommand.SetVoiceRoute(false), VoiceCommand.SetVoiceRoute(true)),
                f.voice.routes(),
            )
            assertTrue(f.transport.stats.value.usingUdp)
            f.serverPing(good = 9)
            f.sync()
            assertEquals(2, f.voice.routes().size)
        } finally {
            f.close()
        }
    }

    @Test
    fun initialRouteIsPushedAtStart() = runTest {
        // The transport alone owns the route (issue 16): voice is told UDP at
        // start, not left on its own default, and nothing more until a switch.
        val f = Fixture(this)
        try {
            assertEquals(listOf(VoiceCommand.SetVoiceRoute(true)), f.initialRoutes)
            assertTrue(f.transport.stats.value.usingUdp)
            f.transport.startKeepalive()
            f.step()
            assertTrue(f.voice.routes().isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun forcedTcpTunnelsFromServerSyncAndNeverUsesUdp() = runTest {
        val f = Fixture(this, forceTcp = true)
        try {
            assertFalse(f.transport.stats.value.usingUdp)
            // Tunnel pushed at start, ahead of voice start; not repeated.
            assertEquals(listOf(VoiceCommand.SetVoiceRoute(false)), f.initialRoutes)
            assertTrue(f.sentOf(HumlaTCPMessageType.UDPTunnel).isEmpty())
            f.transport.startKeepalive()
            f.step()
            assertTrue(f.voice.routes().isEmpty())
            val frames = f.sentFrames()
            assertEquals(HumlaTCPMessageType.UDPTunnel, frames[0].first)
            assertDummyTunnel(frames[0].second)
            assertEquals(HumlaTCPMessageType.Ping, frames[1].first)
            // Healthy counts never switch a forced target to UDP, and no UDP
            // ping is ever requested.
            f.voice.setLocalGood(50)
            f.advance(40_000)
            f.serverPing(good = 50)
            f.sync()
            assertTrue(f.voice.routes().isEmpty())
            assertTrue(f.voice.pings().isEmpty())
            f.transport.startKeepalive()
            f.step()
            assertEquals(1, f.sentOf(HumlaTCPMessageType.UDPTunnel).size)
        } finally {
            f.close()
        }
    }

    @Test
    fun voiceFormatIsFixedByTheFirstVersion() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(VoiceFormat.DEFAULT, f.transport.voiceFormat)
            val v150 = Constants.toVersionV2(1, 5, 0)
            f.serverSends(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setVersionV2(v150).build())
            f.sync()
            val fixed = VoiceFormat(UdpFormat.PROTOBUF, Constants.toLegacyVersion(v150))
            assertEquals(fixed, f.transport.voiceFormat)
            // A second Version does not re-pick the format (issue 17).
            f.serverSends(
                HumlaTCPMessageType.Version,
                Mumble.Version.newBuilder().setVersionV1((1 shl 16) or (4 shl 8)).build(),
            )
            f.sync()
            assertEquals(fixed, f.transport.voiceFormat)
        } finally {
            f.close()
        }
    }

    @Test
    fun legacyServerVersionPicksLegacyFormat() = runTest {
        val f = Fixture(this)
        try {
            val v1 = (1 shl 16) or (2 shl 8) or 2
            f.serverSends(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setVersionV1(v1).build())
            f.sync()
            assertEquals(VoiceFormat(UdpFormat.LEGACY, v1), f.transport.voiceFormat)
        } finally {
            f.close()
        }
    }

    @Test
    fun cryptResyncRequestSendsExactlyOneEmptyCryptSetup() = runTest {
        val f = Fixture(this)
        try {
            assertTrue(f.voice.eventFlow.tryEmit(VoiceEvent.CryptResyncRequested))
            f.step()
            val setups = f.sentOf(HumlaTCPMessageType.CryptSetup)
            assertEquals(1, setups.size)
            assertEquals(0, setups[0].size)
            // One request per event: the rate limit lives at the source
            // (transport.md § Crypt setup), the transport adds nothing.
            assertTrue(f.voice.eventFlow.tryEmit(VoiceEvent.CryptResyncRequested))
            f.step()
            assertEquals(2, f.sentOf(HumlaTCPMessageType.CryptSetup).size)
        } finally {
            f.close()
        }
    }

    @Test
    fun udpUnavailableSwitchesToTunnelOnce() = runTest {
        val f = Fixture(this)
        try {
            assertTrue(f.voice.eventFlow.tryEmit(VoiceEvent.UdpUnavailable))
            f.step()
            assertEquals(listOf(VoiceCommand.SetVoiceRoute(false)), f.voice.routes())
            assertEquals(1, f.sentOf(HumlaTCPMessageType.UDPTunnel).size)
            assertTrue(f.voice.eventFlow.tryEmit(VoiceEvent.UdpUnavailable))
            f.step()
            assertEquals(1, f.voice.routes().size)
            assertEquals(1, f.sentOf(HumlaTCPMessageType.UDPTunnel).size)
        } finally {
            f.close()
        }
    }

    @Test
    fun cryptSetupFollowsTheDecisionTable() = runTest {
        val f = Fixture(this)
        try {
            val key = ByteString.copyFrom(ByteArray(16) { 1 })
            val cn = ByteString.copyFrom(ByteArray(16) { 2 })
            val sn = ByteString.copyFrom(ByteArray(16) { 3 })
            f.serverSends(
                HumlaTCPMessageType.CryptSetup,
                Mumble.CryptSetup.newBuilder().setKey(key).setClientNonce(cn).setServerNonce(sn).build(),
            )
            // Owned by the transport: never forwarded to the session.
            assertTrue(f.sync().isEmpty())
            assertEquals(CryptKeys(key, cn, sn), f.transport.cryptKeys.value)
            assertEquals(listOf(CryptKeys(key, cn, sn)), f.voice.keys)
            // Server nonce only: resync.
            val sn2 = ByteString.copyFrom(ByteArray(16) { 4 })
            f.serverSends(HumlaTCPMessageType.CryptSetup, Mumble.CryptSetup.newBuilder().setServerNonce(sn2).build())
            f.sync()
            assertEquals(listOf(sn2), f.voice.decryptIvs)
            // Empty, or a key without nonces: reply with our nonce.
            f.serverSends(HumlaTCPMessageType.CryptSetup, Mumble.CryptSetup.getDefaultInstance())
            f.serverSends(HumlaTCPMessageType.CryptSetup, Mumble.CryptSetup.newBuilder().setKey(key).build())
            f.sync()
            val replies = f.sentOf(HumlaTCPMessageType.CryptSetup).map { Mumble.CryptSetup.parseFrom(it) }
            assertEquals(2, replies.size)
            for (reply in replies) {
                assertEquals(f.voice.clientNonce, reply.clientNonce)
                assertFalse(reply.hasKey())
                assertFalse(reply.hasServerNonce())
            }
            assertFalse(f.transport.failure.isCompleted)
        } finally {
            f.close()
        }
    }

    @Test
    fun cryptSetupWithBadSizesIsAProtocolViolation() = runTest {
        val good = ByteString.copyFrom(ByteArray(16))
        val bad = ByteString.copyFrom(ByteArray(15))
        for (msg in listOf(
            Mumble.CryptSetup.newBuilder().setKey(bad).setClientNonce(good).setServerNonce(good).build(),
            Mumble.CryptSetup.newBuilder().setKey(good).setClientNonce(good).setServerNonce(bad).build(),
            Mumble.CryptSetup.newBuilder().setServerNonce(bad).build(),
        )) {
            val f = Fixture(this)
            try {
                f.serverSends(HumlaTCPMessageType.CryptSetup, msg)
                val error = f.awaitFailure()
                assertTrue("$error", error is ClientError.Connection)
                assertTrue(f.socket.closed)
                assertTrue(f.voice.keys.isEmpty())
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun inboundRoutesTunnelledAndMessagesButKeepsPings() = runTest {
        val f = Fixture(this)
        try {
            val voicePacket = byteArrayOf(0x20, 1, 2, 3)
            f.serverSendsRaw(frame(HumlaTCPMessageType.UDPTunnel, voicePacket))
            f.serverSends(HumlaTCPMessageType.ChannelState, Mumble.ChannelState.newBuilder().setChannelId(0).build())
            f.serverPing(good = 1)
            val items = f.sync()
            assertEquals(2, items.size)
            assertEquals(Inbound.Tunnelled(voicePacket), items[0])
            assertEquals(HumlaTCPMessageType.ChannelState, (items[1] as Inbound.Message).type)
            assertEquals(1, f.transport.stats.value.remoteCrypt.good)
        } finally {
            f.close()
        }
    }

    @Test
    fun fullOutboundFailsTheTransport() = runTest {
        val f = Fixture(this)
        try {
            // The writer never runs between these sends, as when the server
            // stops reading: the channel fills and send fails the transport.
            val ping = Mumble.Ping.newBuilder().setTimestamp(1).build()
            repeat(TransportImpl.OUTBOUND_CAPACITY + 8) {
                f.transport.send(ping, HumlaTCPMessageType.Ping)
            }
            assertTrue(f.transport.failure.isCompleted)
            val error = f.transport.failure.getCompleted()
            assertTrue("$error", error is ClientError.Connection && error.message.contains("outbound full"))
            assertTrue(f.socket.closed)
        } finally {
            f.close()
        }
    }

    @Test
    fun fullOutboundFailsTheSession() = runTest {
        val f = Fixture(this)
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val session = Session(
            id = 1,
            target = testTarget(forceTcp = false),
            parentScope = backgroundScope,
            transport = f.transport,
            localFacts = LocalFacts.empty(),
            talkActivity = MutableStateFlow(TalkActivity.initial()),
            voiceLink = f.voice,
            observer = NoopObserver,
            localFactsStore = InMemoryLocalFactsStore(),
            mainPoster = { r -> r.run() },
            denyFormatter = { "denied" },
            workDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
        try {
            f.step()
            assertFalse(session.failure.isCompleted)
            // The session's own sends (handshake) went through; now the
            // server stops reading while sends keep coming.
            assertEquals(2, f.sentFrames().size)
            val ping = Mumble.Ping.getDefaultInstance()
            repeat(TransportImpl.OUTBOUND_CAPACITY + 8) { f.transport.send(ping, HumlaTCPMessageType.Ping) }
            f.step()
            assertTrue(session.failure.isCompleted)
            val error = session.failure.getCompleted()
            assertTrue("$error", error is ClientError.Connection && error.message.contains("outbound full"))
        } finally {
            session.close()
            f.step()
        }
    }

    @Test
    fun invalidFrameIsAProtocolViolation() = runTest {
        val f = Fixture(this)
        try {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeShort(0x7fff)
                writeInt(0)
            }
            f.serverSendsRaw(out.toByteArray())
            val error = f.awaitFailure()
            assertTrue("$error", error is ClientError.Connection && error.message.contains("Invalid protocol frame"))
            assertTrue(f.socket.closed)
        } finally {
            f.close()
        }
    }

    @Test
    fun serverHangupFailsWithConnection() = runTest {
        val f = Fixture(this)
        try {
            f.socket.input.close()
            val error = f.awaitFailure()
            assertTrue("$error", error is ClientError.Connection && error.message.contains("TCP read failed"))
        } finally {
            f.close()
        }
    }

    @Test
    fun closeIsQuietAndFinal() = runTest {
        val f = Fixture(this)
        f.transport.startKeepalive()
        f.step()
        val sentBefore = f.sentFrames().size
        f.close()
        f.step()
        assertTrue(f.socket.closed)
        // The reader saw end of stream after close and exited without failing.
        assertFalse(f.transport.failure.isCompleted)
        assertTrue(f.transport.inbound.isClosedForReceive)
        // Sends after close are dropped, not failures; the ping loop is gone.
        repeat(TransportImpl.OUTBOUND_CAPACITY + 8) {
            f.transport.send(Mumble.Ping.getDefaultInstance(), HumlaTCPMessageType.Ping)
        }
        val voicePingsBefore = f.voice.pings().size
        f.advance(60_000)
        assertFalse(f.transport.failure.isCompleted)
        assertEquals(sentBefore, f.sentFrames().size)
        // Nothing in the transport scope runs on: no UDP ping requests, and
        // voice events no longer reach a collector.
        assertEquals(voicePingsBefore, f.voice.pings().size)
        f.voice.eventFlow.tryEmit(VoiceEvent.UdpUnavailable)
        f.step()
        assertTrue(f.voice.routes().isEmpty())
        f.close()
    }
}
