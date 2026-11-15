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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.net.CryptKeys
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.net.Transport
import se.lublin.humla.net.TransportFactory
import se.lublin.humla.net.TransportStats
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.net.VoiceStatus
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.effectiveSelfMute
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.humla.voice.TalkActivity
import se.lublin.humla.voice.VoiceSettings
import se.lublin.humla.voice.voicePolicy

/**
 * Fakes and the per-step invariant harness shared by the client and session
 * virtual-time tests (docs/target-architecture/testing.md#virtual-time-tests).
 */

/** Voice link and lifecycle in one; lifecycle calls go to the shared [order] log. */
internal class FakeVoice(
    val order: MutableList<String> = Collections.synchronizedList(ArrayList()),
) : VoiceLink, VoiceLifecycle {
    private val startsCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val stopsCount = java.util.concurrent.atomic.AtomicInteger(0)
    val starts: Int get() = startsCount.get()
    val stops: Int get() = stopsCount.get()
    val tunnelled: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())

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
    override fun queueTunnelled(packet: ByteArray) {
        tunnelled.add(packet)
    }
}

internal class FakeRoute(val order: MutableList<String>) : RouteLifecycle {
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

internal class FakeTransport(
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
    var keepaliveStarts = 0
    val keepaliveStarted: Boolean get() = keepaliveStarts > 0
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
        keepaliveStarts += 1
    }

    fun fail(error: ClientError) {
        done.complete(error)
    }

    /** Messages of [type] sent so far, in order. */
    @Suppress("UNCHECKED_CAST")
    fun <T : MessageLite> sentOf(type: HumlaTCPMessageType): List<T> =
        synchronized(sent) { sent.filter { it.first == type }.map { it.second as T } }
}

internal class FakeFactory(
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
        return if (transports.isNotEmpty()) transports.removeFirst() else FakeTransport(!forceTcp)
    }
}

internal class FakeNetwork : NetworkMonitor {
    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val available = _available.asSharedFlow()

    suspend fun emitAvailable() {
        _available.emit(Unit)
    }
}

internal object NoopObserver : IHumlaObserver {
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

internal fun testTarget(forceTcp: Boolean = true, username: String = "alice"): ConnectTarget = ConnectTarget(
    server = ServerEndpoint(
        serverId = 1L,
        name = "test",
        host = "127.0.0.1",
        port = 64738,
        username = username,
        password = null,
    ),
    certificate = null,
    trustStore = null,
    forceTcp = forceTcp,
    clientName = "test",
    accessTokens = persistentListOf(),
)

internal fun serverSync(session: Int = 1): Mumble.ServerSync = Mumble.ServerSync.newBuilder()
    .setSession(session)
    .setMaxBandwidth(72000)
    .build()

internal fun channelState(id: Int, name: String, parent: Int? = null): Mumble.ChannelState {
    val b = Mumble.ChannelState.newBuilder().setChannelId(id).setName(name)
    if (parent != null) b.parent = parent
    return b.build()
}

internal fun userState(session: Int, name: String, channel: Int): Mumble.UserState =
    Mumble.UserState.newBuilder().setSession(session).setName(name).setChannelId(channel).build()

internal fun userRemove(session: Int, actor: Int? = null, reason: String? = null, ban: Boolean = false): Mumble.UserRemove {
    val b = Mumble.UserRemove.newBuilder().setSession(session).setBan(ban)
    if (actor != null) b.actor = actor
    if (reason != null) b.reason = reason
    return b.build()
}

internal fun reject(reason: String = "bad pw"): Mumble.Reject = Mumble.Reject.newBuilder()
    .setType(Mumble.Reject.RejectType.WrongServerPW)
    .setReason(reason)
    .build()

/**
 * The sync sequence a Murmur server sends after Authenticate, as the session
 * sees it (CryptSetup and Ping are consumed by the transport). Self is
 * session 1 in channel 0.
 */
internal fun syncSequence(selfName: String = "alice"): List<Inbound.Message> = listOf(
    Inbound.Message(
        HumlaTCPMessageType.Version,
        Mumble.Version.newBuilder().setVersionV2(0x0001000500000000L).setRelease("1.5").build(),
    ),
    Inbound.Message(
        HumlaTCPMessageType.CodecVersion,
        Mumble.CodecVersion.newBuilder().setAlpha(0).setBeta(0).setPreferAlpha(true).setOpus(true).build(),
    ),
    Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")),
    Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(1, "Lobby", parent = 0)),
    Inbound.Message(
        HumlaTCPMessageType.PermissionQuery,
        Mumble.PermissionQuery.newBuilder().setChannelId(0).setPermissions(0x1).build(),
    ),
    Inbound.Message(HumlaTCPMessageType.UserState, userState(2, "bob", 1)),
    Inbound.Message(HumlaTCPMessageType.UserState, userState(1, selfName, 0)),
    Inbound.Message(HumlaTCPMessageType.ServerSync, serverSync(1)),
    Inbound.Message(
        HumlaTCPMessageType.ServerConfig,
        Mumble.ServerConfig.newBuilder().setAllowHtml(true).setMessageLength(5000).build(),
    ),
)

