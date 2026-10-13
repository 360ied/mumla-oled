/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.util

import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener

/**
 * Created by andrew on 23/04/14.
 */
open class HumlaNetworkListener : HumlaTCPMessageListener, HumlaUDPMessageListener {
    override fun messageAuthenticate(msg: Mumble.Authenticate) {
    }

    override fun messageBanList(msg: Mumble.BanList) {
    }

    override fun messageReject(msg: Mumble.Reject) {
    }

    override fun messageServerSync(msg: Mumble.ServerSync) {
    }

    override fun messageServerConfig(msg: Mumble.ServerConfig) {
    }

    override fun messagePermissionDenied(msg: Mumble.PermissionDenied) {
    }

    override fun messageUDPTunnel(msg: Mumble.UDPTunnel) {
    }

    override fun messageUserState(msg: Mumble.UserState) {
    }

    override fun messageUserRemove(msg: Mumble.UserRemove) {
    }

    override fun messageChannelState(msg: Mumble.ChannelState) {
    }

    override fun messageChannelRemove(msg: Mumble.ChannelRemove) {
    }

    override fun messageTextMessage(msg: Mumble.TextMessage) {
    }

    override fun messageACL(msg: Mumble.ACL) {
    }

    override fun messageQueryUsers(msg: Mumble.QueryUsers) {
    }

    override fun messagePing(msg: Mumble.Ping) {
    }

    override fun messageCryptSetup(msg: Mumble.CryptSetup) {
    }

    override fun messageContextAction(msg: Mumble.ContextAction) {
    }

    override fun messageContextActionModify(msg: Mumble.ContextActionModify) {
    }

    override fun messageRemoveContextAction(msg: Mumble.ContextActionModify) {
    }

    override fun messageVersion(msg: Mumble.Version) {
    }

    override fun messageUserList(msg: Mumble.UserList) {
    }

    override fun messagePermissionQuery(msg: Mumble.PermissionQuery) {
    }

    override fun messageCodecVersion(msg: Mumble.CodecVersion) {
    }

    override fun messageUserStats(msg: Mumble.UserStats) {
    }

    override fun messageRequestBlob(msg: Mumble.RequestBlob) {
    }

    override fun messageSuggestConfig(msg: Mumble.SuggestConfig) {
    }

    override fun messageVoiceTarget(msg: Mumble.VoiceTarget) {
    }

    override fun messagePluginDataTransmission(msg: Mumble.PluginDataTransmission) {
    }

    override fun messageUDPPing(data: ByteArray) {
    }

    override fun messageVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
    }

    override fun messageProtobufAudio(msg: MumbleUDP.Audio) {
    }

    override fun messageProtobufPing(msg: MumbleUDP.Ping) {
    }
}
