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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import se.lublin.humla.model.Server
import se.lublin.mumla.R

/**
 * Created by andrew on 05/05/14.
 */
abstract class ServerAdapter<E : Server>(context: Context, viewResource: Int, servers: List<E>) :
    ArrayAdapter<E>(context, 0, servers) {
    private val mInfoResponses = ConcurrentHashMap<Server, ServerInfoResponse>()
    private val mPingExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_ACTIVE_PINGS)
    private val mViewResource: Int = viewResource

    /**
     * Releases the per-adapter ping pool. Call when the adapter is replaced
     * (updateServers builds a new one every onResume) or torn down; queued
     * pings are best-effort and die with the pool.
     */
    fun shutdown() {
        mPingExecutor.shutdownNow()
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

        // Ping server if available
        if (infoResponse == null) {
            val task = object : ServerInfoTask() {
                override fun onPostExecute(result: ServerInfoResponse?) {
                    super.onPostExecute(result)
                    // NPE-exact: ConcurrentHashMap.put rejects null; the original
                    // dereferenced result unconditionally.
                    mInfoResponses[server] = result ?: throw NullPointerException("result")
                    notifyDataSetChanged()
                }
            }
            task.executeOnExecutor(mPingExecutor, server)
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
        private const val MAX_ACTIVE_PINGS = 50
    }
}
