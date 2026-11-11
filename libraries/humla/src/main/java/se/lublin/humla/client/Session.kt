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

import com.google.protobuf.MessageLite
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.lublin.humla.Constants
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.net.Transport
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.ChannelHandle
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.applyChannelRemove
import se.lublin.humla.state.applyChannelState
import se.lublin.humla.state.applyCodecVersion
import se.lublin.humla.state.applyListeningVolumeIntent
import se.lublin.humla.state.applyLocalIgnoreIntent
import se.lublin.humla.state.applyLocalMuteIntent
import se.lublin.humla.state.applyPermissionDenied
import se.lublin.humla.state.applyPermissionQuery
import se.lublin.humla.state.applyPttIntent
import se.lublin.humla.state.applyRegisterWhisperIntent
import se.lublin.humla.state.applySelfMuteDeafIntent
import se.lublin.humla.state.applyServerConfig
import se.lublin.humla.state.applyServerSync
import se.lublin.humla.state.applyTextMessage
import se.lublin.humla.state.applyUnregisterWhisperIntent
import se.lublin.humla.state.applyUserRemove
import se.lublin.humla.state.applyUserState
import se.lublin.humla.state.applyVersion
import se.lublin.humla.state.applyVoiceTargetIntent
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.state.VoiceTargetMode

/**
 * One connection. Created by the client for each attempt and never reused.
 * See docs/tech-debt-whales/target-architecture/client.md#session.
 *
 * Android-free: no android.* imports. All lifecycle logic runs in the
 * session scope; intents are non-blocking (one atomic update and/or one
 * enqueued TCP message). Decisions that depend on current state happen
 * inside update, never after reading state.value.
 */
