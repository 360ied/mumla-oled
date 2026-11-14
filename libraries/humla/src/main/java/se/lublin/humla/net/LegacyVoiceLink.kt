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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.Constants
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.client.VoiceLifecycle
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.protobuf.MumbleUDP

/**
 * Interim VoiceLink over today's Kotlin HumlaUDP and CryptState.
 * See docs/target-architecture/migration.md step 3.
 *
 * Step 5a replaces it with the VoiceEngine. Until then it carries keys,
 * IVs, UDP pings, tunnelled packets, and resync requests between the
 * Transport and the voice path.
 *
 * Android-free: no android.* imports. No tripwire patterns: uses
 * AtomicBoolean/AtomicInteger and coroutines, never synchronized/Volatile.
 */
class LegacyVoiceLink @JvmOverloads constructor(
    private val tunnelSink: (ByteArray) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : VoiceLink, VoiceLifecycle, HumlaUDP.UDPConnectionListener {
    companion object {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(LegacyVoiceLink::class.java.name)
    }

    private val crypt = CryptState()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(VoiceStatus.initial())
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private val udpHandlers = CopyOnWriteArrayList<HumlaUDPMessageListener>()

    private val usingUdp = AtomicBoolean(true)
    private val forceTcp = AtomicBoolean(false)

    private val serverVersion = AtomicInteger(0)
    private val serverVersionV2 = AtomicLong(0L)

    private val udpRef = AtomicReference<HumlaUDP?>(null)
    // Direct executor (no thread hop): UDP data callbacks run on the
    // receiver coroutine per the HumlaUDP contract; resync/error posts
    // execute inline for the same reason.
    private val udpExecutor = java.util.concurrent.Executor { r -> r.run() }

    private val targetFramesPerPacket = AtomicInteger(Constants.DEFAULT_FRAMES_PER_PACKET)
    // Interim encoder target for SessionStats.currentBandwidth until the step-5a
    // VoicePolicy carries real values. Seeded with the Settings default.
    private val targetBitrate = AtomicInteger(40000)

    override fun setForceTcp(force: Boolean) {
        forceTcp.set(force)
        if (force) {
            usingUdp.set(false)
        }
    }

    override fun setServerVersion(v2: Long, v1: Int) {
        serverVersionV2.set(v2)
        serverVersion.set(v1)
    }

    fun setTargetFramesPerPacket(fpp: Int) {
        targetFramesPerPacket.set(Constants.sanitizeFramesPerPacket(fpp))
        udpRef.get()?.setTargetFramesPerPacket(targetFramesPerPacket.get())
        refreshStatus()
    }

    fun getTargetFramesPerPacket(): Int = targetFramesPerPacket.get()

    fun setTargetBitrate(bitrate: Int) {
        targetBitrate.set(bitrate)
        refreshStatus()
    }

    override fun startVoice(host: String, port: Int) {
        // Fresh session, fresh route: a previous session's tunnel decision
        // must not pin the new one on TCP (or skip UDP when forced).
        if (forceTcp.get()) return
        usingUdp.set(true)
        connectUdp(host, port)
    }

    override fun stopVoice() {
        disconnectUdp()
    }

    fun connectUdp(host: String, port: Int) {
        if (forceTcp.get() || !usingUdp.get()) return
        if (udpRef.get() != null) return
        val created = HumlaUDP(crypt, this, udpExecutor, targetFramesPerPacket.get())
        if (!udpRef.compareAndSet(null, created)) {
            created.disconnect()
            return
        }
        created.connect(host, port)
        // A concurrent stopVoice() disconnects the object before connect
        // opens it; verify ownership after the fact so no live socket
        // survives the stop.
        if (udpRef.get() !== created) {
            created.disconnect()
            return
        }
        refreshStatus()
    }

    fun disconnectUdp() {
        val existing = udpRef.getAndSet(null) ?: return
        existing.disconnect()
    }

    /** Fans voice/ping events out to registered UDP handlers, isolating
     * each: a throwing handler must not kill the UDP receiver for the rest. */
    private inline fun broadcast(crossinline action: (HumlaUDPMessageListener) -> Unit) {
        for (handler in udpHandlers) {
            try {
                action(handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.fine("Dropping throwing UDP handler: $e")
            }
        }
    }

    fun addUdpHandler(handler: HumlaUDPMessageListener) {
        udpHandlers.addIfAbsent(handler)
    }

    fun removeUdpHandler(handler: HumlaUDPMessageListener) {
        udpHandlers.remove(handler)
    }

    /**
     * Sends a voice packet, tunneling over TCP or via UDP per route policy.
     * Mirrors HumlaConnection.sendUDPMessage. Framing (including the 1.2.2
     * workaround, a no-op for Opus) is owned by the native packet codec;
     * this only routes the framed datagram.
     */
    fun sendVoice(data: ByteArray, length: Int) {
        if (length < 0 || length > data.size) {
            throw IllegalArgumentException(
                "Requested length $length out of bounds for available data length ${data.size}!",
            )
        }
        if (forceTcp.get() || !usingUdp.get()) {
            // Tunnel: hand the framed packet to the transport's UDPTunnel sender.
            val copy = data.copyOf(length)
            tunnelSink(copy)
            refreshStatus()
            return
        }
        // UDP mode: drop while the socket is not up yet, as before
        // (HumlaConnection dropped when mUDP was null).
        udpRef.get()?.sendMessage(data, length)
        refreshStatus()
    }

    override fun setKeys(keys: CryptKeys) {
        if (!keys.isWellFormed()) return
        crypt.setKeys(
            keys.key.toByteArray(),
            keys.clientNonce.toByteArray(),
            keys.serverNonce.toByteArray(),
        )
        refreshStatus()
    }

    override fun setDecryptIv(iv: ByteString) {
        if (iv.size() != CryptState.AES_BLOCK_SIZE) return
        crypt.setDecryptIV(iv.toByteArray())
    }

    override fun encryptIv(): ByteString {
        return ByteString.copyFrom(crypt.getEncryptIV())
    }

    override fun submit(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.SetVoiceRoute -> usingUdp.set(command.udp && !forceTcp.get())
            is VoiceCommand.SendPing -> sendUdpPing(command.timestampMicros)
        }
        refreshStatus()
    }

    override fun queueTunnelled(packet: ByteArray) {
        // Tunnelled packets arrive unencrypted (TLS protects them).
        onUDPDataReceived(packet)
    }

    private fun sendUdpPing(timestampMicros: Long) {
        if (forceTcp.get() || !usingUdp.get()) return
        val current = udpRef.get() ?: return
        // Ping framing is owned by the native packet codec (step 5a.2),
        // byte-equal to the Java framing it replaces. A null return is an
        // encode failure: drop with a log, as the oversize path did before.
        if (isProtobufUdpSupported()) {
            val packet = try {
                NativeVoiceCodec.frameProtobufPing(timestampMicros)
            } catch (e: Exception) {
                logger.fine("Dropping protobuf ping after codec failure: $e")
                null
            } ?: run {
                logger.fine("Dropping protobuf ping (codec encode failed)")
                return
            }
            current.sendMessage(packet, packet.size)
        } else {
            val packet = try {
                NativeVoiceCodec.frameLegacyPing(timestampMicros)
            } catch (e: Exception) {
                logger.fine("Dropping legacy ping after codec failure: $e")
                null
            } ?: run {
                logger.fine("Dropping legacy ping (codec encode failed)")
                return
            }
            current.sendMessage(packet, packet.size)
        }
    }

    fun isProtobufUdpSupported(): Boolean {
        return Constants.isProtobufUdpSupported(serverVersionV2.get(), serverVersion.get())
    }

    // HumlaUDP.UDPConnectionListener: runs on the UDP receiver coroutine for
    // data, and on the callback executor (direct) for resync/error.
    // Both inbound formats are parsed by the native packet codec (step 5a.2);
    // Kotlin only routes the parsed frames and pings. The protobuf branch no
    // longer calls MumbleUDP.Audio.parseFrom, and no PacketBuffer parser stays
    // behind: both formats hand the same parsed frames to AudioOutput, which
    // keeps applying today's locally-muted and unknown-sender drops to both.
    override fun onUDPDataReceived(data: ByteArray) {
        if (data.isEmpty()) {
            logger.fine("Dropping empty UDP datagram")
            return
        }
        if (isProtobufUdpSupported()) {
            val msgType = data[0].toInt() and 0xFF
            if (msgType == 0) {
                val frames = try {
                    NativeVoiceCodec.parseProtobufVoice(data)
                } catch (e: Exception) {
                    logger.fine("Dropping malformed protobuf voice: $e")
                    null
                }
                if (frames == null) {
                    logger.fine("Dropping malformed protobuf voice")
                    refreshStatus()
                    return
                }
                broadcast { it.messageDecodedVoice(frames.toList()) }
                refreshStatus()
                return
            } else if (msgType == 1) {
                val ping = try {
                    NativeVoiceCodec.parseProtobufPing(data)
                } catch (e: Exception) {
                    logger.fine("Dropping malformed protobuf ping: $e")
                    null
                }
                if (ping == null) {
                    logger.fine("Dropping malformed protobuf ping")
                    return
                }
                val v2 = if (ping.serverVersionV2 != 0L) ping.serverVersionV2 else null
                val bw = if (ping.maxBandwidth != 0) ping.maxBandwidth else null
                if (!_events.tryEmit(VoiceEvent.UdpPing(ping.timestamp, v2, bw))) {
                    logger.fine("Dropping UdpPing (event buffer full)")
                }
                // Rebuilt for the broadcast only; parsing itself was native.
                // AudioHandler ignores pings (handled in Transport).
                val pingMsg = MumbleUDP.Ping.newBuilder()
                    .setTimestamp(ping.timestamp)
                    .apply {
                        if (v2 != null) setServerVersionV2(v2)
                        if (bw != null) setMaxBandwidthPerUser(bw)
                    }.build()
                broadcast { it.messageProtobufPing(pingMsg) }
                if (v2 != null) serverVersionV2.set(v2)
                refreshStatus()
                return
            } else {
                // Protobuf mode: any other lead byte is neither voice nor
                // ping; drop instead of mis-decoding as legacy voice.
                logger.fine("Dropping unknown protobuf-UDP lead byte")
                return
            }
        }
        // Legacy dispatch is a header peek only, not a parse: voice vs ping
        // by the 3-bit type, exactly as before. Parsing itself is native.
        val dataType = (data[0].toInt() and 0xFF) shr 5
        if (dataType == 1) {
            val ping = try {
                NativeVoiceCodec.parseLegacyPing(data)
            } catch (e: Exception) {
                logger.fine("Dropping malformed legacy ping: $e")
                null
            }
            if (ping == null) {
                logger.fine("Dropping malformed legacy ping")
                refreshStatus()
                return
            }
            if (!_events.tryEmit(VoiceEvent.UdpPing(ping.timestamp, null, null))) {
                logger.fine("Dropping UdpPing (event buffer full)")
            }
            broadcast { it.messageUDPPing(data) }
            refreshStatus()
            return
        } else if (dataType == 4) {
            val frames = try {
                NativeVoiceCodec.parseLegacyVoice(data, serverVersion.get())
            } catch (e: Exception) {
                logger.fine("Dropping malformed legacy voice: $e")
                null
            }
            if (frames == null) {
                logger.fine("Dropping malformed legacy voice")
                refreshStatus()
                return
            }
            broadcast { it.messageDecodedVoice(frames.toList()) }
            refreshStatus()
            return
        }
        if (dataType < 0 || dataType > 7) {
            logger.fine("Dropping out-of-range UDP type")
            return
        }
        // Remaining legacy types (0 CELT Alpha, 2 Speex, 3 CELT Beta) predate
        // Opus and never carry voice; 5-7 are unassigned. Drop, as before.
        logger.fine("Dropping legacy-codec voice packet")
        refreshStatus()
    }

    override fun onUDPConnectionError(e: Exception) {
        if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
            logger.fine("Dropping UdpUnavailable (event buffer full): $e")
        }
    }

    override fun resyncCryptState() {
        if (!_events.tryEmit(VoiceEvent.CryptResyncRequested)) {
            logger.fine("Dropping CryptResyncRequested (event buffer full)")
        }
    }

    fun refreshStatus() {
        _status.value = VoiceStatus(
            localCrypt = CryptCounts(
                good = crypt.getGood(),
                late = crypt.getLate(),
                lost = crypt.getLost(),
                resync = crypt.getResync(),
            ),
            bitrate = targetBitrate.get(),
            framesPerPacket = targetFramesPerPacket.get(),
        )
    }

    fun close() {
        disconnectUdp()
        crypt.destroy()
        scope.cancel()
    }

}
