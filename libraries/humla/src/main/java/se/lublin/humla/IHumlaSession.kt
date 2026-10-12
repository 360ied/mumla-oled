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

package se.lublin.humla

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.util.VoiceTargetMode

/**
 * An interface representing a live connection to the server.
 * Created by andrew on 28/02/17.
 *
 * Syntax-only Kotlin conversion: the binder surface stays frozen throughout
 * the migration; refactoring happens strictly behind it.
 */
interface IHumlaSession {
    /**
     * @return the latency in milliseconds for the TCP connection.
     */
    fun getTCPLatency(): Long

    /**
     * @return the latency in milliseconds for the UDP connection.
     */
    fun getUDPLatency(): Long

    /**
     * @return the maximum bandwidth in bps for audio allowed by the server, or -1 if not set.
     */
    fun getMaxBandwidth(): Int

    /**
     * @return the current bandwidth in bps for audio sent to the server, or a negative integer
     *         if unknown (prior to connection or after disconnection).
     */
    fun getCurrentBandwidth(): Int

    /**
     * Returns the protocol version returned by the server in the format 0xAABBCC, where AA
     * indicates the major version, BB indicates the minor version, and CC indicates the patch
     * version. This is the same formatting used by the Mumble protocol in big-endian format.
     * @return the current bandwidth in bps for audio sent to the server, or a negative integer
     *         if unknown (prior to connection or after disconnection).
     */
    fun getServerVersion(): Int

    /**
     * @return the 64-bit protocol version (Mumble 1.5+).
     */
    fun getServerVersionV2(): Long

    /**
     * @return a user-readable string with the server's Mumble release info.
     */
    fun getServerRelease(): String?

    /**
     * @return a user-readable string with the server's OS name.
     */
    fun getServerOSName(): String?

    /**
     * @return a user-readable string with the server's OS version.
     */
    fun getServerOSVersion(): String?

    /**
     * Returns the current user's session. Set during server synchronization.
     * @return an integer identifying the current user's connection.
     */
    fun getSessionId(): Int

    /**
     * Returns the current user. Set during server synchronization.
     * @return the {@link IUser} representing the current user.
     */
    fun getSessionUser(): IUser?

    /**
     * Returns the user's current channel.
     * @return the {@link IChannel} representing the user's current channel.
     */
    fun getSessionChannel(): IChannel?

    /**
     * Retrieves the user with the given session ID.
     * @param session An integer ID identifying a user's session. See {@link IUser#getSession()}.
     * @return A user with the given session, or null if not found.
     */
    fun getUser(session: Int): IUser?

    /**
     * Retrieves the channel with the given ID.
     * @param id An integer ID identifying a channel. See {@link IChannel#getId()}.
     * @return A channel with the given session, or null if not found.
     */
    fun getChannel(id: Int): IChannel?

    /**
     * @return the root channel of the server.
     */
    fun getRootChannel(): IChannel?

    fun getPermissions(): Int

    fun getTransmitMode(): Int

    fun getCodec(): HumlaUDPMessageType?

    fun isTalking(): Boolean

    fun setTalkingState(talking: Boolean)

    fun joinChannel(channel: Int)

    fun moveUserToChannel(session: Int, channel: Int)

    fun sendAccessTokens(tokens: List<String>)

    fun requestPermissions(channel: Int)

    fun requestComment(session: Int)

    fun requestAvatar(session: Int)

    fun requestChannelDescription(channel: Int)

    fun registerUser(session: Int)

    fun kickBanUser(session: Int, reason: String?, ban: Boolean)

    fun sendUserTextMessage(session: Int, message: String?): Message?

    fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message?

    fun setUserComment(session: Int, comment: String?)

    fun setPrioritySpeaker(session: Int, priority: Boolean)

    fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean)

    fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean)

    /**
     * Links the provided two channels together.
     */
    fun linkChannels(channelA: IChannel?, channelB: IChannel?)

    /**
     * Unlinks the two provided channels.
     */
    fun unlinkChannels(channelA: IChannel?, channelB: IChannel?)

    /**
     * Unlinks all channels from the provided channel.
     * @param channel The channel to be unlinked.
     */
    fun unlinkAllChannels(channel: IChannel?)

    /**
     * Registers a whisper target to be used as a voice target on the server.
     * Note that Mumble only supports a maximum of 30 active voice targets at once.
     * @param target The target to register.
     * @return A voice target ID in the range [1, 30], or a negative value if all slots are full.
     */
    fun registerWhisperTarget(target: WhisperTarget?): Byte

    /**
     * Unregisters a whisper target from the server.
     * Note that Mumble only supports a maximum of 30 active voice targets at once.
     * @param targetId The target ID to unregister.
     */
    fun unregisterWhisperTarget(targetId: Byte)

    /**
     * Sets the active voice target to the provided ID.<br></br>
     * 0: Normal speech<br></br>
     * 1-30: Whisper targets<br></br>
     * 31: Server loopback
     * @param targetId A voice target ID in the range [0, 31].
     */
    fun setVoiceTargetId(targetId: Byte)

    /**
     * Gets the current voice target ID in use, in the range [0, 31].
     * @return The active voice target ID.
     */
    fun getVoiceTargetId(): Byte

    /**
     * Gets the current voice target mode.
     * @return The active voice target mode.
     */
    fun getVoiceTargetMode(): VoiceTargetMode?

    /**
     * Returns the current whisper target.
     * @return the set whisper target, or null if the user is not whispering.
     */
    fun getWhisperTarget(): WhisperTarget?

    /**
     * Gets the current server settings.
     * @return Settings of the current server.
     */
    fun getServerSettings(): ServerSettings?

    /**
     * Start or stop listening to a channel (Mumble 1.5+).
     * @param channelId The channel ID to listen to.
     * @param listening True to start listening, false to stop.
     */
    fun setListeningChannel(channelId: Int, listening: Boolean)

    /**
     * Adjust listening volume for a channel (Mumble 1.5+).
     * @param channelId The channel ID.
     * @param volumeAdjustment Volume multiplier factor.
     */
    fun setListeningVolume(channelId: Int, volumeAdjustment: Float)

    /**
     * Checks if currently listening to the specified channel.
     * @param channelId The channel ID.
     * @return True if listening.
     */
    fun isListeningToChannel(channelId: Int): Boolean
}
