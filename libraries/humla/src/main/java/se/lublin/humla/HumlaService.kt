/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import org.minidns.hla.ResolverApi
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.AndroidAudioPlatform
import se.lublin.humla.audio.AppliedRoute
import se.lublin.humla.audio.AudioRouteController
import se.lublin.humla.audio.RouteEvent
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.humla.audio.ScoLink
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientCertificate
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.client.HumlaClient
import se.lublin.humla.client.LocalFacts
import se.lublin.humla.client.LocalFactsStore
import se.lublin.humla.client.NetworkMonitor
import se.lublin.humla.client.ReconnectPolicy
import se.lublin.humla.client.ServerEndpoint
import se.lublin.humla.client.Session
import se.lublin.humla.client.TrustStoreRef
import se.lublin.humla.exception.AudioException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.net.DefaultTransportFactory
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.LegacyVoiceLink
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.InputModes
import se.lublin.humla.protocol.StateEventLogger
import se.lublin.humla.protocol.validateAudioDelta
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.emptySessionState as emptyState
import se.lublin.humla.util.ConfigDelta
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.collections.immutable.toPersistentList

private fun emptySessionState(): SessionState = emptyState()

/**
 * Immutable pending connection parts for the next connect (main-confined
 * writes via apply, atomic reads for connect). ByteArray needs content
 * equality (see CertRef): the generated data-class equals would compare
 * certificate references, so equals/hashCode are manual.
 */
private data class PendingConnection(
    val server: Server? = null,
    val certificate: ByteArray? = null,
    val certificatePassword: String? = null,
    val forceTcp: Boolean = false,
    val clientName: String? = null,
    val accessTokens: List<String>? = null,
    val trustStore: String? = null,
    val trustStorePassword: String? = null,
    val trustStoreFormat: String? = null,
    val localMuteHistory: List<Int>? = null,
    val localIgnoreHistory: List<Int>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingConnection) return false
        return server === other.server &&
            ((certificate === other.certificate) ||
                (certificate != null && other.certificate != null &&
                    certificate.contentEquals(other.certificate))) &&
            certificatePassword == other.certificatePassword &&
            forceTcp == other.forceTcp &&
            clientName == other.clientName &&
            accessTokens == other.accessTokens &&
            trustStore == other.trustStore &&
            trustStorePassword == other.trustStorePassword &&
            trustStoreFormat == other.trustStoreFormat &&
            localMuteHistory == other.localMuteHistory &&
            localIgnoreHistory == other.localIgnoreHistory
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(server)
        result = 31 * result + (certificate?.contentHashCode() ?: 0)
        result = 31 * result + (certificatePassword?.hashCode() ?: 0)
        result = 31 * result + forceTcp.hashCode()
        result = 31 * result + (clientName?.hashCode() ?: 0)
        result = 31 * result + (accessTokens?.hashCode() ?: 0)
        result = 31 * result + (trustStore?.hashCode() ?: 0)
        result = 31 * result + (trustStorePassword?.hashCode() ?: 0)
        result = 31 * result + (trustStoreFormat?.hashCode() ?: 0)
        result = 31 * result + (localMuteHistory?.hashCode() ?: 0)
        result = 31 * result + (localIgnoreHistory?.hashCode() ?: 0)
        return result
    }
}

open class HumlaService : LifecycleService(), IHumlaService, IHumlaSession, HumlaLogger {

    companion object {
        private val TAG = "HumlaService"

        const val ACTION_CONNECT = "se.lublin.humla.CONNECT"

        const val RECONNECT_DELAY_INITIAL = ReconnectPolicy.RECONNECT_DELAY_INITIAL
        const val RECONNECT_DELAY_MAX = ReconnectPolicy.RECONNECT_DELAY_MAX

        private const val WAKELOCK_GRACE_MS = 15000L

        @JvmStatic
        fun init(context: Context?) {
            try {
                val appContext = context?.applicationContext
                if (appContext != null) {
                    AndroidUsingLinkProperties.setup(appContext)
                }
                ResolverApi.INSTANCE.client.dataSource.setTimeout(800)
            } catch (e: Exception) {
                Log.w(TAG, "Could not configure MiniDNS: $e")
            }
        }

        @JvmStatic
        fun getReconnectDelayForAttempts(attempts: Int): Int =
            ReconnectPolicy.delayForAttempts(attempts)
    }

    private lateinit var mAudioBuilder: AudioHandler.Builder
    private val inputModes = InputModes()
    private lateinit var route: AudioRouteController

    private val routeHost = object : AudioRouteController.Host {
        override val isLive: Boolean
            get() = clientStateIsConnected()
        override val sessionToken: Int
            get() = currentSessionId()
        override fun hasLivePipeline(): Boolean {
            val handler = audioHandlerRef.get()
            return handler != null && handler.isInitialized()
        }
        override fun recreatePipeline() {
            createAudioHandler()
        }
        override fun onPipelineError(message: String?) {
            logWarning(message)
        }
        override fun onRouteChanged() {
            onAudioRouteChanged()
            mCallbacks.onAudioRouteChanged()
        }
        override fun onScoChanged(active: Boolean) {
            mCallbacks.onBluetoothScoChanged(active)
        }
        override fun onScoTeardown() {
            onAudioRouteChanged()
            mCallbacks.onBluetoothScoChanged(false)
        }
        override fun onBluetoothScoFailed(reason: String?) {
            mCallbacks.onBluetoothScoFailed(reason)
        }
    }

    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null
    private lateinit var mHandler: Handler
    private lateinit var mCallbacks: HumlaCallbacks

    private val audioHandlerRef = AtomicReference<AudioHandler?>(null)

