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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.net.VoiceFormat
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.net.VoiceStatus
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.effectiveSelfMute
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.humla.voice.TalkActivity
import se.lublin.humla.voice.VoicePolicy
import se.lublin.humla.voice.VoiceSettings
import se.lublin.humla.voice.voicePolicy

/**
 * Fakes and the per-step invariant harness shared by the client and session
 * virtual-time tests (docs/target-architecture/testing.md#virtual-time-tests).
 */

/** Voice link and lifecycle in one; lifecycle calls go to the shared [order] log. */
class FakeVoice(
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

    val startFormats: MutableList<VoiceFormat> = Collections.synchronizedList(ArrayList())

    override fun startVoice(host: String, port: Int, format: VoiceFormat, keys: CryptKeys?) {
        startsCount.incrementAndGet()
        startFormats.add(format)
        order.add("voice-start")
    }

    override fun stopVoice() {
        stopsCount.incrementAndGet()
        order.add("voice-stop")
    }

    override fun setKeys(keys: CryptKeys) = Unit
    override fun setDecryptIv(iv: ByteString) = Unit
    override fun encryptIv(): ByteString = ByteString.EMPTY
    override fun submit(command: VoiceCommand) = Unit
    override fun queueTunnelled(packet: ByteArray) {
        tunnelled.add(packet)
    }

    override fun removeUser(session: Int) = Unit
}

class FakeRoute(val order: MutableList<String>) : RouteLifecycle {
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

class FakeTransport(
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
    // Non-default, so tests can see it forwarded to voice at start.
    override var voiceFormat: VoiceFormat = VoiceFormat(UdpFormat.PROTOBUF, 0x010500)

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

class FakeFactory(
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

class FakeNetwork : NetworkMonitor {
    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val available = _available.asSharedFlow()

    suspend fun emitAvailable() {
        _available.emit(Unit)
    }
}

object NoopObserver : IHumlaObserver {
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

fun testTarget(forceTcp: Boolean = true, username: String = "alice"): ConnectTarget = ConnectTarget(
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

fun serverSync(session: Int = 1): Mumble.ServerSync = Mumble.ServerSync.newBuilder()
    .setSession(session)
    .setMaxBandwidth(72000)
    .build()

fun channelState(id: Int, name: String, parent: Int? = null): Mumble.ChannelState {
    val b = Mumble.ChannelState.newBuilder().setChannelId(id).setName(name)
    if (parent != null) b.parent = parent
    return b.build()
}

fun userState(session: Int, name: String, channel: Int): Mumble.UserState =
    Mumble.UserState.newBuilder().setSession(session).setName(name).setChannelId(channel).build()

fun userRemove(session: Int, actor: Int? = null, reason: String? = null, ban: Boolean = false): Mumble.UserRemove {
    val b = Mumble.UserRemove.newBuilder().setSession(session).setBan(ban)
    if (actor != null) b.actor = actor
    if (reason != null) b.reason = reason
    return b.build()
}

fun reject(reason: String = "bad pw"): Mumble.Reject = Mumble.Reject.newBuilder()
    .setType(Mumble.Reject.RejectType.WrongServerPW)
    .setReason(reason)
    .build()

/**
 * The sync sequence a Murmur server sends after Authenticate, as the session
 * sees it (CryptSetup and Ping are consumed by the transport). Self is
 * session 1 in channel 0.
 */
fun syncSequence(selfName: String = "alice"): List<Inbound.Message> = listOf(
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

