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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.Server
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.R
import se.lublin.mumla.channel.comment.AbstractCommentFragment
import se.lublin.mumla.channel.comment.ChannelDescriptionFragment
import se.lublin.mumla.db.MumlaDatabase

/**
 * Created by andrew on 22/11/15.
 */
class ChannelMenu(
    context: Context,
    channel: IChannel,
    service: IHumlaService,
    database: MumlaDatabase,
    fragmentManager: FragmentManager
) : PermissionsPopupMenu.IOnMenuPrepareListener, PopupMenu.OnMenuItemClickListener {
    private val mContext: Context = context
    private val mChannel: IChannel = channel
    private val mService: IHumlaService = service
    private val mDatabase: MumlaDatabase = database
    private val mFragmentManager: FragmentManager = fragmentManager

    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        menu.findItem(R.id.context_channel_view_description)
            .setVisible(
                mChannel.description != null ||
                    mChannel.descriptionHash != null
            )
        val server: Server? = mService.getTargetServer()
        if (server != null) {
            menu.findItem(R.id.context_channel_pin)
                .setChecked(mDatabase.isChannelPinned(server.id, mChannel.id))
        }
        if (mService.isConnected()) {
            var ourChan: IChannel? = null
            try {
                ourChan = mService.HumlaSession().getSessionChannel()
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onMenuPrepare: $e")
            }
            if (ourChan != null) {
                menu.findItem(R.id.context_channel_link).setChecked(mChannel.links.any { it == ourChan })
            }
            val listenItem = menu.findItem(R.id.context_channel_listen)
            if (listenItem != null) {
                val isListening = mChannel.isListening
                listenItem.setChecked(isListening)
                listenItem.setTitle(if (isListening) R.string.context_channel_stop_listen else R.string.context_channel_listen)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (!mService.isConnected()) {
            return false
        }

        val itemId = item.itemId
        if (itemId == R.id.context_channel_join) {
            mService.HumlaSession().joinChannel(mChannel.id)
        } else if (itemId == R.id.context_channel_view_description) {
            val commentArgs = Bundle()
            commentArgs.putInt(ChannelDescriptionFragment.ARG_CHANNEL, mChannel.id)
            commentArgs.putString(AbstractCommentFragment.ARG_COMMENT, mChannel.description)
            commentArgs.putBoolean(AbstractCommentFragment.ARG_EDITING, false)
            val commentFragment = ChannelDescriptionFragment()
            commentFragment.arguments = commentArgs
            commentFragment.show(mFragmentManager, ChannelDescriptionFragment::class.java.name)
        } else if (itemId == R.id.context_channel_pin) {
            val targetServer = mService.getTargetServer()
            if (targetServer == null) {
                return false
            }
            val serverId = targetServer.id
            val pinned = mDatabase.isChannelPinned(serverId, mChannel.id)
            if (!pinned) {
                mDatabase.addPinnedChannel(serverId, mChannel.id)
            } else {
                mDatabase.removePinnedChannel(serverId, mChannel.id)
            }
        } else if (itemId == R.id.context_channel_link) {
            val channel = mService.HumlaSession().getSessionChannel()
            if (!item.isChecked) {
                mService.HumlaSession().linkChannels(channel, mChannel)
            } else {
                mService.HumlaSession().unlinkChannels(channel, mChannel)
            }
        } else if (itemId == R.id.context_channel_listen) {
            val isListening = mChannel.isListening
            mService.HumlaSession().setListeningChannel(mChannel.id, !isListening)
        } else if (itemId == R.id.context_channel_unlink_all) {
            mService.HumlaSession().unlinkAllChannels(mChannel)
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
                    if (!mService.isConnected()) {
                        return@setPositiveButton
                    }
                    val session: IHumlaSession = mService.HumlaSession()
                    // Unregister any existing voice target.
                    if (session.getVoiceTargetMode() == VoiceTargetMode.WHISPER) {
                        session.unregisterWhisperTarget(session.getVoiceTargetId())
                    }
                    val channelTarget = WhisperTargetChannel(mChannel, linkedBox.isChecked, subchannelBox.isChecked, null)
                    val id = session.registerWhisperTarget(channelTarget)
                    if (id > 0.toByte()) {
                        session.setVoiceTargetId(id)
                    } else {
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
            R.menu.context_channel, this, this, mChannel, mService
        )
        popupMenu.show()
    }

    companion object {
        private val TAG: String = ChannelMenu::class.java.name
    }
}
