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

package se.lublin.mumla.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.Session
import se.lublin.humla.client.SessionStats
import se.lublin.humla.state.ServerState
import se.lublin.humla.state.SessionState

/** One render of the server-info screen. */
data class ServerInfoUiState(
    /** True while a live session exists; every other field is post-sync only. */
    val connected: Boolean = false,
    val release: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val tcpLatencyMicros: Long? = null,
    val udpLatencyMicros: Long? = null,
    val host: String? = null,
    val port: Int? = null,
    val codecName: String? = null,
    val maxBandwidth: Int? = null,
    val currentBandwidth: Int? = null,
)

/**
 * Server info: the connection's [ServerState] plus the live session's
 * statistics and resolved endpoint. Replaces the fragment's 1 Hz polling
 * executor and main-thread handler: every value is a flow emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerInfoViewModel(
    clientState: StateFlow<ClientState>,
    sessionState: StateFlow<SessionState>,
) : ViewModel() {

    /** The parts that exist only while a session lives. */
    private data class LiveSession(
        val stats: SessionStats,
        val host: String,
        val port: Int,
    )

    private val live: Flow<LiveSession?> = clientState
        .map { (it as? ClientState.Connected)?.session }
        .distinctUntilChanged()
        .flatMapLatest { session: Session? ->
            if (session == null) {
                flowOf(null)
            } else {
                session.stats.map { stats ->
                    LiveSession(stats, session.transport.remoteHost, session.transport.remotePort)
                }
            }
        }

    val state: StateFlow<ServerInfoUiState> = combine(
        sessionState.map { it.server }.distinctUntilChanged(),
        live,
    ) { server, current -> uiState(server, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerInfoUiState())

    private fun uiState(server: ServerState, live: LiveSession?): ServerInfoUiState {
        val info = server.info
        // Today's release formatting: an explicit release string wins, then
        // the (v2-encoded) protocol version; unknown stays unknown pre-sync.
        val release = info.release?.takeIf { it.isNotEmpty() }
            ?: info.versionV2?.let { Constants.formatVersion(it) }
        return ServerInfoUiState(
            connected = live != null,
            release = release,
            osName = info.os,
            osVersion = info.osVersion,
            tcpLatencyMicros = live?.stats?.tcpLatencyMicros,
            udpLatencyMicros = live?.stats?.udpLatencyMicros,
            host = live?.host,
            port = live?.port,
            codecName = if (info.opus) "Opus" else null,
            maxBandwidth = info.maxBandwidth,
            currentBandwidth = live?.stats?.currentBandwidth,
        )
    }
}
