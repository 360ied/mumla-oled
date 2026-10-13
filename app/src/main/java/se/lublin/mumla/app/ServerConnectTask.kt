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
import android.content.Intent
import android.os.AsyncTask
import se.lublin.humla.HumlaService
import se.lublin.humla.model.Server
import se.lublin.mumla.service.MumlaService

/**
 * Constructs an intent for connection to a MumlaService and executes it.
 *
 * Migration slice 2: the intent carries only the server id (plus the full
 * parcel for unsaved ad-hoc servers). The service loads certificates, tokens,
 * history, and settings itself and applies them as a ConfigDelta — the old
 * 20-extra bundle is dead. Created by andrew on 20/08/14.
 */
class ServerConnectTask(context: Context) : AsyncTask<Server, Void, Intent>() {
    private val mContext: Context = context.applicationContext

    init {
        // Application context: this task can outlive the activity across
        // rotation, and every use here (intents, startService) is safe on
        // the application context.
    }

    override fun doInBackground(vararg params: Server?): Intent? {
        if (params.isEmpty() || params[0] == null) {
            return null
        }
        // NPE-exact: the original dereferenced params[0] unconditionally.
        val server = params[0] ?: throw NullPointerException("params[0]")

        val connectIntent = Intent(mContext, MumlaService::class.java)
        connectIntent.putExtra(MumlaService.EXTRA_SERVER_ID, server.id)
        if (!server.isSaved()) {
            connectIntent.putExtra(MumlaService.EXTRA_SERVER, server)
        }
        connectIntent.action = HumlaService.ACTION_CONNECT
        return connectIntent
    }

    override fun onPostExecute(intent: Intent?) {
        super.onPostExecute(intent)
        if (intent != null) {
            mContext.startService(intent)
        }
    }
}
