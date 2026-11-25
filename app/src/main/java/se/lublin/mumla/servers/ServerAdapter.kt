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
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.humla.net.SrvResolver
import se.lublin.mumla.R

/**
 * Created by andrew on 05/05/14.
 */
abstract class ServerAdapter<E : Server>(
    context: Context,
    viewResource: Int,
    servers: List<E>,
    /** One server's ping; injected by tests, the real one in production. */
    private val ping: suspend (Server, SrvResolver) -> ServerInfoResponse = ::pingServer,
    /** Where results are stored and the list is notified; the adapter's dispatcher. */
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Where the blocking ping runs; the 50-ping cap lives here. */
    private val pingDispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(MAX_ACTIVE_PINGS),
) : ArrayAdapter<E>(context, 0, servers) {
    // SupervisorJob: a failed ping must not cancel the adapter's other pings.
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val resolver = SrvResolver()
    // Main-confined: written by the ping coroutines after withContext returns.
    private val mInfoResponses = HashMap<Server, ServerInfoResponse>()
    private val mViewResource: Int = viewResource

    /**
     * Releases the per-adapter ping scope. Call when the adapter is replaced
     * (updateServers builds a new one every onResume) or torn down; pings
     * still in flight are cancelled and their results dropped.
     */
    fun shutdown() {
        scope.cancel()
    }

    override fun getItemId(position: Int): Long {
        // NPE-exact: the original dereferenced getItem(position) unconditionally.
        val item = getItem(position) ?: throw NullPointerException("item")
        return item.id
    }

    override fun getView(position: Int, v: View?, parent: ViewGroup): View {
        var view = v

        if (v == null) {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            view = inflater.inflate(mViewResource, parent, false)
        }
        // NPE-exact: inflate never returns null here; the original dereferenced view.
        val row = view ?: throw NullPointerException("view")

        // NPE-exact: the original dereferenced getItem(position) unconditionally.
        val server = getItem(position) ?: throw NullPointerException("item")

        val infoResponse = mInfoResponses[server]
        // If there is a null value for the server info (rather than none at all), the request must have failed.
        val requestExists = infoResponse != null
        val requestFailure = infoResponse != null && infoResponse.isDummy

        val nameText = row.findViewById(R.id.server_row_name) as TextView
        val userText = row.findViewById(R.id.server_row_user) as TextView?
        val addressText = row.findViewById(R.id.server_row_address) as TextView?

        nameText.text = server.getName()

        if (userText != null) userText.text = server.username
        if (addressText != null) {
            addressText.text = server.host +
                (if (server.port == 0) "" else ":" + server.port)
        }

        val moreButton = row.findViewById(R.id.server_row_more) as ImageView?
        if (moreButton != null) {
            moreButton.setOnClickListener { onServerOptionsClick(server, moreButton) }
        }

        val serverVersionText = row.findViewById(R.id.server_row_version_status) as TextView
        val serverLatencyText = row.findViewById(R.id.server_row_latency) as TextView
        val serverUsersText = row.findViewById(R.id.server_row_usercount) as TextView
        val serverInfoProgressBar = row.findViewById(R.id.server_row_ping_progress) as ProgressBar

        serverVersionText.visibility = if (!requestExists) View.INVISIBLE else View.VISIBLE
        serverUsersText.visibility = if (!requestExists) View.INVISIBLE else View.VISIBLE
        serverLatencyText.visibility = if (!requestExists) View.INVISIBLE else View.VISIBLE
        serverInfoProgressBar.visibility = if (!requestExists) View.VISIBLE else View.INVISIBLE

        if (infoResponse != null && !requestFailure) {
            serverVersionText.text = context.getString(R.string.online) + " (" + infoResponse.getVersionString() + ")"
            serverUsersText.text = infoResponse.currentUsers.toString() + "/" + infoResponse.maximumUsers
            serverLatencyText.text = infoResponse.latency.toString() + "ms"
        } else if (requestFailure) {
            serverVersionText.setText(R.string.offline)
            serverUsersText.text = ""
            serverLatencyText.text = ""
        }

        // Ping server if available. A row bound again before the result
        // arrives starts another ping, as the old ping-pool path did.
        if (infoResponse == null) {
            scope.launch {
                val result = withContext(pingDispatcher) { ping(server, resolver) }
                // Back on the adapter's dispatcher: store and repaint.
                mInfoResponses[server] = result
                notifyDataSetChanged()
            }
        }

        return row
    }

    private fun onServerOptionsClick(server: Server, optionsButton: View) {
        val popupMenu = PopupMenu(context, optionsButton)
        popupMenu.inflate(getPopupMenuResource())
        popupMenu.setOnMenuItemClickListener { menuItem -> onPopupItemClick(server, menuItem) }
        popupMenu.show()
    }

    abstract fun getPopupMenuResource(): Int
    abstract fun onPopupItemClick(server: Server, menuItem: MenuItem): Boolean

    companion object {
        /** Concurrent pings per adapter; carried over from the ping pool. */
        internal const val MAX_ACTIVE_PINGS = 50
    }
}
