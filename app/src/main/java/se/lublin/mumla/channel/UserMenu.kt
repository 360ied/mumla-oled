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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.net.Permissions
import se.lublin.humla.state.UserInfo
import se.lublin.mumla.R
import se.lublin.mumla.channel.comment.AbstractCommentFragment
import se.lublin.mumla.channel.comment.UserCommentFragment

/**
 * The context menu of one user row, rendered from the user's current values
 * and acting through the screen's ViewModel.
 * Created by andrew on 19/11/15.
 */
class UserMenu(
    context: Context,
    private val user: UserInfo,
    private val viewModel: ChannelListViewModel,
    private val fragmentManager: FragmentManager,
) : PermissionsPopupMenu.IOnMenuPrepareListener, PopupMenu.OnMenuItemClickListener {
    private val mContext: Context = context

    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        // Use permission data to determine the actions available.
        val self = user.session == viewModel.selfSession()
        val perms = viewModel.rootPermissions()
        val channel = user.channelId?.let { viewModel.channel(it) } ?: return
        val channelPerms = if (channel.id != 0) channel.permissions else perms
        val comment = user.comment
        val commentHash = user.commentHash
        val userHash = user.certHash

        menu.findItem(R.id.context_kick).setVisible(
            !self && perms and (Permissions.Kick or Permissions.Ban or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_ban).setVisible(
            !self && perms and (Permissions.Ban or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_mute).setVisible(
            channelPerms and (Permissions.Write or Permissions.MuteDeafen) > 0 &&
                (!self || user.muted || user.suppressed)
        )
        menu.findItem(R.id.context_deafen).setVisible(
            channelPerms and (Permissions.Write or Permissions.MuteDeafen) > 0 &&
                (!self || user.deafened)
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
            (user.userId ?: -1) < 0 &&
                (userHash != null && userHash.isNotEmpty()) &&
                perms and ((if (self) Permissions.SelfRegister else Permissions.Register) or Permissions.Write) > 0
        )
        menu.findItem(R.id.context_local_mute).setVisible(!self)
        menu.findItem(R.id.context_ignore_messages).setVisible(!self)
        // Highlight toggles
        menu.findItem(R.id.context_mute).setChecked(user.muted || user.suppressed)
        menu.findItem(R.id.context_deafen).setChecked(user.deafened)
        menu.findItem(R.id.context_priority).setChecked(user.prioritySpeaker)
        menu.findItem(R.id.context_local_mute).setChecked(user.localMuted)
        menu.findItem(R.id.context_ignore_messages).setChecked(user.localIgnored)
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
                    viewModel.kickBanUser(user.session, reasonField.text.toString(), isBan)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (itemId == R.id.context_mute) {
            viewModel.setMuteDeafState(
                user.session,
                !(user.muted || user.suppressed),
                user.deafened,
            )
        } else if (itemId == R.id.context_deafen) {
            viewModel.setMuteDeafState(user.session, user.muted, !user.deafened)
        } else if (itemId == R.id.context_move) {
            showChannelMoveDialog()
        } else if (itemId == R.id.context_priority) {
            viewModel.setPrioritySpeaker(user.session, !user.prioritySpeaker)
        } else if (itemId == R.id.context_local_mute) {
            viewModel.setLocalMute(user.session, !user.localMuted)
        } else if (itemId == R.id.context_ignore_messages) {
            viewModel.setLocalIgnore(user.session, !user.localIgnored)
        } else if (itemId == R.id.context_change_comment) {
            showUserComment(true)
        } else if (itemId == R.id.context_view_comment) {
            showUserComment(false)
        } else if (itemId == R.id.context_reset_comment) {
            MaterialAlertDialogBuilder(mContext)
                .setMessage(mContext.getString(R.string.confirm_reset_comment, user.name))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    viewModel.setUserComment(user.session, "")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (itemId == R.id.context_register) {
            viewModel.registerUser(user.session)
        } else {
            return false
        }
        return true
    }

    private fun showUserComment(edit: Boolean) {
        val args = Bundle()
        args.putInt(UserCommentFragment.ARG_SESSION, user.session)
        args.putBoolean(AbstractCommentFragment.ARG_EDITING, edit)
        val fragment = UserCommentFragment()
        fragment.arguments = args
        fragment.show(fragmentManager, UserCommentFragment::class.java.name)
    }

    private fun showChannelMoveDialog() {
        val channels = viewModel.channelsInTreeOrder()
        val channelNames = arrayOfNulls<CharSequence>(channels.size)
        for (i in channels.indices) {
            channelNames[i] = channels[i].name
        }
        MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.user_menu_move)
            .setItems(channelNames) { _, which ->
                viewModel.moveUserToChannel(user.session, channels[which].id)
            }
            .show()
    }

    fun showPopup(anchor: View) {
        val channelId = user.channelId ?: return
        val popupMenu = PermissionsPopupMenu(
            mContext, anchor,
            R.menu.context_user, this, this,
            permissions = viewModel.permissionsFor(channelId),
            requestPermissions = { viewModel.requestPermissions(channelId) },
        )
        popupMenu.show()
    }
}
