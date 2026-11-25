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
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Encapsulates a menu requiring permissions. The permission word is read from
 * the live state flow the screen hands in, which replaces the old observer:
 * the menu re-prepares whenever the state updates, and asks for the channel's
 * permissions when it has none yet.
 * Created by andrew on 19/11/15.
 */
class PermissionsPopupMenu(
    context: Context,
    anchor: View,
    menuRes: Int,
    private val enforcer: IOnMenuPrepareListener,
    itemClickListener: PopupMenu.OnMenuItemClickListener,
    private val permissions: StateFlow<Int>,
    private val requestPermissions: () -> Unit,
) : PopupMenu.OnDismissListener {
    private val mMenu: PopupMenu = PopupMenu(context, anchor)
    private var scope: CoroutineScope? = null

    init {
        mMenu.inflate(menuRes)
        mMenu.setOnDismissListener(this)
        mMenu.setOnMenuItemClickListener(itemClickListener)
    }

    fun show() {
        // A second show without a dismiss replaces the collector, not adds one.
        scope?.cancel()
        val menuScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = menuScope
        menuScope.launch {
            permissions.collect { current ->
                enforcer.onMenuPrepare(mMenu.menu, current)
            }
        }
        if (permissions.value == 0) {
            requestPermissions()
        }
        mMenu.show()
    }

    fun dismiss() {
        mMenu.dismiss()
    }

    override fun onDismiss(popupMenu: PopupMenu) {
        scope?.cancel()
        scope = null
    }

    /**
     * An enforcer of channel permissions, disabling menu items as appropriate.
     */
    interface IOnMenuPrepareListener {
        fun onMenuPrepare(menu: Menu, permissions: Int)
    }
}
