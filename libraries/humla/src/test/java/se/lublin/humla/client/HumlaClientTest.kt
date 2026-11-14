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

package se.lublin.humla.client

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.net.CryptKeys
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.net.Transport
import se.lublin.humla.net.TransportException
import se.lublin.humla.net.TransportFactory
import se.lublin.humla.net.TransportStats
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.net.VoiceStatus
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode

private class FakeVoice : VoiceLink, VoiceLifecycle {
    val order: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val startsCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val stopsCount = java.util.concurrent.atomic.AtomicInteger(0)
    val starts: Int get() = startsCount.get()
    val stops: Int get() = stopsCount.get()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()
    private val _status = MutableStateFlow(VoiceStatus.initial())
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    override fun startVoice(host: String, port: Int) {
        startsCount.incrementAndGet()
        order.add("voice-start")
    }

    override fun stopVoice() {
        stopsCount.incrementAndGet()
        order.add("voice-stop")
    }

    override fun setForceTcp(force: Boolean) {
        order.add("voice-setforcetcp")
    }

    override fun setKeys(keys: CryptKeys) = Unit
    override fun setDecryptIv(iv: ByteString) = Unit
    override fun encryptIv(): ByteString = ByteString.EMPTY
    override fun submit(command: VoiceCommand) = Unit
    override fun queueTunnelled(packet: ByteArray) = Unit
}

private class FakeRoute(val order: MutableList<String>) : RouteLifecycle {
    var starts = 0
    var ends = 0

    override fun sessionStarted() {
        starts += 1
        order.add("route-start")
    }

    override fun sessionEnded() {
        ends += 1
        order.add("route-end")
    }
}

private class FakeTransport(
    val usingUdp: Boolean = true,
) : Transport {
    val inboundChannel = Channel<Inbound>(1024)
    override val inbound = inboundChannel
    private val _stats = MutableStateFlow(TransportStats.initial(usingUdp))
    override val stats: StateFlow<TransportStats> = _stats.asStateFlow()
    private val _keys = MutableStateFlow<CryptKeys?>(null)
    override val cryptKeys: StateFlow<CryptKeys?> = _keys.asStateFlow()
    private val done = CompletableDeferred<ClientError>()
    override val failure: kotlinx.coroutines.Deferred<ClientError> get() = done
    val sent = Collections.synchronizedList(ArrayList<Pair<HumlaTCPMessageType, MessageLite>>())
    var closed = false
    var keepaliveStarted = false
    override val remoteHost: String = "127.0.0.1"
    override val remotePort: Int = 64738

    override fun send(message: MessageLite, type: HumlaTCPMessageType) {
        sent.add(type to message)
    }

    override fun sendTunnelled(packet: ByteArray) = Unit

    override fun close() {
        closed = true
        inboundChannel.close()
    }

    override fun startKeepalive() {
        keepaliveStarted = true
    }

    fun fail(error: ClientError) {
        done.complete(error)
    }
}

private class FakeFactory(
    var transports: ArrayDeque<FakeTransport> = ArrayDeque(),
    var throwOnCreate: Throwable? = null,
    var createGate: CompletableDeferred<Unit>? = null,
    var swallowGateCancel: Boolean = false,
    val created: MutableList<CreateArgs> = Collections.synchronizedList(ArrayList()),
) : TransportFactory {
    data class CreateArgs(
        val host: String,
        val port: Int,
        val certSize: Int?,
        val trustPath: String?,
        val trustPassword: String?,
        val trustFormat: String?,
        val forceTcp: Boolean,
    )

    override suspend fun create(
        parentScope: CoroutineScope,
        host: String,
        port: Int,
        certificate: ByteArray?,
        certificatePassword: String?,
        trustStorePath: String?,
        trustStorePassword: String?,
        trustStoreFormat: String?,
        forceTcp: Boolean,
        voice: VoiceLink,
    ): Transport {
        created.add(
            CreateArgs(
                host, port, certificate?.size, trustStorePath,
                trustStorePassword, trustStoreFormat, forceTcp,
            ),
        )
        val gate = createGate
        if (gate != null) {
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!swallowGateCancel) throw e
            }
        }
        val ex = throwOnCreate
        if (ex != null) throw ex
        val t = if (transports.isNotEmpty()) transports.removeFirst() else FakeTransport(!forceTcp)
        return t
    }
}

