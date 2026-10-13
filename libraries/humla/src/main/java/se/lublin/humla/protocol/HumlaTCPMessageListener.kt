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

package se.lublin.humla.protocol

import se.lublin.humla.protobuf.Mumble

interface HumlaTCPMessageListener {
    fun messageAuthenticate(msg: Mumble.Authenticate)
    fun messageBanList(msg: Mumble.BanList)
    fun messageReject(msg: Mumble.Reject)
    fun messageServerSync(msg: Mumble.ServerSync)
    fun messageServerConfig(msg: Mumble.ServerConfig)
    fun messagePermissionDenied(msg: Mumble.PermissionDenied)
    fun messageUDPTunnel(msg: Mumble.UDPTunnel)
    fun messageUserState(msg: Mumble.UserState)
    fun messageUserRemove(msg: Mumble.UserRemove)
    fun messageChannelState(msg: Mumble.ChannelState)
    fun messageChannelRemove(msg: Mumble.ChannelRemove)
    fun messageTextMessage(msg: Mumble.TextMessage)
    fun messageACL(msg: Mumble.ACL)
    fun messageQueryUsers(msg: Mumble.QueryUsers)
    fun messagePing(msg: Mumble.Ping)
    fun messageCryptSetup(msg: Mumble.CryptSetup)
    fun messageContextAction(msg: Mumble.ContextAction)
    fun messageContextActionModify(msg: Mumble.ContextActionModify)
    fun messageRemoveContextAction(msg: Mumble.ContextActionModify)
    fun messageVersion(msg: Mumble.Version)
    fun messageUserList(msg: Mumble.UserList)
    fun messagePermissionQuery(msg: Mumble.PermissionQuery)
    fun messageCodecVersion(msg: Mumble.CodecVersion)
    fun messageUserStats(msg: Mumble.UserStats)
    fun messageRequestBlob(msg: Mumble.RequestBlob)
    fun messageSuggestConfig(msg: Mumble.SuggestConfig)
    fun messageVoiceTarget(msg: Mumble.VoiceTarget)
    fun messagePluginDataTransmission(msg: Mumble.PluginDataTransmission)

    /**
     * Reads incoming protobuf TCP messages and performs the necessary action(s).
     * Designed to be subclassed at any level of the library, the default implementations do nothing.
     * Created by andrew on 24/06/13.
     */
    open class Stub : HumlaTCPMessageListener {
        open override fun messageAuthenticate(msg: Mumble.Authenticate) {}
        open override fun messageBanList(msg: Mumble.BanList) {}
        open override fun messageReject(msg: Mumble.Reject) {}
        open override fun messageServerSync(msg: Mumble.ServerSync) {}
        open override fun messageServerConfig(msg: Mumble.ServerConfig) {}
        open override fun messagePermissionDenied(msg: Mumble.PermissionDenied) {}
        open override fun messageUDPTunnel(msg: Mumble.UDPTunnel) {}
        open override fun messageUserState(msg: Mumble.UserState) {}
        open override fun messageUserRemove(msg: Mumble.UserRemove) {}
        open override fun messageChannelState(msg: Mumble.ChannelState) {}
        open override fun messageChannelRemove(msg: Mumble.ChannelRemove) {}
        open override fun messageTextMessage(msg: Mumble.TextMessage) {}
        open override fun messageACL(msg: Mumble.ACL) {}
        open override fun messageQueryUsers(msg: Mumble.QueryUsers) {}
        open override fun messagePing(msg: Mumble.Ping) {}
        open override fun messageCryptSetup(msg: Mumble.CryptSetup) {}
        open override fun messageContextAction(msg: Mumble.ContextAction) {}
        open override fun messageContextActionModify(msg: Mumble.ContextActionModify) {}
        open override fun messageRemoveContextAction(msg: Mumble.ContextActionModify) {}
        open override fun messageVersion(msg: Mumble.Version) {}
        open override fun messageUserList(msg: Mumble.UserList) {}
        open override fun messagePermissionQuery(msg: Mumble.PermissionQuery) {}
        open override fun messageCodecVersion(msg: Mumble.CodecVersion) {}
        open override fun messageUserStats(msg: Mumble.UserStats) {}
        open override fun messageRequestBlob(msg: Mumble.RequestBlob) {}
        open override fun messageSuggestConfig(msg: Mumble.SuggestConfig) {}
        open override fun messageVoiceTarget(msg: Mumble.VoiceTarget) {}
        open override fun messagePluginDataTransmission(msg: Mumble.PluginDataTransmission) {}
    }
}