/**
 * Checks the five invariants of testing.md § Virtual-time tests. Transitions
 * and session states are recorded by unconfined collectors, so every
 * published value is seen even when several land between two steps; [check]
 * runs after every scheduler step (see [ClientHarness.step]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InvariantChecker(
    private val testScope: TestScope,
    private val order: List<String>,
) {
    val transitions: MutableList<ClientState> = Collections.synchronizedList(ArrayList())
    val sessions: MutableList<Session> = Collections.synchronizedList(ArrayList())
    private val violations: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val removedChannels = HashMap<Session, MutableSet<Int>>()
    private val lastChannels = HashMap<Session, Set<Int>>()
    private val lastUserChannels = HashMap<Session, Map<Int, Int?>>()
    private val orphanedUsers = HashMap<Session, MutableSet<Int>>()
    private var currentSession: () -> Session? = { null }
    private var tracksClient = false

    private val unconfined = UnconfinedTestDispatcher(testScope.testScheduler)

    fun watchClient(client: HumlaClient) {
        currentSession = { client.currentSession.value }
        tracksClient = true
        testScope.backgroundScope.launch(unconfined) {
            client.state.collect { transitions.add(it) }
        }
        testScope.backgroundScope.launch(unconfined) {
            client.currentSession.collect { s -> if (s != null) watchSession(s) }
        }
    }

    fun watchSession(session: Session) {
        synchronized(sessions) {
            if (sessions.any { it === session }) return
            sessions.add(session)
        }
        testScope.backgroundScope.launch(unconfined) {
            session.state.collect { checkState(session, it) }
        }
    }

    // Invariants 3 and 4, on every state a session publishes.
    private fun checkState(session: Session, s: SessionState) {
        val policy = voicePolicy(s, VoiceSettings.initial())
        val self = s.server.self
        if (policy.transmitAllowed &&
            (self == null || effectiveSelfMute(s) || self.muted || self.deafened || self.suppressed)
        ) {
            violations.add("VoicePolicy allows transmit while muted/deafened/suppressed: self=$self")
        }
        val channels = s.server.channels.keys
        val removed = removedChannels.getOrPut(session) { HashSet() }
        lastChannels[session]?.let { previous -> removed.addAll(previous - channels) }
        lastChannels[session] = channels.toSet()
        // state.md § ChannelRemove: users inside a removed channel lose
        // their channelId (null) until the server moves them. That is the
        // only tolerated gap; any other missing channel is a violation.
        val previousUsers = lastUserChannels[session] ?: emptyMap()
        val orphans = orphanedUsers.getOrPut(session) { HashSet() }
        for (user in s.server.users.values) {
            val cid = user.channelId
            if (cid != null && cid in channels) {
                orphans.remove(user.session)
                continue
            }
            val previous = previousUsers[user.session]
            if (cid == null && ((previous != null && previous in removed) || user.session in orphans)) {
                orphans.add(user.session)
                continue
            }
            violations.add("user ${user.session} in missing channel $cid")
        }
        lastUserChannels[session] = s.server.users.mapValues { it.value.channelId }
    }

    fun check() {
        synchronized(violations) {
            if (violations.isNotEmpty()) fail("Invariant violated: ${violations.first()}")
        }
        checkSessions()
        checkStopBeforeEnded()
        checkEdges()
    }

    // Invariant 1: at most one live session, and it is the published one
    // (a session the client let go of, even with no successor yet, must be
    // cancelled); a cancelled session's scope has nothing left running.
    private fun checkSessions() {
        val snapshot = synchronized(sessions) { sessions.toList() }
        val live = snapshot.filter { it.scopeJob.isActive }
        assertTrue("More than one live session: ${live.map { it.id }}", live.size <= 1)
        if (tracksClient) {
            val current = currentSession()
            for (s in live) {
                assertTrue("Live session ${s.id} is not the current one ($current)", s === current)
            }
        }
        for (s in snapshot) {
            if (s.scopeJob.isActive) continue
            val running = s.scopeJob.children.filter { !it.isCompleted }.toList()
            assertTrue(
                "Session ${s.id} scope cancelled but ${running.size} coroutine(s) still run",
                running.isEmpty(),
            )
        }
    }

    // Invariant 2: every route end is preceded by its own voice stop.
    private fun checkStopBeforeEnded() {
        var stops = 0
        var ends = 0
        for (entry in synchronized(order) { order.toList() }) {
            when (entry) {
                "voice-stop" -> stops += 1
                "route-end" -> {
                    assertTrue("route-end without a preceding voice-stop: $order", stops > ends)
                    ends += 1
                }
            }
        }
    }

    // Invariant 5: ClientState moves only along client.md's edges.
    private fun checkEdges() {
        val list = synchronized(transitions) { transitions.toList() }
        for (i in 1 until list.size) {
            val from = stateName(list[i - 1])
            val to = stateName(list[i])
            assertTrue(
                "Illegal ClientState edge $from -> $to (${list[i - 1]} -> ${list[i]})",
                (from to to) in ALLOWED_EDGES,
            )
        }
    }

    companion object {
        /** Exactly the diagram in client.md § ClientState. */
        val ALLOWED_EDGES: Set<Pair<String, String>> = setOf(
            "Idle" to "Connecting",
            "Connecting" to "Connected",
            "Connecting" to "Reconnecting",
            "Connected" to "Reconnecting",
            "Reconnecting" to "Connecting",
            "Connecting" to "Failed",
            "Connected" to "Failed",
            "Failed" to "Connecting",
            "Connecting" to "Idle",
            "Connected" to "Idle",
            "Reconnecting" to "Idle",
            "Failed" to "Idle",
            "Connected" to "Connecting",
            "Connecting" to "Connecting",
        )

        fun stateName(s: ClientState): String = when (s) {
            is ClientState.Idle -> "Idle"
            is ClientState.Connecting -> "Connecting"
            is ClientState.Connected -> "Connected"
            is ClientState.Reconnecting -> "Reconnecting"
            is ClientState.Failed -> "Failed"
        }
    }
}

