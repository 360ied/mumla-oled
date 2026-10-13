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
import android.util.Log
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaObserver

/**
 * Encapsulates a menu requiring permissions.
 * Created by andrew on 19/11/15.
 */
class PermissionsPopupMenu(
    context: Context,
    anchor: View,
    menuRes: Int,
    enforcer: IOnMenuPrepareListener,
    itemClickListener: PopupMenu.OnMenuItemClickListener,
    channel: IChannel,
    service: IHumlaService
) : PopupMenu.OnDismissListener {
    private val mContext: Context = context
    private val mChannel: IChannel = channel
    private val mService: IHumlaService = service
    private val mMenu: PopupMenu = PopupMenu(context, anchor)
    private val mPrepareListener: IOnMenuPrepareListener = enforcer

    private val mPermissionsObserver = object : HumlaObserver() {
        override fun onChannelPermissionsUpdated(channel: IChannel?) {
            if (mChannel == channel) {
                mPrepareListener.onMenuPrepare(mMenu.menu, getPermissions())
            }
        }
    }

    init {
        mMenu.inflate(menuRes)
        mMenu.setOnDismissListener(this)
        mMenu.setOnMenuItemClickListener(itemClickListener)
    }

    private fun getPermissions(): Int {
        if (mService.isConnected()) {
            return if (mChannel.id == 0) {
                // TOCTOU: popup racing teardown throws on acquisition.
                try {
                    mService.HumlaSession().getPermissions()
                } catch (e: HumlaDisconnectedException) {
                    Log.d(TAG, "permissions with torn-down session: $e")
                    0
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "permissions with torn-down session: $e")
                    0
                }
            } else {
                mChannel.permissions
            }
        }
        return 0
    }

    companion object {
        private val TAG: String = PermissionsPopupMenu::class.java.simpleName
    }

    fun show() {
        mService.registerObserver(mPermissionsObserver)
        if (getPermissions() == 0) {
            // onMenuPrepare will be called once more once permissions have loaded.
            if (mService.isConnected()) {
                // Same TOCTOU as getPermissions() above.
                try {
                    mService.HumlaSession().requestPermissions(mChannel.id)
                } catch (e: HumlaDisconnectedException) {
                    Log.d(TAG, "requestPermissions with torn-down session: $e")
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "requestPermissions with torn-down session: $e")
                }
            }
        } else {
            mPrepareListener.onMenuPrepare(mMenu.menu, getPermissions())
        }
        mMenu.show()
    }

    fun dismiss() {
        mMenu.dismiss()
    }

    override fun onDismiss(popupMenu: PopupMenu) {
        mService.unregisterObserver(mPermissionsObserver)
    }

    /**
     * An enforcer of channel permissions, disabling menu items as appropriate.
     */
    interface IOnMenuPrepareListener {
        fun onMenuPrepare(menu: Menu, permissions: Int)
    }
}
