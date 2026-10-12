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

package se.lublin.mumla.app;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import se.lublin.humla.HumlaService;
import se.lublin.humla.model.Server;
import se.lublin.mumla.service.MumlaService;

/**
 * Constructs an intent for connection to a MumlaService and executes it.
 *
 * Migration slice 2: the intent carries only the server id (plus the full
 * parcel for unsaved ad-hoc servers). The service loads certificates, tokens,
 * history, and settings itself and applies them as a ConfigDelta — the old
 * 20-extra bundle is dead. Created by andrew on 20/08/14.
 */
public class ServerConnectTask extends AsyncTask<Server, Void, Intent> {
    private final Context mContext;

    public ServerConnectTask(Context context) {
        // Application context: this task can outlive the activity across
        // rotation, and every use here (intents, startService) is safe on
        // the application context.
        mContext = context.getApplicationContext();
    }

    @Override
    protected Intent doInBackground(Server... params) {
        if (params == null || params.length == 0 || params[0] == null) {
            return null;
        }
        Server server = params[0];

        Intent connectIntent = new Intent(mContext, MumlaService.class);
        connectIntent.putExtra(MumlaService.EXTRA_SERVER_ID, server.getId());
        if (!server.isSaved()) {
            connectIntent.putExtra(MumlaService.EXTRA_SERVER, server);
        }
        connectIntent.setAction(HumlaService.ACTION_CONNECT);
        return connectIntent;
    }

    @Override
    protected void onPostExecute(Intent intent) {
        super.onPostExecute(intent);
        if (intent != null) {
            mContext.startService(intent);
        }
    }
}
