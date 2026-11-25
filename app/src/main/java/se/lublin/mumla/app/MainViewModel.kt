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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.client.ServerEndpoint
import se.lublin.humla.model.Server
import se.lublin.humla.net.HandshakeFailure
import se.lublin.humla.net.TlsHostnameVerifier
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.mumla.TalkKeyEvent
import se.lublin.mumla.feature.ChatLogFeature

/** What the main screen renders for the connection. */
sealed interface ConnectionUi {
    val serverName: String?

    data object Disconnected : ConnectionUi {
        override val serverName: String? = null
    }

    data class Connecting(val target: ConnectTarget) : ConnectionUi {
        override val serverName: String? get() = target.displayName()
        val host: String get() = target.server.host
    }

    data class Connected(val target: ConnectTarget) : ConnectionUi {
        override val serverName: String? get() = target.displayName()
        val host: String get() = target.server.host
    }

    data class Reconnecting(
        val target: ConnectTarget,
        val attempt: Int,
        val retryAtMillis: Long,
        val lastError: ClientError,
    ) : ConnectionUi {
        override val serverName: String? get() = target.displayName()
    }

    data class Failed(val target: ConnectTarget, val error: ClientError) : ConnectionUi {
        override val serverName: String? get() = target.displayName()
    }
}

/** What the main screen's content frame should do on a connection change. */
enum class ConnectionScreenTransition { NONE, OPEN_SESSION, SHOW_FAVOURITES }

/**
 * The content-frame rule, kept pure so it can be pinned: only a transition
 * observed by this screen moves the content. A screen created while already
 * connected sees no transition, so a rotation never replaces the restored
 * fragment.
 */
fun connectionScreenTransition(
    previous: ConnectionUi?,
    current: ConnectionUi,
): ConnectionScreenTransition = when {
    current is ConnectionUi.Connected && previous is ConnectionUi.Connected ->
        ConnectionScreenTransition.NONE
    current is ConnectionUi.Connected -> ConnectionScreenTransition.OPEN_SESSION
    previous is ConnectionUi.Connected -> ConnectionScreenTransition.SHOW_FAVOURITES
    else -> ConnectionScreenTransition.NONE
}

/**
 * A TLS trust decision the screen must show. [kind] picks today's dialog:
 * a mismatch cannot be pinned, the other two can.
 */
data class TrustPrompt(
    val kind: Kind,
    val host: String,
    val certificate: X509Certificate,
    val server: Server?,
    val pinned: X509Certificate?,
) {
    enum class Kind { HOSTNAME_MISMATCH, FIRST_TRUST, CHANGED_PIN }

    val canPin: Boolean get() = kind != Kind.HOSTNAME_MISMATCH && server != null
}

/** What a connect tap should make the screen do. */
enum class ConnectRequest {
    STARTED,
    ALREADY_CONNECTED,
    CONNECTING_SAME,
    CONNECTING_OTHER,
    SWITCH_CONFIRM,
}

/**
 * The main screen: connection state, the dialogs it implies, talk keys,
 * notification suppression, and the crash-report prompt. Every side effect is
 * a function handed in by the factory, so the model runs on plain flows.
 */
