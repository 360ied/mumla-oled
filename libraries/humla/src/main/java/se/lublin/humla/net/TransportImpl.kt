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
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.trySendBlocking
import org.minidns.hla.ResolverApi
import org.minidns.util.SrvUtil
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.protobuf.Mumble
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transport implementation: TCP control channel with reader thread,
 * writer coroutine, TLS, keepalive, and UDP-health decision.
 * See docs/tech-debt-whales/target-architecture/transport.md.
 *
 * One per Session. Talks to voice only through VoiceLink.
 * Reader is a plain Thread on the allowlist; everything else is coroutines.
 */
class TransportImpl internal constructor(
    private val host: String,
    private val port: Int,
    private val certificate: ByteArray?,
    private val certificatePassword: String?,
    private val trustStorePath: String?,
    private val trustStorePassword: String?,
    private val trustStoreFormat: String?,
    private val forceTcp: Boolean,
    private val voice: VoiceLink,
    parentScope: CoroutineScope,
) : Transport {
    companion object {
        const val CONNECT_TIMEOUT_MS = 10000
        const val READ_TIMEOUT_MS = 30000
        const val OUTBOUND_CAPACITY = 256
        const val INBOUND_CAPACITY = 1024

        const val BOOTSTRAP_PING_SECONDS = 5
        const val STEADY_PING_SECONDS = 10
        const val BOOTSTRAP_DURATION_MICROS = 30_000_000L
        const val UDP_GIVEUP_MICROS = 20_000_000L

        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(TransportImpl::class.java.name)

        /** Dummy UDPTunnel payload telling the server to tunnel (3 zero bytes). */
        fun dummyTunnelPacket(): ByteArray = ByteArray(3)
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)

    private val inboundChannel = Channel<Inbound>(INBOUND_CAPACITY)
    override val inbound: ReceiveChannel<Inbound> = inboundChannel

    private val _stats = MutableStateFlow(TransportStats.initial(usingUdp = !forceTcp))
    override val stats: StateFlow<TransportStats> = _stats.asStateFlow()

    private val _cryptKeys = MutableStateFlow<CryptKeys?>(null)
    override val cryptKeys: StateFlow<CryptKeys?> = _cryptKeys.asStateFlow()

    private val failureDeferred = CompletableDeferred<ClientError>()
    override val failure get() = failureDeferred

    private sealed interface Outbound {
        data class Message(val message: MessageLite, val type: HumlaTCPMessageType) : Outbound
        data class Tunnelled(val packet: ByteArray) : Outbound
    }

    private val outbound = Channel<Outbound>(OUTBOUND_CAPACITY)

    private val socketRef = AtomicReference<javax.net.ssl.SSLSocket?>(null)
    private val closed = AtomicBoolean(false)
    private val pingStarted = AtomicBoolean(false)

    private val startNanos = System.nanoTime()
    private val usingUdpRef = AtomicBoolean(!forceTcp)

    private var readerThread: Thread? = null
    private var writerJob: Job? = null
    private var pingJob: Job? = null
    private var voiceCollectJob: Job? = null

    private var resolvedHost: String = host
    private var resolvedPort: Int = port

    fun elapsedMicros(): Long = (System.nanoTime() - startNanos) / 1000

    /** Connects (DNS + TLS + verify), then starts reader/writer. Throws TransportException. */
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            resolveIfNeeded()
            val factory = createFactory()
            val socket = try {
                factory.setExpectedHost(resolvedHost)
                val created = factory.createSocket(resolvedHost, resolvedPort, CONNECT_TIMEOUT_MS)
                created.keepAlive = true
                created.tcpNoDelay = true
                created.soTimeout = CONNECT_TIMEOUT_MS
                created.startHandshake()
                created
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                val chain = factory.getServerChain()
                if (chain != null) {
                    val recorded = factory.getLastHandshakeFailure()
                    val failure = classifyFailure(factory, recorded, resolvedHost)
                    throw TransportException(
                        ClientError.Trust(
                            chain = kotlinx.collections.immutable.persistentListOf(*chain),
                            failure = failure,
                            verifiedHost = resolvedHost,
                        ),
                    )
                }
                throw TransportException(ClientError.Connection("TLS handshake failed: ${e.message}", e))
            } catch (e: Exception) {
                if (e is TransportException) throw e
                throw TransportException(ClientError.Connection("Connect failed: ${e.message}", e))
            }
            if (!verifyIdentity(factory, socket, resolvedHost)) {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
                val chain = factory.getServerChain()
                val failure = factory.getLastHandshakeFailure()
                throw TransportException(
                    ClientError.Trust(
                        chain = if (chain != null) {
                            kotlinx.collections.immutable.persistentListOf(*chain)
                        } else {
                            kotlinx.collections.immutable.persistentListOf()
                        },
                        failure = failure,
                        verifiedHost = resolvedHost,
                    ),
                )
            }
            socket.soTimeout = READ_TIMEOUT_MS
            socketRef.set(socket)
            startReaderWriter(socket)
            if (forceTcp) {
                _stats.update { it.copy(usingUdp = false) }
                usingUdpRef.set(false)
            }
        }
    }

    private suspend fun resolveIfNeeded() {
        if (resolvedPort != 0) return
        val original = resolvedHost
        if (HumlaTCP.isIpLiteral(original) || original.contains(":") ||
            TlsHostnameVerifier.isOnionHost(original)
        ) {
            resolvedPort = Constants.DEFAULT_PORT
            return
        }
        try {
            val lookup = "_mumble._tcp.$original"
            val res = withTimeoutOrNull(800) {
                ResolverApi.INSTANCE.resolveSrv(lookup)
            }
            if (res != null && res.wasSuccessful()) {
                val answers = res.getAnswersOrEmptySet()
                if (answers != null && answers.isNotEmpty()) {
                    val srvs = SrvUtil.sortSrvRecords(answers)
                    for (srv in srvs) {
                        var target = srv.target.toString()
                        if (target.endsWith(".")) target = target.substring(0, target.length - 1)
                        resolvedHost = target
                        resolvedPort = srv.port
                        break
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("SRV resolution failed for $original: $e")
        }
        if (resolvedPort == 0) resolvedPort = Constants.DEFAULT_PORT
    }

    private fun createFactory(): HumlaSSLSocketFactory {
        try {
            var keyStore: KeyStore? = null
            if (certificate != null) {
                keyStore = KeyStore.getInstance("PKCS12")
                ByteArrayInputStream(certificate).use { ins ->
                    keyStore.load(ins, certificatePassword?.toCharArray() ?: CharArray(0))
                }
            }
            return HumlaSSLSocketFactory(
                keyStore, certificatePassword, trustStorePath,
                trustStorePassword, trustStoreFormat,
            )
        } catch (e: Exception) {
            throw TransportException(ClientError.Connection("Could not load certificate: ${e.message}", e))
        }
    }

    private fun verifyIdentity(
        factory: HumlaSSLSocketFactory,
        socket: javax.net.ssl.SSLSocket,
        expectedHost: String,
    ): Boolean {
        val leaf = presentedLeaf(factory, socket) ?: run {
            factory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH)
            return false
        }
        if (TlsHostnameVerifier.isOnionHost(expectedHost)) return true
        if (factory.isPinnedLeaf(leaf)) return true
        if (TlsHostnameVerifier.verifyHostname(expectedHost, leaf)) return true
        factory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH)
        return false
    }

    private fun presentedLeaf(
        factory: HumlaSSLSocketFactory,
        socket: javax.net.ssl.SSLSocket,
    ): java.security.cert.X509Certificate? {
        val chain = factory.getServerChain()
        if (chain != null && chain.isNotEmpty() && chain[0] != null) return chain[0]
        try {
            val session = socket.session ?: return null
            val peers = session.peerCertificates ?: return null
            for (peer in peers) {
                if (peer is java.security.cert.X509Certificate) return peer
            }
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            return null
        }
        return null
    }

    private fun classifyFailure(
        factory: HumlaSSLSocketFactory,
        recorded: HandshakeFailure?,
        expectedHost: String,
    ): HandshakeFailure {
        if (recorded != null && recorded != HandshakeFailure.NONE) return recorded
        val chain = factory.getServerChain()
        val leaf = if (chain != null && chain.isNotEmpty()) chain[0] else null
        if (leaf != null && !TlsHostnameVerifier.isOnionHost(expectedHost) &&
            !TlsHostnameVerifier.verifyHostname(expectedHost, leaf)
        ) {
            factory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH)
            return HandshakeFailure.HOSTNAME_MISMATCH
        }
        return HandshakeFailure.UNTRUSTED_ISSUER
    }

    private fun startReaderWriter(socket: javax.net.ssl.SSLSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        writerJob = scope.launch {
            for (item in outbound) {
                try {
                    when (item) {
                        is Outbound.Message -> {
                            output.writeShort(item.type.ordinal)
                            output.writeInt(item.message.serializedSize)
                            item.message.writeTo(output)
                            output.flush()
                        }
                        is Outbound.Tunnelled -> {
                            output.writeShort(HumlaTCPMessageType.UDPTunnel.ordinal)
                            output.writeInt(item.packet.size)
                            output.write(item.packet)
                            output.flush()
                        }
                    }
                } catch (e: Exception) {
                    failOnce(ClientError.Connection("TCP write failed: ${e.message}", e))
                    break
                }
            }
        }
        voiceCollectJob = scope.launch {
            try {
                voice.events.collect { event ->
                    when (event) {
                        is VoiceEvent.UdpPing -> onUdpPing(event)
                        is VoiceEvent.CryptResyncRequested -> {
                            val empty = Mumble.CryptSetup.newBuilder().build()
                            send(empty, HumlaTCPMessageType.CryptSetup)
                        }
                        is VoiceEvent.UdpUnavailable -> switchToTunnel("voice reported UDP unavailable")
                    }
                }
            } catch (e: Exception) {
                // Scope cancelled; exit quietly.
            }
        }
        val thread = Thread({
            readerLoop(input)
        }, "transport-reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun readerLoop(input: DataInputStream) {
        try {
            while (!closed.get()) {
                val messageType: Short
                val messageLength: Int
                try {
                    messageType = input.readShort()
                    messageLength = input.readInt()
                } catch (e: Exception) {
                    if (closed.get()) return
                    failOnce(ClientError.Connection("TCP read failed: ${e.message}", e))
                    return
                }
                val frameError = FrameValidator.validateFrame(messageType, messageLength)
                if (frameError != FrameValidator.FrameError.NONE) {
                    failOnce(ClientError.Connection("Invalid protocol frame ($frameError)", null))
                    return
                }
                val data = ByteArray(messageLength)
                try {
                    input.readFully(data)
                } catch (e: Exception) {
                    if (closed.get()) return
                    failOnce(ClientError.Connection("TCP read failed: ${e.message}", e))
                    return
                }
                val tcpType = HumlaTCPMessageType.values()[messageType.toInt()]
                if (tcpType == HumlaTCPMessageType.UDPTunnel) {
                    inboundChannel.trySendBlocking(Inbound.Tunnelled(data))
                    continue
                }
                val parsed: MessageLite
                try {
                    parsed = ProtobufMessages.parse(data, tcpType)
                } catch (e: InvalidProtocolBufferException) {
                    continue
                }
                when (tcpType) {
                    HumlaTCPMessageType.Ping -> handlePing(parsed as Mumble.Ping)
                    HumlaTCPMessageType.CryptSetup -> handleCryptSetup(parsed as Mumble.CryptSetup)
                    HumlaTCPMessageType.Version -> {
                        handleVersion(parsed as Mumble.Version)
                        inboundChannel.trySendBlocking(Inbound.Message(tcpType, parsed))
                    }
                    else -> inboundChannel.trySendBlocking(Inbound.Message(tcpType, parsed))
                }
            }
        } finally {
            try {
                inboundChannel.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handleVersion(msg: Mumble.Version) {
        val v2: Long
        val v1: Int
        if (msg.hasVersionV2()) {
            v2 = msg.versionV2
            v1 = Constants.toLegacyVersion(v2)
        } else if (msg.hasVersionV1()) {
            v1 = msg.versionV1
            v2 = Constants.toVersionV2FromLegacy(v1)
        } else {
            return
        }
        (voice as? LegacyVoiceLink)?.setServerVersion(v2, v1)
    }

    private fun handleCryptSetup(msg: Mumble.CryptSetup) {
        try {
            if (msg.hasKey() && msg.hasClientNonce() && msg.hasServerNonce()) {
                val key = msg.key
                val clientNonce = msg.clientNonce
                val serverNonce = msg.serverNonce
                if (key.size() == CryptState.AES_BLOCK_SIZE &&
                    clientNonce.size() == CryptState.AES_BLOCK_SIZE &&
                    serverNonce.size() == CryptState.AES_BLOCK_SIZE
                ) {
                    val keys = CryptKeys(key, clientNonce, serverNonce)
                    _cryptKeys.value = keys
                    voice.setKeys(keys)
                    (voice as? LegacyVoiceLink)?.refreshStatus()
                } else {
                    failOnce(ClientError.Connection("Invalid key or nonce size in CryptSetup", null))
                }
            } else if (msg.hasServerNonce()) {
                val serverNonce = msg.serverNonce
                if (serverNonce.size() == CryptState.AES_BLOCK_SIZE) {
                    voice.setDecryptIv(serverNonce)
                    (voice as? LegacyVoiceLink)?.refreshStatus()
                } else {
                    failOnce(ClientError.Connection("Invalid server nonce in CryptSetup", null))
                }
            } else {
                val reply = Mumble.CryptSetup.newBuilder()
                    .setClientNonce(voice.encryptIv())
                    .build()
                send(reply, HumlaTCPMessageType.CryptSetup)
            }
        } catch (e: Exception) {
            failOnce(ClientError.Connection("CryptSetup failed: ${e.message}", e))
        }
    }

    private fun handlePing(msg: Mumble.Ping) {
        val elapsed = elapsedMicros()
        val tcpLatency = elapsed - msg.timestamp
        val remote = CryptCounts(
            good = msg.good,
            late = msg.late,
            lost = msg.lost,
            resync = msg.resync,
        )
        val localGood = voice.status.value.localCrypt.good
        val usingUdp = usingUdpRef.get()
        if ((remote.good == 0 || localGood == 0) && usingUdp && elapsed > UDP_GIVEUP_MICROS) {
            switchToTunnel("no good packets (remote=${remote.good} local=$localGood)")
        } else if (!usingUdp && !forceTcp && remote.good > 3 && localGood > 3) {
            usingUdpRef.set(true)
            _stats.update { it.copy(usingUdp = true, tcpLatencyMicros = tcpLatency, remoteCrypt = remote) }
            voice.submit(VoiceCommand.SetVoiceRoute(true))
            logger.fine("Switching back to UDP mode")
            return
        }
        _stats.update { it.copy(tcpLatencyMicros = tcpLatency, remoteCrypt = remote) }
    }

    private fun onUdpPing(event: VoiceEvent.UdpPing) {
        val now = elapsedMicros()
        val latency = now - event.timestampMicros
        _stats.update { it.copy(udpLatencyMicros = latency) }
    }

    private fun switchToTunnel(reason: String) {
        if (!usingUdpRef.getAndSet(false)) return
        _stats.update { it.copy(usingUdp = false) }
        voice.submit(VoiceCommand.SetVoiceRoute(false))
        logger.info("Switching to TCP mode ($reason)")
        try {
            val dummy = dummyTunnelPacket()
            if (!outbound.trySend(Outbound.Tunnelled(dummy)).isSuccess) {
                // Outbound full; the next send will fail the session. Send the
                // dummy on the writer path via a direct try to avoid loss.
            }
            // Also send as a UDPTunnel control message carrying 3 zero bytes
            // inside the raw payload, matching today's enableForceTCP.
            val utb = Mumble.UDPTunnel.newBuilder()
                .setPacket(ByteString.copyFrom(dummy))
                .build()
            // Note: voice-over-TCP uses raw packets (see sendTunnelled);
            // the dummy here is the protobuf form telling the server to tunnel.
            send(utb, HumlaTCPMessageType.UDPTunnel)
        } catch (e: Exception) {
            // Best effort; ping loop will retry the decision.
        }
    }

    /** Starts the keepalive ping loop; called once by Session on ServerSync. */
    fun startKeepalive() {
        if (!pingStarted.compareAndSet(false, true)) return
        if (forceTcp) {
            // Forced TCP: send the dummy tunnel now, never use UDP.
            usingUdpRef.set(false)
            _stats.update { it.copy(usingUdp = false) }
            voice.submit(VoiceCommand.SetVoiceRoute(false))
            val utb = Mumble.UDPTunnel.newBuilder()
                .setPacket(ByteString.copyFrom(dummyTunnelPacket()))
                .build()
            send(utb, HumlaTCPMessageType.UDPTunnel)
        }
        pingJob = scope.launch {
            // First ping at once, matching today's scheduleNextPing(0).
            while (isActive) {
                try {
                    sendKeepalivePing()
                } catch (e: Exception) {
                    logger.warning("Keepalive ping failed: $e")
                }
                delay(nextPingDelaySeconds() * 1000L)
            }
        }
    }

    private fun nextPingDelaySeconds(): Int {
        val elapsed = elapsedMicros()
        if (elapsed < BOOTSTRAP_DURATION_MICROS) return BOOTSTRAP_PING_SECONDS
        if (!forceTcp && usingUdpRef.get()) {
            val remoteGood = _stats.value.remoteCrypt.good
            val localGood = voice.status.value.localCrypt.good
            if (remoteGood <= 3 || localGood <= 3) return BOOTSTRAP_PING_SECONDS
        }
        return STEADY_PING_SECONDS
    }

    private fun sendKeepalivePing() {
        val t = elapsedMicros()
        if (!forceTcp) {
            voice.submit(VoiceCommand.SendPing(t))
        }
        val local = voice.status.value.localCrypt
        val pb = Mumble.Ping.newBuilder()
            .setTimestamp(t)
            .setGood(local.good)
            .setLate(local.late)
            .setLost(local.lost)
            .setResync(local.resync)
            .build()
        send(pb, HumlaTCPMessageType.Ping)
    }

    override fun send(message: MessageLite, type: HumlaTCPMessageType) {
        if (closed.get()) return
        val result = outbound.trySend(Outbound.Message(message, type))
        if (!result.isSuccess) {
            failOnce(ClientError.Connection("Transport outbound full; server stopped reading", null))
        }
    }

    override fun sendTunnelled(packet: ByteArray) {
        if (closed.get()) return
        val result = outbound.trySend(Outbound.Tunnelled(packet.copyOf()))
        if (!result.isSuccess) {
            failOnce(ClientError.Connection("Transport outbound full; server stopped reading", null))
        }
    }

    private fun failOnce(error: ClientError) {
        failureDeferred.complete(error)
        // Unblock the reader promptly; its failure path exits quietly on close.
        if (error is ClientError.Connection) {
            try {
                socketRef.get()?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socketRef.getAndSet(null)?.close()
        } catch (ignored: Exception) {
        }
        outbound.close()
        scope.cancel()
        try {
            readerThread?.join(1000)
        } catch (ignored: Exception) {
        }
    }
}

/** Thrown by TransportFactory creation on connect-time failure. */
class TransportException(val error: ClientError) : Exception(error.toString())

/** Default factory doing DNS + TLS on the IO dispatcher. */
class DefaultTransportFactory : TransportFactory {
    override suspend fun create(
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
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val impl = TransportImpl(
            host = host,
            port = port,
            certificate = certificate,
            certificatePassword = certificatePassword,
            trustStorePath = trustStorePath,
            trustStorePassword = trustStorePassword,
            trustStoreFormat = trustStoreFormat,
            forceTcp = forceTcp,
            voice = voice,
            parentScope = parent,
        )
        try {
            impl.connect()
        } catch (e: TransportException) {
            try {
                impl.close()
            } catch (ignored: Exception) {
            }
            throw e
        } catch (e: Exception) {
            try {
                impl.close()
            } catch (ignored: Exception) {
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw TransportException(ClientError.Connection("Connect failed: ${e.message}", e))
        }
        return impl
    }
}
