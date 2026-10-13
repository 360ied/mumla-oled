/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.channel.comment.AbstractCommentFragment
import se.lublin.mumla.channel.comment.UserCommentFragment
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.util.ModelUtils

/**
 * Created by andrew on 19/11/15.
 */
class UserMenu(
    context: Context,
    user: IUser,
    service: MumlaService,
    fragmentManager: FragmentManager,
    stateListener: IUserLocalStateListener
) : PermissionsPopupMenu.IOnMenuPrepareListener, PopupMenu.OnMenuItemClickListener {
    private val mContext: Context = context
    private val mUser: IUser = user
    private val mService: MumlaService = service
    private val mFragmentManager: FragmentManager = fragmentManager
    private val mStateListener: IUserLocalStateListener = stateListener

    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        // Use permission data to determine the actions available.
        val self: Boolean
        try {
            self = mUser.session == mService.getSessionId()
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in onMenuPrepare: $e")
            return
        }
        val perms = mService.getPermissions()
        val channel = mUser.channel
        if (channel == null) {
            Log.d(TAG, "mUser.getChannel()==null in onMenuPrepare")
            return
        }
        val channelPerms = if (channel.id != 0) channel.permissions else perms
        val comment = mUser.comment
        val commentHash = mUser.commentHash
        val userHash = mUser.hash

        menu.findItem(R.id.context_kick).setVisible(
            !self && perms and (Permissions.Kick or Permissions.Ban or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_ban).setVisible(
            !self && perms and (Permissions.Ban or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_mute).setVisible(
            channelPerms and (Permissions.Write or Permissions.MuteDeafen) > 0 &&
                (!self || mUser.isMuted || mUser.isSuppressed)
        )
        menu.findItem(R.id.context_deafen).setVisible(
            channelPerms and (Permissions.Write or Permissions.MuteDeafen) > 0 &&
                (!self || mUser.isDeafened)
        )
        menu.findItem(R.id.context_priority).setVisible(
            channelPerms and (Permissions.Write or Permissions.MuteDeafen) > 0
        )
        menu.findItem(R.id.context_move).setVisible(
            !self && perms and Permissions.Move > 0
        )
        menu.findItem(R.id.context_change_comment).setVisible(self)
        menu.findItem(R.id.context_reset_comment).setVisible(
            !self && ((comment != null && comment.isNotEmpty()) || commentHash != null) &&
                perms and (Permissions.ResetUserContent or Permissions.Move or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_view_comment).setVisible(
            (comment != null && comment.isNotEmpty()) || commentHash != null
        )
        menu.findItem(R.id.context_register).setVisible(
            mUser.userId < 0 &&
                (userHash != null && userHash.isNotEmpty()) &&
                perms and ((if (self) Permissions.SelfRegister else Permissions.Register) or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_local_mute).setVisible(!self)
        menu.findItem(R.id.context_ignore_messages).setVisible(!self)
        // Highlight toggles
        menu.findItem(R.id.context_mute).setChecked(mUser.isMuted || mUser.isSuppressed)
        menu.findItem(R.id.context_deafen).setChecked(mUser.isDeafened)
        menu.findItem(R.id.context_priority).setChecked(mUser.isPrioritySpeaker)
        menu.findItem(R.id.context_local_mute).setChecked(mUser.isLocalMuted)
        menu.findItem(R.id.context_ignore_messages).setChecked(mUser.isLocalIgnored)
    }

    override fun onMenuItemClick(menuItem: MenuItem): Boolean {
        val itemId = menuItem.itemId
        if (itemId == R.id.context_ban || itemId == R.id.context_kick) {
            val isBan = itemId == R.id.context_ban
            val titleRes = if (isBan) R.string.user_menu_ban else R.string.user_menu_kick
            val reasonField = EditText(mContext)
            reasonField.setHint(R.string.hint_reason)
            MaterialAlertDialogBuilder(mContext)
                .setTitle(titleRes)
                .setView(reasonField)
                .setPositiveButton(titleRes) { _, _ ->
                    if (mService.isConnected()) {
                        mService.kickBanUser(mUser.session, reasonField.text.toString(), isBan)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (itemId == R.id.context_mute) {
            mService.setMuteDeafState(mUser.session, !(mUser.isMuted || mUser.isSuppressed), mUser.isDeafened)
        } else if (itemId == R.id.context_deafen) {
            mService.setMuteDeafState(mUser.session, mUser.isMuted, !mUser.isDeafened)
        } else if (itemId == R.id.context_move) {
            showChannelMoveDialog()
        } else if (itemId == R.id.context_priority) {
            mService.setPrioritySpeaker(mUser.session, !mUser.isPrioritySpeaker)
        } else if (itemId == R.id.context_local_mute) {
            mUser.isLocalMuted = !mUser.isLocalMuted
            mStateListener.onLocalUserStateUpdated(mUser)
        } else if (itemId == R.id.context_ignore_messages) {
            mUser.isLocalIgnored = !mUser.isLocalIgnored
            mStateListener.onLocalUserStateUpdated(mUser)
        } else if (itemId == R.id.context_change_comment) {
            showUserComment(true)
        } else if (itemId == R.id.context_view_comment) {
            showUserComment(false)
        } else if (itemId == R.id.context_reset_comment) {
            MaterialAlertDialogBuilder(mContext)
                .setMessage(mContext.getString(R.string.confirm_reset_comment, mUser.name))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    mService.setUserComment(mUser.session, "")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (itemId == R.id.context_register) {
            mService.registerUser(mUser.session)
        } else {
            return false
        }
        return true
    }

    private fun showUserComment(edit: Boolean) {
        val args = Bundle()
        args.putInt(UserCommentFragment.ARG_SESSION, mUser.session)
        args.putString(AbstractCommentFragment.ARG_COMMENT, mUser.comment)
        args.putBoolean(AbstractCommentFragment.ARG_EDITING, edit)
        val fragment = UserCommentFragment()
        fragment.arguments = args
        fragment.show(mFragmentManager, UserCommentFragment::class.java.name)
    }

    private fun showChannelMoveDialog() {
        // NPE-exact: the original dereferenced getRootChannel() unconditionally.
        val root = mService.getRootChannel() ?: throw NullPointerException("rootChannel")
        val channels: List<IChannel> = ModelUtils.getChannelList(root)
        val channelNames = arrayOfNulls<CharSequence>(channels.size)
        for (i in channels.indices) {
            channelNames[i] = channels[i].name
        }
        MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.user_menu_move)
            .setItems(channelNames) { _, which ->
                val channel = channels[which]
                mService.moveUserToChannel(mUser.session, channel.id)
            }
            .show()
    }

    fun showPopup(anchor: View) {
        // NPE-exact: the original dereferenced getChannel() unconditionally.
        val channel = mUser.channel ?: throw NullPointerException("channel")
        val popupMenu = PermissionsPopupMenu(
            mContext, anchor,
            R.menu.context_user, this, this, channel, mService
        )
        popupMenu.show()
    }

    /**
     * A listener notified whenever the user's local state changes.
     */
    interface IUserLocalStateListener {
        fun onLocalUserStateUpdated(user: IUser)
    }

    companion object {
        private val TAG: String = UserMenu::class.java.name
    }
}
