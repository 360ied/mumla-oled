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
import se.lublin.humla.model.Server
import se.lublin.humla.net.TlsHostnameVerifier
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.SessionState
import se.lublin.mumla.AppLogLine
import se.lublin.mumla.service.IChatMessage

/**
 * One append to the chat log. Informational lines are formatted text; text
 * messages carry their sender and target names as values.
 */
sealed interface ChatLogEntry {
    data class Info(val line: ChatLine) : ChatLogEntry
    data class Text(val message: IChatMessage.TextMessage) : ChatLogEntry
}

/**
 * Process-lifetime chat history. Today's limits and LRU policy move here
 * unchanged. Text entries come from the [SessionEvent.TextMessageReceived]
 * and [SessionEvent.TextMessageSent] records; the open chat screen collects
 * [messages], so nothing is forwarded to it through callbacks.
 */
class ChatLogFeature(
    context: Context,
    private val clientState: StateFlow<ClientState>,
    private val events: SharedFlow<SessionEventRecord>,
    private val voiceEvents: SharedFlow<VoiceEvent>,
    private val appLog: SharedFlow<AppLogLine>,
    private val formatter: ChatEventFormatter = ChatEventFormatter(context),
    /** Test seam for the slow line sink; production appends to the log. */
    private val lineSink: (suspend (ChatLogEntry) -> Unit)? = null,
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
     * and _messages runs here: the collectors already do, and [clear] posts
     * through [onMain].
     */
    private var mainScope: CoroutineScope? = null

    private fun onMain(block: () -> Unit) {
        val child = checkNotNull(mainScope) {
            "ChatLogFeature.start() must run before any log entry point"
        }
        child.launch { block() }
    }

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        mainScope = child
        val sink = lineSink ?: { entry: ChatLogEntry -> append(entry) }
        // Single queue: session and voice entries share one drain so ordering
        // and backpressure tests cover both, and a slow sink never stalls the
        // session emitter.
        val queue = FeatureEventQueue<ChatLogEntry>(child) { entry -> sink(entry) }
        child.launch {
            events.collect { record ->
                textEntry(record)?.let { queue.offer(ChatLogEntry.Text(it)) }
                for (line in formatter.lines(record)) queue.offer(ChatLogEntry.Info(line))
            }
        }
        child.launch { clientState.collect { onClientState(it) } }
        child.launch {
            voiceEvents.collect {
                formatter.voiceLine(it)?.let { line -> queue.offer(ChatLogEntry.Info(line)) }
            }
        }
        child.launch {
            appLog.collect { append(ChatLogEntry.Info(ChatLine(it.text, it.warning))) }
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
                    append(ChatLogEntry.Info(ChatLine(ConnectionLines.reconnected(appContext))))
                }
                wasReconnecting = false
            }
            is ClientState.Reconnecting -> {
                if (!wasReconnecting) {
                    append(
                        ChatLogEntry.Info(
                            ChatLine(
                                ConnectionLines.disconnected(
                                    appContext,
                                    state.lastError.displayMessage(),
                                ),
                                warning = true,
                            ),
                        ),
                    )
                }
                wasReconnecting = true
            }
            is ClientState.Failed -> {
                val cause = state.error.displayMessage()
                if (cause != null) {
                    append(
                        ChatLogEntry.Info(
                            ChatLine(ConnectionLines.disconnected(appContext, cause), warning = true),
                        ),
                    )
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

    /** Defensive, unmodifiable copy of the current value. */
    fun currentMessages(): List<IChatMessage> = _messages.value

    fun clear() {
        onMain {
            if (activeKey.isNotEmpty()) serverLogs.remove(activeKey)
            activeKey = ""
            _messages.value = emptyList()
        }
    }

    /**
     * The text entry a record produces, with sender and target names resolved
     * from the record's own state snapshot.
     */
    private fun textEntry(record: SessionEventRecord): IChatMessage.TextMessage? {
        val event = record.event
        return when (event) {
            is SessionEvent.TextMessageReceived -> IChatMessage.TextMessage(
                actorName = event.actorName,
                targetNames = targetNames(event.channels, event.trees, event.users, record.state),
                body = event.message,
                receivedTime = event.receivedAtMillis,
                // Today's renderer right-aligned a received line when the
                // sender was the self user (another client on the same
                // account): the actor session is authoritative, with the
                // name check kept for parity when the session is absent.
                isSelfAuthored = isSelfAuthored(event, record.state),
            )
            is SessionEvent.TextMessageSent -> IChatMessage.TextMessage(
                actorName = record.state.server.self?.name,
                targetNames = targetNames(event.channels, event.trees, event.users, record.state),
                body = event.message,
                receivedTime = event.sentAtMillis,
                isSelfAuthored = true,
            )
            else -> null
        }
    }

    private fun isSelfAuthored(
        event: SessionEvent.TextMessageReceived,
        state: SessionState,
    ): Boolean {
        val selfSession = state.server.selfSession
        if (event.actor != null && selfSession != null) {
            return event.actor == selfSession
        }
        val selfName = state.server.self?.name
        return selfName != null && event.actorName == selfName
    }

    private fun targetNames(
        channels: List<Int>,
        trees: List<Int>,
        users: List<Int>,
        state: SessionState,
    ): List<String> {
        val names = ArrayList<String>(channels.size + trees.size + users.size)
        for (id in channels) state.server.channels[id]?.name?.let { names.add(it) }
        for (id in trees) state.server.channels[id]?.name?.let { names.add(it) }
        for (session in users) state.server.users[session]?.name?.let { names.add(it) }
        return names
    }

    private fun append(entry: ChatLogEntry) {
        when (entry) {
            is ChatLogEntry.Info -> appendMessage(
                IChatMessage.InfoMessage(
                    if (entry.line.warning) IChatMessage.InfoMessage.Type.WARNING
                    else IChatMessage.InfoMessage.Type.INFO,
                    entry.line.text,
                ),
            )
            is ChatLogEntry.Text -> appendMessage(entry.message)
        }
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
