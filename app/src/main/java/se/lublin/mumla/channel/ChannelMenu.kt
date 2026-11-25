/*
 * Copyright (C) 2015 Andrew Comminos
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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.VoiceTargetMode
import se.lublin.humla.state.WhisperTarget
import se.lublin.humla.state.voiceTargetMode
import se.lublin.mumla.R
import se.lublin.mumla.channel.comment.AbstractCommentFragment
import se.lublin.mumla.channel.comment.ChannelDescriptionFragment

/**
 * The context menu of one channel row. It reads the channel from the latest
 * state and calls the screen's ViewModel for every action.
 * Created by andrew on 22/11/15.
 */
class ChannelMenu(
    context: Context,
    private val snapshot: ChannelInfo,
    private val viewModel: ChannelListViewModel,
    private val fragmentManager: FragmentManager,
) : PermissionsPopupMenu.IOnMenuPrepareListener, PopupMenu.OnMenuItemClickListener {
    private val mContext: Context = context

    /** The channel as it is now; the tapped row's values once it is removed. */
    private val channel: ChannelInfo
        get() = viewModel.channel(snapshot.id) ?: snapshot

    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        menu.findItem(R.id.context_channel_view_description)
            .setVisible(
                channel.description != null ||
                    channel.descriptionHash != null
            )
        menu.findItem(R.id.context_channel_pin)
            .setChecked(viewModel.isChannelPinned(channel.id))
        if (viewModel.selfChannelId() != null) {
            val ourChan = viewModel.selfChannelId()
            menu.findItem(R.id.context_channel_link).setChecked(
                channel.links.contains(ourChan)
            )
            val listenItem = menu.findItem(R.id.context_channel_listen)
            if (listenItem != null) {
                val isListening = viewModel.isListening(channel.id)
                listenItem.setChecked(isListening)
                listenItem.setTitle(
                    if (isListening) R.string.context_channel_stop_listen
                    else R.string.context_channel_listen
                )
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.context_channel_join) {
            viewModel.joinChannel(channel.id)
        } else if (itemId == R.id.context_channel_view_description) {
            val commentArgs = Bundle()
            commentArgs.putInt(ChannelDescriptionFragment.ARG_CHANNEL, channel.id)
            commentArgs.putBoolean(AbstractCommentFragment.ARG_EDITING, false)
            val commentFragment = ChannelDescriptionFragment()
            commentFragment.arguments = commentArgs
            commentFragment.show(fragmentManager, ChannelDescriptionFragment::class.java.name)
        } else if (itemId == R.id.context_channel_pin) {
            viewModel.setChannelPinned(channel.id, !item.isChecked)
        } else if (itemId == R.id.context_channel_link) {
            val ourChan = viewModel.selfChannelId() ?: return false
            if (!item.isChecked) {
                viewModel.linkChannels(ourChan, channel.id)
            } else {
                viewModel.unlinkChannels(ourChan, channel.id)
            }
        } else if (itemId == R.id.context_channel_listen) {
            viewModel.setListening(channel.id, !viewModel.isListening(channel.id))
        } else if (itemId == R.id.context_channel_unlink_all) {
            viewModel.unlinkAllChannels(channel.id)
        } else if (itemId == R.id.context_channel_shout) {
            val layout = LinearLayout(mContext)
            layout.orientation = LinearLayout.VERTICAL
            val subchannelBox = CheckBox(mContext)
            subchannelBox.setText(R.string.shout_include_subchannels)
            layout.addView(subchannelBox)
            val linkedBox = CheckBox(mContext)
            linkedBox.setText(R.string.shout_include_linked)
            layout.addView(linkedBox)
            MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.shout_configure)
                .setView(layout)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    val target = WhisperTarget(
                        channelId = channel.id,
                        includeLinked = linkedBox.isChecked,
                        includeSubchannels = subchannelBox.isChecked,
                        group = null,
                    )
                    if (!viewModel.shout(target)) {
                        Toast.makeText(mContext, R.string.shout_failed, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            return false
        }
        return true
    }

    fun showPopup(anchor: View) {
        val popupMenu = PermissionsPopupMenu(
            mContext, anchor,
            R.menu.context_channel, this, this,
            permissions = viewModel.permissionsFor(channel.id),
            requestPermissions = { viewModel.requestPermissions(channel.id) },
        )
        popupMenu.show()
    }
}