private class FakeNetwork : NetworkMonitor {
    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val available = _available.asSharedFlow()

    suspend fun emitAvailable() {
        _available.emit(Unit)
    }
}

private object NoopObserver : IHumlaObserver {
    override fun onConnected() = Unit
    override fun onConnecting() = Unit
    override fun onDisconnected(e: HumlaException?) = Unit
    override fun onTLSHandshakeFailed(
        chain: Array<java.security.cert.X509Certificate>?,
        failure: se.lublin.humla.net.HandshakeFailure?,
        verifiedHost: String?,
    ) = Unit
    override fun onBluetoothScoChanged(active: Boolean) = Unit
    override fun onChannelAdded(channel: IChannel?) = Unit
    override fun onChannelStateUpdated(channel: IChannel?) = Unit
    override fun onChannelRemoved(channel: IChannel?) = Unit
    override fun onChannelPermissionsUpdated(channel: IChannel?) = Unit
    override fun onUserConnected(user: IUser?) = Unit
    override fun onUserStateUpdated(user: IUser?) = Unit
    override fun onUserTalkStateUpdated(user: IUser?) = Unit
    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) = Unit
    override fun onUserRemoved(user: IUser?, reason: String?) = Unit
    override fun onPermissionDenied(reason: String?) = Unit
    override fun onMessageLogged(message: IMessage?) = Unit
    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) = Unit
    override fun onLogInfo(message: String?) = Unit
    override fun onLogWarning(message: String?) = Unit
    override fun onLogError(message: String?) = Unit
}

private fun testTarget(forceTcp: Boolean = true): ConnectTarget = ConnectTarget(
    server = ServerEndpoint(
        serverId = 1L,
        name = "test",
        host = "127.0.0.1",
        port = 64738,
        username = "alice",
        password = null,
    ),
    certificate = null,
    trustStore = null,
    forceTcp = forceTcp,
    clientName = "test",
    accessTokens = persistentListOf(),
)

private fun serverSync(session: Int = 1): Mumble.ServerSync = Mumble.ServerSync.newBuilder()
    .setSession(session)
    .setMaxBandwidth(72000)
    .build()

private fun channelState(id: Int, name: String): Mumble.ChannelState =
    Mumble.ChannelState.newBuilder().setChannelId(id).setName(name).build()

private fun userState(session: Int, name: String, channel: Int): Mumble.UserState =
    Mumble.UserState.newBuilder().setSession(session).setName(name).setChannelId(channel).build()

