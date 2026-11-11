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
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * See docs/tech-debt-whales/target-architecture/migration.md step 3.
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
    private val crypt = CryptState()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(VoiceStatus.initial())
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private val udpHandlers = CopyOnWriteArrayList<HumlaUDPMessageListener>()

    private val usingUdp = AtomicBoolean(true)
    private val forceTcp = AtomicBoolean(false)

    private val serverVersion = AtomicInteger(0)
    private val serverVersionV2 = java.util.concurrent.atomic.AtomicLong(0L)

    private var udp: HumlaUDP? = null
    private val udpExecutor = java.util.concurrent.Executor { r -> r.run() }

    private var targetFramesPerPacket: Int = Constants.DEFAULT_FRAMES_PER_PACKET

    fun setForceTcp(force: Boolean) {        forceTcp.set(force)
        if (force) {
            usingUdp.set(false)
        }
    }

    fun setServerVersion(v2: Long, v1: Int) {
        serverVersionV2.set(v2)
        serverVersion.set(v1)
    }

    fun setTargetFramesPerPacket(fpp: Int) {
        targetFramesPerPacket = Constants.sanitizeFramesPerPacket(fpp)
        udp?.setTargetFramesPerPacket(targetFramesPerPacket)
    }

    fun getTargetFramesPerPacket(): Int = targetFramesPerPacket

    override fun startVoice(host: String, port: Int) {
        connectUdp(host, port)
    }

    override fun stopVoice() {
        disconnectUdp()
    }

    fun connectUdp(host: String, port: Int) {
        if (forceTcp.get() || !usingUdp.get()) return
        val existing = udp
        if (existing != null) return
        val created = HumlaUDP(crypt, this, udpExecutor, targetFramesPerPacket)
        udp = created
        created.connect(host, port)
        refreshStatus()
    }

    fun disconnectUdp() {
        val existing = udp
        udp = null
        existing?.disconnect()
    }

    fun addUdpHandler(handler: HumlaUDPMessageListener) {
        udpHandlers.addIfAbsent(handler)
    }

    fun removeUdpHandler(handler: HumlaUDPMessageListener) {
        udpHandlers.remove(handler)
    }

    /**
     * Sends a voice packet, tunneling over TCP or via UDP per route policy.
     * Mirrors HumlaConnection.sendUDPMessage, including the 1.2.2 workaround.
     */
    fun sendVoice(data: ByteArray, length: Int) {
        if (length < 0 || length > data.size) {
            throw IllegalArgumentException(
                "Requested length $length out of bounds for available data length ${data.size}!",
            )
        }
        if (serverVersion.get() == 0x10202) applyLegacyCodecWorkaround(data)
        if (!forceTcp.get() && usingUdp.get()) {
            val current = udp
            if (current != null) {
                current.sendMessage(data, length)
                refreshStatus()
                return
            }
        }
        // Tunnel: hand the framed packet to the transport's UDPTunnel sender.
        val copy = data.copyOf(length)
        tunnelSink(copy)
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
        val current = udp ?: return
        if (isProtobufUdpSupported()) {
            val ping = MumbleUDP.Ping.newBuilder().setTimestamp(timestampMicros).build().toByteArray()
            val packet = ByteArray(1 + ping.size)
            packet[0] = 0x01
            System.arraycopy(ping, 0, packet, 1, ping.size)
            current.sendMessage(packet, packet.size)
        } else {
            val pingBuffer = ByteArray(10)
            pingBuffer[0] = ((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte()
            val pb = PacketBuffer(pingBuffer, 10)
            pb.skip(1)
            pb.writeLong(timestampMicros)
            current.sendMessage(pingBuffer, pb.size())
        }
    }

    fun isProtobufUdpSupported(): Boolean {
        return Constants.isProtobufUdpSupported(serverVersionV2.get(), serverVersion.get())
    }

    // HumlaUDP.UDPConnectionListener: runs on the UDP receiver coroutine for
    // data, and on the callback executor (direct) for resync/error.
    override fun onUDPDataReceived(data: ByteArray) {
        if (data.isEmpty()) return
        if (isProtobufUdpSupported()) {
            val msgType = data[0].toInt() and 0xFF
            if (msgType == 0) {
                try {
                    val audioMsg = MumbleUDP.Audio.parseFrom(ByteString.copyFrom(data, 1, data.size - 1))
                    for (handler in udpHandlers) {
                        handler.messageProtobufAudio(audioMsg)
                    }
                } catch (e: InvalidProtocolBufferException) {
                    // Drop malformed voice, as before.
                }
                return
            } else if (msgType == 1) {
                try {
                    val pingMsg = MumbleUDP.Ping.parseFrom(ByteString.copyFrom(data, 1, data.size - 1))
                    val v2 = if (pingMsg.serverVersionV2 != 0L) pingMsg.serverVersionV2 else null
                    val bw = if (pingMsg.maxBandwidthPerUser != 0) pingMsg.maxBandwidthPerUser else null
                    _events.tryEmit(VoiceEvent.UdpPing(pingMsg.timestamp, v2, bw))
                    for (handler in udpHandlers) {
                        handler.messageProtobufPing(pingMsg)
                    }
                    if (v2 != null) serverVersionV2.set(v2)
                } catch (e: InvalidProtocolBufferException) {
                    // Drop malformed ping.
                }
                return
            }
        }
        if (serverVersion.get() == 0x10202) applyLegacyCodecWorkaround(data)
        val dataType = data[0].toInt() shr 5 and 0x7
        if (dataType < 0 || dataType > HumlaUDPMessageType.values().size - 1) return
        val udpDataType = HumlaUDPMessageType.values()[dataType]
        when (udpDataType) {
            HumlaUDPMessageType.UDPPing -> {
                // Legacy ping: bytes 1..9 are the timestamp.
                try {
                    val pb = PacketBuffer(data, data.size)
                    pb.skip(1)
                    val timestamp = pb.readLong()
                    _events.tryEmit(VoiceEvent.UdpPing(timestamp, null, null))
                    for (handler in udpHandlers) {
                        handler.messageUDPPing(data)
                    }
                } catch (e: Exception) {
                    if (data.size >= 9) {
                        val timedata = ByteArray(8)
                        System.arraycopy(data, 1, timedata, 0, 8)
                        val buffer = ByteBuffer.allocate(8)
                        buffer.put(timedata)
                        buffer.flip()
                        val timestamp = buffer.long
                        _events.tryEmit(VoiceEvent.UdpPing(timestamp, null, null))
                        for (handler in udpHandlers) {
                            handler.messageUDPPing(data)
                        }
                    }
                }
            }
            HumlaUDPMessageType.UDPVoiceOpus -> {
                for (handler in udpHandlers) {
                    handler.messageVoiceData(data, udpDataType)
                }
            }
            HumlaUDPMessageType.UDPVoiceCELTAlpha,
            HumlaUDPMessageType.UDPVoiceSpeex,
            HumlaUDPMessageType.UDPVoiceCELTBeta -> Unit
        }
        refreshStatus()
    }

    override fun onUDPConnectionError(e: Exception) {
        _events.tryEmit(VoiceEvent.UdpUnavailable)
    }

    override fun resyncCryptState() {
        _events.tryEmit(VoiceEvent.CryptResyncRequested)
    }

    fun refreshStatus() {
        _status.value = VoiceStatus(
            localCrypt = CryptCounts(
                good = crypt.getGood(),
                late = crypt.getLate(),
                lost = crypt.getLost(),
                resync = crypt.getResync(),
            ),
        )
    }

    fun close() {
        disconnectUdp()
        crypt.destroy()
        scope.cancel()
    }

    private fun applyLegacyCodecWorkaround(data: ByteArray) {
        if (data.isEmpty()) return
        val ordinal = data[0].toInt() shr 5 and 0x7
        if (ordinal < 0 || ordinal >= HumlaUDPMessageType.values().size) return
        var dataType = HumlaUDPMessageType.values()[ordinal]
        if (dataType == HumlaUDPMessageType.UDPVoiceCELTBeta) {
            dataType = HumlaUDPMessageType.UDPVoiceCELTAlpha
        } else if (dataType == HumlaUDPMessageType.UDPVoiceCELTAlpha) {
            dataType = HumlaUDPMessageType.UDPVoiceCELTBeta
        }
        data[0] = ((dataType.ordinal shl 5) and 0xFF).toByte()
    }
}
