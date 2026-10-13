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

package se.lublin.mumla.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.Drawable.ConstantState
import android.os.RemoteException
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Server
import se.lublin.humla.model.TalkState
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.drawable.CircleDrawable
import se.lublin.mumla.service.MumlaService

/**
 * Created by andrew on 31/07/13.
 */
class ChannelListAdapter(
    context: Context,
    service: IHumlaService,
    database: MumlaDatabase,
    fragmentManager: FragmentManager,
    showPinnedOnly: Boolean,
    showChannelUserCount: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), UserMenu.IUserLocalStateListener {

    private val mContext: Context = context
    private var mService: IHumlaService? = service
    private val mDatabase: MumlaDatabase = database
    private var mRootChannels: MutableList<Int>
    private val mNodes: MutableList<Node> = ArrayList()
    /**
     * A mapping of user-set channel expansions.
     * If a key is not mapped, default to hiding empty channels.
     */
    private val mExpandedChannels = HashMap<Int, Boolean>()
    private var mUserClickListener: OnUserClickListener? = null
    private var mChannelClickListener: OnChannelClickListener? = null
    private var mShowChannelUserCount: Boolean = showChannelUserCount
    private val mFragmentManager: FragmentManager = fragmentManager

    private val mAvatarCache = AvatarCache()

    init {
        setHasStableIds(true)

        mRootChannels = ArrayList()
        if (showPinnedOnly) {
            // NPE-exact: the original dereferenced getTargetServer() unconditionally.
            val targetServer = service.getTargetServer() ?: throw NullPointerException("targetServer")
            mRootChannels = database.getPinnedChannels(targetServer.id).toMutableList()
        } else {
            mRootChannels.add(0)
        }

        // Construct channel tree
        updateChannels()
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(viewType, viewGroup, false)
        if (viewType == R.layout.channel_row) {
            return ChannelViewHolder(view)
        } else if (viewType == R.layout.channel_user_row) {
            return UserViewHolder(view)
        }
        throw IllegalArgumentException("unknown viewType $viewType")
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val node = mNodes[position]
        if (node.isChannel()) {
            // NPE-exact: isChannel proves mChannel non-null; the original dereferenced it.
            val channel = node.getChannel() ?: throw NullPointerException("channel")
            val cvh = viewHolder as ChannelViewHolder
            cvh.itemView.setOnClickListener {
                val channelClickListener = mChannelClickListener
                if (channelClickListener != null) {
                    channelClickListener.onChannelClick(channel)
                }
            }

            val expandUsable = channel.subchannels.size > 0 ||
                channel.subchannelUserCount > 0
            cvh.mChannelExpandToggle.setImageResource(
                if (node.isExpanded()) {
                    R.drawable.ic_action_expanded
                } else {
                    R.drawable.ic_action_collapsed
                }
            )
            cvh.mChannelExpandToggle.setOnClickListener {
                mExpandedChannels[channel.id] = !node.isExpanded()
                updateChannels() // FIXME: very inefficient.
                notifyDataSetChanged()
            }
            // Dim channel expand toggle when no subchannels exist
            cvh.mChannelExpandToggle.isEnabled = expandUsable
            cvh.mChannelExpandToggle.visibility = if (expandUsable) View.VISIBLE else View.INVISIBLE

            cvh.mChannelName.text = channel.name

            var nameTypeface = Typeface.NORMAL
            val service = mService
            if (service != null && service.isConnected()) {
                val session = service.HumlaSession()
                var ourChan: IChannel? = null
                try {
                    ourChan = session.getSessionChannel()
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "exception in onBindViewHolder: $e")
                }
                if (ourChan != null) {
                    if (channel == ourChan) {
                        nameTypeface = nameTypeface or Typeface.BOLD
                        // Always italicize our current channel if it has a link.
                        if (channel.links.size > 0) {
                            nameTypeface = nameTypeface or Typeface.ITALIC
                        }
                    }
                    // Italicize channels in a link with our current channel.
                    if (channel.links.any { it == ourChan }) {
                        nameTypeface = nameTypeface or Typeface.ITALIC
                    }
                }
            }
            cvh.mChannelName.setTypeface(null, nameTypeface)

            if (mShowChannelUserCount) {
                cvh.mChannelUserCount.visibility = View.VISIBLE
                val userCount = channel.subchannelUserCount
                cvh.mChannelUserCount.text = String.format("%d", userCount)
            } else {
                cvh.mChannelUserCount.visibility = View.GONE
            }

            cvh.mChannelListening.visibility = if (channel.isListening) View.VISIBLE else View.GONE

            // Pad the view depending on channel's nested level.
            val metrics = mContext.resources.displayMetrics
            val margin = node.getDepth() * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25f, metrics)
            cvh.mChannelHolder.setPadding(
                margin.toInt(),
                cvh.mChannelHolder.paddingTop,
                cvh.mChannelHolder.paddingRight,
                cvh.mChannelHolder.paddingBottom
            )

            cvh.mJoinButton.setOnClickListener {
                val service = mService
                if (service != null && service.isConnected()) {
                    service.HumlaSession().joinChannel(channel.id)
                }
            }

            cvh.mMoreButton.setOnClickListener { v ->
                // NPE-exact: the original returned early only on null service.
                val service = mService ?: return@setOnClickListener
                val menu = ChannelMenu(mContext, channel, service, mDatabase, mFragmentManager)
                menu.showPopup(v)
            }

            cvh.itemView.setOnLongClickListener {
                cvh.mMoreButton.performClick()
                true
            }
        } else if (node.isUser()) {
            // NPE-exact: isUser proves mUser non-null; the original dereferenced it.
            val user = node.getUser() ?: throw NullPointerException("user")
            val uvh = viewHolder as UserViewHolder
            uvh.itemView.setOnClickListener {
                val userClickListener = mUserClickListener
                if (userClickListener != null) {
                    userClickListener.onUserClick(user)
                }
            }

            uvh.mUserName.text = user.name

            val typefaceStyle: Int

            var selfSession = -1
            try {
                val service = mService
                if (service != null) {
                    selfSession = service.HumlaSession().getSessionId()
                }
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onBindViewHolder: $e")
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onBindViewHolder: $e")
            }

            val service = mService
            typefaceStyle = if (service != null && service.isConnected() && user.session == selfSession) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
            uvh.mUserName.setTypeface(null, typefaceStyle)

            uvh.mUserTalkHighlight.setImageDrawable(getTalkStateDrawable(user))

            // Pad the view depending on channel's nested level.
            val metrics = mContext.resources.displayMetrics
            val margin = (node.getDepth() + 1) * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25f, metrics)
            uvh.mUserHolder.setPadding(
                margin.toInt(),
                uvh.mUserHolder.paddingTop,
                uvh.mUserHolder.paddingRight,
                uvh.mUserHolder.paddingBottom
            )

            uvh.mMoreButton.setOnClickListener { v ->
                // NPE-exact: the original returned early only on null service.
                val service = mService ?: return@setOnClickListener
                // CCE-exact: the original cast to MumlaService unconditionally.
                val menu = UserMenu(mContext, user, service as MumlaService, mFragmentManager, this)
                menu.showPopup(v)
            }

            uvh.itemView.setOnLongClickListener {
                uvh.mMoreButton.performClick()
                true
            }
        }
    }

    override fun getItemCount(): Int {
        return mNodes.size
    }

    override fun getItemViewType(position: Int): Int {
        val node = mNodes[position]
        if (node.isChannel()) {
            return R.layout.channel_row
        } else if (node.isUser()) {
            return R.layout.channel_user_row
        } else {
            return 0
        }
    }

    override fun getItemId(position: Int): Long {
        try {
            // NPE-exact: every node carries an id; unboxing null threw NPE in the original.
            return mNodes[position].getId() ?: throw NullPointerException("id")
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        return -1
    }

    /**
     * Updates the channel tree model.
     * To be used after any channel tree modifications.
     */
    fun updateChannels() {
        val service = mService
        if (service == null || !service.isConnected()) {
            return
        }

        val session = service.HumlaSession()
        mNodes.clear()
        try {
            for (cid in mRootChannels) {
                val channel = session.getChannel(cid)
                if (channel != null) {
                    constructNodes(null, channel, 0, mNodes)
                }
            }
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in updateChannels: $e")
        }
    }

    /**
     * Update a user's state icon
     * @param user The user to update.
     * @param view The view containing this adapter.
     */
    fun updateUserStates(user: IUser?, view: RecyclerView?) {
        if (user == null || view == null) {
            return
        }
        val itemId = (user.session.toLong() and 0xFFFFFFFFL) or USER_ID_MASK
        val uvh = view.findViewHolderForItemId(itemId) as UserViewHolder?
        // mUserTalkHighlight is non-null by construction; only the holder lookup
        // can miss.
        if (uvh != null) {
            val newState = getTalkStateDrawable(user)
            val current = uvh.mUserTalkHighlight.drawable
            if (newState != null && current != null) {
                val currentInner = current.current
                val state: ConstantState? = currentInner?.constantState
                if (state == null || state != newState.constantState) {
                    uvh.mUserTalkHighlight.setImageDrawable(newState)
                }
            } else if (newState != null) {
                uvh.mUserTalkHighlight.setImageDrawable(newState)
            }
        }
    }

    // Widened to public: Kotlin has no package visibility (was package-visible in Java).
    fun getTalkStateDrawable(user: IUser?): Drawable? {
        if (user == null) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_off)
        }
        if (user.isSelfDeafened) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_deafened)
        } else if (user.isDeafened) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_server_deafened)
        } else if (user.isSelfMuted) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_muted)
        } else if (user.isMuted) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_server_muted)
        } else if (user.isSuppressed) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_suppressed)
        } else if (user.talkState == TalkState.TALKING ||
            user.talkState == TalkState.SHOUTING ||
            user.talkState == TalkState.WHISPERING
        ) {
            // TODO whisper and shouting?
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_on)
        } else {
            // Passive drawables
            val bitmap: Bitmap? = mAvatarCache.get(user)
            if (bitmap != null) {
                return CircleDrawable(mContext.resources, bitmap)
            }
        }
        // "default" symbol, used also if bitmap decoding fails
        return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_off)
    }

    fun getUserPosition(session: Int): Int {
        val itemId = (session.toLong() and 0xFFFFFFFFL) or USER_ID_MASK
        for (i in mNodes.indices) {
            val node = mNodes[i]
            try {
                if (node.getId() == itemId) {
                    return i
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
        return -1
    }

    fun getChannelPosition(channelId: Int): Int {
        val itemId = (channelId.toLong() and 0xFFFFFFFFL) or CHANNEL_ID_MASK
        for (i in mNodes.indices) {
            val node = mNodes[i]
            try {
                if (node.getId() == itemId) {
                    return i
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
        return -1
    }

    fun setOnUserClickListener(listener: OnUserClickListener?) {
        mUserClickListener = listener
    }

    fun setOnChannelClickListener(listener: OnChannelClickListener?) {
        mChannelClickListener = listener
    }

    /**
     * Sets whether to show the channel user count in a channel row.
     */
    fun setShowChannelUserCount(showUserCount: Boolean) {
        mShowChannelUserCount = showUserCount
        notifyDataSetChanged()
    }

    /**
     * Recursively creates a list of {@link Node}s representing the channel hierarchy.
     * @param parent The parent node to propagate under.
     * @param channel The parent channel.
     * @param depth The current depth of the subtree.
     * @param nodes An accumulator to store generated nodes into.
     */
    private fun constructNodes(
        parent: Node?,
        channel: IChannel,
        depth: Int,
        nodes: MutableList<Node>
    ) {
        val channelNode = Node(parent, depth, channel)
        nodes.add(channelNode)

        val expandSetting = mExpandedChannels[channel.id]
        if ((expandSetting == null && channel.subchannelUserCount == 0) ||
            (expandSetting != null && !expandSetting)
        ) {
            channelNode.setExpanded(false)
            return // Skip adding children of contracted/empty channels.
        }

        for (user in channel.users) {
            nodes.add(Node(channelNode, depth, user))
        }
        for (subc in channel.subchannels) {
            constructNodes(channelNode, subc, depth + 1, nodes)
        }
    }

    /**
     * Changes the service backing the adapter. Updates the list as well.
     * @param service The new service to retrieve channels from.
     */
    fun setService(service: IHumlaService) {
        mService = service
        if (service.getConnectionState() == HumlaService.ConnectionState.CONNECTED) {
            updateChannels()
            notifyDataSetChanged()
        }
    }

    override fun onLocalUserStateUpdated(user: IUser) {
        notifyDataSetChanged()

        // Add or remove registered user from local mute history
        // NPE-exact: the original dereferenced the service and server unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        val server: Server? = service.getTargetServer()
        val target = server ?: throw NullPointerException("server")

        if (user.userId >= 0 && target.isSaved()) {
            Thread {
                if (user.isLocalMuted) {
                    mDatabase.addLocalMutedUser(target.id, user.userId)
                } else {
                    mDatabase.removeLocalMutedUser(target.id, user.userId)
                }
                if (user.isLocalIgnored) {
                    mDatabase.addLocalIgnoredUser(target.id, user.userId)
                } else {
                    mDatabase.removeLocalIgnoredUser(target.id, user.userId)
                }
            }.start()
        }
    }

    private class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val mUserHolder: LinearLayout = itemView.findViewById(R.id.user_row_title)
        val mUserName: TextView = itemView.findViewById(R.id.user_row_name)
        val mUserTalkHighlight: ImageView = itemView.findViewById(R.id.user_row_talk_highlight)
        val mMoreButton: ImageView = itemView.findViewById(R.id.user_row_more)
    }

    private class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val mChannelHolder: LinearLayout = itemView.findViewById(R.id.channel_row_title)
        val mChannelExpandToggle: ImageView = itemView.findViewById(R.id.channel_row_expand)
        val mChannelName: TextView = itemView.findViewById(R.id.channel_row_name)
        val mChannelListening: ImageView = itemView.findViewById(R.id.channel_row_listening)
        val mChannelUserCount: TextView = itemView.findViewById(R.id.channel_row_count)
        val mJoinButton: ImageView = itemView.findViewById(R.id.channel_row_join)
        val mMoreButton: ImageView = itemView.findViewById(R.id.channel_row_more)
    }

    fun removeUser(session: Int) {
        mAvatarCache.remove(session)
    }

    fun clearAvatarCache() {
        mAvatarCache.clear()
    }

    @VisibleForTesting
    fun getAvatarCache(): AvatarCache {
        return mAvatarCache
    }

    /**
     * An arbitrary node in the channel-user hierarchy.
     * Can be either a channel or user.
     */
    private class Node {
        private val mParent: Node?
        private val mChannel: IChannel?
        private val mUser: IUser?
        private val mDepth: Int
        private var mExpanded: Boolean

        constructor(parent: Node?, depth: Int, channel: IChannel) {
            mParent = parent
            mChannel = channel
            mUser = null
            mDepth = depth
            mExpanded = true
        }

        constructor(parent: Node?, depth: Int, user: IUser) {
            mParent = parent
            mChannel = null
            mUser = user
            mDepth = depth
            mExpanded = false
        }

        fun isChannel(): Boolean {
            return mChannel != null
        }

        fun isUser(): Boolean {
            return mUser != null
        }

        fun getParent(): Node? {
            return mParent
        }

        fun getChannel(): IChannel? {
            return mChannel
        }

        fun getUser(): IUser? {
            return mUser
        }

        @Throws(RemoteException::class)
        fun getId(): Long? {
            // Apply flags to differentiate integer-length identifiers
            if (isChannel()) {
                // NPE-exact: isChannel proves mChannel non-null; unboxing null threw NPE.
                val channel = mChannel ?: throw NullPointerException("channel")
                return CHANNEL_ID_MASK or (channel.id.toLong() and 0xFFFFFFFFL)
            } else if (isUser()) {
                // NPE-exact: isUser proves mUser non-null; unboxing null threw NPE.
                val user = mUser ?: throw NullPointerException("user")
                return USER_ID_MASK or (user.session.toLong() and 0xFFFFFFFFL)
            }
            return null
        }

        fun getDepth(): Int {
            return mDepth
        }

        fun isExpanded(): Boolean {
            return mExpanded
        }

        fun setExpanded(expanded: Boolean) {
            mExpanded = expanded
        }
    }

    companion object {
        private val TAG: String = ChannelListAdapter::class.java.name

        // Set particular bits to make the integer-based model item ids unique.
        // (0x1L << 32) and (0x1L << 33); written out because const forbids shl.
        const val CHANNEL_ID_MASK = 0x100000000L
        const val USER_ID_MASK = 0x200000000L
    }
}
