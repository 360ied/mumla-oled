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

import android.content.Context
import android.util.Log
import se.lublin.humla.R
import se.lublin.humla.model.Channel
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.MessageFormatter
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles network messages related to the user-channel tree model.
 * This includes channels, users, messages, and permissions.
 * Created by andrew on 18/07/13.
 *
 * Slice 4 threading contract: all message* writers are confined to the
 * single-threaded model dispatcher, which is the main thread
 * (Dispatchers.Main.immediate equivalent — HumlaTCP fans TCP frames out via
 * its main-Looper handler, exactly today's main-confinement). The
 * ConcurrentHashMaps stay: the UDP-thread getUser path reads lock-free and
 * depends on them. @Synchronized on writers is retained as defense-in-depth
 * beside confinement (same monitor as the optimistic-mute path in
 * HumlaService). HumlaCallbacks fan-out stays as-is (no SharedFlow).
 */
class ModelHandler(
    private val mContext: Context,
    private val mObserver: IHumlaObserver,
    private val mLogger: HumlaLogger,
    private val mLocalMuteHistory: List<Int>?,
    private val mLocalIgnoreHistory: List<Int>?
) : HumlaTCPMessageListener.Stub() {
    companion object {
        private val TAG = ModelHandler::class.java.name
    }

    private val mChannels: ConcurrentHashMap<Int, Channel> = ConcurrentHashMap()
    private val mUsers: ConcurrentHashMap<Int, User> = ConcurrentHashMap()

    @Volatile
    private var mServerSettings: ServerSettings? = null

    @Volatile
    private var mPermissions: Int = 0
    private var mSession: Int = 0

    fun getChannel(id: Int): Channel? {
        return mChannels[id]
    }

    fun getUser(session: Int): User? {
        return mUsers[session]
    }

    fun getServerSettings(): ServerSettings? {
        return mServerSettings
    }

    /**
     * Creates a stub channel with the given ID.
     * Useful for keeping user references when we get a UserState message before a ChannelState.
     * @param id The channel ID.
     * @return The newly created stub channel.
     */
    private fun createStubChannel(id: Int): Channel {
        val channel = Channel(id, false)
        mChannels[id] = channel
        return channel
    }

    fun getChannels(): Map<Int, Channel> {
        return Collections.unmodifiableMap(mChannels)
    }

    fun getUsers(): Map<Int, User> {
        return Collections.unmodifiableMap(mUsers)
    }

    /**
     * Returns the current user's permissions.
     * @return The server-wide permissions.
     */
    fun getPermissions(): Int {
        return mPermissions
    }

    @Synchronized
    fun clear() {
        mChannels.clear()
        mUsers.clear()
        mSession = 0
        mPermissions = 0
    }

    @Synchronized
    override fun messageChannelState(msg: Mumble.ChannelState) {
        if (!msg.hasChannelId())
            return

        var channel = mChannels[msg.channelId]
        var parent: Channel? = null
        if (msg.hasParent()) {
            parent = mChannels[msg.parent]
            if (parent == null) {
                parent = createStubChannel(msg.parent)
            }
        }

        val newChannel = channel == null

        if (channel == null) {
            channel = Channel(msg.channelId, msg.temporary)
            mChannels[msg.channelId] = channel
        }
        val ch = channel

        if (msg.hasName())
            ch.name = msg.name

        if (msg.hasPosition())
            ch.position = msg.position

        if (msg.hasParent()) {
            val oldParent = ch.parent
            if (oldParent != parent) {
                if (oldParent != null) {
                    oldParent.removeSubchannel(ch)
                }
                ch.parent = parent
                if (parent != null) {
                    parent.addSubchannel(ch)
                }
            }
        }

        if (msg.hasDescriptionHash()) {
            ch.descriptionHash = msg.descriptionHash.toByteArray()
            ch.description = null
        }

        if (msg.hasDescription()) {
            ch.description = msg.description
            ch.descriptionHash = null
        }

        if (msg.linksCount > 0) {
            for (linked in ArrayList(ch.links)) {
                linked.removeLink(ch)
            }
            ch.clearLinks()
            for (link in msg.linksList) {
                var linked = mChannels[link]
                if (linked == null) {
                    linked = createStubChannel(link)
                }
                ch.addLink(linked)
                linked.addLink(ch)
            }
        }

        if (msg.linksRemoveCount > 0) {
            for (link in msg.linksRemoveList) {
                val linked = mChannels[link]
                if (linked != null) {
                    ch.removeLink(linked)
                    linked.removeLink(ch)
                }
            }
        }

        if (msg.linksAddCount > 0) {
            for (link in msg.linksAddList) {
                var linked = mChannels[link]
                if (linked == null) {
                    linked = createStubChannel(link)
                }
                ch.addLink(linked)
                linked.addLink(ch)
            }
        }

        if (msg.hasMaxUsers())
            ch.maxUsers = msg.maxUsers

        if (msg.hasIsEnterRestricted())
            ch.isEnterRestricted = msg.isEnterRestricted

        if (msg.hasCanEnter())
            ch.setCanEnter(msg.canEnter)

        if (newChannel)
            mObserver.onChannelAdded(ch)
        else
            mObserver.onChannelStateUpdated(ch)
    }

    @Synchronized
    override fun messageChannelRemove(msg: Mumble.ChannelRemove) {
        val channel = mChannels[msg.channelId]
        if (channel != null && channel.id != 0) {
            for (u in mUsers.values) {
                u.removeListeningChannel(channel.id)
            }
            for (linked in ArrayList(channel.links)) {
                linked.removeLink(channel)
            }
            channel.clearLinks()
            mChannels.remove(channel.id)
            val parent = channel.parent
            if (parent != null) {
                parent.removeSubchannel(channel)
            }
            mObserver.onChannelRemoved(channel)
        }
    }

    @Synchronized
    override fun messagePermissionQuery(msg: Mumble.PermissionQuery) {
        if (msg.flush)
            for (channel in mChannels.values)
                channel.permissions = 0

        val channel = mChannels[msg.channelId]
        if (channel != null) {
            channel.permissions = msg.permissions
            if (msg.channelId == 0) // If we're provided permissions for the root channel, we'll apply these as our server permissions.
                mPermissions = channel.permissions
            mObserver.onChannelPermissionsUpdated(channel)
        }
    }

    @Synchronized
    override fun messageUserState(msg: Mumble.UserState) {
        var user = mUsers[msg.session]
        var newUser = false

        val self = mUsers[mSession]

        if (user == null) {
            if (msg.hasName()) {
                user = User(msg.session, msg.name)
                mUsers[msg.session] = user
                newUser = true
                // Add user to root channel by default. This works because for some reason, we don't get a channel ID when the user joins into root.
                var root = mChannels[0]
                if (root == null) root = createStubChannel(0)
                user.channel = root
            } else
                return
        }
        val usr = user

        var actor: User? = null
        if (msg.hasActor())
            actor = getUser(msg.actor)

        if (msg.hasUserId()) {
            usr.userId = msg.userId
            // Restore local mute and ignore from history
            if (mLocalMuteHistory != null && mLocalMuteHistory.contains(usr.userId)) {
                usr.isLocalMuted = true
            }
            if (mLocalIgnoreHistory != null && mLocalIgnoreHistory.contains(usr.userId)) {
                usr.isLocalIgnored = true
            }
        }

        if (msg.hasHash()) {
            usr.hash = msg.hash

            /*
             * TODO:
             * - Check if user is local muted in database, if so re-mute them here
             * - Check if user is friend, if so indicate
             */
        }

        if (newUser)
            mLogger.logInfo(mContext.getString(R.string.chat_notify_connected, MessageFormatter.highlightString(usr.name)))

        if (msg.hasSelfDeaf() || msg.hasSelfMute()) {
            // Desktop parity (Messages.cpp logs OtherSelfMute only): the self
            // echo is pure confirm and never logs; the toggle site owns the
            // self line (see HumlaService). Logging here would re-log stale
            // echoes on rapid toggles. Other users' transitions still log on
            // change: duplicate packets are anti-spam-suppressed, an
            // intentional divergence from desktop's unconditional re-log.
            val otherChanged = (msg.hasSelfMute() && usr.isSelfMuted != msg.selfMute) ||
                (msg.hasSelfDeaf() && usr.isSelfDeafened != msg.selfDeaf)
            if (msg.hasSelfMute())
                usr.isSelfMuted = msg.selfMute
            if (msg.hasSelfDeaf())
                usr.isSelfDeafened = msg.selfDeaf

            if (otherChanged && self != null) {
                val userChan = usr.channel
                if (usr.session != self.session && userChan != null && userChan == self.channel) {
                    if (usr.isSelfMuted && usr.isSelfDeafened)
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_now_muted_deafened, MessageFormatter.highlightString(usr.name)))
                    else if (usr.isSelfMuted)
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_now_muted, MessageFormatter.highlightString(usr.name)))
                    else
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_now_unmuted, MessageFormatter.highlightString(usr.name)))
                }
            }
        }

        if (msg.hasRecording()) {
            usr.isRecording = msg.recording

            if (self != null) {
                if (usr.session == self.session) {
                    if (usr.isRecording)
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_self_recording_started))
                    else
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_self_recording_stopped))
                } else {
                    val selfChannel = self.channel
                    // If in a linked channel OR the same channel as the current user, notify the user about recording
                    if (selfChannel != null && usr.channel != null && selfChannel.getAllLinks().contains(usr.channel)) {
                        if (usr.isRecording)
                            mLogger.logInfo(mContext.getString(R.string.chat_notify_user_recording_started, MessageFormatter.highlightString(usr.name)))
                        else
                            mLogger.logInfo(mContext.getString(R.string.chat_notify_user_recording_stopped, MessageFormatter.highlightString(usr.name)))
                    }
                }
            }
        }

        if (msg.hasDeaf() || msg.hasMute() || msg.hasSuppress() || msg.hasPrioritySpeaker()) {
            if (msg.hasDeaf())
                usr.isDeafened = msg.deaf
            if (msg.hasMute())
                usr.isMuted = msg.mute
            if (msg.hasSuppress())
                usr.isSuppressed = msg.suppress
            if (msg.hasPrioritySpeaker())
                usr.isPrioritySpeaker = msg.prioritySpeaker

//            if(self != null && ((user.getChannelId() == self.getChannelId()) || (actor.getSessionId() == self.getSessionId()))) {
//                if(user.getSessionId() == self.getSessionId()) {
//                    if(msg.hasMute() && msg.hasDeaf() && user.isMuted() && user.isDeafened()) {
//                        mLogger.logInfo();
//                    }
//                }
//            }

            /*
             * TODO: logging
             * Base this off of Messages.cpp:353
             */
        }

        if (msg.hasChannelId()) {
            val channel = mChannels[msg.channelId]
            if (channel == null) {
                Log.e(TAG, "Invalid channel for user!")
                return // TODO handle better
            }
            val old = usr.channel

            usr.channel = channel

            if (!newUser) {
                mObserver.onUserJoinedChannel(usr, channel, old)
            }

            val sessionChannel = self?.channel

            // Notify the user of other users' current channel changes
            if (self != null && sessionChannel != null && old != null && self != usr) {
                // TODO add logic for other user moving self
                val actorString = if (actor != null) MessageFormatter.highlightString(actor.name) else mContext.getString(R.string.the_server)
                if (sessionChannel != channel && sessionChannel == old) {
                    // User moved out of self's channel
                    if (actor != null && actor.session == usr.session) {
                        // By themselves
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_left_channel, MessageFormatter.highlightString(usr.name), MessageFormatter.highlightString(channel.name)))
                    } else {
                        // By external actor
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_left_channel_by, MessageFormatter.highlightString(usr.name), MessageFormatter.highlightString(channel.name), actorString))
                    }
                } else if (sessionChannel == channel) {
                    // User moved into self's channel
                    if (actor != null && actor.session == usr.session) {
                        // By themselves
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_joined_channel, MessageFormatter.highlightString(usr.name)))
                    } else {
                        // By external actor
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_joined_channel_by, MessageFormatter.highlightString(usr.name), MessageFormatter.highlightString(old.name), actorString))
                    }
                }
            }

            /*
             * TODO: logging
             * Base this off of Messages.cpp:454
             */
        }

        if (msg.hasName())
            usr.name = msg.name

        if (msg.hasTextureHash()) {
            usr.setTextureHash(msg.textureHash)
            usr.setTexture(null) // clear cached texture when we receive a new hash
        }

        if (msg.hasTexture()) {
            // FIXME: is it reasonable to create a bitmap here? How expensive?
            usr.setTexture(msg.texture)
            usr.setTextureHash(null)
        }

        if (msg.hasCommentHash()) {
            usr.setCommentHash(msg.commentHash)
            usr.comment = null
        }

        if (msg.hasComment()) {
            usr.comment = msg.comment
            usr.setCommentHash(null)
        }

        if (msg.listeningChannelAddCount > 0) {
            for (channelId in msg.listeningChannelAddList) {
                usr.addListeningChannel(channelId)
                if (usr.session == mSession) {
                    val ch = mChannels[channelId]
                    if (ch != null) {
                        ch.isListening = true
                        mObserver.onChannelStateUpdated(ch)
                    }
                }
            }
        }

        if (msg.listeningChannelRemoveCount > 0) {
            for (channelId in msg.listeningChannelRemoveList) {
                usr.removeListeningChannel(channelId)
                if (usr.session == mSession) {
                    val ch = mChannels[channelId]
                    if (ch != null) {
                        ch.isListening = false
                        mObserver.onChannelStateUpdated(ch)
                    }
                }
            }
        }

        if (msg.listeningVolumeAdjustmentCount > 0) {
            for (adj in msg.listeningVolumeAdjustmentList) {
                if (adj.hasListeningChannel() && adj.hasVolumeAdjustment()) {
                    val ch = mChannels[adj.listeningChannel]
                    if (ch != null) {
                        ch.listeningVolume = adj.volumeAdjustment
                    }
                }
            }
        }

        if (newUser)
            mObserver.onUserConnected(usr)
        else
            mObserver.onUserStateUpdated(usr)
    }

    @Synchronized
    override fun messageUserRemove(msg: Mumble.UserRemove) {
        val user = mUsers[msg.session]
        val actor = mUsers[msg.actor]
        val reason = msg.reason

        // TODO? hackish fix of crash that was happening. The original logic
        // here is possible flawed, regarding presence of session, actor etc.
        // Consult Mumble.proto and official client?
        val userName = user?.name ?: "unknown"
        val actorName = actor?.name ?: "unknown"
        if (msg.session == mSession)
            mLogger.logWarning(mContext.getString(if (msg.ban) R.string.chat_notify_kick_ban_self else R.string.chat_notify_kick_self, MessageFormatter.highlightString(actorName), reason))
        else if (actor != null)
            mLogger.logWarning(mContext.getString(if (msg.ban) R.string.chat_notify_kick_ban else R.string.chat_notify_kick, MessageFormatter.highlightString(actorName), reason, MessageFormatter.highlightString(userName)))
        else
            mLogger.logInfo(mContext.getString(R.string.chat_notify_disconnected, MessageFormatter.highlightString(userName)))

        if (user != null) {
            user.channel = null
        }
        mObserver.onUserRemoved(user, reason)
        mUsers.remove(msg.session)
    }

    override fun messagePermissionDenied(msg: Mumble.PermissionDenied) {
        val reason: String
        when (msg.type) {
            Mumble.PermissionDenied.DenyType.ChannelName ->
                reason = mContext.getString(R.string.deny_reason_channel_name)
            Mumble.PermissionDenied.DenyType.TextTooLong ->
                reason = mContext.getString(R.string.deny_reason_text_too_long)
            Mumble.PermissionDenied.DenyType.TemporaryChannel ->
                reason = mContext.getString(R.string.deny_reason_no_operation_temp)
            Mumble.PermissionDenied.DenyType.MissingCertificate ->
                reason = mContext.getString(R.string.deny_reason_no_certificate)
            Mumble.PermissionDenied.DenyType.UserName ->
                reason = mContext.getString(R.string.deny_reason_invalid_username)
            Mumble.PermissionDenied.DenyType.ChannelFull ->
                reason = mContext.getString(R.string.deny_reason_channel_full)
            Mumble.PermissionDenied.DenyType.NestingLimit ->
                reason = mContext.getString(R.string.deny_reason_channel_nesting)
            else -> {
                reason = if (msg.hasReason()) mContext.getString(R.string.deny_reason_other, msg.reason)
                else mContext.getString(R.string.perm_denied)
            }
        }
        mObserver.onPermissionDenied(reason)
    }

    override fun messageTextMessage(msg: Mumble.TextMessage) {
        val sender = mUsers[msg.actor]

        if (sender != null && sender.isLocalIgnored)
            return

        val channels = ArrayList<Channel>(msg.channelIdCount)
        for (channelId in msg.channelIdList) {
            val ch = mChannels[channelId]
            if (ch != null) channels.add(ch)
        }
        val trees = ArrayList<Channel>(msg.treeIdCount)
        for (treeId in msg.treeIdList) {
            val t = mChannels[treeId]
            if (t != null) trees.add(t)
        }
        val users = ArrayList<User>(msg.sessionCount)
        for (userId in msg.sessionList) {
            val u = mUsers[userId]
            if (u != null) users.add(u)
        }

        val actorName = sender?.name ?: mContext.getString(R.string.server)

        val message = Message(msg.actor, actorName, channels, trees, users, msg.message)
        mObserver.onMessageLogged(message)
    }

    @Synchronized
    override fun messageServerSync(msg: Mumble.ServerSync) {
        mSession = msg.session
        if (msg.hasPermissions()) {
            mPermissions = msg.permissions.toInt()
        }
        if (msg.hasWelcomeText()) {
            mLogger.logInfo(msg.welcomeText)
        }
    }

    override fun messageServerConfig(msg: Mumble.ServerConfig) {
        mServerSettings = ServerSettings(msg)
    }
}