    // Mirrored session state: single flow across reconnects so live handles
    // survive (Session owns per-attempt flows; this mirrors the current one).
    private val _sessionState = MutableStateFlow(emptySessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var stateLogger: StateEventLogger? = null

    private lateinit var voiceLink: LegacyVoiceLink
    private lateinit var client: HumlaClient
    private val networkFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    private val pendingConnection = AtomicReference(PendingConnection())
    private val pendingVoiceTarget = AtomicInteger(0)
    private val lastServer = AtomicReference<Server?>(null)

    private val mAudioInputListener: AudioHandler.AudioEncodeListener =
        object : AudioHandler.AudioEncodeListener {
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                try {
                    val session = currentSession()
                    if (session != null && session.state.value.server.phase == SyncPhase.SYNCED) {
                        voiceLink.sendVoice(data, length)
                    }
                } catch (e: Exception) {
                    // Best effort; transport failure is reported via Session.failure.
                }
            }

            override fun onTalkingStateChanged(talking: Boolean) {
                lifecycleScope.launch {
                    try {
                        if (!isSynchronized())
                            return@launch
                        val session = currentSession() ?: return@launch
                        val selfSession = try {
                            session.getSessionId()
                        } catch (e: IllegalStateException) {
                            return@launch
                        }
                        if (_sessionState.value.server.users[selfSession] == null) return@launch
                        val currentUser = UserHandle(selfSession, sessionState)
                        currentUser.talkState =
                            if (talking) TalkState.TALKING else TalkState.PASSIVE
                        mCallbacks.onUserTalkStateUpdated(currentUser)
                    } catch (e: Exception) {
                        Log.w(TAG, "Talking-state update after unsync", e)
                    }
                }
            }
        }

    private val mAudioOutputListener: AudioOutput.AudioOutputListener =
        object : AudioOutput.AudioOutputListener {
            override fun onUserTalkStateUpdated(user: IUser) {
                mCallbacks.onUserTalkStateUpdated(user)
            }

            override fun getUser(session: Int): IUser? {
                if (_sessionState.value.server.users[session] == null) return null
                return UserHandle(session, sessionState)
            }
        }

    private fun clientStateIsConnected(): Boolean {
        if (!::client.isInitialized) return false
        return client.state.value is ClientState.Connected
    }

