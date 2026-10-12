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
    open override fun messageAuthenticate(msg: Mumble.Authenticate) {
    }

    open override fun messageBanList(msg: Mumble.BanList) {
    }

    open override fun messageReject(msg: Mumble.Reject) {
    }

    open override fun messageServerSync(msg: Mumble.ServerSync) {
    }

    open override fun messageServerConfig(msg: Mumble.ServerConfig) {
    }

    open override fun messagePermissionDenied(msg: Mumble.PermissionDenied) {
    }

    open override fun messageUDPTunnel(msg: Mumble.UDPTunnel) {
    }

    open override fun messageUserState(msg: Mumble.UserState) {
    }

    open override fun messageUserRemove(msg: Mumble.UserRemove) {
    }

    open override fun messageChannelState(msg: Mumble.ChannelState) {
    }

    open override fun messageChannelRemove(msg: Mumble.ChannelRemove) {
    }

    open override fun messageTextMessage(msg: Mumble.TextMessage) {
    }

    open override fun messageACL(msg: Mumble.ACL) {
    }

    open override fun messageQueryUsers(msg: Mumble.QueryUsers) {
    }

    open override fun messagePing(msg: Mumble.Ping) {
    }

    open override fun messageCryptSetup(msg: Mumble.CryptSetup) {
    }

    open override fun messageContextAction(msg: Mumble.ContextAction) {
    }

    open override fun messageContextActionModify(msg: Mumble.ContextActionModify) {
    }

    open override fun messageRemoveContextAction(msg: Mumble.ContextActionModify) {
    }

    open override fun messageVersion(msg: Mumble.Version) {
    }

    open override fun messageUserList(msg: Mumble.UserList) {
    }

    open override fun messagePermissionQuery(msg: Mumble.PermissionQuery) {
    }

    open override fun messageCodecVersion(msg: Mumble.CodecVersion) {
    }

    open override fun messageUserStats(msg: Mumble.UserStats) {
    }

    open override fun messageRequestBlob(msg: Mumble.RequestBlob) {
    }

    open override fun messageSuggestConfig(msg: Mumble.SuggestConfig) {
    }

    open override fun messageVoiceTarget(msg: Mumble.VoiceTarget) {
    }

    open override fun messagePluginDataTransmission(msg: Mumble.PluginDataTransmission) {
    }

    open override fun messageUDPPing(data: ByteArray) {
    }

    open override fun messageVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
    }

    open override fun messageProtobufAudio(msg: MumbleUDP.Audio) {
    }

    open override fun messageProtobufPing(msg: MumbleUDP.Ping) {
    }
}
