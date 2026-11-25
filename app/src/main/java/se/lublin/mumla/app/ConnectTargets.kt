/*
 * Copyright (C) 2026 Brian Zhu
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app

import android.content.Context
import com.google.protobuf.ByteString
import java.io.File
import java.io.IOException
import kotlinx.collections.immutable.toPersistentList
import se.lublin.humla.client.ClientCertificate
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.client.ServerEndpoint
import se.lublin.humla.client.TrustStoreRef
import se.lublin.humla.model.Server
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.util.MumlaTrustStore

/**
 * Builds the immutable [ConnectTarget] for a server. The connect-time
 * mapping is unchanged: a saved server is re-read from the database (still
 * the source of truth), and the certificate, access tokens, trust store,
 * force-TCP flag and client name are frozen at connect time.
 */
object ConnectTargets {

    /**
     * @return the target, or null when the server is gone from the database
     * or has no host (the old connect path logged and ignored it).
     */
    suspend fun build(
        context: Context,
        database: MumlaRoomDatabase,
        settings: Settings,
        server: Server,
    ): ConnectTarget? {
        val saved = if (server.isSaved()) {
            database.getServers().firstOrNull { it.id == server.id }
        } else {
            server
        }
        val host = saved?.host ?: return null
        val accessTokens = database.getAccessTokens(saved.id)
        val certificate = if (settings.isUsingCertificate) {
            val certificateId = settings.defaultCertificate
            database.getCertificateData(certificateId)?.let { data ->
                data to database.getCertificatePassword(certificateId)
            }
        } else {
            null
        }
        val trustStorePath = MumlaTrustStore.getTrustStorePath(context)
        val trustStore = if (trustStorePath != null &&
            isTrustStorePathAllowed(context.filesDir, trustStorePath)
        ) {
            TrustStoreRef(
                path = trustStorePath,
                password = MumlaTrustStore.getTrustStorePassword(),
                format = MumlaTrustStore.getTrustStoreFormat(),
            )
        } else {
            null
        }
        return ConnectTarget(
            server = ServerEndpoint(
                serverId = saved.id,
                name = saved.name,
                host = host,
                port = saved.port,
                username = saved.username,
                password = saved.password,
            ),
            certificate = certificate?.let { (data, password) ->
                ClientCertificate(ByteString.copyFrom(data), password)
            },
            trustStore = trustStore,
            forceTcp = settings.isTcpForced,
            clientName = context.getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
            accessTokens = accessTokens.toPersistentList(),
        )
    }

    /**
     * A `.bks` file under the app's files directory, or no path at all.
     */
    fun isTrustStorePathAllowed(filesDir: File?, path: String?): Boolean {
        if (path == null) {
            return true
        }
        if (!path.endsWith(".bks", ignoreCase = true)) {
            return false
        }
        try {
            val base = (filesDir ?: return false).canonicalFile.path
            val raw = File(path)
            val candidate = (if (raw.isAbsolute) raw else File(filesDir, path)).canonicalFile.path
            return candidate.startsWith(base + File.separator)
        } catch (e: IOException) {
            return false
        }
    }
}
