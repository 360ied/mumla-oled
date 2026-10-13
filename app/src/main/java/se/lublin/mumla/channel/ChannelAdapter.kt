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
import android.graphics.drawable.Drawable.ConstantState
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.drawable.CircleDrawable

/**
 * Modern RecyclerView adapter to display channel members in the overlay HUD.
 */
class ChannelAdapter(context: Context?, channel: IChannel?) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private val mAvatarCache = AvatarCache()
    private val mContext: Context? = context
    private var mChannel: IChannel? = channel

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val stateIcon: ImageView = itemView.findViewById(R.id.user_row_state)
        val userName: TextView = itemView.findViewById(R.id.user_row_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // NPE-exact: the original dereferenced mContext unconditionally.
        val context = mContext ?: throw NullPointerException("mContext")
        val v = LayoutInflater.from(context).inflate(R.layout.overlay_user_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = mChannel
        val users = channel?.users
        if (channel == null || users == null || position < 0 || position >= users.size) {
            return
        }
        val user = users[position]
        if (user == null) {
            return
        }

        holder.userName.text = user.name
        holder.stateIcon.setImageDrawable(getTalkStateDrawable(user))
    }

    override fun getItemCount(): Int {
        val users = mChannel?.users
        return if (mChannel != null && users != null) users.size else 0
    }

    override fun getItemId(position: Int): Long {
        val users = mChannel?.users
        if (mChannel != null && users != null && position >= 0 && position < users.size) {
            val user = users[position]
            if (user != null) {
                return user.session.toLong()
            }
        }
        return RecyclerView.NO_ID
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package tests).
    fun getTalkStateDrawable(user: IUser): Drawable? {
        val context = mContext
        if (context == null) {
            return null
        }
        if (user.isSelfDeafened) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_deafened)
        } else if (user.isDeafened) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_server_deafened)
        } else if (user.isSelfMuted) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_muted)
        } else if (user.isMuted) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_server_muted)
        } else if (user.isSuppressed) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_suppressed)
        } else if (user.talkState == TalkState.TALKING ||
            user.talkState == TalkState.SHOUTING ||
            user.talkState == TalkState.WHISPERING
        ) {
            return AppCompatResources.getDrawable(context, R.drawable.outline_circle_talking_on)
        } else {
            val bitmap: Bitmap? = mAvatarCache.get(user)
            if (bitmap != null) {
                return CircleDrawable(context.resources, bitmap)
            }
        }
        return AppCompatResources.getDrawable(context, R.drawable.outline_circle_talking_off)
    }

    fun setChannel(channel: IChannel?) {
        mChannel = channel
        notifyDataSetChanged()
    }

    /**
     * Immediately updates a user's display name and talk state icon on the visible view holder,
     * bypassing RecyclerView rebind cycles and eliminating animation delays.
     */
    fun updateUserState(user: IUser?, view: RecyclerView?) {
        val users = mChannel?.users
        if (mChannel == null || users == null || user == null) {
            return
        }
        val index = users.indexOfFirst { it == user }
        if (index < 0) {
            return
        }
        if (view != null) {
            val vh = view.findViewHolderForAdapterPosition(index) as ViewHolder?
            if (vh != null) {
                val name = user.name
                // ViewHolder fields are non-null by construction; the dead
                // null comparisons below only obscured that. Diff text and
                // drawable directly.
                if (name != null && !name.contentEquals(vh.userName.text)) {
                    vh.userName.text = name
                }
                run {
                    val newState = getTalkStateDrawable(user)
                    val currentDrawable = vh.stateIcon.drawable
                    if (newState != null && currentDrawable != null) {
                        val currentInner = currentDrawable.current
                        val state: ConstantState? = currentInner?.constantState
                        if (state == null || state != newState.constantState) {
                            vh.stateIcon.setImageDrawable(newState)
                        }
                    } else if (newState != null) {
                        vh.stateIcon.setImageDrawable(newState)
                    }
                }
                return
            }
        }
        notifyItemChanged(index)
    }

    fun notifyUserChanged(user: IUser?) {
        val users = mChannel?.users
        if (mChannel == null || users == null || user == null) {
            return
        }
        val index = users.indexOfFirst { it == user }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun getChannel(): IChannel? {
        return mChannel
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
}
