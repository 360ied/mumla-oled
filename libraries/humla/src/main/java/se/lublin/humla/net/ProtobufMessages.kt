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

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import se.lublin.humla.protobuf.Mumble

/**
 * Pure protobuf helpers extracted from HumlaConnection for the Transport.
 * Android-free and side-effect-free so JVM tests pin them directly.
 */
object ProtobufMessages {
    /**
     * Calculates the bandwidth required to send audio with the given parameters.
     * Overhead per packet: 20 (IP) + 8 (UDP) + 4 (crypt) + 1 (type) + 2 (session)
     * + 12 (positional, worst case) + framesPerPacket (frame headers); packets
     * per second are 800/framesPerPacket (10 ms frames). Ported as-is from
     * HumlaConnection, including the single conditional +12 (upstream adds a
     * second +12 for TCP mode; see the incidental note in HumlaService).
     */
    @JvmStatic
    fun calculateAudioBandwidth(bitrate: Int, framesPerPacket: Int): Int {
        require(framesPerPacket > 0) { "framesPerPacket must be > 0" }
        var overhead = 20 + 8 + 4 + 1 + 2 + 12 + framesPerPacket
        overhead *= (800 / framesPerPacket)
        return overhead + bitrate
    }

    /**
     * Parses raw TCP payload into the matching protobuf message.
     * Isolated so the reader parses once before fanning out.
     */
    @JvmStatic
    @Throws(InvalidProtocolBufferException::class)
    fun parse(data: ByteArray, messageType: HumlaTCPMessageType): MessageLite {
        when (messageType) {
            HumlaTCPMessageType.Authenticate -> return Mumble.Authenticate.parseFrom(data)
            HumlaTCPMessageType.BanList -> return Mumble.BanList.parseFrom(data)
            HumlaTCPMessageType.Reject -> return Mumble.Reject.parseFrom(data)
            HumlaTCPMessageType.ServerSync -> return Mumble.ServerSync.parseFrom(data)
            HumlaTCPMessageType.ServerConfig -> return Mumble.ServerConfig.parseFrom(data)
            HumlaTCPMessageType.PermissionDenied -> return Mumble.PermissionDenied.parseFrom(data)
            // The reader intercepts UDPTunnel as raw voice before parse();
            // this branch only serves direct callers. Wire tunnel payloads are
            // raw bytes (Mumble.proto: "Not used. Not even for tunneling").
            HumlaTCPMessageType.UDPTunnel -> return Mumble.UDPTunnel.parseFrom(data)
            HumlaTCPMessageType.UserState -> return Mumble.UserState.parseFrom(data)
            HumlaTCPMessageType.UserRemove -> return Mumble.UserRemove.parseFrom(data)
            HumlaTCPMessageType.ChannelState -> return Mumble.ChannelState.parseFrom(data)
            HumlaTCPMessageType.ChannelRemove -> return Mumble.ChannelRemove.parseFrom(data)
            HumlaTCPMessageType.TextMessage -> return Mumble.TextMessage.parseFrom(data)
            HumlaTCPMessageType.ACL -> return Mumble.ACL.parseFrom(data)
            HumlaTCPMessageType.QueryUsers -> return Mumble.QueryUsers.parseFrom(data)
            HumlaTCPMessageType.Ping -> return Mumble.Ping.parseFrom(data)
            HumlaTCPMessageType.CryptSetup -> return Mumble.CryptSetup.parseFrom(data)
            HumlaTCPMessageType.ContextAction -> return Mumble.ContextAction.parseFrom(data)
            HumlaTCPMessageType.ContextActionModify -> return Mumble.ContextActionModify.parseFrom(data)
            HumlaTCPMessageType.Version -> return Mumble.Version.parseFrom(data)
            HumlaTCPMessageType.UserList -> return Mumble.UserList.parseFrom(data)
            HumlaTCPMessageType.PermissionQuery -> return Mumble.PermissionQuery.parseFrom(data)
            HumlaTCPMessageType.CodecVersion -> return Mumble.CodecVersion.parseFrom(data)
            HumlaTCPMessageType.UserStats -> return Mumble.UserStats.parseFrom(data)
            HumlaTCPMessageType.RequestBlob -> return Mumble.RequestBlob.parseFrom(data)
            HumlaTCPMessageType.SuggestConfig -> return Mumble.SuggestConfig.parseFrom(data)
            HumlaTCPMessageType.VoiceTarget -> return Mumble.VoiceTarget.parseFrom(data)
            HumlaTCPMessageType.PluginDataTransmission -> return Mumble.PluginDataTransmission.parseFrom(data)
            else -> throw InvalidProtocolBufferException("Unknown TCP data passed for $messageType.")
        }
    }
}
