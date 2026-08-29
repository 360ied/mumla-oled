/*
 * Copyright (C) 2026 Mumla Contributors
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

package se.lublin.mumla.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.data.model.ServerCardUiState
import se.lublin.mumla.db.MumlaDatabase

class ServerRepository(
    private val database: MumlaDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _servers = MutableStateFlow<List<ServerCardUiState>>(emptyList())
    val servers: StateFlow<List<ServerCardUiState>> = _servers.asStateFlow()

    suspend fun refreshServers(activeConnectedHost: String? = null, activeConnectedPort: Int = -1) {
        withContext(ioDispatcher) {
            val dbServers: List<Server> = database.servers ?: emptyList()
            val mapped = dbServers.map { s ->
                val isConnected = activeConnectedHost != null &&
                        s.host.equals(activeConnectedHost, ignoreCase = true) &&
                        (s.port == activeConnectedPort || (s.port == 0 && activeConnectedPort == 64738))
                ServerCardUiState(
                    id = s.id,
                    name = s.name ?: s.host ?: "Server",
                    host = s.host ?: "",
                    port = if (s.port == 0) 64738 else s.port,
                    username = s.username ?: "",
                    isConnected = isConnected
                )
            }
            _servers.value = mapped
        }
    }

    suspend fun addServer(server: Server) {
        withContext(ioDispatcher) {
            database.addServer(server)
            refreshServers()
        }
    }

    suspend fun updateServer(server: Server) {
        withContext(ioDispatcher) {
            database.updateServer(server)
            refreshServers()
        }
    }

    suspend fun deleteServer(server: Server) {
        withContext(ioDispatcher) {
            database.removeServer(server)
            refreshServers()
        }
    }

    suspend fun getServerById(id: Long): Server? {
        return withContext(ioDispatcher) {
            val dbServers: List<Server> = database.servers ?: emptyList()
            dbServers.firstOrNull { it.id == id }
        }
    }
}
