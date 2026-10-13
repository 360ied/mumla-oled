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

package se.lublin.mumla.app

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import se.lublin.mumla.R

/**
 * Created by andrew on 01/08/13.
 */
class DrawerAdapter(context: Context, provider: DrawerDataProvider) : ArrayAdapter<DrawerAdapter.DrawerRow>(context, 0) {

    /**
     * Provides context for the drawer module.
     */
    interface DrawerDataProvider {

        /**
         * @return true if connected, false otherwise.
         */
        fun isConnected(): Boolean

        /**
         * @return The name of the connected server. If not connected, then null.
         */
        fun getConnectedServerName(): String?
    }

    open class DrawerRow(val id: Int, val title: String)

    class DrawerHeader(id: Int, title: String) : DrawerRow(id, title)

    class DrawerItem(id: Int, title: String, val icon: Int) : DrawerRow(id, title)

    private val mProvider: DrawerDataProvider = provider

    init {
        // TODO clean this up.
        add(DrawerHeader(HEADER_CONNECTED_SERVER, context.getString(R.string.drawer_not_connected)))
        add(DrawerItem(ITEM_SERVER, context.getString(R.string.drawer_server), R.drawable.ic_action_channels))
        add(DrawerItem(ITEM_PINNED_CHANNELS, context.getString(R.string.drawer_pinned), R.drawable.ic_action_comment))
        add(DrawerItem(ITEM_INFO, context.getString(R.string.information), R.drawable.ic_action_info_dark))
        add(DrawerItem(ITEM_ACCESS_TOKENS, context.getString(R.string.drawer_tokens), R.drawable.ic_action_save))
        add(DrawerHeader(HEADER_SERVERS, context.getString(R.string.drawer_header_servers)))
        add(DrawerItem(ITEM_FAVOURITES, context.getString(R.string.drawer_favorites), R.drawable.ic_action_favourite_on))
        add(DrawerHeader(HEADER_GENERAL, context.getString(R.string.general)))
        add(DrawerItem(ITEM_SETTINGS, context.getString(R.string.action_settings), R.drawable.ic_action_settings))
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var v = convertView
        val viewType = getItemViewType(position)
        if (v == null) {
            if (viewType == HEADER_TYPE) {
                v = LayoutInflater.from(context).inflate(R.layout.list_drawer_header, parent, false)
            } else if (viewType == ITEM_TYPE) {
                v = LayoutInflater.from(context).inflate(R.layout.list_drawer_item, parent, false)
            }
        }
        // NPE-exact: the original dereferenced v unconditionally.
        val row = v ?: throw NullPointerException("view")

        if (viewType == HEADER_TYPE) {
            // CCE-exact: the original cast unconditionally.
            val header = getItem(position) as DrawerHeader
            val title = row.findViewById(R.id.drawer_header_title) as TextView

            when (getItemId(position).toInt()) {
                HEADER_CONNECTED_SERVER -> {
                    if (mProvider.isConnected()) {
                        title.text = mProvider.getConnectedServerName()
                    } else {
                        title.text = header.title
                    }
                }
                else -> title.text = header.title
            }
        } else if (viewType == ITEM_TYPE) {
            // CCE-exact: the original cast unconditionally.
            val item = getItem(position) as DrawerItem
            val title = row.findViewById(R.id.drawer_item_title) as TextView
            val icon = row.findViewById(R.id.drawer_item_icon) as ImageView
            title.text = item.title
            icon.setImageResource(item.icon)

            val enabled = isEnabled(position)

            // Set text and icon color+alpha based on enabled/disabled state
            var textColor = title.currentTextColor
            textColor = textColor and 0x00FFFFFF // Clear alpha bits
            textColor = textColor or if (enabled) 0xFF000000.toInt() else 0x55000000 // Set alpha bits depending on whether the state is enabled or disabled
            title.setTextColor(textColor)
            icon.setColorFilter(textColor, PorterDuff.Mode.MULTIPLY)
        }

        return row
    }

    override fun getItemId(position: Int): Long {
        // NPE-exact: the original dereferenced getItem(position) unconditionally.
        val item = getItem(position) ?: throw NullPointerException("item")
        return item.id.toLong()
    }

    fun getItemWithId(id: Int): DrawerRow? {
        for (x in 0 until count) {
            val row = getItem(x)
            if (row != null && row.id == id) return row
        }
        return null
    }

    override fun isEnabled(position: Int): Boolean {
        val viewType = getItemViewType(position)
        if (viewType == ITEM_TYPE) {
            when (getItemId(position).toInt()) {
                ITEM_SERVER, ITEM_INFO, ITEM_ACCESS_TOKENS, ITEM_PINNED_CHANNELS ->
                    return mProvider.isConnected()
//                    return false;
                else -> return true
            }
        }
        return false // Default false for headers
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        if (item is DrawerHeader) {
            return HEADER_TYPE
        } else if (item is DrawerItem) {
            return ITEM_TYPE
        }
        return ITEM_TYPE
    }

    override fun getViewTypeCount(): Int {
        return 2
    }

    companion object {
        // Drawer rows, integer value is id
        const val HEADER_CONNECTED_SERVER = 0
        const val ITEM_SERVER = 1
        const val ITEM_PINNED_CHANNELS = 2
        const val ITEM_INFO = 3
        const val ITEM_ACCESS_TOKENS = 4
        const val HEADER_SERVERS = 5
        const val ITEM_FAVOURITES = 6
        const val HEADER_GENERAL = 9
        const val ITEM_SETTINGS = 10

        private const val HEADER_TYPE = 0
        private const val ITEM_TYPE = 1
    }
}
