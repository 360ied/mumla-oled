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

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.net.Transport
import se.lublin.humla.net.TransportException
import se.lublin.humla.net.TransportFactory
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.util.IHumlaObserver

/**
 * Single entry point for connecting. Owns today's reconnect policy and
 * sequences session, route, and voice lifetimes.
 * See docs/tech-debt-whales/target-architecture/client.md#humlaclient.
 *
 * All lifecycle logic runs on one client dispatcher
 * (Default.limitedParallelism(1)). Public methods only launch onto it and
 * return, whatever thread calls them. Never handles server messages or
 * voice here, so it cannot become a bottleneck.
 *
 * Android-free: platform behavior arrives via small interfaces, so the
 * client runs in plain JVM tests with fakes.
 */
class HumlaClient(
    parentScope: CoroutineScope,
    private val transports: TransportFactory,
    private val voiceLifecycle: VoiceLifecycle,
    private val routeLifecycle: RouteLifecycle,
    private val voiceLink: VoiceLink,
    private val network: NetworkMonitor,
    private val localFactsStore: LocalFactsStore,
    private val clock: Clock,
    private val observer: IHumlaObserver,
    private val mainPoster: (Runnable) -> Unit,
    private val denyFormatter: (se.lublin.humla.state.SessionEvent.PermissionDenied) -> String,
    private val clientDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val sessionDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    private val _state: MutableStateFlow<ClientState> = MutableStateFlow(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val sessionIds = AtomicLong(0L)

    // Dispatcher-confined lifecycle state. Reads from other threads go
    // through state.value (which carries attempt/target/error).
    private var attempt: Int = 0
    private var currentTarget: ConnectTarget? = null
    private var attemptJob: Job? = null
    private var reconnectDelayJob: Job? = null
    private var networkJob: Job? = null
    private var liveSession: Session? = null

    fun connect(target: ConnectTarget) {
        scope.launch(clientDispatcher) {
            doConnect(target, automatic = false)
        }
    }

    fun disconnect() {
        scope.launch(clientDispatcher) {
            doDisconnect()
        }
    }

    fun cancelReconnect() {
        scope.launch(clientDispatcher) {
            val s = _state.value
            if (s is ClientState.Reconnecting) {
                cancelJobs()
                attempt = 0
                currentTarget = null
                _state.value = ClientState.Idle
            }
        }
    }

    /** Test seam: current attempt counter (dispatcher-confined, read via state in prod). */
    fun testAttempt(): Int = attempt

    private fun cancelJobs() {
        try {
            attemptJob?.cancel()
        } catch (ignored: Exception) {
        }
        attemptJob = null
        try {
            reconnectDelayJob?.cancel()
        } catch (ignored: Exception) {
        }
        reconnectDelayJob = null
        try {
            networkJob?.cancel()
        } catch (ignored: Exception) {
        }
        networkJob = null
    }

    private suspend fun teardownLiveSession() {
        val session = liveSession
        liveSession = null
        _currentSession.value = null
        if (session == null) return
        try {
            voiceLifecycle.stopVoice()
        } catch (ignored: Exception) {
        }
        try {
            routeLifecycle.sessionEnded()
        } catch (ignored: Exception) {
        }
        try {
            session.close()
        } catch (ignored: Exception) {
        }
    }

    private suspend fun doDisconnect() {
        cancelJobs()
        attempt = 0
        currentTarget = null
        teardownLiveSession()
        _state.value = ClientState.Idle
    }

    private suspend fun doConnect(target: ConnectTarget, automatic: Boolean) {
        // Cancel any in-flight attempt/session before starting the new one.
        // Voice Stop is submitted before Route Ended inside teardown, and the
        // next Start can only run after this teardown (same dispatcher order),
        // so the next session's Start follows this Stop.
        cancelJobs()
        teardownLiveSession()
        currentTarget = target
        // Manual connects neither reset nor increment; automatic increments
        // before starting (caller increments). Publishing Connecting here
        // keeps the UI on Connecting through TLS (which suspends below).
        _state.value = ClientState.Connecting(target, attempt)
        val job = scope.launch(clientDispatcher) {
            runAttempt(this, target)
        }
        attemptJob = job
    }

    private suspend fun runAttempt(attemptScope: CoroutineScope, target: ConnectTarget) {
        // Load facts on IO; a DB problem never blocks connecting.
        val facts: LocalFacts = try {
            withContext(ioDispatcher) {
                localFactsStore.load(target.server.serverId)
            }
        } catch (e: Exception) {
            LocalFacts.empty()
        }
        // Create the transport (DNS + TLS). Cancellable via attemptJob cancel.
        val transport: Transport = try {
            transports.create(
                parentScope = attemptScope,
                host = target.server.host,
                port = target.server.port,
                certificate = target.certificate?.pkcs12?.toByteArray(),
                certificatePassword = target.certificate?.password,
                trustStorePath = target.trustStore?.path,
                trustStorePassword = target.trustStore?.password,
                trustStoreFormat = target.trustStore?.format,
                forceTcp = target.forceTcp,
                voice = voiceLink,
            )
        } catch (e: TransportException) {
            onConnectFailed(target, e.error, lastState = null)
            return
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onConnectFailed(
                target,
                ClientError.Connection("Connect failed: ${e.message}", e),
                lastState = null,
            )
            return
        }
        // Transport connected; create the session and publish it at once.
        val session = Session(
            id = sessionIds.incrementAndGet(),
            target = target,
            parentScope = attemptScope,
            transport = transport,
            localFacts = facts,
            voiceLink = voiceLink,
            observer = observer,
            localFactsStore = localFactsStore,
            mainPoster = mainPoster,
            denyFormatter = denyFormatter,
            workDispatcher = sessionDispatcher,
            ioDispatcher = ioDispatcher,
        )
        liveSession = session
        _currentSession.value = session
        // Still Connecting (HANDSHAKING published at construction).
        _state.value = ClientState.Connecting(target, attempt)
        // Watch for SYNCED -> Connected.
        val syncWatcher = attemptScope.launch(clientDispatcher) {
            try {
                var connected = false
                session.state.collect { s ->
                    if (!connected && s.server.phase == SyncPhase.SYNCED) {
                        connected = true
                        onSynced(target, session)
                    }
                }
            } catch (e: Exception) {
            }
        }
        // Watch for failure -> Reconnecting/Failed.
        try {
            val error = session.failure.await()
            try {
                syncWatcher.cancel()
            } catch (ignored: Exception) {
            }
            onSessionFailed(target, session, error)
        } catch (e: Exception) {
            // Attempt cancelled (disconnect/new connect); session already torn
            // down by the canceller. Nothing to publish here.
            try {
                syncWatcher.cancel()
            } catch (ignored: Exception) {
            }
        }
    }

    private suspend fun onSynced(target: ConnectTarget, session: Session) {
        // Only the live session may publish Connected (a stale SYNCED after
        // teardown must not resurrect).
        if (liveSession !== session) return
        if (_state.value !is ClientState.Connecting) return
        attempt = 0
        _state.value = ClientState.Connected(target, session)
        try {
            routeLifecycle.sessionStarted()
        } catch (ignored: Exception) {
        }
        try {
            voiceLifecycle.startVoice(transportRemoteHost(session), transportRemotePort(session))
        } catch (ignored: Exception) {
        }
    }

    private fun transportRemoteHost(session: Session): String {
        return try {
            session.transport.remoteHost
        } catch (e: Exception) {
            session.target.server.host
        }
    }

    private fun transportRemotePort(session: Session): Int {
        return try {
            session.transport.remotePort
        } catch (e: Exception) {
            session.target.server.port
        }
    }

    private suspend fun onConnectFailed(
        target: ConnectTarget,
        error: ClientError,
        lastState: se.lublin.humla.state.ServerState?,
    ) {
        // No session existed (DNS/TLS failure before Session). Teardown any
        // half-open voice/route (no-ops) for a clean state transition.
        teardownLiveSession()
        when (error) {
            is ClientError.Connection -> enterReconnecting(target, error, lastState)
            is ClientError.Rejected,
            is ClientError.Removed,
            is ClientError.Trust,
            -> {
                _state.value = ClientState.Failed(target, error)
            }
        }
    }

    private suspend fun onSessionFailed(target: ConnectTarget, session: Session, error: ClientError) {
        if (liveSession !== session) return
        val lastState = try {
            session.state.value.server
        } catch (e: Exception) {
            null
        }
        teardownLiveSession()
        when (error) {
            is ClientError.Connection -> enterReconnecting(target, error, lastState)
            is ClientError.Rejected,
            is ClientError.Removed,
            is ClientError.Trust,
            -> {
                _state.value = ClientState.Failed(target, error)
            }
        }
    }

    private suspend fun enterReconnecting(
        target: ConnectTarget,
        error: ClientError,
        lastState: se.lublin.humla.state.ServerState?,
    ) {
        val delayMs = ReconnectPolicy.delayForAttempts(attempt)
        val retryAt = try {
            clock.millis() + delayMs
        } catch (e: Exception) {
            System.currentTimeMillis() + delayMs
        }
        _state.value = ClientState.Reconnecting(target, attempt, retryAt, error, lastState)
        // Delay job: backoff then automatic attempt (increments first).
        reconnectDelayJob?.cancel()
        reconnectDelayJob = scope.launch(clientDispatcher) {
            try {
                delay(delayMs.toLong())
                if (_state.value is ClientState.Reconnecting && currentTarget == target) {
                    attempt += 1
                    doConnect(target, automatic = true)
                }
            } catch (e: Exception) {
            }
        }
        // Network job: availability cancels the timer and retries at once
        // (incrementing), mirroring today's network callback. Ignored while
        // Connecting (this collector only runs in Reconnecting).
        networkJob?.cancel()
        networkJob = scope.launch(clientDispatcher) {
            try {
                network.available.collect {
                    val s = _state.value
                    if (s is ClientState.Reconnecting && currentTarget == target) {
                        try {
                            reconnectDelayJob?.cancel()
                        } catch (ignored: Exception) {
                        }
                        attempt += 1
                        doConnect(target, automatic = true)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
}