class MainViewModel(
    private val clientState: StateFlow<ClientState>,
    events: kotlinx.coroutines.flow.SharedFlow<SessionEventRecord>,
    private val denyFormatter: (SessionEvent.PermissionDenied) -> String,
    private val actions: MainActions,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Session and client intents; the factory implements it over production. */
    interface MainActions {
        fun connect(server: Server)
        fun disconnect()
        fun cancelReconnect()
        fun setSuppressNotifications(suppress: Boolean)
        fun emitTalkKey(event: TalkKeyEvent)
        suspend fun saveServerPassword(server: Server)
        fun pinnedCertificate(host: String): X509Certificate?
        fun pinCertificate(host: String, certificate: X509Certificate): Boolean
        suspend fun newestUnseenCrashReport(drain: Boolean): String?
        suspend fun markCrashReportSeen(id: String)
        fun clearChatNotifications()
    }

    private val _permissionDenied = MutableStateFlow<String?>(null)
    val permissionDenied: StateFlow<String?> = _permissionDenied

    private val _crashReportId = MutableStateFlow<String?>(null)
    val crashReportId: StateFlow<String?> = _crashReportId

    private val _dismissedError = MutableStateFlow<ClientError?>(null)
    private val _pendingSwitch = MutableStateFlow<Server?>(null)

    private var activityStarted = false
    private var crashRecheckJob: Job? = null
    private var seenCrashId: String? = null

    val connection: StateFlow<ConnectionUi> = clientState
        .map(::toUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, toUi(clientState.value))

    /** The error the screen should show, or null once dismissed or cleared. */
    val connectionError: StateFlow<ClientError?> = combine(
        clientState,
        _dismissedError,
    ) { state, dismissed ->
        val error = when (state) {
            is ClientState.Reconnecting -> state.lastError
            // Trust has its own dialog set; it is never the error dialog.
            is ClientState.Failed -> state.error.takeIf { it !is ClientError.Trust }
            else -> null
        }
        if (error != null && error !== dismissed) error else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The trust dialog the screen should show, or null. */
    val trustPrompt: StateFlow<TrustPrompt?> = clientState
        .map(::trustPromptOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, trustPromptOf(clientState.value))

    val pendingSwitch: StateFlow<Boolean> = _pendingSwitch
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            clientState.collect { state ->
                if (state is ClientState.Connecting) {
                    // A fresh attempt forgets the dismissed error.
                    _dismissedError.value = null
                }
                runPendingSwitchIfPossible()
            }
        }
        viewModelScope.launch {
            events.collect { record ->
                val event = record.event
                if (event is SessionEvent.PermissionDenied) {
                    _permissionDenied.value = denyFormatter(event)
                }
            }
        }
    }

    // Lifecycle seam: a pending switch only connects while the screen is started.

    fun onActivityStarted() {
        activityStarted = true
        runPendingSwitchIfPossible()
    }

    fun onActivityStopped() {
        activityStarted = false
    }

    /**
     * A connect tap. Connected-other asks the screen for its confirmation
     * dialog; everything else either starts the attempt or is a no-op with a
     * message.
     */
    fun connect(server: Server): ConnectRequest {
        val state = clientState.value
        return when {
            state is ClientState.Connected &&
                isSameServer(state.target.server, server) -> ConnectRequest.ALREADY_CONNECTED
            state is ClientState.Connected -> ConnectRequest.SWITCH_CONFIRM
            state is ClientState.Connecting && isSameServer(state.target.server, server) ->
                ConnectRequest.CONNECTING_SAME
            state is ClientState.Connecting -> ConnectRequest.CONNECTING_OTHER
            else -> {
                _pendingSwitch.value = null
                actions.connect(server)
                ConnectRequest.STARTED
            }
        }
    }

    /**
     * The user confirmed a server switch: disconnect and hold the target. The
     * connect runs at the first Idle while the screen is started, so a switch
     * confirmed just before leaving connects on return and the microphone
     * service is never started from the background.
     */
    fun confirmSwitch(server: Server) {
        _pendingSwitch.value = server
        actions.disconnect()
    }

    fun disconnect() {
        _pendingSwitch.value = null
        actions.disconnect()
    }

    fun cancelReconnect() {
        _pendingSwitch.value = null
        actions.cancelReconnect()
    }

    /** The user acknowledged the connection error dialog. */
    fun dismissError() {
        val state = clientState.value
        _dismissedError.value = when (state) {
            is ClientState.Reconnecting -> state.lastError
            is ClientState.Failed -> state.error
            else -> null
        }
    }

    fun dismissPermissionDenied() {
        _permissionDenied.value = null
    }

    /**
     * The wrong-password retry: persist the corrected password first, then
     * reconnect, serializing behind an in-flight attempt as before.
     */
    fun retryWithPassword(server: Server) {
        viewModelScope.launch {
            actions.saveServerPassword(server)
            if (clientState.value is ClientState.Connecting) {
                _pendingSwitch.value = server
                actions.disconnect()
            } else {
                _pendingSwitch.value = null
                actions.connect(server)
            }
        }
    }

    /**
     * Pins the presented certificate and reconnects. False means the trust
     * store write failed, so the screen can show its failure toast.
     */
    fun pinAndConnect(prompt: TrustPrompt): Boolean {
        val server = prompt.server ?: return false
        if (!prompt.canPin) return false
        if (!actions.pinCertificate(prompt.host, prompt.certificate)) return false
        connect(server)
        return true
    }

    fun setSuppressNotifications(suppress: Boolean) = actions.setSuppressNotifications(suppress)

    fun clearChatNotifications() = actions.clearChatNotifications()

    fun talkKeyDown() = actions.emitTalkKey(TalkKeyEvent.Down)

    fun talkKeyUp() = actions.emitTalkKey(TalkKeyEvent.Up)

    fun talkKeyCancel() = actions.emitTalkKey(TalkKeyEvent.Cancel)

    // Crash reports.

    /** Initial check plus the delayed recheck, once per screen lifetime. */
    fun startCrashChecks() = recheckCrashReports()

    /**
     * Re-drains and rechecks unless a prompt is already up. A prompt that was
     * dismissed without being shown is retried on the next resume.
     */
    fun recheckCrashReports() {
        if (crashRecheckJob?.isActive == true || _crashReportId.value != null) {
            return
        }
        crashRecheckJob = viewModelScope.launch(ioDispatcher) {
            publishCrashReport(actions.newestUnseenCrashReport(drain = false))
            delay(CRASH_RECHECK_DELAY_MS)
            publishCrashReport(actions.newestUnseenCrashReport(drain = true))
        }
    }

    /** The snackbar was shown: remember the report so it never resurfaces. */
    fun markCrashReportSeen(id: String) {
        seenCrashId = id
        if (_crashReportId.value == id) {
            _crashReportId.value = null
        }
        viewModelScope.launch(ioDispatcher) { actions.markCrashReportSeen(id) }
    }

    /** The prompt went away without being shown (pause, swipe). */
    fun dismissCrashPrompt() {
        _crashReportId.value = null
    }

    private fun publishCrashReport(id: String?) {
        if (id == null || id == seenCrashId) {
            return
        }
        _crashReportId.value = id
    }

    private fun runPendingSwitchIfPossible() {
        val pending = _pendingSwitch.value ?: return
        if (!activityStarted || clientState.value !is ClientState.Idle) {
            return
        }
        _pendingSwitch.value = null
        actions.connect(pending)
    }

    private fun toUi(state: ClientState): ConnectionUi = when (state) {
        is ClientState.Idle -> ConnectionUi.Disconnected
        is ClientState.Connecting -> ConnectionUi.Connecting(state.target)
        is ClientState.Connected -> ConnectionUi.Connected(state.target)
        is ClientState.Reconnecting -> ConnectionUi.Reconnecting(
            state.target,
            state.attempt,
            state.retryAtMillis,
            state.lastError,
        )
        is ClientState.Failed -> ConnectionUi.Failed(state.target, state.error)
    }

    private fun trustPromptOf(state: ClientState): TrustPrompt? {
        if (state !is ClientState.Failed) return null
        val trust = state.error as? ClientError.Trust ?: return null
        val certificate = trust.chain.firstOrNull() ?: return null
        val host = TlsHostnameVerifier.canonicalizeHost(
            trust.verifiedHost ?: state.target.server.host,
        ) ?: return null
        val pinned = actions.pinnedCertificate(host)
        val kind = when {
            trust.failure == HandshakeFailure.HOSTNAME_MISMATCH -> TrustPrompt.Kind.HOSTNAME_MISMATCH
            trust.failure == HandshakeFailure.PIN_CHANGED || pinned != null ->
                TrustPrompt.Kind.CHANGED_PIN
            else -> TrustPrompt.Kind.FIRST_TRUST
        }
        val server = if (kind == TrustPrompt.Kind.HOSTNAME_MISMATCH) {
            null
        } else {
            serverOf(state.target)
        }
        return TrustPrompt(kind, host, certificate, server, pinned)
    }

    companion object {
        const val CRASH_RECHECK_DELAY_MS = 5_000L

        /** Today's "same server" test over a connect target and a Server row. */
        fun isSameServer(endpoint: ServerEndpoint, server: Server): Boolean {
            val samePassword = (endpoint.password ?: "") == (server.password ?: "")
            if (endpoint.serverId != -1L && server.isSaved()) {
                return endpoint.serverId == server.id && samePassword
            }
            return samePassword &&
                ChatLogFeature.serverKey(server) == ChatLogFeature.serverKey(serverOf(endpoint))
        }

        fun serverOf(target: ConnectTarget): Server = serverOf(target.server)

        fun serverOf(endpoint: ServerEndpoint): Server =
            Server(
                endpoint.serverId,
                endpoint.name,
                endpoint.host,
                endpoint.port,
                endpoint.username,
                endpoint.password,
            )

    }
}

private fun ConnectTarget.displayName(): String? = server.name ?: server.host
