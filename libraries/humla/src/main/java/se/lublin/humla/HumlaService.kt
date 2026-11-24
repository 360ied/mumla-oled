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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import org.minidns.hla.ResolverApi
import se.lublin.humla.audio.AndroidAudioPlatform
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.humla.audio.ScoLink
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RouteControl
import se.lublin.humla.route.RouteEvent
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.RouteThread
import se.lublin.humla.voice.PolicyCollector
import se.lublin.humla.voice.TalkActivityPublisher
import se.lublin.humla.voice.VoiceSettings
import se.lublin.humla.voice.VoiceSettingsMapper
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientCertificate
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.client.HumlaClient
import se.lublin.humla.client.InMemoryLocalFactsStore
import se.lublin.humla.client.LocalFactsStore
import se.lublin.humla.client.NetworkMonitor
import se.lublin.humla.client.ReconnectPolicy
import se.lublin.humla.client.ServerEndpoint
import se.lublin.humla.client.Session
import se.lublin.humla.client.TrustStoreRef
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
import se.lublin.humla.voice.VoiceEngine
import se.lublin.humla.protocol.StateEventLogger
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
            trustStoreFormat == other.trustStoreFormat
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

    private lateinit var appRuntime: HumlaRuntime

    /** Observers registered through this service instance's binder. */
    private val boundObservers = mutableSetOf<IHumlaObserver>()

    /** The runtime's mirror; live handles read it across reconnects. */
    val sessionState: StateFlow<SessionState> get() = appRuntime.sessionState

    private var stateLogger: StateEventLogger? = null

    /** Points at the runtime-owned callbacks once onCreate has run. */
    private var mCallbacks: HumlaCallbacks = HumlaCallbacks()

    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    private val pendingConnection = AtomicReference(PendingConnection())
    private val pendingVoiceTarget = AtomicInteger(0)
    private val lastServer = AtomicReference<Server?>(null)

    private fun clientStateIsConnected(): Boolean {
        if (!::appRuntime.isInitialized) return false
        return appRuntime.client.state.value is ClientState.Connected
    }

    private fun currentSessionId(): Int {
        return try {
            appRuntime.client.currentSession.value?.id?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun currentSession(): Session? {
        if (!::appRuntime.isInitialized) return null
        return try {
            appRuntime.client.currentSession.value
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
        if (!::appRuntime.isInitialized || appRuntime.client.state.value is ClientState.Idle) {
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
            if (isConnect && !::appRuntime.isInitialized) {
                Log.w(TAG, "$ACTION_CONNECT before onCreate; ignoring.")
                stopSelfIfIdle(startId)
                return START_NOT_STICKY
            }
            // Guard initialization (unit tests construct the service without
            // onCreate): without a client there is nothing to connect.
            if (isConnect && ::appRuntime.isInitialized && appRuntime.client.state.value is ClientState.Connecting) {
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

        appRuntime = runtime()
        mCallbacks = appRuntime.callbacks
        stateLogger = StateEventLogger(this, this)

        // Per-instance route hook: the runtime's own collector only fans out
        // hints into HumlaCallbacks and cannot reach a service override.
        lifecycleScope.launch {
            try {
                appRuntime.routeState.collect { rs ->
                    when (rs) {
                        is RouteState.Ready, is RouteState.Fallback, is RouteState.Idle ->
                            onAudioRouteChanged()
                        is RouteState.Settling -> Unit
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        // Foreground lifetime + Doze shield + app-level Connected/Disconnected
        // fanout (for MumlaService UI: restore mute, overlay, notifications).
        lifecycleScope.launch {
            try {
                appRuntime.client.state.collect { s ->
                    when (s) {
                        is ClientState.Idle -> {
                            try {
                                val wakeLock = mWakeLock
                                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
                            } catch (ignored: Exception) {
                            }
                            releaseWifiLock()
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
                            // Apply a voice-target set while disconnected (policy carries
                            // it to native; no handler to seed).
                            try {
                                val pending = pendingVoiceTarget.get()
                                if (pending != 0) {
                                    s.session.setVoiceTarget(pending)
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
                            resetVoiceTargetSilent()
                            // Trust failures surface via onTLSHandshakeFailed below;
                            // still fan out the terminal disconnect for UI.
                            if (s.error is ClientError.Trust) {
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
                if (e is CancellationException) throw e
            }
        }

        // Chat-log adapter: one application-lifetime event stream, so no
        // session can emit before this collector exists.
        lifecycleScope.launch {
            try {
                appRuntime.client.events.collect { record ->
                    try {
                        stateLogger?.log(listOf(record.event), record.state)
                    } catch (ignored: Exception) {
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        init(this)
    }

    private fun ClientError.toHumlaException(): HumlaException {
        return when (this) {
            is ClientError.Connection ->
                HumlaException(message ?: "Connection error", cause, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
            is ClientError.Rejected ->
                HumlaException("Rejected: $reason", null, HumlaException.HumlaDisconnectReason.REJECT)
            is ClientError.Removed ->
                HumlaException(reason ?: "Removed", null, HumlaException.HumlaDisconnectReason.USER_REMOVE)
            is ClientError.Trust ->
                HumlaException("TLS trust failure", null, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        }
    }

    private fun ClientError.toHumlaExceptionOrNull(): HumlaException? {
        return when (this) {
            is ClientError.Trust -> null
            else -> toHumlaException()
        }
    }

    private fun resetVoiceTargetSilent() {
        val prev = pendingVoiceTarget.getAndSet(0)
        if (prev != 0) {
            mCallbacks.onVoiceTargetChanged(VoiceTargetMode.NORMAL)
        }
    }

    open override fun onDestroy() {
        // The container owns everything application-scoped: this service only
        // ends the connection, releases its own locks, and unregisters the
        // observers it registered through its binder.
        try {
            appRuntime.client.disconnect()
        } catch (ignored: Exception) {
        }
        try {
            val wakeLock = mWakeLock
            if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
        } catch (ignored: Exception) {
        }
        for (observer in boundObservers.toList()) {
            boundObservers.remove(observer)
        mCallbacks.unregisterObserver(observer)
        }
        boundObservers.clear()
        resetVoiceTargetSilent()
        releaseWifiLock()
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
            appRuntime.client.connect(target)
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
        }
    }

    open override fun disconnect() {
        try {
            if (::appRuntime.isInitialized) appRuntime.client.disconnect()
        } catch (ignored: Exception) {
        }
    }

    // From the published route state (client.md maps this query to
    // route.state), never the SCO link itself: the link is confined to the
    // route thread, and callers query from main. The same test as the
    // route collector's onBluetoothScoChanged, so the two cannot disagree.
    open override fun isBluetoothScoActive(): Boolean {
        if (!::appRuntime.isInitialized) return false
        val rs = appRuntime.routeControl.state.value
        return rs is RouteState.Ready && rs.route == AppliedRoute.VOICE_SCO
    }

    open override fun isMediaRouteActive(): Boolean {
        return if (::appRuntime.isInitialized) appRuntime.routeControl.isMediaRouteActive else false
    }

    open override fun retryBluetoothSco(): Boolean {
        if (!::appRuntime.isInitialized) return false
        return try {
            appRuntime.routeControl.retrySco()
            true
        } catch (_: Exception) {
            false
        }
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
        if (!::appRuntime.isInitialized) return RECONNECT_DELAY_INITIAL
        val s = appRuntime.client.state.value
        val attempts = when (s) {
            is ClientState.Connecting -> s.attempt
            is ClientState.Reconnecting -> s.attempt
            else -> 0
        }
        return getReconnectDelayForAttempts(attempts)
    }

    /** The container hands the same process-lifetime runtime to every service instance. */
    protected open fun runtime(): HumlaRuntime =
        throw IllegalStateException("runtime() must be implemented by the app service")

    protected open fun onAudioRouteChanged() {
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
        var reconnectNeeded = false
        // 1. Merge pending connection parts (lock-free AtomicReference;
        // main-confined writes, atomic reads for connect). Presence-based
        // reconnect: any delta carrying a reconnect-relevant key returns true.
        // Device-local facts live in the container's LocalFactsStore, keyed
        // to the server at connect time, not in this snapshot.
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
                try {
                    session.sendAccessTokens(requireNotNull(pendingConnection.get().accessTokens))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Live access-token push failed", e)
                }
            }
        }
        return reconnectNeeded
    }

    open override fun getConnectionState(): ConnectionState {
        if (!::appRuntime.isInitialized) return ConnectionState.DISCONNECTED
        return when (val s = appRuntime.client.state.value) {
            is ClientState.Idle -> ConnectionState.DISCONNECTED
            is ClientState.Connecting -> ConnectionState.CONNECTING
            is ClientState.Connected -> ConnectionState.CONNECTED
            is ClientState.Reconnecting -> ConnectionState.CONNECTION_LOST
            is ClientState.Failed -> ConnectionState.CONNECTION_LOST
        }
    }

    open override fun getConnectionError(): HumlaException? {
        if (!::appRuntime.isInitialized) return null
        return when (val s = appRuntime.client.state.value) {
            is ClientState.Reconnecting -> s.lastError.toHumlaException()
            is ClientState.Failed -> s.error.toHumlaExceptionOrNull()
            else -> null
        }
    }

    open override fun isReconnecting(): Boolean {
        if (!::appRuntime.isInitialized) return false
        return appRuntime.client.state.value is ClientState.Reconnecting
    }

    open override fun getReconnectAttempts(): Int {
        if (!::appRuntime.isInitialized) return 0
        return when (val s = appRuntime.client.state.value) {
            is ClientState.Connecting -> s.attempt
            is ClientState.Reconnecting -> s.attempt
            else -> 0
        }
    }

    open override fun cancelReconnect() {
        try {
            if (::appRuntime.isInitialized) appRuntime.client.cancelReconnect()
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
        return requireSession().getTCPLatency()
    }

    open override fun getUDPLatency(): Long {
        return requireSession().getUDPLatency()
    }

    open override fun getMaxBandwidth(): Int {
        return try {
            requireSession().getMaxBandwidth()
        } catch (e: Exception) {
            -1
        }
    }

    open override fun getCurrentBandwidth(): Int {
        // Spec client.md: read stats.value; Session combines transport.stats
        // and voice.status into SessionStats.currentBandwidth on its scope.
        return requireSession().getCurrentBandwidth()
    }

    open override fun getServerVersion(): Int {
        return requireSession().getServerVersion()
    }

    open override fun getServerVersionV2(): Long {
        return requireSession().getServerVersionV2()
    }

    open override fun getServerRelease(): String? {
        return requireSession().getServerRelease()
    }

    open override fun getServerOSName(): String? {
        return requireSession().getServerOSName()
    }

    open override fun getServerOSVersion(): String? {
        return requireSession().getServerOSVersion()
    }

    open override fun getSessionId(): Int {
        return requireSession().getSessionId()
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
        return VoiceSettingsMapper.transmitModeFor(appRuntime.voiceSettings.value.inputMode)
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
        // SessionState.local.pttPressed is the only record of talk state.
        return currentSession()?.state?.value?.local?.pttPressed ?: false
    }

    open override fun setTalkingState(talking: Boolean) {
        // PTT reaches native through SessionState -> VoicePolicy.
        try {
            currentSession()?.setPtt(talking)
        } catch (ignored: Exception) {
        }
    }

    /** Toggle mode decides inside the session's atomic state update. */
    open fun toggleTalkingState() {
        try {
            currentSession()?.togglePtt()
        } catch (ignored: Exception) {
        }
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
        // Step 5a.4: the mute gate is VoicePolicy.transmitAllowed from
        // SessionState (desired wins over echo); no optimistic handler seed.
        session.setSelfMuteDeaf(mute, deaf)
        val selfSession = session.state.value.server.selfSession
        if (selfSession != null && session.state.value.server.users.containsKey(selfSession)) {
            // Self hint shape, like the talkActivity fanout below: the local
            // user's state lives in selfTalking, not the remote table.
            mCallbacks.onUserStateUpdated(
                UserHandle(
                    selfSession,
                    session.state,
                    appRuntime.talkActivity,
                    talkStateOverride = if (appRuntime.talkActivity.value.selfTalking) {
                        TalkState.TALKING
                    } else {
                        TalkState.PASSIVE
                    },
                ),
            )
        }
    }

    open override fun registerObserver(observer: IHumlaObserver) {
        boundObservers.add(observer)
        mCallbacks.registerObserver(observer)
    }

    open override fun unregisterObserver(observer: IHumlaObserver) {
        boundObservers.remove(observer)
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
        val created = t.createTarget()
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
