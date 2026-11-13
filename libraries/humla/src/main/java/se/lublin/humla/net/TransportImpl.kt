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
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
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
import se.lublin.humla.client.checkNotMainThread
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.protobuf.Mumble
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transport implementation: TCP control channel with reader thread,
 * writer coroutine, TLS, keepalive, and UDP-health decision.
 * See docs/target-architecture/transport.md.
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

        /**
         * Pure ping-interval policy (see transport.md keepalive). Extracted
         * for JVM pinning (ported from HumlaConnection.getNextPingIntervalSeconds).
         */
        @JvmStatic
        fun nextPingDelaySeconds(
            elapsedMicros: Long,
            remoteGood: Int,
            localGood: Int,
            usingUdp: Boolean,
            forceTcp: Boolean,
        ): Int {
            if (elapsedMicros < BOOTSTRAP_DURATION_MICROS) return BOOTSTRAP_PING_SECONDS
            if (!forceTcp && usingUdp) {
                if (remoteGood <= 3 || localGood <= 3) return BOOTSTRAP_PING_SECONDS
            }
            return STEADY_PING_SECONDS
        }
    }

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
    )

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
        data class Tunnelled(val packet: ByteArray) : Outbound {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Tunnelled) return false
                return packet.contentEquals(other.packet)
            }

            override fun hashCode(): Int = packet.contentHashCode()
        }
    }

    private val outbound = Channel<Outbound>(OUTBOUND_CAPACITY)

    private val socketRef = AtomicReference<javax.net.ssl.SSLSocket?>(null)
    private val closed = AtomicBoolean(false)
    private val pingStarted = AtomicBoolean(false)

    private val startNanos = System.nanoTime()
    private val usingUdpRef = AtomicBoolean(!forceTcp)

    private val readerThreadRef = AtomicReference<Thread?>(null)
    private val writerJobRef = AtomicReference<Job?>(null)
    private val pingJobRef = AtomicReference<Job?>(null)
    private val voiceCollectJobRef = AtomicReference<Job?>(null)

    private val resolvedHostRef = AtomicReference(host)
    private val resolvedPortRef = AtomicInteger(port)

    override val remoteHost: String get() = resolvedHostRef.get()
    override val remotePort: Int get() = resolvedPortRef.get()

    fun elapsedMicros(): Long = (System.nanoTime() - startNanos) / 1000

    /** Connects (DNS + TLS + verify), then starts reader/writer. Throws TransportException. */
    suspend fun connect() {
        checkNotMainThread()
        withContext(Dispatchers.IO) {
            resolveIfNeeded()
            val factory = createFactory()
            val expectedHost = resolvedHostRef.get()
            factory.setExpectedHost(expectedHost)
            val created = factory.createSocket(expectedHost, resolvedPortRef.get(), CONNECT_TIMEOUT_MS)
            val socket = try {
                created.keepAlive = true
                created.tcpNoDelay = true
                created.soTimeout = CONNECT_TIMEOUT_MS
                try {
                    created.startHandshake()
                } catch (e: javax.net.ssl.SSLHandshakeException) {
                    // Handshake allocated the socket; close it before mapping
                    // the failure (verifyIdentity path below already closes).
                    try {
                        created.close()
                    } catch (ignored: Exception) {
                    }
                    throw e
                }
                created
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                val chain = factory.getServerChain()
                if (chain != null) {
                    val recorded = factory.getLastHandshakeFailure()
                    val failure = classifyFailure(factory, recorded, expectedHost)
                    throw TransportException(
                        ClientError.Trust(
                            chain = kotlinx.collections.immutable.persistentListOf(*chain),
                            failure = failure,
                            verifiedHost = expectedHost,
                        ),
                    )
                }
                throw TransportException(ClientError.Connection("TLS handshake failed: ${e.message}", e))
            } catch (e: CancellationException) {
                try {
                    created.close()
                } catch (ignored: Exception) {
                }
                throw e
            } catch (e: Exception) {
                try {
                    created.close()
                } catch (ignored: Exception) {
                }
                throw TransportException(ClientError.Connection("Connect failed: ${e.message}", e))
            }
            if (!verifyIdentity(factory, socket, expectedHost)) {
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
                        verifiedHost = expectedHost,
                    ),
                )
            }
            socket.soTimeout = READ_TIMEOUT_MS
            socketRef.set(socket)
            startReaderWriter(socket)
            // usingUdpRef and _stats were already seeded with !forceTcp at
            // construction; no post-connect reset needed.
        }
    }

    private suspend fun resolveIfNeeded() {
        if (resolvedPortRef.get() != 0) return
        val original = resolvedHostRef.get()
        if (HumlaTCP.isIpLiteral(original) || original.contains(":") ||
            TlsHostnameVerifier.isOnionHost(original)
        ) {
            // contains(":") is subsumed by isIpLiteral (true for IPv6) but
            // kept explicit at the call site for readability.
            resolvedPortRef.set(Constants.DEFAULT_PORT)
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
                        resolvedHostRef.set(target)
                        resolvedPortRef.set(srv.port)
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            // Attempt cancelled (disconnect/new connect): propagate so the
            // attempt tears down instead of connecting with a default port.
            throw e
        } catch (e: Exception) {
            logger.warning("SRV resolution failed for $original: $e")
        }
        if (resolvedPortRef.get() == 0) resolvedPortRef.set(Constants.DEFAULT_PORT)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw TransportException(ClientError.Connection("Could not load certificate: ${e.message}", e))
        } catch (e: Exception) {
            // Platform/provider bugs fail loud instead of reconnect-looping.
            throw e
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

    // Cached wire-type lookup: HumlaTCPMessageType order IS the wire value
    // (frozen by the Mumble protocol); avoid values() allocation per frame.
    // entries (not values()) matches FrameValidator.MESSAGE_TYPE_COUNT.
    private val tcpTypes = HumlaTCPMessageType.entries.toTypedArray()

    private fun startReaderWriter(socket: javax.net.ssl.SSLSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        writerJobRef.set(scope.launch {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failOnce(ClientError.Connection("TCP write failed: ${e.message}", e))
                    break
                }
            }
        })
        voiceCollectJobRef.set(scope.launch {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Collection only throws on genuine voice-side bugs now that
                // cancellation is rethrown above; log instead of silently
                // disabling UDP fallback for the rest of the session.
                logger.fine("Voice collector dropped: $e")
            }
        })
        val thread = Thread({
            readerLoop(input)
        }, "transport-reader")
        thread.isDaemon = true
        readerThreadRef.set(thread)
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
                val tcpType = tcpTypes[messageType.toInt()]
                if (tcpType == HumlaTCPMessageType.UDPTunnel) {
                    inboundChannel.trySendBlocking(Inbound.Tunnelled(data))
                    continue
                }
                val parsed: MessageLite
                try {
                    parsed = ProtobufMessages.parse(data, tcpType)
                } catch (e: InvalidProtocolBufferException) {
                    logger.fine("Dropping malformed $tcpType frame")
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
        voice.setServerVersion(v2, v1)
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
                } else {
                    failOnce(ClientError.Connection("Invalid key or nonce size in CryptSetup", null))
                }
            } else if (msg.hasServerNonce()) {
                val serverNonce = msg.serverNonce
                if (serverNonce.size() == CryptState.AES_BLOCK_SIZE) {
                    voice.setDecryptIv(serverNonce)
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
        if ((remote.good == 0 || localGood == 0) && usingUdpRef.get() && elapsed > UDP_GIVEUP_MICROS) {
            switchToTunnel("no good packets (remote=${remote.good} local=$localGood)")
        } else if (!forceTcp && !usingUdpRef.get() && remote.good > 3 && localGood > 3) {
            // Guarded both directions with CAS: a concurrent switchToTunnel
            // (voice-collector thread) may interleave on a stale read. The
            // loser self-heals on the next ping round either way.
            if (!usingUdpRef.compareAndSet(false, true)) {
                _stats.update { it.copy(tcpLatencyMicros = tcpLatency, remoteCrypt = remote) }
                return
            }
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
        // Single dummy in the protobuf form, matching enableForceTCP parity
        // (HumlaConnection sent Mumble.UDPTunnel{packet=3 zeros}; raw voice
        // frames always travel as raw UDPTunnel payloads, never protobuf).
        // The dummy is best-effort: an outbound-full channel already failed
        // the session via failOnce, so reaching here means it was enqueued.
        try {
            val utb = Mumble.UDPTunnel.newBuilder()
                .setPacket(ByteString.copyFrom(dummyTunnelPacket()))
                .build()
            send(utb, HumlaTCPMessageType.UDPTunnel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreachable today (send is trySend-only); kept for symmetry
            // with the surrounding best-effort handling.
        }
    }

    /** Starts the keepalive ping loop; called once by Session on ServerSync. */
    override fun startKeepalive() {
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
        pingJobRef.set(scope.launch {
            // First ping at once, matching today's scheduleNextPing(0).
            while (isActive) {
                try {
                    sendKeepalivePing()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warning("Keepalive ping failed: $e")
                }
                delay(nextPingDelaySeconds() * 1000L)
            }
        })
    }

    private fun nextPingDelaySeconds(): Int {
        return nextPingDelaySeconds(
            elapsedMicros(),
            _stats.value.remoteCrypt.good,
            voice.status.value.localCrypt.good,
            usingUdpRef.get(),
            forceTcp,
        )
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
        if (closed.get()) return
        // complete() is the once-gate: concurrent callers converge here and
        // exactly one proceeds to close the socket.
        if (!failureDeferred.complete(error)) return
        // Unblock the reader promptly; its failure path exits quietly on close.
        try {
            socketRef.get()?.close()
        } catch (ignored: Exception) {
        }
    }

    override fun close() {
        checkNotMainThread()
        if (!closed.compareAndSet(false, true)) return
        // Wake a reader parked in trySendBlocking on a full inbound channel;
        // its finally block is what observes the close.
        try {
            inboundChannel.close()
        } catch (ignored: Exception) {
        }
        try {
            socketRef.getAndSet(null)?.close()
        } catch (ignored: Exception) {
        }
        outbound.close()
        scope.cancel()
        try {
            readerThreadRef.get()?.join(1000)
        } catch (ignored: Exception) {
        }
    }
}

/** Thrown by TransportFactory creation on connect-time failure. */
class TransportException(val error: ClientError) : Exception(error.toString())

/** Default factory doing DNS + TLS on the IO dispatcher. */
class DefaultTransportFactory : TransportFactory {
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
            parentScope = parentScope,
        )
        try {
            impl.connect()
        } catch (e: TransportException) {
            try {
                impl.close()
            } catch (ignored: Exception) {
            }
            throw e
        } catch (e: CancellationException) {
            try {
                impl.close()
            } catch (ignored: Exception) {
            }
            throw e
        } catch (e: IOException) {
            try {
                impl.close()
            } catch (ignored: Exception) {
            }
            throw TransportException(ClientError.Connection("Connect failed: ${e.message}", e))
        } catch (e: Exception) {
            // Unexpected failures propagate to the client, which fails the
            // attempt terminally (no retry, no crash): deterministic bugs
            // stay visible instead of looping reconnects forever.
            try {
                impl.close()
            } catch (ignored: Exception) {
            }
            throw e
        }
        return impl
    }
}
