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
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.model.TalkState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.textureCacheKey
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.R
import se.lublin.mumla.drawable.CircleDrawable

/**
 * Modern RecyclerView adapter to display channel members in the overlay HUD.
 * Rows are keyed by user session and rendered from submitted values plus the
 * live talk levels.
 */
class ChannelAdapter(context: Context?) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private val mAvatarCache = AvatarCache()
    private val mContext: Context? = context
    private var mUsers: List<UserInfo> = emptyList()
    private var mTalkActivity: TalkActivity = TalkActivity.initial()
    private var mSelfSession: Int? = null

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val stateIcon: ImageView = itemView.findViewById(R.id.user_row_state)
        val userName: TextView = itemView.findViewById(R.id.user_row_name)
    }

    /**
     * Replaces the rows. Users whose session list changed rebuild the list;
     * a talk-state or user-value change repaints only the affected rows.
     */
    fun submit(users: List<UserInfo>, talkActivity: TalkActivity, selfSession: Int?) {
        val previousSessions = mUsers.map { it.session }
        val previousUsers = mUsers
        val previousTalk = mTalkActivity
        val previousSelf = mSelfSession
        mUsers = users
        mTalkActivity = talkActivity
        mSelfSession = selfSession
        if (users.map { it.session } != previousSessions) {
            notifyDataSetChanged()
            return
        }
        for (position in users.indices) {
            if (users[position] != previousUsers[position] ||
                talkStateOf(users[position].session, talkActivity, selfSession) !=
                talkStateOf(previousUsers[position].session, previousTalk, previousSelf)
            ) {
                notifyItemChanged(position)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // NPE-exact: the original dereferenced mContext unconditionally.
        val context = mContext ?: throw NullPointerException("mContext")
        val v = LayoutInflater.from(context).inflate(R.layout.overlay_user_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < 0 || position >= mUsers.size) {
            return
        }
        val user = mUsers[position]
        holder.userName.text = user.name
        holder.stateIcon.setImageDrawable(
            getTalkStateDrawable(
                user,
                talkStateOf(user.session, mTalkActivity, mSelfSession),
            ),
        )
    }

    override fun getItemCount(): Int = mUsers.size

    override fun getItemId(position: Int): Long {
        if (position < 0 || position >= mUsers.size) {
            return RecyclerView.NO_ID
        }
        return mUsers[position].session.toLong()
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package tests).
    fun getTalkStateDrawable(user: UserInfo?, talkState: TalkState): Drawable? {
        val context = mContext
        if (context == null || user == null) {
            return null
        }
        if (user.selfDeafened) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_deafened)
        } else if (user.deafened) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_server_deafened)
        } else if (user.selfMuted) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_muted)
        } else if (user.muted) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_server_muted)
        } else if (user.suppressed) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_suppressed)
        } else if (talkState == TalkState.TALKING ||
            talkState == TalkState.SHOUTING ||
            talkState == TalkState.WHISPERING
        ) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_talking_on)
        } else {
            val bitmap: Bitmap? = mAvatarCache.get(
                user.session,
                user.textureCacheKey(),
                user.texture?.toByteArray(),
            )
            if (bitmap != null) {
                return CircleDrawable(context.resources, bitmap)
            }
        }
        return AppCompatResources.getDrawable(context, R.drawable.outline_circle_talking_off)
    }

    fun removeUser(session: Int) {
        mAvatarCache.remove(session)
    }

    fun clearAvatarCache() {
        mAvatarCache.clear()
    }

    /** The users currently shown, for the adapter tests. */
    @VisibleForTesting
    fun users(): List<UserInfo> = mUsers

    @VisibleForTesting
    fun getAvatarCache(): AvatarCache {
        return mAvatarCache
    }

    private fun talkStateOf(
        session: Int,
        talkActivity: TalkActivity,
        selfSession: Int?,
    ): TalkState =
        if (talkActivity.selfTalking && session == selfSession) {
            TalkState.TALKING
        } else {
            talkActivity.users[session] ?: TalkState.PASSIVE
        }
}