class HumlaClientTest {
    private fun newClient(
        scope: CoroutineScope,
        dispatcher: TestDispatcher,
        factory: FakeFactory,
        voice: FakeVoice,
        route: FakeRoute,
        network: FakeNetwork,
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC),
        localFactsStore: LocalFactsStore = InMemoryLocalFactsStore(),
    ): HumlaClient = HumlaClient(
        parentScope = scope,
        transports = factory,
        voiceLifecycle = voice,
        routeLifecycle = route,
        voiceLink = voice,
        network = network,
        localFactsStore = localFactsStore,
        clock = clock,
        observer = NoopObserver,
        mainPoster = { r -> r.run() },
        denyFormatter = { "denied" },
        clientDispatcher = dispatcher,
        ioDispatcher = dispatcher,
        sessionDispatcher = dispatcher,
    )

    private fun allowedEdges(): Set<Pair<String, String>> = setOf(
        "Idle" to "Connecting",
        "Connecting" to "Connected",
        "Connecting" to "Reconnecting",
        "Connected" to "Reconnecting",
        "Connecting" to "Failed",
        "Connected" to "Failed",
        "Failed" to "Connecting",
        "Connecting" to "Idle",
        "Connected" to "Idle",
        "Reconnecting" to "Idle",
        "Failed" to "Idle",
        "Connected" to "Connecting",
        "Connecting" to "Connecting",
        "Reconnecting" to "Connecting",
    )

    private fun stateName(s: ClientState): String = when (s) {
        is ClientState.Idle -> "Idle"
        is ClientState.Connecting -> "Connecting"
        is ClientState.Connected -> "Connected"
        is ClientState.Reconnecting -> "Reconnecting"
        is ClientState.Failed -> "Failed"
    }

    private fun assertEdges(transitions: List<ClientState>) {
        val allowed = allowedEdges()
        for (i in 1 until transitions.size) {
            val from = stateName(transitions[i - 1])
            val to = stateName(transitions[i])
            if (from == to) continue
            assertTrue(
                "Illegal ClientState edge $from -> $to",
                allowed.contains(from to to),
            )
        }
    }

    @Test
    fun connectSyncDisconnect() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val order = Collections.synchronizedList(ArrayList<String>())
        val voice = FakeVoice()
        // Share the order list for Stop-before-Ended checks.
        val trackingVoice = object : VoiceLifecycle {
            override fun startVoice(host: String, port: Int) {
                voice.startVoice(host, port)
                order.add("voice-start")
            }

            override fun stopVoice() {
                voice.stopVoice()
                order.add("voice-stop")
            }

            override fun setForceTcp(force: Boolean) {
                voice.setForceTcp(force)
                order.add("voice-setforcetcp")
            }
        }
        val route = FakeRoute(order)
        val network = FakeNetwork()
        // Re-wire voice to track order: use a delegating link for transport.
        val client = HumlaClient(
            parentScope = this,
            transports = factory,
            voiceLifecycle = trackingVoice,
            routeLifecycle = route,
            voiceLink = voice,
            network = network,
            localFactsStore = InMemoryLocalFactsStore(),
            clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC),
            observer = NoopObserver,
            mainPoster = { r -> r.run() },
            denyFormatter = { "denied" },
            clientDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            sessionDispatcher = dispatcher,
        )
        val seen = ArrayList<ClientState>()
        val collectJob = launch(dispatcher) {
            client.state.collect { seen.add(it) }
        }
        runCurrent()

        client.connect(testTarget())
        runCurrent()
        // FakeTransport created; handshake sent (Version + Authenticate).
        val session = client.currentSession.value
        assertTrue(session != null)
        // Drive sync: channel + users then ServerSync through the live transport.
        // The live transport is reachable via the session.
        val live = session!!
        // Reach the fake via the session's transport (must be FakeTransport).
        val fake = live.transport as FakeTransport
        assertTrue(fake.sent.any { it.first == HumlaTCPMessageType.Version })
        assertTrue(fake.sent.any { it.first == HumlaTCPMessageType.Authenticate })
        // TransportFactory forwarding: host/port/forceTcp arrive intact.
        assertEquals(1, factory.created.size)
        assertEquals("127.0.0.1", factory.created[0].host)
        assertEquals(64738, factory.created[0].port)
        assertEquals(true, factory.created[0].forceTcp)
        assertEquals(null, factory.created[0].certSize)
        assertEquals(null, factory.created[0].trustPath)
        fake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")))
        fake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "alice", 0)))
        fake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)))
        runCurrent()
        // Connected, route started, voice started.
        assertTrue(client.state.value is ClientState.Connected)
        assertEquals(1, route.starts)
        assertEquals(1, voice.starts)
        // Route pushed before the socket opened.
        assertTrue(order.indexOf("voice-setforcetcp") < order.indexOf("voice-start"))

        client.disconnect()
        runCurrent()
        assertTrue(client.state.value is ClientState.Idle)
        assertEquals(1, voice.stops)
        assertEquals(1, route.ends)
        // Stop before Ended.
        val stopIdx = order.indexOf("voice-stop")
        val endIdx = order.indexOf("route-end")
        assertTrue(stopIdx >= 0 && endIdx >= 0 && stopIdx < endIdx)
        assertEdges(seen)
        client.close().join()
        collectJob.cancel()
    }

    @Test
    fun localFactsLoadFailureStartsEmptyAndLogs() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val records = Collections.synchronizedList(ArrayList<LogRecord>())
        val logger = java.util.logging.Logger.getLogger("HumlaClient")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = logger.level
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            // LocalFacts load throws: the session must still start with empty
            // lists, and the failure must be logged (spec, client.md start
            // sequence step 1).
            val store = object : LocalFactsStore {
                override suspend fun load(serverId: Long): LocalFacts =
                    throw IllegalStateException("db unavailable")

                override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) = Unit
                override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) = Unit
                override suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean) = Unit
            }
            val client = newClient(
                this,
                dispatcher,
                factory,
                FakeVoice(),
                FakeRoute(Collections.synchronizedList(ArrayList())),
                FakeNetwork(),
                localFactsStore = store,
            )
            client.connect(testTarget())
            runCurrent()
            // A DB problem never blocks connecting: the transport was created.
            assertEquals(1, factory.created.size)
            assertTrue(client.state.value is ClientState.Connecting)
            assertTrue(
                "expected a LocalFacts load warning, got $records",
                records.any {
                    it.level == Level.WARNING &&
                        it.message.contains("LocalFacts load failed")
                },
            )
            client.disconnect()
            runCurrent()
            client.close().join()
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
        }
    }

    @Test
    fun reconnectBackoffSequence() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val order = Collections.synchronizedList(ArrayList<String>())
        val voice = FakeVoice()
        val route = FakeRoute(order)
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        val seen = ArrayList<ClientState>()
        val collectJob = launch(dispatcher) { client.state.collect { seen.add(it) } }
        runCurrent()

        client.connect(testTarget())
        runCurrent()
        var live = client.currentSession.value!!
        var fake = live.transport as FakeTransport
        // Fail the first attempt with a connection error -> Reconnecting attempt 0, 1s.
        fake.fail(ClientError.Connection("boom", null))
        runCurrent()
        val first = client.state.value
        assertTrue(first is ClientState.Reconnecting)
        assertEquals(0, (first as ClientState.Reconnecting).attempt)
        // Advance 1s -> second attempt (attempt 1).
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(client.state.value is ClientState.Connecting)
        assertEquals(1, (client.state.value as ClientState.Connecting).attempt)
        live = client.currentSession.value!!
        fake = live.transport as FakeTransport
        fake.fail(ClientError.Connection("boom", null))
        runCurrent()
        assertTrue(client.state.value is ClientState.Reconnecting)
        assertEquals(1, (client.state.value as ClientState.Reconnecting).attempt)
        // 3s -> attempt 2, 6s -> attempt 3, 12s -> attempt 4, 25s stays.
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(2, (client.state.value as ClientState.Connecting).attempt)
        (client.currentSession.value!!.transport as FakeTransport)
            .fail(ClientError.Connection("boom", null))
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(3, (client.state.value as ClientState.Connecting).attempt)
        (client.currentSession.value!!.transport as FakeTransport)
            .fail(ClientError.Connection("boom", null))
        runCurrent()
        advanceTimeBy(12000)
        runCurrent()
        assertEquals(4, (client.state.value as ClientState.Connecting).attempt)
        (client.currentSession.value!!.transport as FakeTransport)
            .fail(ClientError.Connection("boom", null))
        runCurrent()
        advanceTimeBy(25000)
        runCurrent()
        assertEquals(5, (client.state.value as ClientState.Connecting).attempt)
        assertEdges(seen)
        client.close().join()
        collectJob.cancel()
    }

    @Test
    fun rejectGoesFailedWithoutReconnect() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        client.connect(testTarget())
        runCurrent()
        val live = client.currentSession.value!!
        val fake = live.transport as FakeTransport
        fake.inboundChannel.send(
            Inbound.Message(
                HumlaTCPMessageType.Reject,
                Mumble.Reject.newBuilder()
                    .setType(Mumble.Reject.RejectType.WrongServerPW)
                    .setReason("bad pw")
                    .build(),
            ),
        )
        runCurrent()
        val s = client.state.value
        assertTrue(s is ClientState.Failed)
        assertTrue((s as ClientState.Failed).error is ClientError.Rejected)
        // Rejected sessions never start voice or route.
        assertEquals(0, voice.starts)
        assertEquals(0, route.starts)
        // No reconnect after a long wait.
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(client.state.value is ClientState.Failed)
        client.close().join()
    }

    @Test
    fun networkAvailableRetriesAtOnce() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        client.connect(testTarget())
        runCurrent()
        val live = client.currentSession.value!!
        (live.transport as FakeTransport).fail(ClientError.Connection("down", null))
        runCurrent()
        assertTrue(client.state.value is ClientState.Reconnecting)
        // Network available cancels the 1s timer and retries immediately as attempt 1.
        network.emitAvailable()
        runCurrent()
        assertTrue(client.state.value is ClientState.Connecting)
        assertEquals(1, (client.state.value as ClientState.Connecting).attempt)
        client.close().join()
    }

    @Test
    fun connectWhileConnectedKeepsOneSession() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        client.connect(testTarget())
        runCurrent()
        val first = client.currentSession.value!!
        val firstFake = first.transport as FakeTransport
        firstFake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")))
        firstFake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "alice", 0)))
        firstFake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)))
        runCurrent()
        assertTrue(client.state.value is ClientState.Connected)
        // Connect to another target while connected: old session torn down.
        client.connect(testTarget())
        runCurrent()
        val second = client.currentSession.value!!
        assertTrue(first !== second)
        assertTrue(firstFake.closed)
        assertTrue(client.state.value is ClientState.Connecting)
        client.close().join()
    }

    @Test
    fun preSyncIntentsSurviveServerSync() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        client.connect(testTarget())
        runCurrent()
        val live = client.currentSession.value!!
        val fake = live.transport as FakeTransport
        // Pre-sync dump accumulates in the session's private builder; the
        // intents below land only on the published state.
        fake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")))
        fake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "alice", 0)))
        runCurrent()
        live.setPtt(true)
        live.setVoiceTarget(3)
        runCurrent()
        fake.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)))
        runCurrent()
        assertTrue(client.state.value is ClientState.Connected)
        // The ServerSync publish merges the dump with the pre-sync intents
        // instead of overwriting them with the builder's initial values.
        val synced = live.state.value
        assertTrue(synced.local.pttPressed)
        assertEquals(3, synced.local.voiceTargetId)
        client.close().join()
    }

    @Test
    fun muteSpamDuringConnectIsSafe() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        client.connect(testTarget())
        runCurrent()
        val live = client.currentSession.value!!
        // Spam intents during Connecting (pre-sync no-ops, must not throw).
        repeat(50) { i ->
            live.setSelfMuteDeaf(i % 2 == 0, false)
            live.setPtt(i % 2 == 0)
            live.setVoiceTarget(0)
        }
        runCurrent()
        // Disconnect mid-connect is safe.
        client.disconnect()
        runCurrent()
        assertTrue(client.state.value is ClientState.Idle)
        // Intents after disconnect are safe no-ops.
        live.setSelfMuteDeaf(true, false)
        live.setPtt(true)
        runCurrent()
        client.close().join()
    }

    @Test
    fun staleConnectFailureDoesNotClobberNewerAttempt() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        // Park attempt A in a non-cooperative factory that reports failure
        // even after cancellation.
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        factory.swallowGateCancel = true
        factory.throwOnCreate = TransportException(ClientError.Connection("stale", null))
        val targetA = testTarget()
        client.connect(targetA)
        runCurrent()
        // Take over with B while A is parked; B parks behind the same gate.
        val targetB = targetA.copy(server = targetA.server.copy(username = "InteropBob"))
        client.connect(targetB)
        runCurrent()
        // A's stale failure must not touch B's pending attempt.
        val connecting = client.state.value
        assertTrue(connecting is ClientState.Connecting)
        assertEquals("InteropBob", (connecting as ClientState.Connecting).target.server.username)
        // Finish B normally; the stale A failure must not have torn it down.
        factory.throwOnCreate = null
        gate.complete(Unit)
        runCurrent()
        val liveB = client.currentSession.value!!
        val fakeB = liveB.transport as FakeTransport
        assertFalse(fakeB.closed)
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")))
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "InteropBob", 0)))
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)))
        runCurrent()
        assertTrue(client.state.value is ClientState.Connected)
        assertTrue(client.currentSession.value === liveB)
        client.close().join()
    }

    @Test
    fun staleEqualTargetFailureDoesNotClobberNewerAttempt() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        // Park attempt A in a non-cooperative factory that reports failure
        // even after cancellation.
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        factory.swallowGateCancel = true
        factory.throwOnCreate = TransportException(ClientError.Connection("stale", null))
        val targetA = testTarget()
        client.connect(targetA)
        runCurrent()
        // Same-server manual retry: equal value, distinct instance. Value
        // equality on the target cannot tell A apart from B; the epoch can.
        val targetB = targetA.copy()
        assertEquals(targetA, targetB)
        assertTrue(targetA !== targetB)
        client.connect(targetB)
        runCurrent()
        // A's stale failure must not touch B's pending attempt.
        val connecting = client.state.value
        assertTrue(connecting is ClientState.Connecting)
        assertEquals(targetB, (connecting as ClientState.Connecting).target)
        // Finish B normally; the stale A failure must not have torn it down.
        factory.throwOnCreate = null
        gate.complete(Unit)
        runCurrent()
        val liveB = client.currentSession.value!!
        val fakeB = liveB.transport as FakeTransport
        assertFalse(fakeB.closed)
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")))
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "alice", 0)))
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)))
        runCurrent()
        assertTrue(client.state.value is ClientState.Connected)
        assertTrue(client.currentSession.value === liveB)
        client.close().join()
    }

    @Test
    fun staleBugFailureDoesNotClobberNewerAttempt() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        val factory = FakeFactory()
        val voice = FakeVoice()
        val route = FakeRoute(Collections.synchronizedList(ArrayList()))
        val network = FakeNetwork()
        val client = newClient(this, dispatcher, factory, voice, route, network)
        runCurrent()
        // Park attempt A in a non-cooperative factory whose stale error is
        // a deterministic bug, not a transport failure.
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        factory.swallowGateCancel = true
        factory.throwOnCreate = IllegalStateException("stale-bug")
        val targetA = testTarget()
        client.connect(targetA)
        runCurrent()
        // Take over with an equal target while A is parked.
        client.connect(targetA.copy())
        runCurrent()
        // The stale bug must neither tear down B nor publish Failed.
        assertTrue(client.state.value is ClientState.Connecting)
        // Finish B normally.
        factory.throwOnCreate = null
        gate.complete(Unit)
        runCurrent()
        val liveB = client.currentSession.value!!
        val fakeB = liveB.transport as FakeTransport
        assertFalse(fakeB.closed)
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")))
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "alice", 0)))
        fakeB.inboundChannel.send(Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)))
        runCurrent()
        assertTrue(client.state.value is ClientState.Connected)
        assertTrue(client.currentSession.value === liveB)
        client.close().join()
    }

    @Test
    fun ioErrorReconnectsButBugDoesNot() = runTest {
        val dispatcher = coroutineContext[ContinuationInterceptor] as TestDispatcher
        // IOException (DNS/TLS I/O class) reconnects.
        run {
            val factory = FakeFactory()
            val voice = FakeVoice()
            val route = FakeRoute(Collections.synchronizedList(ArrayList()))
            val network = FakeNetwork()
            val client = newClient(this, dispatcher, factory, voice, route, network)
            runCurrent()
            factory.throwOnCreate = java.io.IOException("dns")
            client.connect(testTarget())
            runCurrent()
            assertTrue(client.state.value is ClientState.Reconnecting)
            client.close().join()
        }
        // Programming bugs fail terminally instead of looping reconnects
        // forever (and without crashing the process).
        run {
            val factory = FakeFactory()
            val voice = FakeVoice()
            val route = FakeRoute(Collections.synchronizedList(ArrayList()))
            val network = FakeNetwork()
            val client = newClient(this, dispatcher, factory, voice, route, network)
            runCurrent()
            factory.throwOnCreate = IllegalStateException("bug")
            client.connect(testTarget())
            runCurrent()
            val failed = client.state.value
            assertTrue(failed is ClientState.Failed)
            val error = (failed as ClientState.Failed).error
            assertTrue(error is ClientError.Connection)
            assertTrue((error as ClientError.Connection).cause is IllegalStateException)
            client.close().join()
        }
    }
}
