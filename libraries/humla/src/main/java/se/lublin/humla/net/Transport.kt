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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.ReceiveChannel
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.CryptCounts

/**
 * TCP control channel. See
 * docs/target-architecture/transport.md.
 *
 * Android-free: no Android imports. Owned by one Session; talks to the
 * voice side only through [VoiceLink], so transport tests use a fake.
 */
interface Transport {
    /** Parsed messages, server order. Capacity 1024; reader blocks when full. */
    val inbound: ReceiveChannel<Inbound>

    val stats: StateFlow<TransportStats>

    /** Latest full key set from CryptSetup, null until the first one. */
    val cryptKeys: StateFlow<CryptKeys?>

    /** Completes once on a fatal transport error. */
    val failure: Deferred<ClientError>

    /** Enqueue an outbound control message; never blocks. */
    fun send(message: MessageLite, type: HumlaTCPMessageType)

    /** Voice over TCP (UDPTunnel); never blocks. */
    fun sendTunnelled(packet: ByteArray)

    /** Close the socket and fail with Connection (idempotent). */
    fun close()

    /** Starts the keepalive ping loop; called once by Session on ServerSync. */
    fun startKeepalive()

    /** Resolved remote host/port for voice start (SRV resolved). */
    val remoteHost: String
    val remotePort: Int

    /**
     * UDP packet format for this session, decided once from the server's
     * first `Version` (transport.md § UDP packet format). The client passes
     * it to voice at start; [VoiceFormat.DEFAULT] until `Version` arrives.
     */
    val voiceFormat: VoiceFormat
}

/** Legacy vs protobuf UDP framing (voice.md packet codec). */
enum class UdpFormat { LEGACY, PROTOBUF }

/**
 * The session's voice format: the UDP framing plus the server's legacy
 * version, retained from the first `Version`. The version used to key the
 * 1.2.2 codec workaround; that workaround is gone, so no codec path reads it
 * (issue 35). Carried by `VoiceCommand.Start` as `format` (5a.3b facade).
 */
data class VoiceFormat(val udp: UdpFormat, val legacyServerVersion: Int) {
    companion object {
        /** No `Version` seen: legacy framing, unknown version (today's default). */
        @JvmField
        val DEFAULT = VoiceFormat(UdpFormat.LEGACY, 0)

        /** Today's rule (`Constants.isProtobufUdpSupported`): protobuf from 1.5.0. */
        @JvmStatic
        fun fromServerVersion(v2: Long, v1: Int): VoiceFormat = VoiceFormat(
            if (se.lublin.humla.Constants.isProtobufUdpSupported(v2, v1)) UdpFormat.PROTOBUF else UdpFormat.LEGACY,
            v1,
        )
    }
}

/** Inbound control-channel items, server order. */
sealed interface Inbound {
    data class Message(val type: HumlaTCPMessageType, val message: MessageLite) : Inbound
    data class Tunnelled(val packet: ByteArray) : Inbound {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Tunnelled) return false
            return packet.contentEquals(other.packet)
        }

        override fun hashCode(): Int = packet.contentHashCode()
        override fun toString(): String = "Tunnelled(<${packet.size} bytes>)"
    }
}

/**
 * Voice side of the transport boundary. Implemented by the VoiceEngine
 * (step 5a.3b native receive and transport).
 */
interface VoiceLink {
    fun setKeys(keys: CryptKeys)
    fun setDecryptIv(iv: ByteString)
    fun encryptIv(): ByteString
    fun submit(command: VoiceCommand)
    fun queueTunnelled(packet: ByteArray)
    /**
     * Another user left: reaches the render thread as a control item on
     * the tunnel ring, ordered after that user's queued tunnelled voice.
     * Called on the session message coroutine, the tunnel ring's producer.
     */
    fun removeUser(session: Int)
    val events: SharedFlow<VoiceEvent>
    val status: StateFlow<VoiceStatus>
}

/** Commands the transport pushes to voice; voice only follows. */
sealed interface VoiceCommand {
    data class SetVoiceRoute(val udp: Boolean) : VoiceCommand
    data class SendPing(val timestampMicros: Long) : VoiceCommand
    /**
     * Start network voice (5a.3b facade). Carries the session's UDP
     * address plus the transport's per-session decisions, fixed once:
     * the keys (when CryptSetup already arrived) and the UDP format with
     * the legacy server version from the first Version (issue 17).
     * No route: voice follows the transport's SetVoiceRoute, and Stop
     * resets to tunnel so no route carries over.
     */
    data class Start(
        val host: String,
        val port: Int,
        val keys: CryptKeys?,
        val format: VoiceFormat,
    ) : VoiceCommand
    data object Stop : VoiceCommand
}

/** Events voice pushes to the transport. */
sealed interface VoiceEvent {
    data class UdpPing(
        val timestampMicros: Long,
        val serverVersionV2: Long?,
        val maxBandwidth: Int?,
    ) : VoiceEvent

    data object CryptResyncRequested : VoiceEvent
    data object UdpUnavailable : VoiceEvent

    /** Capture or playback stream cannot open; the session stays connected. */
    data class PipelineError(val message: String) : VoiceEvent

    /** The server bandwidth cap lowered the effective encoder settings. */
    data class EncoderLimited(
        val maxBandwidth: Int,
        val bitrate: Int,
        val framesPerPacket: Int,
    ) : VoiceEvent
}

/**
 * Voice-side status the transport merges into SessionStats.
 * Shape matches voice.md (bitrate/framesPerPacket are the current encoder
 * settings); the interim link publishes its targets until the step-5a
 * VoicePolicy drives real values.
 */
data class VoiceStatus(
    val localCrypt: CryptCounts,
    val bitrate: Int = 40000,
    val framesPerPacket: Int = 2,
    val running: Boolean = false,
    val droppedOutbound: Long = 0,
    val droppedInbound: Long = 0,
    val droppedJitter: Long = 0,
    val lastError: String? = null,
) {
    companion object {
        fun initial(): VoiceStatus = VoiceStatus(localCrypt = CryptCounts.zero())
    }
}

/** Creates a Transport for one session attempt. */
interface TransportFactory {
    suspend fun create(
        parentScope: kotlinx.coroutines.CoroutineScope,
        host: String,
        port: Int,
        certificate: ByteArray?,
        certificatePassword: String?,
        trustStorePath: String?,
        trustStorePassword: String?,
        trustStoreFormat: String?,
        forceTcp: Boolean,
        voice: VoiceLink,
    ): Transport
}