class Session internal constructor(
    val id: Long,
    val target: ConnectTarget,
    parentScope: CoroutineScope,
    val transport: Transport,
    val localFacts: LocalFacts,
    private val voiceLink: VoiceLink,
    private val observer: IHumlaObserver,
    private val localFactsStore: LocalFactsStore,
    private val mainPoster: (Runnable) -> Unit,
    private val denyFormatter: (SessionEvent.PermissionDenied) -> String,
    private val workDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + workDispatcher)

    private val initialState: SessionState = run {
        var s = emptySessionState()
        if (localFacts.selfMuted || localFacts.selfDeafened) {
            s = s.copy(
                local = s.local.copy(
                    selfMuteDesired = localFacts.selfMuted,
                    selfDeafDesired = localFacts.selfDeafened,
                ),
            )
        }
        s
    }

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(SessionStats.initial())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val failureDeferred = CompletableDeferred<ClientError>()
    val failure get() = failureDeferred

    // Private sync builder: SYNCING applies here without publishing.
    // Published once on ServerSync, then every batch publishes once.
    private var syncingState: SessionState = initialState
    private var synced = false

    init {
        scope.launch {
            try {
                transport.failure.await()
            } catch (e: Exception) {
                return@launch
            }
            val error = try {
                transport.failure.getCompleted()
            } catch (e: Exception) {
                return@launch
            }
            failOnce(error)
        }
        scope.launch {
            try {
                voiceLink.events.collect { event ->
                    when (event) {
                        is se.lublin.humla.net.VoiceEvent.UdpUnavailable -> {
                            _events.emit(SessionEvent.ConnectionWarning("UDP unavailable, switched to TCP"))
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                // Scope cancelled.
            }
        }
        scope.launch {
            try {
                transport.stats.collect { t ->
                    val local = try {
                        voiceLink.status.value.localCrypt
                    } catch (e: Exception) {
                        CryptCounts.zero()
                    }
                    _stats.update {
                        it.copy(
                            tcpLatencyMicros = t.tcpLatencyMicros,
                            udpLatencyMicros = t.udpLatencyMicros,
                            usingUdp = t.usingUdp,
                            localCrypt = local,
                            remoteCrypt = t.remoteCrypt,
                        )
                    }
                }
            } catch (e: Exception) {
            }
        }
        scope.launch {
            try {
                voiceLink.status.collect { v ->
                    _stats.update { it.copy(localCrypt = v.localCrypt) }
                }
            } catch (e: Exception) {
            }
        }
        scope.launch {
            runMessages()
        }
    }

    private fun failOnce(error: ClientError) {
        failureDeferred.complete(error)
    }

    /** Sends Version + Authenticate, then drains inbound. Runs in session scope. */
    private suspend fun runMessages() {
        try {
            val version = Constants.createVersionMessage(target.clientName).build()
            transport.send(version, HumlaTCPMessageType.Version)
            val auth = Mumble.Authenticate.newBuilder()
                .setUsername(target.server.username)
                .setOpus(true)
            if (target.server.password != null) {
                auth.password = target.server.password
            }
            auth.addAllTokens(target.accessTokens)
            transport.send(auth.build(), HumlaTCPMessageType.Authenticate)
        } catch (e: Exception) {
            failOnce(ClientError.Connection("Failed to send handshake: ${e.message}", e))
            return
        }
        try {
            for (first in transport.inbound) {
                val batch = ArrayList<Inbound>(64)
                batch.add(first)
                while (batch.size < 64) {
                    val next = transport.inbound.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                for (item in batch) {
                    when (item) {
                        is Inbound.Tunnelled -> {
                            try {
                                voiceLink.queueTunnelled(item.packet)
                            } catch (e: Exception) {
                            }
                        }
                        is Inbound.Message -> handleMessage(item.type, item.message)
                    }
                    if (failureDeferred.isCompleted) return
                }
            }
        } catch (e: Exception) {
            // Inbound closed on Transport.close (clean shutdown); failure
            // already completed for fatal transport errors.
        }
    }

    private suspend fun emitAll(events: List<SessionEvent>) {
        for (event in events) {
            _events.emit(event)
        }
    }

    private fun postObserver(r: Runnable) {
        try {
            mainPoster(r)
        } catch (e: Exception) {
            // Poster torn down (service destroyed); drop the callback.
        }
    }

    private suspend fun handleMessage(type: HumlaTCPMessageType, message: MessageLite) {
        when (type) {
            HumlaTCPMessageType.Version -> {
                val msg = message as Mumble.Version
                if (!synced) {
                    syncingState = applyVersion(syncingState, msg).state
                } else {
                    var snap: SessionState? = null
                    _state.update { current ->
                        val next = applyVersion(current, msg).state
                        snap = next
                        next
                    }
                }
            }
            HumlaTCPMessageType.CodecVersion -> {
                val msg = message as Mumble.CodecVersion
                if (!synced) {
                    syncingState = applyCodecVersion(syncingState, msg).state
                } else {
                    _state.update { current -> applyCodecVersion(current, msg).state }
                }
            }
            HumlaTCPMessageType.ChannelState -> {
                val msg = message as Mumble.ChannelState
                if (!msg.hasChannelId()) return
                val id = msg.channelId
                if (!synced) {
                    val before = syncingState.server.channels.containsKey(id)
                    syncingState = applyChannelState(syncingState, msg).state
                    val after = syncingState.server.channels.containsKey(id)
                    if (!before && after) {
                        val handle = ChannelHandle(id, state)
                        postObserver(Runnable { observer.onChannelAdded(handle) })
                    }
                } else {
                    var added = false
                    _state.update { current ->
                        val next = applyChannelState(current, msg).state
                        added = !current.server.channels.containsKey(id) &&
                            next.server.channels.containsKey(id)
                        next
                    }
                    val handle = ChannelHandle(id, state)
                    postObserver(
                        Runnable {
                            if (added) observer.onChannelAdded(handle) else observer.onChannelStateUpdated(handle)
                        },
                    )
                }
            }
            HumlaTCPMessageType.ChannelRemove -> {
                val msg = message as Mumble.ChannelRemove
                val id = msg.channelId
                if (!synced) {
                    if (id == 0 || !syncingState.server.channels.containsKey(id)) return
                    syncingState = applyChannelRemove(syncingState, msg).state
                } else {
                    var removedInfos: List<ChannelInfo> = emptyList()
                    var known = false
                    _state.update { current ->
                        known = current.server.channels.containsKey(id)
                        if (id == 0 || !known) return@update current
                        val next = applyChannelRemove(current, msg).state
                        val removedIds = current.server.channels.keys - next.server.channels.keys
                        fun depthOf(start: Int): Int {
                            var depth = 0
                            var node: Int? = start
                            val seen = HashSet<Int>()
                            while (node != null && removedIds.contains(node) && seen.add(node)) {
                                node = current.server.channels[node]?.parentId
                                depth++
                            }
                            return depth
                        }
                        removedInfos = removedIds.mapNotNull { current.server.channels[it] }
                            .sortedByDescending { depthOf(it.id) }
                        next
                    }
                    if (id == 0 || !known) return
                    for (info in removedInfos) {
                        val seeded = ChannelHandle(info.id, state, seed = info)
                        postObserver(Runnable { observer.onChannelRemoved(seeded) })
                    }
                }
            }
            HumlaTCPMessageType.UserState -> {
                val msg = message as Mumble.UserState
                if (!msg.hasSession()) return
                val session = msg.session
                if (!synced) {
                    val before = syncingState.server.users[session]
                    val result = applyUserState(
                        syncingState, msg,
                        localFacts.mutedUserIds, localFacts.ignoredUserIds,
                    )
                    syncingState = result.state
                    // No observer fanout pre-sync (initial dump fanned on ServerSync).
                    // No events pre-sync (transitions suppress informational).
                    if (result.events.isNotEmpty()) {
                        emitAll(result.events)
                    }
                } else {
                    var isNew = false
                    var oldChannelId: Int? = null
                    var newChannelId: Int? = null
                    var outcomeEvents: List<SessionEvent> = emptyList()
                    _state.update { current ->
                        val oldUser = current.server.users[session]
                        isNew = oldUser == null
                        oldChannelId = oldUser?.channelId
                        val result = applyUserState(
                            current, msg,
                            localFacts.mutedUserIds, localFacts.ignoredUserIds,
                        )
                        outcomeEvents = result.events
                        newChannelId = result.state.server.users[session]?.channelId
                        result.state
                    }
                    emitAll(outcomeEvents)
                    val handle = UserHandle(session, state)
                    postObserver(
                        Runnable {
                            if (isNew) {
                                observer.onUserConnected(handle)
                            } else {
                                observer.onUserStateUpdated(handle)
                                if (newChannelId != oldChannelId) {
                                    val newChannel = if (newChannelId != null) {
                                        ChannelHandle(newChannelId, state)
                                    } else {
                                        null
                                    }
                                    val oldChannel = if (oldChannelId != null) {
                                        ChannelHandle(oldChannelId, state)
                                    } else {
                                        null
                                    }
                                    observer.onUserJoinedChannel(handle, newChannel, oldChannel)
                                }
                            }
                        },
                    )
                }
            }
            HumlaTCPMessageType.UserRemove -> {
                val msg = message as Mumble.UserRemove
                val session = msg.session
                val selfSession = if (synced) _state.value.server.selfSession else syncingState.server.selfSession
                if (session == selfSession) {
                    val actor = if (msg.hasActor()) msg.actor else null
                    val reason = if (msg.hasReason()) msg.reason else null
                    failOnce(ClientError.Removed(actor, reason, msg.ban))
                    // Apply removal to state as well (HumlaService cleared on disconnect).
                    if (!synced) {
                        syncingState = applyUserRemove(syncingState, msg).state
                    } else {
                        _state.update { current -> applyUserRemove(current, msg).state }
                    }
                    return
                }
                if (!synced) {
                    val oldInfo = syncingState.server.users[session]
                    val actorName = if (msg.hasActor()) syncingState.server.users[msg.actor]?.name else null
                    val result = applyUserRemove(syncingState, msg, oldInfo?.name, actorName)
                    syncingState = result.state
                    if (result.events.isNotEmpty()) emitAll(result.events)
                } else {
                    var oldInfo: UserInfo? = null
                    var outcomeEvents: List<SessionEvent> = emptyList()
                    _state.update { current ->
                        oldInfo = current.server.users[session]
                        val actorName = if (msg.hasActor()) current.server.users[msg.actor]?.name else null
                        val result = applyUserRemove(current, msg, oldInfo?.name, actorName)
                        outcomeEvents = result.events
                        result.state
                    }
                    emitAll(outcomeEvents)
                    val reason = outcomeEvents.filterIsInstance<SessionEvent.UserLeft>().firstOrNull()?.reason
                    val seed = oldInfo
                    postObserver(
                        Runnable {
                            if (seed != null) {
                                observer.onUserRemoved(UserHandle(session, state, seed = seed), reason)
                            } else {
                                observer.onUserRemoved(null, reason)
                            }
                        },
                    )
                }
            }
            HumlaTCPMessageType.ServerSync -> {
                val msg = message as Mumble.ServerSync
                if (!synced) {
                    val result = applyServerSync(syncingState, msg)
                    syncingState = result.state
                    synced = true
                    _state.value = syncingState
                    emitAll(result.events)
                    // Fan out the initial dump for screens still on observers.
                    for ((id) in syncingState.server.channels) {
                        val handle = ChannelHandle(id, state)
                        postObserver(Runnable { observer.onChannelAdded(handle) })
                    }
                    for ((sessionId) in syncingState.server.users) {
                        val handle = UserHandle(sessionId, state)
                        postObserver(Runnable { observer.onUserConnected(handle) })
                    }
                    try {
                        transport.startKeepalive()
                    } catch (e: Exception) {
                    }
                    // Restored self-mute, as MumlaService.onConnectionSynchronized does.
                    if (localFacts.selfMuted || localFacts.selfDeafened) {
                        setSelfMuteDeaf(localFacts.selfMuted, localFacts.selfDeafened)
                    }
                } else {
                    var outcomeEvents: List<SessionEvent> = emptyList()
                    _state.update { current ->
                        val result = applyServerSync(current, msg)
                        outcomeEvents = result.events
                        result.state
                    }
                    emitAll(outcomeEvents)
                }
            }
            HumlaTCPMessageType.ServerConfig -> {
                val msg = message as Mumble.ServerConfig
                if (!synced) {
                    syncingState = applyServerConfig(syncingState, msg).state
                } else {
                    _state.update { current -> applyServerConfig(current, msg).state }
                }
            }
            HumlaTCPMessageType.PermissionQuery -> {
                val msg = message as Mumble.PermissionQuery
                if (!synced) {
                    syncingState = applyPermissionQuery(syncingState, msg).state
                } else {
                    var known = false
                    _state.update { current ->
                        known = current.server.channels.containsKey(msg.channelId)
                        applyPermissionQuery(current, msg).state
                    }
                    if (known) {
                        val handle = ChannelHandle(msg.channelId, state)
                        postObserver(Runnable { observer.onChannelPermissionsUpdated(handle) })
                    }
                }
            }
            HumlaTCPMessageType.PermissionDenied -> {
                val msg = message as Mumble.PermissionDenied
                var denied: SessionEvent.PermissionDenied? = null
                if (!synced) {
                    val result = applyPermissionDenied(syncingState, msg)
                    syncingState = result.state
                    denied = result.events.filterIsInstance<SessionEvent.PermissionDenied>().firstOrNull()
                    if (denied != null) emitAll(listOf(denied))
                } else {
                    _state.update { current ->
                        val result = applyPermissionDenied(current, msg)
                        denied = result.events.filterIsInstance<SessionEvent.PermissionDenied>().firstOrNull()
                        result.state
                    }
                    val event = denied
                    if (event != null) {
                        emitAll(listOf(event))
                        val text = try {
                            denyFormatter(event)
                        } catch (e: Exception) {
                            "Permission denied"
                        }
                        postObserver(Runnable { observer.onPermissionDenied(text) })
                    }
                }
            }
            HumlaTCPMessageType.TextMessage -> {
                val msg = message as Mumble.TextMessage
                var received: SessionEvent.TextMessageReceived? = null
                if (!synced) {
                    val result = applyTextMessage(syncingState, msg, System.currentTimeMillis())
                    syncingState = result.state
                    received = result.events.filterIsInstance<SessionEvent.TextMessageReceived>().firstOrNull()
                    if (received != null) emitAll(listOf(received))
                } else {
                    _state.update { current ->
                        val result = applyTextMessage(current, msg, System.currentTimeMillis())
                        received = result.events.filterIsInstance<SessionEvent.TextMessageReceived>().firstOrNull()
                        result.state
                    }
                    val event = received
                    if (event != null) {
                        emitAll(listOf(event))
                        val built = buildMessage(event)
                        postObserver(Runnable { observer.onMessageLogged(built) })
                    }
                }
            }
            HumlaTCPMessageType.Reject -> {
                val msg = message as Mumble.Reject
                failOnce(ClientError.Rejected(msg.type.number, if (msg.hasReason()) msg.reason else null))
            }
            HumlaTCPMessageType.CryptSetup,
            HumlaTCPMessageType.Ping,
            HumlaTCPMessageType.UDPTunnel,
            -> {
                // Owned by the Transport (intercepted in the reader); never forwarded.
            }
            else -> Unit
        }
    }

    private fun buildMessage(event: SessionEvent.TextMessageReceived): se.lublin.humla.model.IMessage {
        val channels = ArrayList<IChannel>(event.channels.size)
        for (id in event.channels) {
            if (_state.value.server.channels.containsKey(id)) {
                channels.add(ChannelHandle(id, state))
            }
        }
        val trees = ArrayList<IChannel>(event.trees.size)
        for (id in event.trees) {
            if (_state.value.server.channels.containsKey(id)) {
                trees.add(ChannelHandle(id, state))
            }
        }
        val users = ArrayList<IUser>(event.users.size)
        for (session in event.users) {
            if (_state.value.server.users.containsKey(session)) {
                users.add(UserHandle(session, state))
            }
        }
        return Message(
            event.actor ?: -1,
            event.actorName,
            channels,
            trees,
            users,
            event.message,
        )
    }

    /** Ends the session: cancels its scope; Transport.close stops socket/threads. */
    fun close() {
        try {
            transport.close()
        } catch (ignored: Exception) {
        }
        scope.cancel()
    }

    // Intents. Each is one atomic update and/or one enqueued TCP message.
    // No-ops returning nothing (or -1) when not SYNCED, never throwing.

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) {
        var events: List<SessionEvent> = emptyList()
        var outbound: Mumble.UserState? = null
        _state.update { current ->
            val result = applySelfMuteDeafIntent(current, mute, deaf)
            events = result.events
            outbound = result.userStateOutbound
            result.state
        }
        val out = outbound ?: return
        // Persist off the state update (fire-and-forget in session scope).
        scope.launch(ioDispatcher) {
            try {
                localFactsStore.setSelfMuteDeaf(out.selfMute, out.selfDeaf)
            } catch (ignored: Exception) {
            }
        }
        try {
            transport.send(out, HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
        scope.launch {
            emitAll(events)
            val s = _state.value
            val selfSession = s.server.selfSession
            if (selfSession != null && s.server.users.containsKey(selfSession)) {
                val handle = UserHandle(selfSession, state)
                postObserver(Runnable { observer.onUserStateUpdated(handle) })
            }
        }
    }

    fun setPtt(pressed: Boolean) {
        _state.update { current -> applyPttIntent(current, pressed).state }
    }

    fun setLocalMute(session: Int, muted: Boolean) {
        var userId: Int? = null
        _state.update { current ->
            userId = current.server.users[session]?.userId
            applyLocalMuteIntent(current, session, muted).state
        }
        val registered = userId
        if (registered != null) {
            val serverId = target.server.serverId
            scope.launch(ioDispatcher) {
                try {
                    localFactsStore.setLocalMute(serverId, registered, muted)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    fun setLocalIgnore(session: Int, ignored: Boolean) {
        var userId: Int? = null
        _state.update { current ->
            userId = current.server.users[session]?.userId
            applyLocalIgnoreIntent(current, session, ignored).state
        }
        val registered = userId
        if (registered != null) {
            val serverId = target.server.serverId
            scope.launch(ioDispatcher) {
                try {
                    localFactsStore.setLocalIgnore(serverId, registered, ignored)
                } catch (ignored2: Exception) {
                }
            }
        }
    }

    fun setVoiceTarget(id: Int) {
        _state.update { current -> applyVoiceTargetIntent(current, id).state }
    }

    fun registerWhisperTarget(target: se.lublin.humla.state.WhisperTarget): Int {
        var allocated = -1
        _state.update { current ->
            val result = applyRegisterWhisperIntent(current, target)
            allocated = result.whisperId ?: -1
            result.state
        }
        if (allocated < 0) return -1
        try {
            val vtbTarget = Mumble.VoiceTarget.Target.newBuilder()
                .setChannelId(target.channelId)
                .setLinks(target.includeLinked)
                .setChildren(target.includeSubchannels)
            if (target.group != null) {
                vtbTarget.group = target.group
            }
            val vtb = Mumble.VoiceTarget.newBuilder()
                .setId(allocated)
                .addTargets(vtbTarget.build())
                .build()
            transport.send(vtb, HumlaTCPMessageType.VoiceTarget)
        } catch (e: Exception) {
            _state.update { current -> applyUnregisterWhisperIntent(current, allocated).state }
            return -1
        }
        return allocated
    }

    fun unregisterWhisperTarget(id: Int) {
        if (id < 1 || id > 30) return
        _state.update { current -> applyUnregisterWhisperIntent(current, id).state }
    }

    fun setListening(channel: Int, on: Boolean) {
        val s = _state.value
        if (s.server.phase != SyncPhase.SYNCED) return
        val selfSession = s.server.selfSession ?: return
        try {
            val usb = Mumble.UserState.newBuilder().setSession(selfSession)
            if (on) {
                usb.addListeningChannelAdd(channel)
            } else {
                usb.addListeningChannelRemove(channel)
            }
            transport.send(usb.build(), HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun setListeningVolume(channel: Int, volume: Float) {
        val s = _state.value
        if (s.server.phase != SyncPhase.SYNCED) return
        val selfSession = s.server.selfSession ?: return
        var outbound: Mumble.UserState? = null
        _state.update { current ->
            val result = applyListeningVolumeIntent(current, channel, volume)
            outbound = result.userStateOutbound
            result.state
        }
        val out = outbound ?: return
        try {
            val withSession = out.toBuilder().setSession(selfSession).build()
            transport.send(withSession, HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun joinChannel(channel: Int) {
        val s = _state.value
        if (s.server.phase != SyncPhase.SYNCED) return
        val selfSession = s.server.selfSession ?: return
        moveUserToChannel(selfSession, channel)
    }

    fun moveUserToChannel(session: Int, channel: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val usb = Mumble.UserState.newBuilder().setSession(session).setChannelId(channel).build()
            transport.send(usb, HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun sendAccessTokens(tokens: List<String>) {
        try {
            val ab = Mumble.Authenticate.newBuilder().addAllTokens(tokens).build()
            transport.send(ab, HumlaTCPMessageType.Authenticate)
        } catch (ignored: Exception) {
        }
    }

    fun requestPermissions(channel: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val pq = Mumble.PermissionQuery.newBuilder().setChannelId(channel).build()
            transport.send(pq, HumlaTCPMessageType.PermissionQuery)
        } catch (ignored: Exception) {
        }
    }

    fun requestComment(session: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val rb = Mumble.RequestBlob.newBuilder().addSessionComment(session).build()
            transport.send(rb, HumlaTCPMessageType.RequestBlob)
        } catch (ignored: Exception) {
        }
    }

    fun requestTexture(session: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val rb = Mumble.RequestBlob.newBuilder().addSessionTexture(session).build()
            transport.send(rb, HumlaTCPMessageType.RequestBlob)
        } catch (ignored: Exception) {
        }
    }

    fun requestDescription(channel: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val rb = Mumble.RequestBlob.newBuilder().addChannelDescription(channel).build()
            transport.send(rb, HumlaTCPMessageType.RequestBlob)
        } catch (ignored: Exception) {
        }
    }

    fun registerUser(session: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val usb = Mumble.UserState.newBuilder().setSession(session).setUserId(0).build()
            transport.send(usb, HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val urb = Mumble.UserRemove.newBuilder().setSession(session).setBan(ban)
            if (reason != null) urb.reason = reason
            transport.send(urb.build(), HumlaTCPMessageType.UserRemove)
        } catch (ignored: Exception) {
        }
    }

    fun setUserComment(session: Int, comment: String?) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val usb = Mumble.UserState.newBuilder().setSession(session)
            if (comment != null) usb.comment = comment
            transport.send(usb.build(), HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun setPrioritySpeaker(session: Int, priority: Boolean) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val usb = Mumble.UserState.newBuilder().setSession(session).setPrioritySpeaker(priority).build()
            transport.send(usb, HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        var m = mute
        var d = deaf
        if (d) m = true
        if (!m) d = false
        try {
            val usb = Mumble.UserState.newBuilder().setSession(session).setMute(m).setDeaf(d)
            if (!m) usb.suppress = false
            transport.send(usb.build(), HumlaTCPMessageType.UserState)
        } catch (ignored: Exception) {
        }
    }

    fun linkChannels(a: Int, b: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val csb = Mumble.ChannelState.newBuilder().setChannelId(a).addLinksAdd(b).build()
            transport.send(csb, HumlaTCPMessageType.ChannelState)
        } catch (ignored: Exception) {
        }
    }

    fun unlinkChannels(a: Int, b: Int) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val csb = Mumble.ChannelState.newBuilder().setChannelId(a).addLinksRemove(b).build()
            transport.send(csb, HumlaTCPMessageType.ChannelState)
        } catch (ignored: Exception) {
        }
    }

    fun unlinkAllChannels(channel: Int) {
        val s = _state.value
        if (s.server.phase != SyncPhase.SYNCED) return
        val info = s.server.channels[channel] ?: return
        try {
            val csb = Mumble.ChannelState.newBuilder().setChannelId(channel)
            for (linked in info.links) {
                csb.addLinksRemove(linked)
            }
            transport.send(csb.build(), HumlaTCPMessageType.ChannelState)
        } catch (ignored: Exception) {
        }
    }

    fun sendText(channels: List<Int>, trees: List<Int>, users: List<Int>, message: String) {
        if (_state.value.server.phase != SyncPhase.SYNCED) return
        try {
            val tmb = Mumble.TextMessage.newBuilder().setMessage(message)
            for (c in channels) tmb.addChannelId(c)
            for (t in trees) tmb.addTreeId(t)
            for (u in users) tmb.addSession(u)
            transport.send(tmb.build(), HumlaTCPMessageType.TextMessage)
        } catch (ignored: Exception) {
            return
        }
        scope.launch {
            _events.emit(
                SessionEvent.TextMessageSent(
                    channels = channels,
                    trees = trees,
                    users = users,
                    message = message,
                    sentAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setComment(session: Int, comment: String?) = setUserComment(session, comment)

    // Old IHumlaSession reads, for HumlaService delegation.
    fun getTCPLatency(): Long = stats.value.tcpLatencyMicros ?: throw IllegalStateException("No TCP latency yet")

    fun getUDPLatency(): Long = stats.value.udpLatencyMicros ?: throw IllegalStateException("No UDP latency yet")

    fun getMaxBandwidth(): Int = state.value.server.info.maxBandwidth ?: -1

    fun getServerVersion(): Int {
        val v2 = state.value.server.info.versionV2 ?: throw IllegalStateException("Not synced")
        return Constants.toLegacyVersion(v2)
    }

    fun getServerVersionV2(): Long {
        return state.value.server.info.versionV2 ?: throw IllegalStateException("Not synced")
    }

    fun getServerRelease(): String? = state.value.server.info.release

    fun getServerOSName(): String? = state.value.server.info.os

    fun getServerOSVersion(): String? = state.value.server.info.osVersion

    fun getSessionId(): Int {
        return state.value.server.selfSession ?: throw IllegalStateException("Not synced")
    }

    fun getSessionUser(): IUser? {
        val s = state.value
        val session = s.server.selfSession ?: return null
        if (!s.server.users.containsKey(session)) return null
        return UserHandle(session, state)
    }

    fun getSessionChannel(): IChannel? {
        return getSessionUser()?.channel
    }

    fun getUser(session: Int): IUser? {
        val s = state.value
        if (!s.server.users.containsKey(session)) return null
        return UserHandle(session, state)
    }

    fun getChannel(id: Int): IChannel? {
        val s = state.value
        if (!s.server.channels.containsKey(id)) return null
        return ChannelHandle(id, state)
    }

    fun getRootChannel(): IChannel? = getChannel(0)

    fun getPermissions(): Int = state.value.server.rootPermissions

    fun getServerSettings(): ServerSettings? {
        val info = state.value.server.info
        val settings = info.settings ?: return null
        val msg = Mumble.ServerConfig.newBuilder()
            .setAllowHtml(settings.allowHtml)
            .setMessageLength(settings.messageLength)
            .setImageMessageLength(settings.imageMessageLength)
            .setMaxBandwidth(info.maxBandwidth ?: -1)
            .setMaxUsers(settings.maxUsers)
            .setWelcomeText(info.welcomeText ?: "")
            .build()
        return ServerSettings(msg)
    }

    fun sendUserTextMessage(session: Int, message: String?): Message? {
        if (state.value.server.phase != SyncPhase.SYNCED) throw IllegalStateException("Not synced")
        if (message == null) return null
        sendText(emptyList(), emptyList(), listOf(session), message)
        val s = state.value
        val selfSession = s.server.selfSession ?: throw IllegalStateException("Not synced")
        val selfName = s.server.users[selfSession]?.name
        val users = ArrayList<IUser>()
        if (s.server.users.containsKey(session)) users.add(UserHandle(session, state))
        return Message(selfSession, selfName, emptyList(), emptyList(), users, message)
    }

    fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message? {
        if (state.value.server.phase != SyncPhase.SYNCED) throw IllegalStateException("Not synced")
        if (message == null) return null
        if (tree) {
            sendText(emptyList(), listOf(channel), emptyList(), message)
        } else {
            sendText(listOf(channel), emptyList(), emptyList(), message)
        }
        val s = state.value
        val selfSession = s.server.selfSession ?: throw IllegalStateException("Not synced")
        val selfName = s.server.users[selfSession]?.name
        val targetChannels = ArrayList<IChannel>()
        if (s.server.channels.containsKey(channel)) targetChannels.add(ChannelHandle(channel, state))
        return Message(
            selfSession, selfName, targetChannels,
            if (tree) targetChannels else emptyList(), emptyList(), message,
        )
    }

    fun getVoiceTargetId(): Int = state.value.local.voiceTargetId

    fun getVoiceTargetMode(): VoiceTargetMode = VoiceTargetMode.fromInt(state.value.local.voiceTargetId)

    fun getWhisperTarget(): se.lublin.humla.state.WhisperTarget? {
        val id = state.value.local.voiceTargetId
        if (id in 1..30) return state.value.local.whisperTargets[id]
        return null
    }

    fun isListeningToChannel(channelId: Int): Boolean {
        val self = state.value.server.self ?: return false
        return self.listeningChannels.contains(channelId)
    }
}
