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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.model.TalkState
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.isListening
import se.lublin.humla.state.textureCacheKey
import se.lublin.mumla.R
import se.lublin.mumla.drawable.CircleDrawable

/**
 * The channel/user tree. Rows are addressed by channel id and user session
 * and rendered from the latest submitted state; the expanded/collapsed map
 * stays here, as the live-handle adapter kept it.
 * Created by andrew on 31/07/13.
 */
class ChannelListAdapter(
    context: Context,
    showPinnedOnly: Boolean,
    private val onChannelClick: (ChannelInfo) -> Unit,
    private val onUserClick: (UserInfo) -> Unit,
    private val onChannelJoin: (ChannelInfo) -> Unit,
    private val onChannelMenu: (ChannelInfo, View) -> Unit,
    private val onUserMenu: (UserInfo, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val mContext: Context = context
    private val mShowPinnedOnly: Boolean = showPinnedOnly

    private var mState: ChannelListUiState = ChannelListUiState()
    private var mRows: List<Row> = emptyList()

    /**
     * A mapping of user-set channel expansions.
     * If a key is not mapped, default to hiding empty channels.
     */
    private val mExpandedChannels = HashMap<Int, Boolean>()

    private val mAvatarCache = AvatarCache()

    init {
        setHasStableIds(true)
    }

    /** Rebuilds the rows from a new state and repaints what changed. */
    fun submit(state: ChannelListUiState) {
        val previousState = mState
        val previousRows = mRows
        mState = state
        mRows = buildRows(state)
        if (previousState.showUserCount != state.showUserCount ||
            mRows.map { it.stableId } != previousRows.map { it.stableId }
        ) {
            // The count column is state-driven, not part of a row's value.
            notifyDataSetChanged()
            return
        }
        for (position in mRows.indices) {
            if (mRows[position] != previousRows[position]) {
                notifyItemChanged(position)
            }
        }
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
        when (val row = mRows[position]) {
            is Row.ChannelRow -> bindChannel(viewHolder as ChannelViewHolder, row)
            is Row.UserRow -> bindUser(viewHolder as UserViewHolder, row)
        }
    }

    private fun bindChannel(holder: ChannelViewHolder, row: Row.ChannelRow) {
        val channel = row.channel
        holder.itemView.setOnClickListener { onChannelClick(row.channel) }

        holder.mChannelExpandToggle.setImageResource(
            if (row.expanded) R.drawable.ic_action_expanded else R.drawable.ic_action_collapsed
        )
        holder.mChannelExpandToggle.setOnClickListener {
            mExpandedChannels[channel.id] = !row.expanded
            submit(mState) // Same state, new expansion: rebuild and repaint.
        }
        holder.mChannelExpandToggle.isEnabled = row.hasChildren
        holder.mChannelExpandToggle.visibility =
            if (row.hasChildren) View.VISIBLE else View.INVISIBLE

        holder.mChannelName.text = channel.name

        var nameTypeface = Typeface.NORMAL
        if (row.isSelfChannel) {
            nameTypeface = nameTypeface or Typeface.BOLD
            // Always italicize our current channel if it has a link.
            if (channel.links.isNotEmpty()) {
                nameTypeface = nameTypeface or Typeface.ITALIC
            }
        }
        // Italicize channels in a link with our current channel.
        if (row.linkedToSelf) {
            nameTypeface = nameTypeface or Typeface.ITALIC
        }
        holder.mChannelName.setTypeface(null, nameTypeface)

        if (mState.showUserCount) {
            holder.mChannelUserCount.visibility = View.VISIBLE
            holder.mChannelUserCount.text = String.format("%d", row.userCount)
        } else {
            holder.mChannelUserCount.visibility = View.GONE
        }

        holder.mChannelListening.visibility = if (row.listening) View.VISIBLE else View.GONE

        // Pad the view depending on channel's nested level.
        val metrics = mContext.resources.displayMetrics
        val margin = row.depth * TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 25f, metrics
        )
        holder.mChannelHolder.setPadding(
            margin.toInt(),
            holder.mChannelHolder.paddingTop,
            holder.mChannelHolder.paddingRight,
            holder.mChannelHolder.paddingBottom
        )

        holder.mJoinButton.setOnClickListener { onChannelJoin(row.channel) }

        holder.mMoreButton.setOnClickListener { v -> onChannelMenu(row.channel, v) }

        holder.itemView.setOnLongClickListener {
            holder.mMoreButton.performClick()
            true
        }
    }

    private fun bindUser(holder: UserViewHolder, row: Row.UserRow) {
        val user = row.user
        holder.itemView.setOnClickListener { onUserClick(row.user) }

        holder.mUserName.text = user.name
        holder.mUserName.setTypeface(null, if (row.isSelf) Typeface.BOLD else Typeface.NORMAL)
        holder.mUserTalkHighlight.setImageDrawable(getTalkStateDrawable(user, row.talkState))

        // Pad the view depending on channel's nested level.
        val metrics = mContext.resources.displayMetrics
        val margin = (row.depth + 1) * TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 25f, metrics
        )
        holder.mUserHolder.setPadding(
            margin.toInt(),
            holder.mUserHolder.paddingTop,
            holder.mUserHolder.paddingRight,
            holder.mUserHolder.paddingBottom
        )

        holder.mMoreButton.setOnClickListener { v -> onUserMenu(row.user, v) }

        holder.itemView.setOnLongClickListener {
            holder.mMoreButton.performClick()
            true
        }
    }

    override fun getItemCount(): Int {
        return mRows.size
    }

    override fun getItemViewType(position: Int): Int {
        return when (mRows[position]) {
            is Row.ChannelRow -> R.layout.channel_row
            is Row.UserRow -> R.layout.channel_user_row
        }
    }

    override fun getItemId(position: Int): Long {
        return mRows[position].stableId
    }

    /**
     * The drawable a user row shows, from the user's values and talk state.
     */
    fun getTalkStateDrawable(user: UserInfo?, talkState: TalkState): Drawable? {
        if (user == null) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_off)
        }
        if (user.selfDeafened) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_deafened)
        } else if (user.deafened) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_server_deafened)
        } else if (user.selfMuted) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_muted)
        } else if (user.muted) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_server_muted)
        } else if (user.suppressed) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_suppressed)
        } else if (talkState == TalkState.TALKING ||
            talkState == TalkState.SHOUTING ||
            talkState == TalkState.WHISPERING
        ) {
            // TODO whisper and shouting?
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_on)
        } else {
            // Passive drawables
            val bitmap: Bitmap? = avatarOf(user)
            if (bitmap != null) {
                return CircleDrawable(mContext.resources, bitmap)
            }
        }
        // "default" symbol, used also if bitmap decoding fails
        return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_off)
    }

    private fun avatarOf(user: UserInfo): Bitmap? = mAvatarCache.get(
        user.session,
        user.textureCacheKey(),
        user.texture?.toByteArray(),
    )

    fun getUserPosition(session: Int): Int {
        val itemId = (session.toLong() and 0xFFFFFFFFL) or USER_ID_MASK
        return mRows.indexOfFirst { it.stableId == itemId }
    }

    fun getChannelPosition(channelId: Int): Int {
        val itemId = (channelId.toLong() and 0xFFFFFFFFL) or CHANNEL_ID_MASK
        return mRows.indexOfFirst { it.stableId == itemId }
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

    /** The flat list the adapter currently shows, for the screen-level test. */
    @VisibleForTesting
    fun rows(): List<Row> = mRows

    /**
     * Builds the deterministic flat row list. The same expansion rules as the
     * live-handle adapter: an unset entry hides a channel with no users
     * anywhere in its subtree; an explicit false always hides children.
     */
    private fun buildRows(state: ChannelListUiState): List<Row> {
        val server = state.server
        val selfChannelId = server.self?.channelId
        val out = ArrayList<Row>()
        val roots = if (mShowPinnedOnly) {
            state.pinnedChannelIds.filter { server.channels.containsKey(it) }
        } else {
            listOf(0).filter { server.channels.containsKey(it) }
        }
        for (rootId in roots) {
            appendChannel(state, rootId, 0, selfChannelId, out)
        }
        return out
    }

    private fun appendChannel(
        state: ChannelListUiState,
        channelId: Int,
        depth: Int,
        selfChannelId: Int?,
        out: MutableList<Row>,
    ) {
        val server = state.server
        val channel = server.channels[channelId] ?: return
        val children = server.tree.children(channelId)
        val users = server.tree.users(channelId)
        val userCount = server.tree.userCountInSubtree(channelId)
        val expandSetting = mExpandedChannels[channelId]
        val expanded =
            !((expandSetting == null && userCount == 0) || (expandSetting != null && !expandSetting))
        out.add(
            Row.ChannelRow(
                channel = channel,
                depth = depth,
                hasChildren = children.isNotEmpty() || userCount > 0,
                userCount = userCount,
                isSelfChannel = channelId == selfChannelId,
                linkedToSelf = selfChannelId != null && channel.links.contains(selfChannelId),
                listening = channel.isListening(state.session),
                expanded = expanded,
            ),
        )
        if (!expanded) {
            return // Skip adding children of contracted/empty channels.
        }
        for (user in users) {
            out.add(
                Row.UserRow(
                    user = user,
                    depth = depth,
                    isSelf = user.session == state.server.selfSession,
                    talkState = user.session.let {
                        if (state.talkActivity.selfTalking && it == state.server.selfSession) {
                            TalkState.TALKING
                        } else {
                            state.talkActivity.users[it] ?: TalkState.PASSIVE
                        }
                    },
                ),
            )
        }
        for (child in children) {
            appendChannel(state, child.id, depth + 1, selfChannelId, out)
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

    /** One flattened row. Value equality drives the minimal repaint. */
    sealed interface Row {
        val depth: Int
        val stableId: Long

        data class ChannelRow(
            val channel: ChannelInfo,
            override val depth: Int,
            val hasChildren: Boolean,
            val userCount: Int,
            val isSelfChannel: Boolean,
            val linkedToSelf: Boolean,
            val listening: Boolean,
            val expanded: Boolean,
        ) : Row {
            override val stableId: Long
                get() = CHANNEL_ID_MASK or (channel.id.toLong() and 0xFFFFFFFFL)
        }

        data class UserRow(
            val user: UserInfo,
            override val depth: Int,
            val isSelf: Boolean,
            val talkState: TalkState,
        ) : Row {
            override val stableId: Long
                get() = USER_ID_MASK or (user.session.toLong() and 0xFFFFFFFFL)
        }
    }

    companion object {
        // Set particular bits to make the integer-based model item ids unique.
        // (0x1L << 32) and (0x1L << 33); written out because const forbids shl.
        const val CHANNEL_ID_MASK = 0x100000000L
        const val USER_ID_MASK = 0x200000000L
    }
}