/**
 * A HumlaClient on the test dispatcher with fakes, and the step wrappers
 * every scenario uses so the invariants run after each scheduler step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientHarness(
    val scope: TestScope,
    val factory: FakeFactory = FakeFactory(),
    val network: FakeNetwork = FakeNetwork(),
    val localFactsStore: LocalFactsStore = InMemoryLocalFactsStore(),
    val talkActivity: MutableStateFlow<TalkActivity> = MutableStateFlow(TalkActivity.initial()),
) {
    val order: MutableList<String> = Collections.synchronizedList(ArrayList())
    val voice = FakeVoice(order)
    val route = FakeRoute(order)
    val dispatcher = scope.coroutineContext[ContinuationInterceptor] as TestDispatcher
    val invariants = InvariantChecker(scope, order)
    val client = HumlaClient(
        parentScope = scope,
        transports = factory,
        voiceLifecycle = voice,
        routeLifecycle = route,
        voiceLink = voice,
        network = network,
        localFactsStore = localFactsStore,
        talkActivity = talkActivity,
        clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC),
        observer = NoopObserver,
        mainPoster = { r -> r.run() },
        denyFormatter = { "denied" },
        clientDispatcher = dispatcher,
        ioDispatcher = dispatcher,
        sessionDispatcher = dispatcher,
    )

    init {
        invariants.watchClient(client)
    }

    val state: ClientState get() = client.state.value
    val session: Session get() = client.currentSession.value ?: error("no current session")
    val transport: FakeTransport get() = session.transport as FakeTransport

    fun step() {
        scope.runCurrent()
        invariants.check()
    }

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        step()
    }

    fun deliver(vararg items: Inbound) {
        val t = transport
        for (item in items) {
            check(t.inboundChannel.trySend(item).isSuccess) { "inbound full or closed" }
        }
        step()
    }

    fun deliver(type: HumlaTCPMessageType, message: MessageLite) = deliver(Inbound.Message(type, message))

    fun connectAndSync(target: ConnectTarget = testTarget()) {
        client.connect(target)
        step()
        deliver(*syncSequence(target.server.username ?: "alice").toTypedArray())
        assertTrue("expected Connected, got $state", state is ClientState.Connected)
    }

    fun failTransport(error: ClientError = ClientError.Connection("boom", null)) {
        transport.fail(error)
        step()
    }

    /** Closes the client and runs the final invariant check. */
    suspend fun finish() {
        client.close().join()
        step()
    }
}
