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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.net.Transport
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.emptySessionState

/**
 * One connection. Created by the client for each attempt and never reused.
 * See docs/tech-debt-whales/target-architecture/client.md#session.
 *
 * Step-3 stub: holds the state/events/stats flows and the failure signal.
 * The message coroutine, intents, and statistics combine land in the
 * Session commit; HumlaService does not use this yet.
 */
class Session internal constructor(
    val id: Long,
    val target: ConnectTarget,
    parentScope: CoroutineScope,
    val transport: Transport,
    val localFacts: LocalFacts,
) {
    private val _state = MutableStateFlow(emptySessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(SessionStats.initial())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    /** Completes once when the session fails; cancelled sessions never fail. */
    val failure: Deferred<ClientError> = parentScope.async {
        transport.failure.await()
    }
}
