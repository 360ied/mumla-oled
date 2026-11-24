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

package se.lublin.mumla.feature

import android.content.Context
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.Server
import se.lublin.humla.net.TlsHostnameVerifier
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.AppLogLine
import se.lublin.mumla.service.IChatMessage

/**
 * Process-lifetime chat history. Today's limits and LRU policy move here
 * unchanged; the chat screen reads [messages] through the service binder.
 * Until step 7, received text arrives through the observer bridge and sent
 * text through the service's send delegations. Every appended line is also
 * fanned out to HumlaCallbacks so an open chat screen keeps updating live.
 */
class ChatLogFeature(
    context: Context,
    private val clientState: StateFlow<ClientState>,
    private val events: SharedFlow<SessionEventRecord>,
    private val voiceEvents: SharedFlow<VoiceEvent>,
    private val appLog: SharedFlow<AppLogLine>,
    private val callbacks: HumlaCallbacks,
    private val formatter: ChatEventFormatter = ChatEventFormatter(context),
    /** Test seam for the slow line sink; production appends to the log. */
    private val lineSink: (suspend (ChatLine) -> Unit)? = null,
) {
    private val appContext = context.applicationContext

    /** LRU over server keys, in least-recently-used order. */
    private val serverLogs = object : LinkedHashMap<String, MutableList<IChatMessage>>(
        MAX_CACHED_SERVERS + 1, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MutableList<IChatMessage>>,
        ): Boolean = size > MAX_CACHED_SERVERS
    }

    private val _messages = MutableStateFlow<List<IChatMessage>>(emptyList())
    val messages: StateFlow<List<IChatMessage>> = _messages.asStateFlow()

    private var activeKey = ""
    private var currentTarget: ConnectTarget? = null
    private var wasReconnecting = false

    /**
     * The feature's Main child scope. Every mutation of serverLogs, activeKey
     * and _messages runs here: the collectors already do, and the interim
     * binder/observer entry points post through [onMain].
     */
    private var mainScope: CoroutineScope? = null

    /** Registered by the container for onMessageLogged only (interim bridge). */
    val receivedTextObserver: IHumlaObserver = object : HumlaObserver() {
        override fun onMessageLogged(message: IMessage?) {
            onMain { addText(message, selfAuthored = false) }
        }
    }

    private fun onMain(block: () -> Unit) {
        val child = checkNotNull(mainScope) {
            "ChatLogFeature.start() must run before any log entry point"
        }
        child.launch { block() }
    }

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        mainScope = child
        val sink = lineSink ?: { line: ChatLine -> append(line) }
        // Single ChatLine queue: session and voice lines share one drain so
        // ordering and backpressure tests cover both, and a slow sink never
        // stalls the session emitter.
        val lineQueue = FeatureEventQueue<ChatLine>(child) { line -> sink(line) }
        child.launch {
            events.collect { record ->
                for (line in formatter.lines(record)) lineQueue.offer(line)
            }
        }
        child.launch { clientState.collect { onClientState(it) } }
        child.launch {
            voiceEvents.collect { formatter.voiceLine(it)?.let { lineQueue.offer(it) } }
        }
        child.launch {
            appLog.collect { append(ChatLine(it.text, it.warning)) }
        }
    }

    private fun onClientState(state: ClientState) {
        when (state) {
            is ClientState.Idle -> wasReconnecting = false
            is ClientState.Connecting -> {
                wasReconnecting = false
                switchServer(state.target)
            }
            is ClientState.Connected -> {
                switchServer(state.target)
                if (wasReconnecting) {
                    append(ChatLine(ConnectionLines.reconnected(appContext)))
                }
                wasReconnecting = false
            }
            is ClientState.Reconnecting -> {
                if (!wasReconnecting) {
                    append(
                        ChatLine(
                            ConnectionLines.disconnected(appContext, state.lastError.displayMessage()),
                            warning = true,
                        ),
                    )
                }
                wasReconnecting = true
            }
            is ClientState.Failed -> {
                val cause = state.error.displayMessage()
                if (cause != null) {
                    append(ChatLine(ConnectionLines.disconnected(appContext, cause), warning = true))
                }
                wasReconnecting = false
            }
        }
    }

    private fun switchServer(target: ConnectTarget) {
        currentTarget = target
        val key = serverKey(target)
        if (key.isNotEmpty() && key != activeKey) {
            activeKey = key
            _messages.value = snapshot(serverLogs[key])
        }
    }

    private fun ensureActiveServer() {
        if (activeKey.isEmpty()) {
            val key = currentTarget?.let { serverKey(it) } ?: ""
            if (key.isNotEmpty()) {
                activeKey = key
                _messages.value = snapshot(serverLogs[key])
            }
        }
    }

    /** Binder read: a defensive, unmodifiable copy of the current value. */
    fun currentMessages(): List<IChatMessage> = _messages.value

    fun clear() {
        onMain {
            if (activeKey.isNotEmpty()) serverLogs.remove(activeKey)
            activeKey = ""
            _messages.value = emptyList()
        }
    }

    /** Sent text from MumlaService's binder delegations (interim). */
    fun addSentText(message: IMessage?) {
        onMain { addText(message, selfAuthored = true) }
    }

    private fun addText(message: IMessage?, selfAuthored: Boolean) {
        val text = IChatMessage.TextMessage(message, selfAuthored)
        appendMessage(text)
    }

    private fun append(line: ChatLine) {
        val message = IChatMessage.InfoMessage(
            if (line.warning) IChatMessage.InfoMessage.Type.WARNING
            else IChatMessage.InfoMessage.Type.INFO,
            line.text,
        )
        appendMessage(message)
        if (line.warning) callbacks.onLogWarning(line.text) else callbacks.onLogInfo(line.text)
    }

    private fun appendMessage(message: IChatMessage) {
        ensureActiveServer()
        val log = serverLogs.getOrPut(activeKey) { ArrayList() }
        if (log.size >= MAX_CHAT_LOG_SIZE) log.removeAt(0)
        log.add(message)
        if (activeKey.isNotEmpty()) serverLogs[activeKey] = log
        _messages.value = snapshot(log)
    }

    private fun snapshot(log: List<IChatMessage>?): List<IChatMessage> =
        Collections.unmodifiableList(ArrayList(log ?: emptyList()))

    companion object {
        const val MAX_CHAT_LOG_SIZE = 500
        const val MAX_CACHED_SERVERS = 10

        fun serverKey(target: ConnectTarget): String {
            val endpoint = target.server
            if (endpoint.serverId != -1L) return "id:${endpoint.serverId}"
            return serverKey(
                Server(
                    -1, null, endpoint.host, endpoint.port,
                    endpoint.username, endpoint.password,
                ),
            )
        }

        /** Shared canonicalization with TLS pinning; password is excluded. */
        @JvmStatic
        fun serverKey(server: Server?): String {
            if (server == null) return ""
            if (server.isSaved()) return "id:" + server.id
            val host = TlsHostnameVerifier.canonicalizeHost(server.host?.trim()) ?: ""
            val port = if (server.port > 0) server.port else Constants.DEFAULT_PORT
            return "endpoint:$host:$port:${server.username ?: ""}"
        }
    }
}
