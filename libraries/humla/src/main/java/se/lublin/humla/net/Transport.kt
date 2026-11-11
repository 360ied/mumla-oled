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
 * docs/tech-debt-whales/target-architecture/transport.md.
 *
 * Android-free: no android.* imports. Owned by one Session; talks to the
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
 * (step 5a); until then by [LegacyVoiceLink] over HumlaUDP/CryptState.
 */
interface VoiceLink {
    fun setKeys(keys: CryptKeys)
    fun setDecryptIv(iv: ByteString)
    fun encryptIv(): ByteString
    fun submit(command: VoiceCommand)
    fun queueTunnelled(packet: ByteArray)
    val events: SharedFlow<VoiceEvent>
    val status: StateFlow<VoiceStatus>
}

/** Commands the transport pushes to voice; voice only follows. */
sealed interface VoiceCommand {
    data class SetVoiceRoute(val udp: Boolean) : VoiceCommand
    data class SendPing(val timestampMicros: Long) : VoiceCommand
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
}

/** Voice-side status the transport merges into SessionStats. */
data class VoiceStatus(
    val localCrypt: CryptCounts,
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