    private fun currentSessionId(): Int {
        return try {
            client.currentSession.value?.id?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun currentSession(): Session? {
        if (!::client.isInitialized) return null
        return try {
            client.currentSession.value
        } catch (e: Exception) {
            null
        }
    }

    // Binder contract: throws IllegalStateException (unchecked) when there is
    // no live synced session, never the checked NotSynchronizedException, so
    // Java/Kotlin callers only ever see the documented ISE.
    private fun requireSession(): Session {
        val session = currentSession()
            ?: throw IllegalStateException("No active connection")
        if (session.state.value.server.phase != SyncPhase.SYNCED) {
            throw IllegalStateException("Not synchronized")
        }
        return session
    }

    private fun stopSelfIfIdle(startId: Int) {
        if (!::client.isInitialized || client.state.value is ClientState.Idle) {
            stopSelf(startId)
        }
    }

    private fun acquireWifiLock() {
        try {
            val wifiLock = mWifiLock
            if (wifiLock != null && !wifiLock.isHeld) {
                wifiLock.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WifiLock.", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            val wifiLock = mWifiLock
            if (wifiLock != null && wifiLock.isHeld) {
                wifiLock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release WifiLock.", e)
        }
    }

    protected open fun buildConnectDelta(intent: Intent?): ConfigDelta? {
        return null
    }

    open override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val isConnect = ACTION_CONNECT == intent.action
            // Pre-onCreate commands cannot connect: lateinits (client, route,
            // builder) are untouched, so stop idle instead of crashing.
            if (isConnect && !::client.isInitialized) {
                Log.w(TAG, "$ACTION_CONNECT before onCreate; ignoring.")
                stopSelfIfIdle(startId)
                return START_NOT_STICKY
            }
            // Guard initialization (unit tests construct the service without
            // onCreate): without a client there is nothing to connect.
            if (isConnect && ::client.isInitialized && client.state.value is ClientState.Connecting) {
                Log.v(TAG, "Ignoring duplicate connect request while already connecting")
                return START_NOT_STICKY
            }
            if (isConnect) {
                val delta = buildConnectDelta(intent)
                if (delta == null || delta.server == null) {
                    Log.w(TAG, "$ACTION_CONNECT requires a server; ignoring.")
                    stopSelfIfIdle(startId)
                    return START_NOT_STICKY
                }
                try {
                    apply(delta)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Ignoring invalid service configuration.", e)
                    stopSelfIfIdle(startId)
                    return START_NOT_STICKY
                }
            }

            if (isConnect) {
                connect()
            }
        }

        return START_NOT_STICKY
    }

    open override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager?
        if (powerManager != null) {
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService")
            wakeLock.setReferenceCounted(false)
            mWakeLock = wakeLock
        } else {
            Log.w(TAG, "No PowerManager; running without wake lock")
            mWakeLock = null
        }

        try {
            val wifiManager = applicationContext
                .getSystemService(WIFI_SERVICE) as WifiManager?
            if (wifiManager != null) {
                val wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Humla:HumlaServiceWifi"
                )
                wifiLock.setReferenceCounted(false)
                mWifiLock = wifiLock
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create WifiLock; radio may sleep on battery.", e)
            mWifiLock = null
        }

        mHandler = Handler(mainLooper)
        mCallbacks = HumlaCallbacks()
        stateLogger = StateEventLogger(this, this)
        mAudioBuilder = AudioHandler.Builder()
            .setContext(this)
            .setLogger(this)
            .setEncodeListener(mAudioInputListener)
            .setTalkingListener(mAudioOutputListener)
            .setFramesPerPacketListener { fpp ->
                try {
                    if (::voiceLink.isInitialized) {
                        voiceLink.setTargetFramesPerPacket(fpp)
                    }
                } catch (ignored: Exception) {
                }
            }
        val audioPlatform = try {
            AndroidAudioPlatform(getSystemService(Context.AUDIO_SERVICE) as AudioManager?)
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode: no AudioManager", e)
            AndroidAudioPlatform(null)
        }
        val scoLink: ScoLink? = try {
            BluetoothScoManager(this, object : BluetoothScoManager.Listener {
                override fun onScoConnected() {
                    if (::route.isInitialized) route.onScoConnected()
                }

                override fun onScoDisconnected() {
                    if (::route.isInitialized) route.onScoDisconnected()
                }

                override fun onScoFailed(reason: String?) {
                    if (::route.isInitialized) route.onScoFailed(reason)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "SCO: no AudioManager", e)
            null
        }
        route = AudioRouteController(audioPlatform, scoLink, lifecycleScope, routeHost)
        route.registerDeviceListener(mHandler)
        route.onInit()

        // Application-scoped voice link (survives reconnects). The tunnel sink
        // routes to the current session's transport (present only in tunnel mode).
        voiceLink = LegacyVoiceLink(
            tunnelSink = { packet ->
                try {
                    currentSession()?.transport?.sendTunnelled(packet)
                } catch (ignored: Exception) {
                }
            },
        )

        // LocalFacts persistence division of labor (legacy parity): the app
        // adapter writes mute/ignore straight to the database on toggle and
        // ConfigDelta reloads them at the next connect; the writes below
        // additionally keep the pending snapshot current so a reconnect in
        // this process picks them up even before the next apply(). Self
        // mute/deafen stays app-owned (settings, re-pushed post-sync).
        val localFactsStore = object : LocalFactsStore {
            override suspend fun load(serverId: Long): LocalFacts {
                val pending = pendingConnection.get()
                return LocalFacts(
                    mutedUserIds = (pending.localMuteHistory ?: emptyList()).toSet()
                        .let { kotlinx.collections.immutable.persistentSetOf(*it.toTypedArray()) },
                    ignoredUserIds = (pending.localIgnoreHistory ?: emptyList()).toSet()
                        .let { kotlinx.collections.immutable.persistentSetOf(*it.toTypedArray()) },
                    selfMuted = false,
                    selfDeafened = false,
                )
            }

            override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) {
                var current = pendingConnection.get()
                while (true) {
                    val list = current.localMuteHistory?.toMutableList() ?: mutableListOf()
                    if (muted) {
                        if (!list.contains(userId)) list.add(userId)
                    } else {
                        list.remove(userId)
                    }
                    val next = current.copy(localMuteHistory = list.toList())
                    if (pendingConnection.compareAndSet(current, next)) return
                    current = pendingConnection.get()
                }
            }
            override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) {
                var current = pendingConnection.get()
                while (true) {
                    val list = current.localIgnoreHistory?.toMutableList() ?: mutableListOf()
                    if (ignored) {
                        if (!list.contains(userId)) list.add(userId)
                    } else {
                        list.remove(userId)
                    }
                    val next = current.copy(localIgnoreHistory = list.toList())
                    if (pendingConnection.compareAndSet(current, next)) return
                    current = pendingConnection.get()
                }
            }
            override suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean) = Unit
        }

        val networkMonitor = object : NetworkMonitor {
            override val available = networkFlow.asSharedFlow()
        }

        // Always-registered network callback (Client collects only while
        // Reconnecting; repeated events while Connecting are ignored there).
        // Retained for unregister in onDestroy; the system holds binder refs.
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            connectivityManager = cm
            if (cm != null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        networkFlow.tryEmit(Unit)
                    }
                }
                networkCallback = callback
                cm.registerDefaultNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback: " + e.message)
        }

        client = HumlaClient(
            parentScope = lifecycleScope,
            transports = DefaultTransportFactory(),
            voiceLifecycle = voiceLink,
            routeLifecycle = object : se.lublin.humla.client.RouteLifecycle {
                override fun sessionStarted() {
                    route.onEvent(RouteEvent.SessionStarted)
                }

                override fun sessionEnded() {
                    route.onEvent(RouteEvent.SessionEnded)
                }
            },
            voiceLink = voiceLink,
            network = networkMonitor,
            localFactsStore = localFactsStore,
            clock = Clock.systemUTC(),
            observer = mCallbacks,
            mainPoster = { r -> mHandler.post(r) },
            denyFormatter = { event ->
                try {
                    stateLogger?.denyReason(event) ?: "Permission denied"
                } catch (e: Exception) {
                    "Permission denied"
                }
            },
        )

        // Mirror the current session's state into the single service flow so
        // live handles survive reconnects. Clear on disconnect. collectLatest:
        // the inner collection suspends forever, so a nested collect would
        // pin the first session and stall every reconnect after it.
        lifecycleScope.launch {
            try {
                client.currentSession.collectLatest { session ->
                    if (session == null) {
                        _sessionState.value = emptySessionState()
                    } else {
                        try {
                            session.state.collect { s ->
                                _sessionState.value = s
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        // Chat-log adapter: semantic events to today's logger strings.
        // collectLatest for the same reason: each session's event stream
        // ends with its scope, and the next session must be observed.
        lifecycleScope.launch {
            try {
                client.currentSession.collectLatest { session ->
                    if (session != null) {
                        try {
                            session.events.collect { event ->
                                try {
                                    stateLogger?.log(listOf(event), session.state.value)
                                } catch (ignored: Exception) {
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        // Foreground lifetime + Doze shield + app-level Connected/Disconnected
        // fanout (for MumlaService UI: restore mute, overlay, notifications).
        lifecycleScope.launch {
            try {
                client.state.collect { s ->
                    when (s) {
                        is ClientState.Idle -> {
                            try {
                                val wakeLock = mWakeLock
                                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
                            } catch (ignored: Exception) {
                            }
                            releaseWifiLock()
                            // Clean disconnect ends the pipeline too (Reconnecting
                            // and Failed do the same below); all three are
                            // idempotent no-ops when already torn down.
                            tearDownAudioPipeline()
                            _sessionState.value = emptySessionState()
                            resetVoiceTargetSilent()
                            onConnectionDisconnected(null)
                        }
                        is ClientState.Connecting -> {
                            try {
                                val wakeLock = mWakeLock
                                if (wakeLock != null) {
                                    if (wakeLock.isHeld) wakeLock.release()
                                    wakeLock.acquire(WAKELOCK_GRACE_MS)
                                }
                            } catch (ignored: Exception) {
                            }
                            acquireWifiLock()
                            mCallbacks.onConnecting()
                        }
                        is ClientState.Connected -> {
                            try {
                                val wakeLock = mWakeLock
                                if (wakeLock != null) {
                                    if (wakeLock.isHeld) wakeLock.release()
                                    wakeLock.acquire()
                                }
                            } catch (ignored: Exception) {
                            }
                            acquireWifiLock()
                            // Record the resolved host for the app's Server row.
                            try {
                                val session = s.session
                                val remoteHost = session.transport.remoteHost
                                val remotePort = session.transport.remotePort
                                lastServer.get()?.setResolved(remoteHost, remotePort)
                            } catch (ignored: Exception) {
                            }
                            // Apply a voice-target set while disconnected.
                            try {
                                val pending = pendingVoiceTarget.get()
                                if (pending != 0) {
                                    s.session.setVoiceTarget(pending)
                                    audioHandlerRef.get()?.setVoiceTargetId(pending.toByte())
                                }
                            } catch (ignored: Exception) {
                            }
                            onConnectionSynchronized()
                        }
                        is ClientState.Reconnecting -> {
                            try {
                                val wakeLock = mWakeLock
                                if (wakeLock != null) {
                                    if (wakeLock.isHeld) wakeLock.release()
                                    val delayMs = getReconnectDelay()
                                    wakeLock.acquire((delayMs + WAKELOCK_GRACE_MS).toLong())
                                }
                            } catch (ignored: Exception) {
                            }
                            acquireWifiLock()
                            tearDownAudioPipeline()
                            _sessionState.value = emptySessionState()
                            resetVoiceTargetSilent()
                            onConnectionDisconnected(s.lastError.toHumlaException())
                        }
                        is ClientState.Failed -> {
                            try {
                                val wakeLock = mWakeLock
                                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
                            } catch (ignored: Exception) {
                            }
                            releaseWifiLock()
                            tearDownAudioPipeline()
                            _sessionState.value = emptySessionState()
                            resetVoiceTargetSilent()
                            // Trust failures surface via onTLSHandshakeFailed below;
                            // still fan out the terminal disconnect for UI.
                            if (s.error is se.lublin.humla.client.ClientError.Trust) {
                                val trust = s.error
                                mCallbacks.onTLSHandshakeFailed(
                                    trust.chain.toTypedArray(),
                                    trust.failure,
                                    trust.verifiedHost,
                                )
                            }
                            onConnectionDisconnected(s.error.toHumlaExceptionOrNull())
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        init(this)
    }

    private fun se.lublin.humla.client.ClientError.toHumlaException(): HumlaException {
        return when (this) {
            is se.lublin.humla.client.ClientError.Connection ->
                HumlaException(message ?: "Connection error", cause, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
            is se.lublin.humla.client.ClientError.Rejected ->
                HumlaException("Rejected: $reason", null, HumlaException.HumlaDisconnectReason.REJECT)
            is se.lublin.humla.client.ClientError.Removed ->
                HumlaException(reason ?: "Removed", null, HumlaException.HumlaDisconnectReason.USER_REMOVE)
            is se.lublin.humla.client.ClientError.Trust ->
                HumlaException("TLS trust failure", null, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        }
    }

    private fun se.lublin.humla.client.ClientError.toHumlaExceptionOrNull(): HumlaException? {
        return when (this) {
            is se.lublin.humla.client.ClientError.Trust -> null
            else -> toHumlaException()
        }
    }

    // The session that owns the live pipeline (captured at creation).
    // teardownLiveSession-style races (teardown after the client swapped
    // sessions) must remove handlers from THIS session, not the current one.
    private val pipelineSession = AtomicReference<Session?>(null)

    private fun tearDownAudioPipeline() {
        val handler = audioHandlerRef.getAndSet(null) ?: return
        val owning = pipelineSession.getAndSet(null)
        try {
            owning?.removeTcpHandler(handler)
        } catch (ignored: Exception) {
        }
        try {
            voiceLink.removeUdpHandler(handler)
        } catch (ignored: Exception) {
        }
        try {
            handler.shutdown()
        } catch (ignored: Exception) {
        }
    }

    private fun resetVoiceTargetSilent() {
        val prev = pendingVoiceTarget.getAndSet(0)
        if (prev != 0) {
            mCallbacks.onVoiceTargetChanged(VoiceTargetMode.NORMAL)
        }
    }

    open override fun onDestroy() {
        // Order matters: disconnect first (async teardown onto the client
        // dispatcher), synchronous releases next, super.onDestroy() last so
        // the parented client scope survives until teardown is launched.
        try {
            if (::client.isInitialized) client.disconnect()
        } catch (ignored: Exception) {
        }
        try {
            val callback = networkCallback
            networkCallback = null
            if (callback != null) {
                try {
                    connectivityManager?.unregisterNetworkCallback(callback)
                } catch (ignored: Exception) {
                }
            }
            connectivityManager = null
        } catch (ignored: Exception) {
        }
        try {
            val wakeLock = mWakeLock
            if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
        } catch (ignored: Exception) {
        }
        try {
            route.onEvent(RouteEvent.Destroyed)
        } catch (ignored: Exception) {
        }
        tearDownAudioPipeline()
        _sessionState.value = emptySessionState()
        resetVoiceTargetSilent()
        releaseWifiLock()
        try {
            voiceLink.close()
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }

    open override fun onBind(intent: Intent): IBinder {
        return HumlaBinder(this)
    }

    protected open fun connect() {
        val pending = pendingConnection.get()
        val server = pending.server
        if (server == null) {
            Log.w(TAG, "connect() called with null server, ignoring")
            return
        }
        if (server.host == null) {
            Log.w(TAG, "connect() called with null host, ignoring")
            return
        }
        // Reset whisper routing explicitly (a prior WHISPER must not strand).
        val prevVoiceTarget = pendingVoiceTarget.getAndSet(0)
        if (prevVoiceTarget != 0) {
            mCallbacks.onVoiceTargetChanged(VoiceTargetMode.NORMAL)
        }
        lastServer.set(
            Server(server.id, server.name, server.host, server.port, server.username, server.password).also {
                it.resolvedHost = server.resolvedHost
                it.resolvedPort = server.resolvedPort
            },
        )
        val target = ConnectTarget(
            server = ServerEndpoint(
                serverId = server.id,
                name = server.name,
                host = requireNotNull(server.host),
                port = server.port,
                username = server.username,
                password = server.password,
            ),
            certificate = pending.certificate?.let {
                ClientCertificate(
                    pkcs12 = com.google.protobuf.ByteString.copyFrom(it),
                    password = pending.certificatePassword,
                )
            },
            trustStore = TrustStoreRef(
                path = pending.trustStore,
                password = pending.trustStorePassword,
                format = pending.trustStoreFormat,
            ).takeIf { pending.trustStore != null },
            forceTcp = pending.forceTcp,
            clientName = pending.clientName ?: "Mumla",
            accessTokens = (pending.accessTokens ?: emptyList()).toPersistentList(),
        )
        try {
            client.connect(target)
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
        }
    }

    open override fun disconnect() {
        try {
            route.stopScoWithNotify()
        } catch (ignored: Exception) {
        }
        try {
            if (::client.isInitialized) client.disconnect()
        } catch (ignored: Exception) {
        }
    }

    open override fun isBluetoothScoActive(): Boolean {
        return route.isScoActive()
    }

    open override fun isMediaRouteActive(): Boolean {
        return route.isMediaRouteActive
    }

    open override fun retryBluetoothSco(): Boolean {
        return route.retrySco()
    }

    open fun isConnectionEstablished(): Boolean {
        return clientStateIsConnected()
    }

    // Equivalent to isConnectionEstablished: Connected is published on
    // ServerSync, so a connected client is always synced. Kept as a separate
    // name for the app-layer call sites that distinguish the two phases.
    open fun isSynchronized(): Boolean {
        return clientStateIsConnected()
    }

    protected open fun onConnectionSynchronized() {
        Log.v(TAG, "Connected")
        mCallbacks.onConnected()
    }

    protected open fun onConnectionDisconnected(e: HumlaException?) {
        if (e != null) {
            Log.e(TAG, "Error: " + e.message)
        } else {
            Log.v(TAG, "Disconnected")
        }
        mCallbacks.onDisconnected(e)
    }

    open override fun logInfo(message: String?) {
        if (!isSynchronized())
            return
        mCallbacks.onLogInfo(message)
    }

    open override fun logWarning(message: String?) {
        mCallbacks.onLogWarning(message)
    }

    open override fun logError(message: String?) {
        mCallbacks.onLogError(message)
    }

    open override fun getReconnectDelay(): Int {
        if (!::client.isInitialized) return RECONNECT_DELAY_INITIAL
        val s = client.state.value
        val attempts = when (s) {
            is ClientState.Connecting -> s.attempt
            is ClientState.Reconnecting -> s.attempt
            else -> 0
        }
        return getReconnectDelayForAttempts(attempts)
    }

    protected open fun onAudioRouteChanged() {
    }

    @Throws(AudioException::class)
    private fun createAudioHandler() {
        if (!clientStateIsConnected()) {
            throw IllegalStateException("Attempted to instantiate audio handler when not connected!")
        }
        val session = currentSession()
            ?: throw IllegalStateException("Attempted to instantiate audio handler when not connected!")

        val existing = audioHandlerRef.get()
        if (existing != null) {
            // Remove from the owning session (usually current, but a rotation
            // between creation and retry would strand it otherwise).
            val owning = pipelineSession.get() ?: session
            try {
                owning.removeTcpHandler(existing)
            } catch (ignored: Exception) {
            }
            try {
                voiceLink.removeUdpHandler(existing)
            } catch (ignored: Exception) {
            }
            existing.shutdown()
        }

        val routeDecision = route.decision()
        when (routeDecision) {
            AppliedRoute.VOICE_SCO -> {
                mAudioBuilder.setScoEnabled(true)
                mAudioBuilder.setVoiceRouteEnabled(true)
                mAudioBuilder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                mAudioBuilder.setAudioStream(AudioManager.STREAM_VOICE_CALL)
            }
            AppliedRoute.VOICE_HANDSET -> {
                mAudioBuilder.setScoEnabled(false)
                mAudioBuilder.setVoiceRouteEnabled(true)
                mAudioBuilder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                mAudioBuilder.setAudioStream(route.handsetStream())
            }
            AppliedRoute.MEDIA -> {
                mAudioBuilder.setScoEnabled(false)
                mAudioBuilder.setVoiceRouteEnabled(false)
                mAudioBuilder.setAudioSource(MediaRecorder.AudioSource.MIC)
                mAudioBuilder.setAudioStream(AudioManager.STREAM_MUSIC)
            }
        }
        try {
            val s = session.state.value
            val selfInfo = s.server.self
            val maxBandwidth = s.server.info.maxBandwidth ?: -1
            val opus = s.server.info.opus
            val codec = if (opus) HumlaUDPMessageType.UDPVoiceOpus else null
            val voiceTargetId = s.local.voiceTargetId.toByte()
            val created = mAudioBuilder.initialize(
                selfInfo,
                maxBandwidth, codec,
                voiceTargetId,
            )
            val v2 = s.server.info.versionV2 ?: 0L
            created.setProtobufUdp(
                Constants.isProtobufUdpSupported(v2, Constants.toLegacyVersion(v2)),
            )
            session.addTcpHandler(created)
            voiceLink.addUdpHandler(created)
            voiceLink.setTargetFramesPerPacket(created.getFramesPerPacket())
            audioHandlerRef.set(created)
            pipelineSession.set(session)
        } catch (e: AudioException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw AudioException("Audio pipeline torn down during recreate", e)
        }
    }

    private fun isTrustStorePathAllowed(path: String?): Boolean {
        if (path == null) {
            return true
        }
        if (!path.endsWith(".bks", ignoreCase = true)) {
            return false
        }
        try {
            val filesDir = filesDir ?: return false
            val base = filesDir.canonicalFile.path
            val raw = File(path)
            val candidate = (if (raw.isAbsolute) raw else File(filesDir, path)).canonicalFile.path
            return candidate.startsWith(base + File.separator)
        } catch (e: IOException) {
            return false
        }
    }

    open fun apply(delta: ConfigDelta): Boolean {
        check(Looper.getMainLooper() == Looper.myLooper()) { "apply must be called on main" }
        inputModes.validateAudioDelta(delta)
        var reconnectNeeded = false
        var suppressReload = false
        // 1. Merge pending connection parts (lock-free AtomicReference;
        // main-confined writes, atomic reads for connect). Presence-based
        // reconnect: any delta carrying a reconnect-relevant key returns true.
        run {
            var updated: PendingConnection
            do {
                val current = pendingConnection.get()
                var next = current
                if (delta.server != null) {
                    val s = delta.server
                    val snapshot = Server(s.id, s.name, s.host, s.port, s.username, s.password).also {
                        it.resolvedHost = s.resolvedHost
                        it.resolvedPort = s.resolvedPort
                    }
                    next = next.copy(server = snapshot)
                    reconnectNeeded = true
                }
                val certificate = delta.certificate
                if (certificate != null) {
                    next = next.copy(
                        certificate = certificate.data.copyOf(),
                        certificatePassword = certificate.password ?: next.certificatePassword,
                    )
                    reconnectNeeded = true
                }
                val forceTcp = delta.forceTcp
                if (forceTcp != null) {
                    next = next.copy(forceTcp = forceTcp)
                    reconnectNeeded = true
                }
                if (delta.clientName != null) {
                    next = next.copy(clientName = delta.clientName)
                    reconnectNeeded = true
                }
                if (delta.accessTokens != null) {
                    next = next.copy(accessTokens = delta.accessTokens.toList())
                }
                val trustStore = delta.trustStore
                if (trustStore != null) {
                    val trustStorePath = trustStore.path
                    if (isTrustStorePathAllowed(trustStorePath)) {
                        next = next.copy(
                            trustStore = trustStorePath,
                            trustStorePassword = trustStore.password ?: next.trustStorePassword,
                            trustStoreFormat = trustStore.format ?: next.trustStoreFormat,
                        )
                        reconnectNeeded = true
                    } else {
                        Log.w(
                            TAG,
                            "Rejected trust store path (must be a .bks file under the app files directory); " +
                                "keeping previous trust store settings."
                        )
                    }
                }
                if (delta.localMuteHistory != null) {
                    next = next.copy(localMuteHistory = delta.localMuteHistory.toList())
                    reconnectNeeded = true
                }
                if (delta.localIgnoreHistory != null) {
                    next = next.copy(localIgnoreHistory = delta.localIgnoreHistory.toList())
                    reconnectNeeded = true
                }
                updated = next
            } while (!pendingConnection.compareAndSet(current, updated))
            if (delta.server != null) {
                lastServer.set(
                    Server(
                        delta.server.id, delta.server.name, delta.server.host,
                        delta.server.port, delta.server.username, delta.server.password,
                    ).also {
                        it.resolvedHost = delta.server.resolvedHost
                        it.resolvedPort = delta.server.resolvedPort
                    },
                )
            }
        }
        // 2. Push access tokens live (post-sync only).
        if (delta.accessTokens != null) {
            val session = currentSession()
            if (session != null && session.state.value.server.phase == SyncPhase.SYNCED) {
                session.sendAccessTokens(requireNotNull(pendingConnection.get().accessTokens))
            }
        }
        // 3. Audio settings.
        inputModes.applyAudioDelta(delta, mAudioBuilder, audioHandlerRef.get(), voiceLink)
        // Interim encoder target for SessionStats.currentBandwidth until the
        // step-5a VoicePolicy carries real values (see LegacyVoiceLink).
        if (delta.inputQuality != null) {
            try {
                voiceLink.setTargetBitrate(delta.inputQuality)
            } catch (ignored: Exception) {
            }
        }
        // 4. Route inputs.
        val audioStream = delta.audioStream
        if (audioStream != null) {
            route.setHandsetStream(audioStream)
        }
        val bluetoothSco = delta.bluetoothSco
        if (bluetoothSco != null) {
            val requested = bluetoothSco
            val wasScoRequested = route.isScoRequested
            suppressReload = !delta.hasOtherAudioKeys() &&
                (requested || !wasScoRequested)
            route.setScoRequested(requested)
            if (!requested) {
                route.stopScoWithNotify()
            }
            route.updateScoRoute()
        }

        // 5. Reload audio subsystem if initialized and connected.
        val audioHandler = audioHandlerRef.get()
        if (audioHandler != null && audioHandler.isInitialized() &&
            clientStateIsConnected()
        ) {
            route.onEvent(RouteEvent.SettingsChanged(!suppressReload))
            if (!suppressReload) {
                Log.i(TAG, "Audio subsystem reloaded after settings change.")
            }
        }
        return reconnectNeeded
    }

    @Throws(NotSynchronizedException::class)
    private fun getAudioHandler(): AudioHandler {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return audioHandlerRef.get()
            ?: throw IllegalStateException("Audio handler should always be instantiated while connected!")
    }

    open override fun getConnectionState(): ConnectionState {
        if (!::client.isInitialized) return ConnectionState.DISCONNECTED
        return when (val s = client.state.value) {
            is ClientState.Idle -> ConnectionState.DISCONNECTED
            is ClientState.Connecting -> ConnectionState.CONNECTING
            is ClientState.Connected -> ConnectionState.CONNECTED
            is ClientState.Reconnecting -> ConnectionState.CONNECTION_LOST
            is ClientState.Failed -> ConnectionState.CONNECTION_LOST
        }
    }

    open override fun getConnectionError(): HumlaException? {
        if (!::client.isInitialized) return null
        return when (val s = client.state.value) {
            is ClientState.Reconnecting -> s.lastError.toHumlaException()
            is ClientState.Failed -> s.error.toHumlaExceptionOrNull()
            else -> null
        }
    }

    open override fun isReconnecting(): Boolean {
        if (!::client.isInitialized) return false
        return client.state.value is ClientState.Reconnecting
    }

    open override fun getReconnectAttempts(): Int {
        if (!::client.isInitialized) return 0
        return when (val s = client.state.value) {
            is ClientState.Connecting -> s.attempt
            is ClientState.Reconnecting -> s.attempt
            else -> 0
        }
    }

    open override fun cancelReconnect() {
        try {
            if (::client.isInitialized) client.cancelReconnect()
        } catch (ignored: Exception) {
        }
        if (!clientStateIsConnected()) {
            try {
                val wakeLock = mWakeLock
                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
            } catch (ignored: Exception) {
            }
            releaseWifiLock()
        }
    }

    open override fun getTargetServer(): Server? {
        val s = lastServer.get() ?: return null
        return Server(s.id, s.name, s.host, s.port, s.username, s.password).also {
            it.resolvedHost = s.resolvedHost
            it.resolvedPort = s.resolvedPort
        }
    }

    @Suppress("FunctionName")
    @Throws(HumlaDisconnectedException::class)
    open override fun HumlaSession(): IHumlaSession {
        if (!clientStateIsConnected()) {
            throw HumlaDisconnectedException()
        }
        return this
    }

    open override fun getTCPLatency(): Long {
        try {
            return requireSession().getTCPLatency()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getUDPLatency(): Long {
        try {
            return requireSession().getUDPLatency()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getMaxBandwidth(): Int {
        return try {
            requireSession().getMaxBandwidth()
        } catch (e: Exception) {
            -1
        }
    }

    open override fun getCurrentBandwidth(): Int {
        try {
            return getAudioHandler().getCurrentBandwidth()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerVersion(): Int {
        try {
            return requireSession().getServerVersion()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerVersionV2(): Long {
        try {
            return requireSession().getServerVersionV2()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerRelease(): String? {
        try {
            return requireSession().getServerRelease()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerOSName(): String? {
        try {
            return requireSession().getServerOSName()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerOSVersion(): String? {
        try {
            return requireSession().getServerOSVersion()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getSessionId(): Int {
        try {
            return requireSession().getSessionId()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getSessionUser(): IUser? {
        try {
            return requireSession().getSessionUser()
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    open override fun getSessionChannel(): IChannel? {
        return try {
            requireSession().getSessionChannel()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getUser(session: Int): IUser? {
        try {
            return requireSession().getUser(session)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    open override fun getChannel(id: Int): IChannel? {
        try {
            return requireSession().getChannel(id)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    open override fun getRootChannel(): IChannel? {
        return getChannel(0)
    }

    open override fun getPermissions(): Int {
        return try {
            requireSession().getPermissions()
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    open override fun getTransmitMode(): Int {
        return inputModes.transmitMode
    }

    open override fun getCodec(): HumlaUDPMessageType? {
        return try {
            val opus = requireSession().state.value.server.info.opus
            if (opus) HumlaUDPMessageType.UDPVoiceOpus else null
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    open override fun isTalking(): Boolean {
        return inputModes.toggle.isTalkingOn
    }

    open override fun setTalkingState(talking: Boolean) {
        inputModes.toggle.isTalkingOn = talking
        try {
            currentSession()?.setPtt(talking)
        } catch (ignored: Exception) {
        }
        audioHandlerRef.get()?.setPttTalking(talking)
    }

    open override fun joinChannel(channel: Int) {
        currentSession()?.joinChannel(channel)
    }

    open override fun moveUserToChannel(session: Int, channel: Int) {
        currentSession()?.moveUserToChannel(session, channel)
    }

    open override fun sendAccessTokens(tokens: List<String>) {
        currentSession()?.sendAccessTokens(tokens)
    }

    open override fun requestPermissions(channel: Int) {
        currentSession()?.requestPermissions(channel)
    }

    open override fun requestComment(session: Int) {
        currentSession()?.requestComment(session)
    }

    open override fun requestAvatar(session: Int) {
        currentSession()?.requestTexture(session)
    }

    open override fun requestChannelDescription(channel: Int) {
        currentSession()?.requestDescription(channel)
    }

    open override fun registerUser(session: Int) {
        currentSession()?.registerUser(session)
    }

    open override fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
        currentSession()?.kickBanUser(session, reason, ban)
    }

    open override fun sendUserTextMessage(session: Int, message: String?): Message? {
        val current = currentSession() ?: throw IllegalStateException("No active connection")
        try {
            return current.sendUserTextMessage(session, message)
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message? {
        val current = currentSession() ?: throw IllegalStateException("No active connection")
        try {
            return current.sendChannelTextMessage(channel, message, tree)
        } catch (e: IllegalStateException) {
            throw IllegalStateException(e)
        }
    }

    open override fun setUserComment(session: Int, comment: String?) {
        currentSession()?.setUserComment(session, comment)
    }

    open override fun setPrioritySpeaker(session: Int, priority: Boolean) {
        currentSession()?.setPrioritySpeaker(session, priority)
    }

    open override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        currentSession()?.setMuteDeafState(session, mute, deaf)
    }

    open override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
        val session = currentSession()
        if (session == null) {
            // Disconnected: no model to update (old send-then-ignore shape is
            // gone with the connection; the next session restores from settings
            // via the app's explicit post-sync call).
            return
        }
        session.setSelfMuteDeaf(mute, deaf)
        try {
            val self = session.state.value.server.self
            if (self != null) {
                audioHandlerRef.get()?.setSelfMutedOptimistic(self.selfMuted)
            }
        } catch (ignored: Exception) {
        }
        val selfSession = session.state.value.server.selfSession
        if (selfSession != null && session.state.value.server.users.containsKey(selfSession)) {
            mCallbacks.onUserStateUpdated(UserHandle(selfSession, session.state))
        }
    }

    open override fun registerObserver(observer: IHumlaObserver) {
        mCallbacks.registerObserver(observer)
    }

    open override fun unregisterObserver(observer: IHumlaObserver) {
        mCallbacks.unregisterObserver(observer)
    }

    open override fun isConnected(): Boolean {
        return clientStateIsConnected()
    }

    open override fun linkChannels(channelA: IChannel?, channelB: IChannel?) {
        val a = channelA ?: throw NullPointerException("channelA")
        val b = channelB ?: throw NullPointerException("channelB")
        currentSession()?.linkChannels(a.id, b.id)
    }

    open override fun unlinkChannels(channelA: IChannel?, channelB: IChannel?) {
        val a = channelA ?: throw NullPointerException("channelA")
        val b = channelB ?: throw NullPointerException("channelB")
        currentSession()?.unlinkChannels(a.id, b.id)
    }

    open override fun unlinkAllChannels(channel: IChannel?) {
        val ch = channel ?: throw NullPointerException("channel")
        currentSession()?.unlinkAllChannels(ch.id)
    }

    open override fun registerWhisperTarget(target: WhisperTarget?): Byte {
        val t = target ?: throw NullPointerException("target")
        val session = currentSession() ?: throw IllegalStateException("No active connection")
        if (session.state.value.server.phase != SyncPhase.SYNCED) {
            throw IllegalStateException("No active connection")
        }
        val created = try {
            t.createTarget()
        } catch (e: RuntimeException) {
            throw e
        }
        val stateTarget = se.lublin.humla.state.WhisperTarget(
            channelId = created.channelId,
            includeLinked = created.links,
            includeSubchannels = created.children,
            group = if (created.hasGroup()) created.group else null,
        )
        val allocated = session.registerWhisperTarget(stateTarget)
        if (allocated < 0) return (-1).toByte()
        return allocated.toByte()
    }

    open override fun unregisterWhisperTarget(targetId: Byte) {
        if (targetId < WhisperTargetList.TARGET_MIN || targetId > WhisperTargetList.TARGET_MAX) {
            return
        }
        currentSession()?.unregisterWhisperTarget(targetId.toInt() and 0xFF)
    }

    open override fun setVoiceTargetId(targetId: Byte) {
        if ((targetId.toInt() and 0xFF) > 0x1F) {
            throw IllegalArgumentException(
                "Target ID must be at most 5 bits: " + (targetId.toInt() and 0xFF)
            )
        }
        pendingVoiceTarget.set(targetId.toInt() and 0xFF)
        try {
            currentSession()?.setVoiceTarget(targetId.toInt() and 0xFF)
        } catch (ignored: Exception) {
        }
        audioHandlerRef.get()?.setVoiceTargetId(targetId)
        mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(targetId))
    }

    open override fun getVoiceTargetId(): Byte {
        val session = currentSession()
        if (session != null) {
            return try {
                session.getVoiceTargetId().toByte()
            } catch (e: Exception) {
                pendingVoiceTarget.get().toByte()
            }
        }
        return pendingVoiceTarget.get().toByte()
    }

    open override fun getVoiceTargetMode(): VoiceTargetMode? {
        return VoiceTargetMode.fromId(getVoiceTargetId())
    }

    open override fun getWhisperTarget(): WhisperTarget? {
        val session = currentSession() ?: return null
        val stateTarget = try {
            session.getWhisperTarget()
        } catch (e: Exception) {
            null
        } ?: return null
        val mode = try {
            session.getVoiceTargetMode()
        } catch (e: Exception) {
            return null
        }
        if (mode != se.lublin.humla.state.VoiceTargetMode.WHISPER) return null
        val channel = try {
            session.getChannel(stateTarget.channelId)
        } catch (e: Exception) {
            null
        } ?: return null
        return WhisperTargetChannel(
            channel,
            stateTarget.includeLinked,
            stateTarget.includeSubchannels,
            stateTarget.group,
        )
    }

    open override fun getServerSettings(): ServerSettings? {
        return try {
            requireSession().getServerSettings()
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    open override fun setListeningChannel(channelId: Int, listening: Boolean) {
        currentSession()?.setListening(channelId, listening)
    }

    open override fun setListeningVolume(channelId: Int, volumeAdjustment: Float) {
        currentSession()?.setListeningVolume(channelId, volumeAdjustment)
    }

    open override fun isListeningToChannel(channelId: Int): Boolean {
        return try {
            currentSession()?.isListeningToChannel(channelId) ?: false
        } catch (e: Exception) {
            false
        }
    }

    open override fun setLocalMute(session: Int, muted: Boolean) {
        currentSession()?.setLocalMute(session, muted)
    }

    open override fun setLocalIgnore(session: Int, ignored: Boolean) {
        currentSession()?.setLocalIgnore(session, ignored)
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CONNECTION_LOST
    }

    class HumlaBinder internal constructor(private val mService: IHumlaService) : Binder() {
        fun getService(): IHumlaService {
            return mService
        }
    }
}
