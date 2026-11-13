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

import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.net.Transport
import se.lublin.humla.net.TransportException
import se.lublin.humla.net.TransportFactory
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.state.ServerState
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.util.IHumlaObserver

/**
 * Single entry point for connecting. Owns today's reconnect policy and
 * sequences session, route, and voice lifetimes.
 * See docs/target-architecture/client.md#humlaclient.
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
    private val denyFormatter: (SessionEvent.PermissionDenied) -> String,
    private val clientDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val sessionDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) {
    // Child of the parent scope: parent cancellation (service destroy,
    // test scope) tears the client down with it. Teardown ordering still
    // goes through teardownLiveSession (voice stop, route end, close).
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )

    private val _state: MutableStateFlow<ClientState> = MutableStateFlow(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    // All lifecycle vars below are confined to clientDispatcher; cross-thread
    // readers go through state.value (which carries attempt/target/error).
    private var sessionIds = 0L
    private var attempt: Int = 0
    // Attempt epoch: stamped at every doConnect/doDisconnect/cancelReconnect
    // and carried by each attempt. Staleness is identity on the epoch, not
    // value equality on the target: a manual retry to the same server
    // yields an equal-but-distinct ConnectTarget, and only the epoch tells
    // the stale attempt apart from the live one.
    private var connectEpoch = 0L
    private var attemptJob: Job? = null
    private var reconnectDelayJob: Job? = null
    private var networkJob: Job? = null
    private var liveSession: Session? = null

    fun connect(target: ConnectTarget) {
        scope.launch(clientDispatcher) {
            doConnect(target)
        }
    }

    fun disconnect() {
        scope.launch(clientDispatcher) {
            doDisconnect()
        }
    }

    // Matches legacy parity: only a pending Reconnecting is disarmed; a live
    // Connecting attempt (even automatic) runs to outcome. Callers needing
    // to stop everything use disconnect().
    fun cancelReconnect() {
        scope.launch(clientDispatcher) {
            val s = _state.value
            if (s is ClientState.Reconnecting) {
                cancelJobs()
                attempt = 0
                connectEpoch += 1
                _state.value = ClientState.Idle
            }
        }
    }

    /**
     * Best-effort shutdown (tests, service destroy): disarms timers, tears
     * down the live session, then cancels the scope. Returns the teardown
     * job so tests can join it; teardown itself has no suspensions, so
     * parent cancellation cannot interrupt it mid-flight.
     */
    fun close(): Job {
        return scope.launch(clientDispatcher) {
            cancelJobs()
            // Retire the epoch for symmetry with disconnect: a parked
            // non-cooperative failure must not republish after close.
            connectEpoch += 1
            teardownLiveSession()
            scope.cancel()
        }
    }

    /** Test seam: current attempt counter, read from published state. */
    fun testAttempt(): Int = when (val s = _state.value) {
        is ClientState.Connecting -> s.attempt
        is ClientState.Reconnecting -> s.attempt
        else -> attempt
    }

    private fun cancelJobs() {
        // Snapshot-then-clear: invoked from within these very jobs
        // (delay/network firing doConnect), where self-cancel is a harmless
        // no-op. Job.cancel never throws; no catch needed.
        val attemptJ = attemptJob
        attemptJob = null
        val delayJob = reconnectDelayJob
        reconnectDelayJob = null
        val netJob = networkJob
        networkJob = null
        attemptJ?.cancel()
        delayJob?.cancel()
        netJob?.cancel()
    }

    private fun teardownLiveSession() {
        val session = liveSession
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
        // Clear last: until now currentSession still resolves for in-flight
        // readers; teardown itself runs sequentially on this dispatcher.
        liveSession = null
        _currentSession.value = null
    }

    private suspend fun doDisconnect() {
        cancelJobs()
        attempt = 0
        // Retire the epoch: a cancelled attempt whose factory ignores
        // cancellation must not resurrect Reconnecting after Idle.
        connectEpoch += 1
        teardownLiveSession()
        _state.value = ClientState.Idle
    }

    private suspend fun doConnect(target: ConnectTarget) {
        // Cancel any in-flight attempt/session before starting the new one.
        // Voice Stop is submitted before Route Ended inside teardown, and the
        // next Start can only run after this teardown (same dispatcher order),
        // so the next session's Start follows this Stop.
        // Note: the attempt counter is intentionally NOT reset here. A manual
        // retry after failures keeps the grown backoff (spec, client.md).
        cancelJobs()
        teardownLiveSession()
        // Stamp the new attempt: any failure arriving with an older epoch
        // belongs to a retired attempt and must be ignored.
        connectEpoch += 1
        val epoch = connectEpoch
        // The attempt counter is intentionally NOT reset here. A manual
        // retry after failures keeps the grown backoff (spec, client.md).
        // Publishing Connecting here keeps the UI on Connecting through TLS.
        _state.value = ClientState.Connecting(target, attempt)
        val job = scope.launch(clientDispatcher) {
            runAttempt(this, target, epoch)
        }
        attemptJob = job
    }

    private suspend fun runAttempt(
        attemptScope: CoroutineScope,
        target: ConnectTarget,
        epoch: Long,
    ) {
        // Load facts on IO; a DB problem never blocks connecting.
        val facts: LocalFacts = try {
            withContext(ioDispatcher) {
                localFactsStore.load(target.server.serverId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Starting with empty lists is right, but a failing store makes
            // the user's mute/ignore lists look silently cleared, so leave a
            // trace (spec, client.md start sequence step 1).
            logger.warning("LocalFacts load failed for ${target.server.serverId}: $e")
            LocalFacts.empty()
        }
        attemptScope.ensureActive()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            onConnectFailed(target, epoch, e.error, lastState = null)
            return
        } catch (e: IOException) {
            onConnectFailed(
                target,
                epoch,
                ClientError.Connection("Connect failed: ${e.message}", e),
                lastState = null,
            )
            return
        } catch (e: Exception) {
            // Unexpected failures fail terminally without retry: deterministic
            // bugs must neither loop reconnects forever nor crash the process
            // via an uncaught coroutine exception. The error stays visible.
            // Stale-attempt guard: a retired attempt surfacing a bug after
            // takeover must not tear down the live session.
            if (epoch != connectEpoch) return
            teardownLiveSession()
            _state.value = ClientState.Failed(
                target,
                ClientError.Connection("Connect failed: ${e.message}", e),
            )
            return
        }
        attemptScope.ensureActive()
        // Transport connected; create the session and publish it at once.
        val session = Session(
            id = sessionIds++,
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
            clock = clock,
        )
        liveSession = session
        _currentSession.value = session
        // Still Connecting (HANDSHAKING published at construction).
        _state.value = ClientState.Connecting(target, attempt)
        // Watch for SYNCED -> Connected. Cancelled once connected: the
        // pre-sync burst would otherwise wake this dispatcher per emission.
        val syncWatcher = attemptScope.launch(clientDispatcher) {
            try {
                session.state.collect { s ->
                    if (s.server.phase == SyncPhase.SYNCED) {
                        onSynced(target, session)
                        throw WatcherDone()
                    }
                }
            } catch (e: WatcherDone) {
                // Connected; watcher retired.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.fine("Sync watcher dropped: $e")
            }
        }
        // Watch for failure -> Reconnecting/Failed.
        try {
            val error = session.failure.await()
            syncWatcher.cancel()
            onSessionFailed(target, session, error)
        } catch (e: CancellationException) {
            // Attempt cancelled (disconnect/new connect); the canceller tore
            // the session down. Propagate so the attempt job ends cancelled.
            syncWatcher.cancel()
            throw e
        }
    }

    /** Control-flow signal: the sync watcher connected and retires. */
    private class WatcherDone : Exception() {
        // Thrown once per connection; no stack needed.
        override fun fillInStackTrace(): Throwable = this
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
        // Push the route before opening UDP: startVoice skips the socket when
        // forced (or when a previous session left the link tunnelled).
        try {
            voiceLifecycle.setForceTcp(target.forceTcp)
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
        epoch: Long,
        error: ClientError,
        lastState: ServerState?,
    ) {
        // Stale-attempt guard (mirrors the liveSession checks in onSynced /
        // onSessionFailed): a cancelled attempt whose factory ignores
        // cancellation can still report failure after a newer attempt took
        // over. Never tear down or republish for it. Epoch identity, not
        // target equality: a same-server retry carries an equal target.
        if (epoch != connectEpoch) return
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
        lastState: ServerState?,
    ) {
        val delayMs = ReconnectPolicy.delayForAttempts(attempt)
        val retryAt = try {
            clock.millis() + delayMs
        } catch (e: Exception) {
            System.currentTimeMillis() + delayMs
        }
        _state.value = ClientState.Reconnecting(target, attempt, retryAt, error, lastState)
        // Staleness is epoch identity (see onConnectFailed): the state check
        // alone cannot tell a same-target manual retry apart.
        val epoch = connectEpoch
        // Delay job: backoff then automatic attempt (increments first).
        reconnectDelayJob?.cancel()
        reconnectDelayJob = scope.launch(clientDispatcher) {
            try {
                delay(delayMs.toLong())
                if (_state.value is ClientState.Reconnecting && epoch == connectEpoch) {
                    attempt += 1
                    doConnect(target)
                }
            } catch (e: CancellationException) {
                throw e
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
                    if (s is ClientState.Reconnecting && epoch == connectEpoch) {
                        try {
                            reconnectDelayJob?.cancel()
                        } catch (ignored: Exception) {
                        }
                        attempt += 1
                        doConnect(target)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    companion object {
        /** Same name as the rest of the client package, so logcat filters keep working. */
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger("HumlaClient")
    }
}
