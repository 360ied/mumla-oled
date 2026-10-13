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

package se.lublin.mumla.servers

import android.content.Context
import android.view.MenuItem
import se.lublin.humla.model.Server
import se.lublin.mumla.R

/**
 * Created by andrew on 11/05/14.
 */
class FavouriteServerAdapter(
    context: Context,
    servers: List<Server>,
    listener: FavouriteServerAdapterMenuListener
) : ServerAdapter<Server>(context, R.layout.server_list_row, servers) {

    private val mListener: FavouriteServerAdapterMenuListener = listener

    override fun getPopupMenuResource(): Int {
        return R.menu.popup_favourite_server
    }

    override fun onPopupItemClick(server: Server, menuItem: MenuItem): Boolean {
        val itemId = menuItem.itemId
        if (itemId == R.id.menu_server_edit) {
            mListener.editServer(server)
            return true
        } else if (itemId == R.id.menu_server_share) {
            mListener.shareServer(server)
            return true
        } else if (itemId == R.id.menu_server_delete) {
            mListener.deleteServer(server)
            return true
        }
        return false
    }

    interface FavouriteServerAdapterMenuListener {
        fun editServer(server: Server)
        fun shareServer(server: Server)
        fun deleteServer(server: Server)
    }
}
