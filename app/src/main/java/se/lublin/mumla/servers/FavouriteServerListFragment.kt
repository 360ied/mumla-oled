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
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.GridView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseProvider

/**
 * Displays a list of servers, and allows the user to connect and edit them.
 * @author morlunk
 *
 */
class FavouriteServerListFragment : Fragment(), OnItemClickListener,
    FavouriteServerAdapter.FavouriteServerAdapterMenuListener {

    private var mConnectHandler: ServerConnectHandler? = null
    private var mDatabaseProvider: DatabaseProvider? = null
    private var mServerGrid: GridView? = null
    private var mServerAdapter: ServerAdapter<Server>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        try {
            mConnectHandler = context as ServerConnectHandler
            mDatabaseProvider = context as DatabaseProvider
        } catch (e: ClassCastException) {
            throw ClassCastException(context.toString() + " must implement ServerConnectHandler!")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_server_list, container, false)
        // NPE-exact: the original dereferenced the grid lookups unconditionally.
        val grid = view.findViewById(R.id.server_list_grid) as GridView?
            ?: throw NullPointerException("server_list_grid")
        mServerGrid = grid
        grid.onItemClickListener = this
        grid.emptyView = view.findViewById(R.id.server_list_grid_empty)

        registerForContextMenu(grid)
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_server_list, menu)
    }

    override fun onResume() {
        super.onResume()
        updateServers()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.menu_add_server_item) {
            addServer()
            return true
        } else if (itemId == R.id.menu_quick_connect) {
            ServerEditFragment.createServerEditDialog(null, ServerEditFragment.Action.CONNECT_ACTION, true)
                .show(fragmentManager ?: throw NullPointerException("fragmentManager"), "serverInfo")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun addServer() {
        ServerEditFragment.createServerEditDialog(null, ServerEditFragment.Action.ADD_ACTION, false)
            .show(fragmentManager ?: throw NullPointerException("fragmentManager"), "serverInfo")
    }

    override fun editServer(server: Server) {
        ServerEditFragment.createServerEditDialog(server, ServerEditFragment.Action.EDIT_ACTION, false)
            .show(fragmentManager ?: throw NullPointerException("fragmentManager"), "serverInfo")
    }

    override fun shareServer(server: Server) {
        // Build Mumble server URL
        val serverUrl = "mumble://" + server.host +
            (if (server.port == 0) "" else ":" + server.port) + "/"

        val intent = Intent()
        intent.action = Intent.ACTION_SEND
        intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.shareMessage, serverUrl))
        intent.type = "text/plain"
        startActivity(intent)
    }

    override fun deleteServer(server: Server) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.confirm_delete_server)
            .setPositiveButton(R.string.delete) { _, _ ->
                // NPE-exact: the original dereferenced both fields unconditionally.
                val provider = mDatabaseProvider ?: throw NullPointerException("mDatabaseProvider")
                val adapter = mServerAdapter ?: throw NullPointerException("mServerAdapter")
                provider.getDatabase().removeServer(server)
                adapter.remove(server)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun updateServers() {
        val servers = getServers()
        // NPE-exact: the original passed getActivity() straight through.
        val adapter = FavouriteServerAdapter(activity ?: throw NullPointerException("activity"), servers, this)
        mServerAdapter = adapter
        val grid = mServerGrid ?: throw NullPointerException("mServerGrid")
        grid.adapter = adapter
    }

    fun getServers(): List<Server> {
        // NPE-exact: the original dereferenced mDatabaseProvider unconditionally.
        val provider = mDatabaseProvider ?: throw NullPointerException("mDatabaseProvider")
        return provider.getDatabase().getServers()
    }

    override fun onItemClick(arg0: AdapterView<*>?, arg1: View?, arg2: Int, arg3: Long) {
        // NPE-exact: the original dereferenced both fields unconditionally.
        val handler = mConnectHandler ?: throw NullPointerException("mConnectHandler")
        val adapter = mServerAdapter ?: throw NullPointerException("mServerAdapter")
        val item = adapter.getItem(arg2) ?: throw NullPointerException("item")
        handler.connectToServer(item)
    }

    interface ServerConnectHandler {
        fun connectToServer(server: Server)
    }
}
