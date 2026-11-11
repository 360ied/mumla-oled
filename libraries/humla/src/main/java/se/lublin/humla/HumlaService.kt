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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import se.lublin.humla.exception.AudioException
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IServerSettings
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.state.ChannelHandle
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.applyChannelRemove
import se.lublin.humla.state.applyChannelState
import se.lublin.humla.state.applyCodecVersion
import se.lublin.humla.state.applyListeningVolumeIntent
import se.lublin.humla.state.applyPermissionDenied
import se.lublin.humla.state.applyPermissionQuery
import se.lublin.humla.state.applyRegisterWhisperIntent
import se.lublin.humla.state.applySelfMuteDeafIntent
import se.lublin.humla.state.applyServerConfig
import se.lublin.humla.state.applyServerSync
import se.lublin.humla.state.applyTextMessage
import se.lublin.humla.state.applyUnregisterWhisperIntent
import se.lublin.humla.state.applyUserRemove
import se.lublin.humla.state.applyUserState
import se.lublin.humla.state.applyVoiceTargetIntent
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.net.HandshakeFailure
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.InputModes
import se.lublin.humla.protocol.StateEventLogger
import se.lublin.humla.protocol.StateMessageHandler
import se.lublin.humla.protocol.validateAudioDelta
import se.lublin.humla.util.ConfigDelta
import se.lublin.humla.util.ConnectionParams
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import java.io.File
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.annotations.TestOnly

open class HumlaService : LifecycleService(), IHumlaService, IHumlaSession,
    HumlaConnection.HumlaConnectionListener, HumlaLogger {

    companion object {
        private val TAG = "HumlaService"

        /**
         * An action to immediately connect to a given Mumble server.
         * Requires a server (see [buildConnectDelta]).
         */
        const val ACTION_CONNECT = "se.lublin.humla.CONNECT"

        const val RECONNECT_DELAY_INITIAL = 1000
        const val RECONNECT_DELAY_MAX = 25000

        /** Grace beyond a scheduled reconnect delay while the Doze shield is held. */
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

        /**
         * Pure reconnect-backoff truth table behind [getReconnectDelay].
         * Static so the JVM test pins the switch without constructing a
         * Service (mirrors AudioRouteController.computeVoiceRoute).
         */
        @JvmStatic
        fun getReconnectDelayForAttempts(attempts: Int): Int {
            // Negative attempts are unreachable (mReconnectAttempts resets to 0
            // and only increments); fail closed to MAX like any large value.
            if (attempts < 0) return RECONNECT_DELAY_MAX
            return when (attempts) {
                0 -> RECONNECT_DELAY_INITIAL
                1 -> 3000
                2 -> 6000
                3 -> 12000
                else -> RECONNECT_DELAY_MAX
            }
        }
    }

    // Connection parameters (config track): one immutable snapshot, frozen at
    // the apply() boundary under the service monitor and published through a
    // single volatile. connect() and onConnectionEstablished() read one
    // snapshot instead of eleven fields.
    @Volatile
    private var connectionParams = ConnectionParams()
    private lateinit var mAudioBuilder: AudioHandler.Builder
    // Audio-knob holder: three mode instances plus transmit mode and half
    // duplex in one field. Main-confined like apply(); the route controller
    // never sees it (its SettingsChanged event carries only reload).
    private val inputModes = InputModes()
    // Route owner (slice 4): all route state and choreography live in the
    // controller behind ports; this service owns one instance, feeds it
    // lifecycle events, and forwards three API members to it. The route
    // monitor group moved atomically into the controller (main-confined volatile
    // fields, no lock) — no route field remains under applyLock.
    private lateinit var route: AudioRouteController

    private val routeHost = object : AudioRouteController.Host {
        override val isLive: Boolean
            get() = mConnectionState == ConnectionState.CONNECTED
        override val sessionToken: Int
            get() = connectionGeneration.get()
        override fun hasLivePipeline(): Boolean {
            val handler = mAudioHandler
            return handler != null && handler.isInitialized()
        }
        override fun recreatePipeline() {
            createAudioHandler()
        }
        override fun onPipelineError(message: String?) {
            onConnectionWarning(message)
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
    // Service-scope Jobs replace Handler postDelayed chains. Disarm =
    // job?.cancel(), replacing removeCallbacks one-for-one. mHandler stays
    // only for AudioManager device-callback registration, which requires a
    // Handler. Volatile: cancelReconnect()/disconnect() are
    // binder-reachable off-main, matching every neighboring cross-thread
    // flag in this file. The connection generation below is bumped on
    // connect (main-confined today) and onDestroy; only
    // onConnectionSynchronized arrives via a Main Handler post
    // (HumlaConnection posts sync), while onConnectionDisconnected runs
    // directly on connection threads, so atomicity (not just visibility) is
    // required for the disconnect path, not belt-and-braces.
    // Serializes reconnect timer + network-callback registration: setReconnecting
    // runs from Main/binder (disconnect/cancelReconnect/onStartCommand) and from
    // connection threads (onConnectionDisconnected), so cancel+assign and
    // check-then-register must not interleave and orphan a job or double-register.
    private val reconnectLock = Any()

    // Serializes connect()'s generation-bump-to-CONNECTING section against
    // binder-reachable disconnect(): without it a disconnect landing between
    // the bump and the mConnection assignment reads null, takes the
    // no-socket branch, and connect() then overwrites the synchronous
    // DISCONNECTED with CONNECTING. Both sides hold this only for local
    // teardown/state (socket close, never network wait); connection-thread
    // callbacks never take it, so no lock-ordering hazard.
    private val connectLock = Any()

    // Serializes apply()'s read-modify-write merge+publish: two interleaved
    // applies must not lose each other's keys (see apply()).
    private val applyLock = Any()

    // Bumped on connect (main-confined today) and onDestroy (main); read on
    // connection threads via the listener stale filter. Atomic, not
    // @Volatile++: volatile increment is read+write, so two racing connects
    // could share a generation and the stale filter would fail.
    private val connectionGeneration = AtomicInteger(0)
    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var mVoiceTargetId: Byte = 0
    private lateinit var mWhisperTargetList: WhisperTargetList
    private val whisperLock = Any()

    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null
    private lateinit var mHandler: Handler
    private lateinit var mCallbacks: HumlaCallbacks

    @Volatile
    private var mConnection: HumlaConnection? = null

    @Volatile
    private var mConnectionState: ConnectionState = ConnectionState.DISCONNECTED

    // Plain pipeline/model fields were a cross-thread hazard once the
    // device-callback funnel started reading them off the main thread:
    // disconnect writes them on connection threads. Volatile for visibility
    // only, not atomic group transitions: readers pair them with the
    // volatile mConnectionState gate (see the route controller), never a lock.
    // Immutable session state. One value per connection; every server
    // message and user intent is state.update { ... } with a pure
    // transition. Live handles behind IUser/IChannel read this flow.
    private val _sessionState = MutableStateFlow(emptySessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var stateLogger: StateEventLogger? = null

    @Volatile
    private var mAudioHandler: AudioHandler? = null

    // Written from main (start/connect UI) and connection threads
    // (disconnect path): volatile so reconnect UI never strands on stale state.
    @Volatile
    private var mReconnecting: Boolean = false

    // Incremented on the main scope (timer/onAvailable), reset from Main and
    // binder threads: atomic, not @Volatile++, so a reset can never tear an
    // increment mid-flight (lost increment = wrong backoff; lost reset =
    // inflated backoff after cancel).
    private val mReconnectAttempts = AtomicInteger(0)
    @Volatile
    private var mNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var mNetworkCallbackRegistered = false

    private val mAudioInputListener: AudioHandler.AudioEncodeListener =
        object : AudioHandler.AudioEncodeListener {
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                val connection = mConnection
                if (connection != null && connection.isSynchronized()) {
                    connection.sendUDPMessage(data, length, false)
                }
            }

            override fun onTalkingStateChanged(talking: Boolean) {
                lifecycleScope.launch {
                    try {
                        // If the server session is inactive, ignore this message.
                        // It's likely that this is leftover from a terminated connection.
                        if (!isSynchronized())
                            return@launch

                        val connection = mConnection
                        if (connection == null) {
                            return@launch
                        }
                        val session = try {
                            connection.getSession()
                        } catch (e: NotSynchronizedException) {
                            return@launch
                        }
                        val currentUser = UserHandle(session, sessionState)
                        if (_sessionState.value.server.users[session] == null) return@launch

                        currentUser.talkState =
                            if (talking) TalkState.TALKING else TalkState.PASSIVE
                        mCallbacks.onUserTalkStateUpdated(currentUser)
                    } catch (e: NotSynchronizedException) {
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

    /**
     * Stops the service for a start request that was ignored, unless a
     * connection attempt or established connection is in progress.
     * CONNECTION_LOST without reconnect is intentionally not stopped here:
     * kick/ban idles with the UI still bound (settings/history visible), so
     * the service lingers until an explicit disconnect or process death.
     */
    private fun stopSelfIfIdle(startId: Int) {
        if (mConnectionState == ConnectionState.DISCONNECTED) {
            stopSelf(startId)
        }
    }

    /**
     * Acquires the high-performance WiFi lock, keeping the radio out of power-save
     * while the connection (including auto-reconnect gaps) is alive. No-op when the
     * lock could not be created. Mirrors the mWakeLock lifecycle.
     */
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

    /**
     * Releases the WiFi lock on final teardown so the radio may sleep again.
     * Never called across auto-reconnect retries: the radio must stay up while
     * redialing or the handshake itself stalls past the server timeout.
     */
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

    /**
     * Builds the connect-time [ConfigDelta] from the CONNECT intent.
     *
     * Base implementation returns null (humla has no database or settings);
     * the app layer overrides it to load the server, certificate, tokens,
     * and history from its database plus the current settings. Returning
     * null (or a delta without a server) rejects the intent as malformed.
     */
    protected open fun buildConnectDelta(intent: Intent?): ConfigDelta? {
        return null
    }

    open override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val isConnect = ACTION_CONNECT == intent.action
            if (isConnect && mConnectionState == ConnectionState.CONNECTING) {
                // Ignore duplicate connect requests while a connection attempt is already
                // in progress; each would otherwise spawn a parallel connection. Guarded
                // here (before apply) so the in-flight attempt's target server
                // isn't overwritten by the duplicate request.
                Log.v(TAG, "Ignoring duplicate connect request while already connecting")
                return START_NOT_STICKY
            }
            if (isConnect) {
                val delta = buildConnectDelta(intent)
                if (delta == null || delta.server == null) {
                    // Malformed CONNECT: log and ignore. Never throw from
                    // onStartCommand (crash primitive) and never proceed to
                    // apply/connect() on the malformed intent. The prior
                    // auto-reconnect schedule (if any) is intentionally preserved:
                    // a malformed intent carries no new target, so it neither
                    // connects nor disarms the stale timer.
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
                // A user-initiated connect supersedes any scheduled automatic reconnect.
                // Disarm it so a stale retry can't orphan this attempt once it succeeds.
                // Consecutive-failure backoff is intentionally NOT reset here: a
                // failing manual retry means the server is still down, so the
                // next auto-retry keeps the grown delay instead of hammering
                // at INITIAL. Success resets via onConnectionSynchronized.
                setReconnecting(false)
                connect()
            }
        }

        return START_NOT_STICKY
    }

    open override fun onCreate() {
        super.onCreate()
        // Null-tolerant like every neighboring manager (Wifi/Audio/BT): a
        // missing PowerManager must degrade to no wake lock, never crash the
        // service out of onCreate. All wake uses already null-check.
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager?
        if (powerManager != null) {
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService")
            wakeLock.setReferenceCounted(false)
            mWakeLock = wakeLock
        } else {
            Log.w(TAG, "No PowerManager; running without wake lock")
            mWakeLock = null
        }

        // Doze shield: keep the WiFi radio out of firmware power-save for the
        // lifetime of the connection. Without a WifiLock (dumpsys wifi showed
        // zero locks acquired), on battery + screen-off the radio slept between
        // Doze maintenance windows, stalling keepalives past Murmur's 30 s
        // timeout. Created here, acquired in connect(), released on final
        // teardown. Uses the application context so the lock outlives config
        // changes; reference-counted false to mirror mWakeLock semantics.
        // WIFI_MODE_FULL_HIGH_PERF (not FULL_LOW_LATENCY): low-latency saves
        // power but throttles throughput under load; high-perf is the
        // documented VoIP-compatible mode that keeps keepalives flowing on
        // battery + screen-off. Both need API 30+ (minSdk floor), so
        // availability is not the discriminator -- power vs. voice latency is.
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
        mAudioBuilder = AudioHandler.Builder()
            .setContext(this)
            .setLogger(this)
            .setEncodeListener(mAudioInputListener)
            .setTalkingListener(mAudioOutputListener)
            .setFramesPerPacketListener { fpp ->
                // Bandwidth-degraded effective packet size: rescale the UDP send queue
                // to preserve the ~200ms latency ceiling (ODD-09). The connection is
                // resolved at call time; it may be gone on a disconnect race.
                val connection = mConnection
                if (connection != null) {
                    connection.setTargetFramesPerPacket(fpp)
                }
            }
        mConnectionState = ConnectionState.DISCONNECTED
        val audioPlatform = try {
            AndroidAudioPlatform(getSystemService(Context.AUDIO_SERVICE) as AudioManager?)
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode: no AudioManager", e)
            AndroidAudioPlatform(null)
        }
        // SCO callbacks forward to the route controller. They are posted
        // async (manager scope Main.immediate), so `route` is initialized
        // before any callback fires. Null-tolerant like the platform above:
        // a missing AudioManager must not crash onCreate.
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
        mWhisperTargetList = WhisperTargetList()

        // initialize minidns dns lookup mechanisms
        init(this)
    }

    open override fun onDestroy() {
        super.onDestroy()
        mReconnectAttempts.set(0)
        // Synchronous terminal state: a binder reader racing destroy must see
        // DISCONNECTED on the dead service, never a stale CONNECTED/CONNECTING.
        mConnectionState = ConnectionState.DISCONNECTED
        // Invalidate the shared listener: the old connection's still-flying
        // async posts must not mutate state or fan out observers after death.
        // (Pipeline + route teardown below run synchronously, so nothing the
        // stale callback would do is still needed.)
        connectionGeneration.incrementAndGet()
        setReconnecting(false)
        val wakeLock = mWakeLock
        if (wakeLock != null && wakeLock.isHeld) {
            wakeLock.release()
        }
        // Route teardown (link, mode, device listener, timers) lives in the
        // controller; bare teardown, no notify by design.
        route.onEvent(RouteEvent.Destroyed)
        // Socket + mic teardown: disconnect() alone only drops the socket
        // (pipeline shutdown rides the async disconnect callback, which may
        // never run after destroy). Shut down synchronously so a destroyed
        // service never leaks the connection or capture while the process
        // lives on.
        mAudioHandler?.let { oldHandler ->
            mConnection?.removeTCPMessageHandler(oldHandler)
            mConnection?.removeUDPMessageHandler(oldHandler)
            oldHandler.shutdown()
        }
        _sessionState.value = emptySessionState()
        mAudioHandler = null
        mConnection?.disconnect()
        mConnection = null
        releaseWifiLock()
    }

    // Non-null Intent: matches Service.onBind's platform type (Intent!)
    // exactly; both Intent and Intent? override. The pre-image Java was
    // non-null, so this keeps the non-null form.
    @Suppress("NullabilityMismatch")
    open override fun onBind(intent: Intent): IBinder {
        return HumlaBinder(this)
    }

    protected open fun connect() {
        synchronized(connectLock) {
            // One atomic snapshot for the whole attempt; the server reference
            // travels by reference (mutable, resolved in onConnectionEstablished).
            val params = connectionParams
            val server = params.server
            if (server == null) {
                Log.w(TAG, "connect() called with null server, cancelling reconnect")
                setReconnecting(false)
                return
            }
            if (server.host == null) {
                Log.w(TAG, "connect() called with null host, cancelling reconnect")
                setReconnecting(false)
                return
            }

            try {
                // Straight to CONNECTING: teardown below runs synchronously
                // before the new attempt, so observers never see a transient
                // CONNECTED -> DISCONNECTED -> CONNECTING flap on re-connect.
                // Reset voice-target routing explicitly: a prior WHISPER session
                // must not strand ChannelFragment's panel. NORMAL (0) matches
                // setVoiceTargetId's emit, but connect() runs before observers
                // expect callbacks, so only emit if previously non-normal.
                // Id and list reset atomically: the id write rides the same
                // monitor as the list clear (getWhisperTarget snapshots id then
                // gets under lock), and the emit stays outside the lock --
                // onVoiceTargetChanged fans out to observers that may re-enter
                // getWhisperTarget, which would deadlock inside the monitor.
                val prevVoiceTarget = synchronized(whisperLock) {
                    val prev = mVoiceTargetId
                    mVoiceTargetId = 0
                    mWhisperTargetList.clear()
                    prev
                }
                if (prevVoiceTarget.toInt() != 0) {
                    mCallbacks.onVoiceTargetChanged(VoiceTargetMode.NORMAL)
                }

                val wakeLock = mWakeLock
                if (wakeLock != null) {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                    wakeLock.acquire(WAKELOCK_GRACE_MS)
                }
                acquireWifiLock()

                // Invalidate first: the old connection's read loop may still be
                // alive (silent disconnect posts async; a racing fatal error
                // delivers synchronously on the IO thread). Bumping before any
                // old-teardown work -- matching onDestroy -- closes the window
                // where a stale post between disconnect() and the bump would
                // slip the generation filter and drive the new attempt's state.
                // The whole bump-to-CONNECTING section rides connectLock
                // against binder disconnect() (see the lock's declaration).
                val generation = connectionGeneration.incrementAndGet()
                // Tear down any prior connection before overwriting: a second
                // CONNECT while CONNECTED/CONNECTION_LOST (or a racing
                // NetworkCallback reconnect) must not orphan the old socket.
                // End the old session explicitly: the old connection's async
                // disconnect callback will be dropped as stale once generation
                // moves below, so without this the old route teardown and the
                // old pipeline shutdown would never run. Observers go straight
                // to onConnecting with no onDisconnected (a spurious disconnect
                // would flash the reconnect UI and TTS). The old pipeline must
                // end here too — the route event alone would leave it capturing.
                mAudioHandler?.let { oldHandler ->
                    mConnection?.removeTCPMessageHandler(oldHandler)
                    mConnection?.removeUDPMessageHandler(oldHandler)
                    oldHandler.shutdown()
                }
                _sessionState.value = emptySessionState()
                mAudioHandler = null
                route.onEvent(RouteEvent.SessionEnded)
                mConnection?.disconnect()
                mConnection = null
                // Generation was bumped above (before teardown), so the old
                // connection's async posts arrive stale and are filtered below.
                // Interface delegation forwards every other callback untouched.
                val connectionListener = object : HumlaConnection.HumlaConnectionListener by this {
                    // All five callbacks filter on generation: a stale connection's
                    // async posts (established/warning/handshake/disconnect/sync)
                    // must never drive the live attempt's state machine. The
                    // CONNECTING guards make stale established/handshake
                    // unreachable today, but the filter is airtight regardless.
                    private fun isStale(): Boolean {
                        if (generation != connectionGeneration.get()) {
                            Log.v(TAG, "Ignoring stale connection callback (gen $generation)")
                            return true
                        }
                        return false
                    }

                    override fun onConnectionEstablished() {
                        if (isStale()) return
                        this@HumlaService.onConnectionEstablished()
                    }

                    override fun onConnectionWarning(warning: String?) {
                        if (isStale()) return
                        this@HumlaService.onConnectionWarning(warning)
                    }

                    override fun onConnectionHandshakeFailed(
                        chain: Array<X509Certificate>?,
                        failure: HandshakeFailure,
                        verifiedHost: String?
                    ) {
                        if (isStale()) return
                        this@HumlaService.onConnectionHandshakeFailed(chain, failure, verifiedHost)
                    }

                    override fun onConnectionDisconnected(e: HumlaException?) {
                        if (isStale()) return
                        this@HumlaService.onConnectionDisconnected(e)
                    }

                    override fun onConnectionSynchronized() {
                        if (isStale()) return
                        this@HumlaService.onConnectionSynchronized()
                    }
                }
                val connection = HumlaConnection(connectionListener)
                mConnection = connection
                connection.setTargetFramesPerPacket(mAudioBuilder.getTargetFramesPerPacket())
                connection.setForceTCP(params.forceTcp)
                connection.setKeys(params.certificate, params.certificatePassword)
                connection.setTrustStore(params.trustStore, params.trustStorePassword, params.trustStoreFormat)

                _sessionState.value = emptySessionState()
                stateLogger = StateEventLogger(this, this)
                val stateHandler = StateMessageHandler(
                    this, _sessionState, mCallbacks, this,
                    params.localMuteHistory?.toSet() ?: emptySet(),
                    params.localIgnoreHistory?.toSet() ?: emptySet(),
                )
                connection.addTCPMessageHandlers(stateHandler)

                mConnectionState = ConnectionState.CONNECTING

                mCallbacks.onConnecting()

                connection.connect(requireNotNull(server.host), server.port)
            } catch (e: HumlaException) {
                Log.e(TAG, "Connect failed", e)
                onConnectionDisconnected(e)
            }
        }
    }

    open override fun disconnect() {
        synchronized(connectLock) {
            mReconnectAttempts.set(0)
            setReconnecting(false)
            // Synchronous state: the async read-loop finally still delivers the
            // formal onConnectionDisconnected, but observers must see
            // DISCONNECTED from the explicit call, not a lingering CONNECTING.
            // Serialized against connect() via connectLock: a disconnect
            // landing mid-connect either runs fully before the bump (then
            // connect proceeds to CONNECTING) or tears down the new attempt
            // (final state DISCONNECTED) — never a null-read resurrection.
            mConnectionState = ConnectionState.DISCONNECTED
            val wakeLock = mWakeLock
            if (wakeLock != null && wakeLock.isHeld) {
                wakeLock.release()
            }
            releaseWifiLock()
            // Tear down SCO synchronously: the async disconnect callback may lag,
            // and a quick reconnect must not reuse a stale link or route flag.
            route.stopScoWithNotify()
            val connection = mConnection
            if (connection != null) {
                connection.disconnect()
            } else {
                // No socket, so no async callback will run the full teardown
                // (mode/route/settle): run the route half explicitly. Posted to
                // the funnel when off-main; idempotent with the callback path.
                route.onEvent(RouteEvent.SessionEnded)
            }
        }
    }

    open override fun isBluetoothScoActive(): Boolean {
        return route.isScoActive()
    }

    /**
     * Whether a media sink (BT A2DP / BLE / wired headset) currently routes output.
     * While true (and SCO inactive) the pipeline runs the media triple to
     * preserve A2DP quality; platform AEC is out of scope there by design.
     * Derived from the confirmed route flag (not the live manager query) so
     * pipeline, cues, and volume observers never diverge mid-callback.
     */
    open override fun isMediaRouteActive(): Boolean {
        return route.isMediaRouteActive
    }

    open override fun retryBluetoothSco(): Boolean {
        return route.retrySco()
    }

    open fun isConnectionEstablished(): Boolean {
        return mConnection?.isConnected() == true
    }

    /**
     * @return True if Humla has received the ServerSync message, indicating synchronization with
     * the server's model and settings. This is the main state of the service.
     */
    open fun isSynchronized(): Boolean {
        return mConnection?.isSynchronized() == true
    }

    open override fun onConnectionEstablished() {
        val params = connectionParams
        val server = params.server
        val connection = mConnection
        if (server != null && connection != null) {
            server.setResolved(connection.getHost(), connection.getPort())
        }

        // Send version information and authenticate.
        val version = Constants.createVersionMessage(params.clientName)

        val auth = Mumble.Authenticate.newBuilder()
        if (server != null) {
            auth.username = server.username
            auth.password = server.password
        }
        auth.opus = true
        auth.addAllTokens(params.accessTokens ?: emptyList())

        // onConnectionEstablished is invoked by the connection itself, which
        // cannot be gone here; require it instead of silently dropping auth.
        val live = requireNotNull(connection)
        live.sendTCPMessage(version.build(), HumlaTCPMessageType.Version)
        live.sendTCPMessage(auth.build(), HumlaTCPMessageType.Authenticate)
    }

    open override fun onConnectionSynchronized() {
        val connection = mConnection
        // early disconnected?
        if (connection == null || !connection.isConnected()) {
            return
        }

        mReconnectAttempts.set(0)
        setReconnecting(false)
        mConnectionState = ConnectionState.CONNECTED

        Log.v(TAG, "Connected")
        val wakeLock = mWakeLock
        if (wakeLock != null) {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            wakeLock.acquire()
        }

        // Route application (mode, pipeline, SCO, cues) lives in the
        // controller; pipeline errors are reported via onPipelineError.
        // First creation runs through the funnel with firstCreate, which
        // keeps the unconditional build plus route notify the sync path
        // requires (see controller).
        route.onEvent(RouteEvent.SessionStarted)

        mCallbacks.onConnected()
    }

    open override fun onConnectionHandshakeFailed(
        chain: Array<X509Certificate>?,
        failure: HandshakeFailure,
        verifiedHost: String?
    ) {
        mCallbacks.onTLSHandshakeFailed(chain, failure, verifiedHost)
    }

    open override fun onConnectionDisconnected(e: HumlaException?) {
        val reconnect = (e != null && e.reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        if (e != null) {
            Log.e(TAG, "Error: " + e.message + " (reason: " + e.reason.name + ")")
            mConnectionState = ConnectionState.CONNECTION_LOST
            setReconnecting(reconnect)
        } else {
            Log.v(TAG, "Disconnected")
            mConnectionState = ConnectionState.DISCONNECTED
            setReconnecting(false)
        }

        if (!reconnect) {
            val wakeLock = mWakeLock
            if (wakeLock != null && wakeLock.isHeld) {
                wakeLock.release()
            }
            releaseWifiLock()
        }

        mAudioHandler?.shutdown()

        _sessionState.value = emptySessionState()
        mAudioHandler = null
        // Atomic id+list reset (same deadlock discipline as connect(): emit
        // outside the monitor). Disconnect resets silently only when already
        // NORMAL so the UI never strands in WHISPER across the gap.
        val prevVoiceTarget = synchronized(whisperLock) {
            val prev = mVoiceTargetId
            mVoiceTargetId = 0
            mWhisperTargetList.clear()
            prev
        }
        if (prevVoiceTarget.toInt() != 0) {
            mCallbacks.onVoiceTargetChanged(VoiceTargetMode.NORMAL)
        }
        // Route teardown (media refresh, SCO release, mode release, settle
        // cancel) lives in the controller.
        route.onEvent(RouteEvent.SessionEnded)

        mCallbacks.onDisconnected(e)
    }

    open override fun onConnectionWarning(warning: String?) {
        logWarning(warning)
    }

    open override fun logInfo(message: String?) {
        val connection = mConnection
        if (connection == null || !connection.isSynchronized())
            return // don't log info prior to synchronization
        mCallbacks.onLogInfo(message)
    }

    open override fun logWarning(message: String?) {
        mCallbacks.onLogWarning(message)
    }

    open override fun logError(message: String?) {
        mCallbacks.onLogError(message)
    }

    // Binder boundary: translate the missing-connection IAE into
    // IllegalStateException so disconnected-before-connect matches the
    // connected-then-lost path (getters translate to ISE). App catches ISE.
    private fun requireConnection(): HumlaConnection {
        return try {
            requireNotNull(mConnection) { "No active connection" }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(e.message, e)
        }
    }

    private fun requireSynced(): SessionState {
        val s = _sessionState.value
        if (s.server.phase != SyncPhase.SYNCED) throw NotSynchronizedException()
        return s
    }

    open override fun getReconnectDelay(): Int {
        return getReconnectDelayForAttempts(mReconnectAttempts.get())
    }

    private fun registerNetworkCallback() {
        synchronized(reconnectLock) {
            if (mNetworkCallbackRegistered) return
        }
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (cm != null) {
                if (mNetworkCallback == null) {
                    mNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            // Guard before launching: network flapping fires
                            // this callback often, and each launch is a Main
                            // coroutine even when no reconnect is pending.
                            if (!mReconnecting) {
                                return
                            }
                            lifecycleScope.launch {
                                // CONNECTING guard mirrors onStartCommand's
                                // duplicate-connect guard: two rapid onAvailable
                                // events must not spawn parallel connections.
                                if (mReconnecting &&
                                    mConnectionState != ConnectionState.CONNECTING
                                ) {
                                    Log.v(TAG, "Network available, triggering immediate reconnect.")
                                    synchronized(reconnectLock) {
                                        reconnectJob?.cancel()
                                        reconnectJob = null
                                    }
                                    mReconnectAttempts.incrementAndGet()
                                    connect()
                                }
                            }
                        }
                    }
                }
                cm.registerDefaultNetworkCallback(requireNotNull(mNetworkCallback))
                synchronized(reconnectLock) {
                    mNetworkCallbackRegistered = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback: " + e.message)
        }
    }

    private fun unregisterNetworkCallback() {
        synchronized(reconnectLock) {
            if (!mNetworkCallbackRegistered) return
        }
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            val callback = mNetworkCallback
            if (cm != null && callback != null) {
                cm.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback: " + e.message)
        } finally {
            synchronized(reconnectLock) {
                mNetworkCallbackRegistered = false
            }
        }
    }

    /** Internal reconnect driver; not part of `IHumlaService`. Tests use `setReconnectAttemptsForTest`. */
    internal fun setReconnecting(reconnecting: Boolean) {
        // One monitor for the whole method (register/unregister take the same
        // lock reentrantly): a true+true race must not launch two jobs or
        // register the network callback twice and orphan one of each.
        synchronized(reconnectLock) {
            if (!reconnecting) {
                mReconnecting = false
                reconnectJob?.cancel()
                reconnectJob = null
                unregisterNetworkCallback()
                return
            }

            mReconnecting = true
            registerNetworkCallback()
            val delayMs = getReconnectDelay()
            Log.v(TAG, "Scheduling reconnect in $delayMs ms (attempt ${mReconnectAttempts.get()})")
            val wakeLock = mWakeLock
            if (wakeLock != null) {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                wakeLock.acquire((delayMs + WAKELOCK_GRACE_MS).toLong())
            }
            reconnectJob?.cancel()
            val launchedReconnect = lifecycleScope.launch {
                delay(delayMs.toLong())
                // Mirror the onAvailable CONNECTING guard: the timer can resume
                // after onAvailable already connected (cancel is best-effort),
                // and an unguarded connect() would double-increment attempts and
                // tear down a just-started handshake.
                if (mReconnecting && mConnectionState != ConnectionState.CONNECTING) {
                    mReconnectAttempts.incrementAndGet()
                    connect()
                }
            }
        launchedReconnect.invokeOnCompletion {
            synchronized(reconnectLock) {
                if (reconnectJob === launchedReconnect) reconnectJob = null
            }
        }
            reconnectJob = launchedReconnect
        }
    }

    
    /**
     * Invoked on the main thread after every confirmed audio-route change
     * (SCO or media-sink), after the pipeline reload. Subclasses override to
     * refresh route-dependent audio that the pipeline does not own (e.g. cue
     * streams). Overrides must be idempotent and non-blocking, and must not
     * call back into apply; this also runs when no pipeline exists.
     */
    protected open fun onAudioRouteChanged() {
    }

    /**
     * Instantiates an audio handler with the current service settings, destroying any previous
     * handler. Requires synchronization with the server, as the maximum bandwidth and session must
     * be known.
     */
    @Throws(AudioException::class)
    private fun createAudioHandler() {
        // Unconditional (was DEBUG-only): release and debug must share the
        // same containment path. The funnel catches RuntimeException, and
        // app callers translate binder ISE, so throwing here is contained.
        if (mConnectionState != ConnectionState.CONNECTED) {
            throw IllegalStateException("Attempted to instantiate audio handler when not connected!")
        }

        val existing = mAudioHandler
        if (existing != null) {
            mConnection?.removeTCPMessageHandler(existing)
            mConnection?.removeUDPMessageHandler(existing)
            existing.shutdown()
        }

        // Route decides; the builder carries it: push the controller decision
        // into the builder immediately before initialize, the only point
        // these fields are consumed. The service re-reads the current voice
        // target below (ordering: push decision, then init with live IDs).
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
            val connection = requireConnection()
            val selfInfo = _sessionState.value.server.self
            mAudioHandler = mAudioBuilder.initialize(
                selfInfo,
                connection.getMaxBandwidth(), connection.getCodec(),
                mVoiceTargetId
            )
            val handler = requireNotNull(mAudioHandler)
            handler.setProtobufUdp(connection.isProtobufUdpSupported())
            connection.addTCPMessageHandlers(handler)
            connection.addUDPMessageHandlers(handler)
        } catch (e: NotSynchronizedException) {
            throw AudioException("Attempted to create audio handler when not synchronized!")
        } catch (e: IllegalArgumentException) {
            // TOCTOU: isLive (volatile mConnectionState) can flip between the
            // funnel's gate and these requires when a disconnect lands on a
            // connection thread. Contain as AudioException so the route
            // funnel reports via onPipelineError instead of crashing Main.
            throw AudioException("Audio pipeline torn down during recreate", e)
        }
    }

    /**
     * Validates a trust-store path for [apply]. The null
     * (system store) common case always passes. Non-null paths must
     * canonical-resolve under the app files directory and must end in
     * '.bks'. Canonicalisation resolves any '..' segments, so a separate
     * substring check is unnecessary.
     *
     * @param path Trust-store path, or null for the system store.
     * @return true if the path may be applied.
     */
    private fun isTrustStorePathAllowed(path: String?): Boolean {
        if (path == null) {
            return true
        }
        if (!path.endsWith(".bks", ignoreCase = true)) {
            return false
        }
        // Known TOCTOU (check-then-open races a symlink swap) accepted for the
        // app-private filesDir: the store opens immediately after in the same
        // flow and a swapped link still resolves under filesDir or is rejected.
        try {
            val filesDir = filesDir ?: return false
            val base = filesDir.canonicalFile.path
            // Resolve relative paths against the app files dir (the process
            // CWD is not the files dir), then canonicalize to collapse '..'.
            val raw = File(path)
            val candidate = (if (raw.isAbsolute) raw else File(filesDir, path)).canonicalFile.path
            return candidate.startsWith(base + File.separator)
        } catch (e: IOException) {
            return false
        }
    }

    /**
     * Loads all defined settings from the given delta into the HumlaService.
     * Null fields are unchanged. Some settings may only take effect after a
     * reconnect. Throws IllegalArgumentException on invalid audio input
     * (unknown transmit mode, non-finite knobs, sub-floor bitrate);
     * the pipeline recreate funnels through the route controller, which handles
     * AudioException internally.
     * @param delta A one-shot configuration command.
     * @return true if a reconnect is required for changes to take effect.
     * See also [HumlaService]
     */
    /**
     * Must be called on the main thread: mutates `mAudioBuilder`, the route
     * controller, and `mAudioHandler` gate without locking (see `InputModes`
     * main-confinement). Binder callers hop via the service handler.
     */
    open fun apply(delta: ConfigDelta): Boolean {
        check(Looper.getMainLooper() == Looper.myLooper()) { "apply must be called on main" }
        // Pure validation first: the throwing inputs (unknown transmit mode,
        // non-finite knobs, out-of-range bitrate/gain) must fail before any
        // commit, so a bad delta cannot publish params/push tokens and then
        // abort the audio/route steps. `applyAudioDelta` below re-validates
        // for its direct callers; the double check here is intentional.
        inputModes.validateAudioDelta(delta)
        var reconnectNeeded = false
        var suppressReload = false
        // 1. Merge connection params under applyLock: the read-modify-write
        // must not lose keys when two applies interleave. Presence-based
        // reconnect: any delta carrying a reconnect-relevant key returns true.
        synchronized(applyLock) {
            var params = connectionParams
            if (delta.server != null) {
                // Snapshot the app's Server: ConnectionParams stores the
                // reference and onConnectionEstablished mutates it via
                // setResolved() on the connection thread, so publishing the
                // live ref would let the app mutate host/port/user under an
                // in-flight connect (and leak resolved state back into the
                // app's object). Copy field-for-field like getTargetServer.
                val s = delta.server
                val snapshot = Server(s.id, s.name, s.host, s.port, s.username, s.password).also {
                    it.resolvedHost = s.resolvedHost
                    it.resolvedPort = s.resolvedPort
                }
                params = params.copy(server = snapshot)
                reconnectNeeded = true
            }
            val certificate = delta.certificate
            if (certificate != null) {
                params = params.copy(
                    certificate = certificate.data.copyOf(),
                    // A null password keeps the previous one, mirroring the old
                    // bundle where an absent password key left it untouched.
                    certificatePassword = certificate.password ?: params.certificatePassword,
                )
                reconnectNeeded = true
            }
            val forceTcp = delta.forceTcp
            if (forceTcp != null) {
                params = params.copy(forceTcp = forceTcp)
                reconnectNeeded = true
            }
            if (delta.clientName != null) {
                params = params.copy(clientName = delta.clientName)
                reconnectNeeded = true
            }
            if (delta.accessTokens != null) {
                params = params.copy(accessTokens = delta.accessTokens.toList())
            }
            val trustStore = delta.trustStore
            if (trustStore != null) {
                val trustStorePath = trustStore.path
                if (isTrustStorePathAllowed(trustStorePath)) {
                    params = params.copy(
                        trustStore = trustStorePath,
                        // Password and format describe the store at this path, so
                        // they are only applied together with an accepted path.
                        trustStorePassword = trustStore.password ?: params.trustStorePassword,
                        trustStoreFormat = trustStore.format ?: params.trustStoreFormat,
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
                params = params.copy(localMuteHistory = delta.localMuteHistory.toList())
                reconnectNeeded = true
            }
            if (delta.localIgnoreHistory != null) {
                params = params.copy(localIgnoreHistory = delta.localIgnoreHistory.toList())
                reconnectNeeded = true
            }
            // Serialized publish of the locally-built snapshot; volatile
            // visibility is what cross-thread readers need once the lock
            // guarantees a single writer at a time.
            connectionParams = params
        }
        // 2. Push access tokens live. Post-sync only: pre-sync the snapshot
        // already carries them into connect()'s Authenticate, and an early
        // duplicate would race the handshake auth.
        if (delta.accessTokens != null) {
            val connection = mConnection
            if (connection != null && connection.isSynchronized()) {
                // Re-read the published snapshot (merged above): the local
                // merge copy never escapes the applyLock region.
                connection.sendAccessTokens(requireNotNull(connectionParams.accessTokens))
            }
        }
        // 3. Apply audio settings (builder knobs, input modes, leveler).
        // TODO(step3-wiring): pass the voice link for live FPP rescaling; null keeps
        // the gate green until HumlaService owns LegacyVoiceLink.
        inputModes.applyAudioDelta(delta, mAudioBuilder, mAudioHandler, null)
        // 4. Route inputs and toggle side effects.
        val audioStream = delta.audioStream
        if (audioStream != null) {
            // Handset-derived intent (earpiece vs speakerphone voice); the
            // route policy re-derives the effective stream before every
            // pipeline creation, so mid-call toggles apply on recreate.
            // Funnel-only: no direct builder write here (it would be
            // overwritten by the reload below anyway).
            route.setHandsetStream(audioStream)
        }
        val bluetoothSco = delta.bluetoothSco
        if (bluetoothSco != null) {
            val requested = bluetoothSco
            // Read the previous request before overwriting: the enable rule
            // below depends on old-vs-new, so name it explicitly.
            val wasScoRequested = route.isScoRequested
            // A fresh enable skips the end-of-method reload only when the
            // bundle holds nothing else audio-affecting: the confirmed
            // callback then performs the single pipeline recreate on the
            // SCO route. Bundled audio changes reload immediately (and the
            // callback reloads once more on confirm) — the immediate reload
            // stays even for pure enables because the failure path
            // early-returns without reloading, and stranding new audio
            // settings on a failed bring-up would be worse than one extra
            // recreate; the connect-time bundle never reloads here since no
            // handler exists yet.
            suppressReload = !delta.hasOtherAudioKeys() &&
                (requested || !wasScoRequested)
            route.setScoRequested(requested)
            if (!requested) {
                // Synchronous teardown with notifications: stop() itself never
                // callbacks, and the generic reload below only refreshes the
                // pipeline, not observers or cue audio.
                route.stopScoWithNotify()
            }
            route.updateScoRoute()
        }

        // 5. Reload audio subsystem if initialized. Guarded on CONNECTED as well
        // as initialization: a disconnect racing a settings change must not
        // resurrect the pipeline (or trip the DEBUG assertion inside).
        // The SCO-only suppressReload rule stays here (it reads the config
        // delta): reload=false updates inputs only with no recreate, still
        // re-evaluating notify; reload=true recreates even when unchanged.
        // Connect-time (no pipeline) records the decision for the next
        // create, never recreating.
        val audioHandler = mAudioHandler
        if (audioHandler != null && audioHandler.isInitialized() &&
            mConnectionState == ConnectionState.CONNECTED
        ) {
            route.onEvent(RouteEvent.SettingsChanged(!suppressReload))
            if (!suppressReload) {
                Log.i(TAG, "Audio subsystem reloaded after settings change.")
            }
        }
        return reconnectNeeded
    }

    /**
     * Exposes the current connection. The current connection is set once an attempt to connect to
     * a server is made, and remains set until a subsequent connection. It remains available
     * after disconnection to provide information regarding the terminated connection.
     * @return The active [HumlaConnection].
     */
    open fun getConnection(): HumlaConnection? {
        return mConnection
    }

    /**
     * Return the current [AudioHandler]. An AudioHandler is instantiated upon connection
     * to a server, and destroyed upon disconnection.
     * @return the active AudioHandler, or null if there is no active connection.
     */
    @Throws(NotSynchronizedException::class)
    private fun getAudioHandler(): AudioHandler {
        if (!isSynchronized())
            throw NotSynchronizedException()
        val handler = mAudioHandler
        if (handler == null)
            throw IllegalStateException("Audio handler should always be instantiated while connected!")
        return handler
    }

    /**
     * Returns the current immutable session state. Valid once the session
     * has synced; throws [NotSynchronizedException] before that.
     */
    @Throws(NotSynchronizedException::class)
    private fun getSessionState(): SessionState {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return _sessionState.value
    }

    open override fun getConnectionState(): ConnectionState {
        return mConnectionState
    }

    open override fun getConnectionError(): HumlaException? {
        val connection = getConnection()
        return connection?.getError()
    }

    open override fun isReconnecting(): Boolean {
        return mReconnecting
    }

    open override fun getReconnectAttempts(): Int {
        return mReconnectAttempts.get()
    }

    /**
     * Test seam: the JVM suite cannot drive the Main timers that mutate the
     * counter, so tests set it directly. Production never calls this.
     */
    @TestOnly
    fun setReconnectAttemptsForTest(attempts: Int) {
        mReconnectAttempts.set(attempts)
    }

    open override fun cancelReconnect() {
        mReconnectAttempts.set(0)
        setReconnecting(false)
        // Only release the Doze shield when no live or in-flight attempt holds
        // it: cancelling a future retry while CONNECTED must not drop the
        // indefinite hold from onConnectionSynchronized, and cancelling while
        // CONNECTING must not drop the 15 s hold from connect() — the TCP
        // handshake still runs (cancelReconnect never aborts a live attempt).
        if (mConnectionState != ConnectionState.CONNECTED &&
            mConnectionState != ConnectionState.CONNECTING
        ) {
            val wakeLock = mWakeLock
            if (wakeLock != null && wakeLock.isHeld) {
                wakeLock.release()
            }
            releaseWifiLock()
        }
    }

    // Returns a copy: the published snapshot's Server travels by reference and
    // is mutated by setResolved() on connect; handing out the live ref would
    // let callers mutate host/port under an in-flight connect().
    open override fun getTargetServer(): Server? {
        val s = connectionParams.server ?: return null
        return Server(s.id, s.name, s.host, s.port, s.username, s.password).also {
            it.resolvedHost = s.resolvedHost
            it.resolvedPort = s.resolvedPort
        }
    }

    // Upper-Camel is required by `IHumlaService.HumlaSession`; keep (API stability).
    @Suppress("FunctionName")
    @Throws(HumlaDisconnectedException::class)
    open override fun HumlaSession(): IHumlaSession {
        if (mConnectionState != ConnectionState.CONNECTED) {
            throw HumlaDisconnectedException()
        }
        return this
    }

    open override fun getTCPLatency(): Long {
        try {
            return requireConnection().getTCPLatency()
        } catch (e: NotConnectedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getUDPLatency(): Long {
        try {
            return requireConnection().getUDPLatency()
        } catch (e: NotConnectedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getMaxBandwidth(): Int {
        try {
            return requireConnection().getMaxBandwidth()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
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
            return requireConnection().getServerVersion()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerVersionV2(): Long {
        try {
            return requireConnection().getServerVersionV2()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerRelease(): String? {
        try {
            return requireConnection().getServerRelease()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerOSName(): String? {
        try {
            return requireConnection().getServerOSName()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getServerOSVersion(): String? {
        try {
            return requireConnection().getServerOSVersion()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getSessionId(): Int {
        try {
            return requireConnection().getSession()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getSessionUser(): IUser? {
        try {
            val s = getSessionState()
            val session = s.server.selfSession ?: throw IllegalStateException("No self session post-synchronization!")
            if (!s.server.users.containsKey(session)) return null
            return UserHandle(session, sessionState)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getSessionChannel(): IChannel? {
        val user = getSessionUser()
        if (user != null)
            return user.channel
        throw IllegalStateException("Session user should be set post-synchronization!")
    }

    open override fun getUser(session: Int): IUser? {
        try {
            val s = getSessionState()
            if (!s.server.users.containsKey(session)) return null
            return UserHandle(session, sessionState)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getChannel(id: Int): IChannel? {
        try {
            val s = getSessionState()
            if (!s.server.channels.containsKey(id)) return null
            return ChannelHandle(id, sessionState)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getRootChannel(): IChannel? {
        return getChannel(0)
    }

    open override fun getPermissions(): Int {
        try {
            return getSessionState().server.rootPermissions
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getTransmitMode(): Int {
        return inputModes.transmitMode
    }

    open override fun getCodec(): HumlaUDPMessageType? {
        try {
            return requireConnection().getCodec()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun isTalking(): Boolean {
        return inputModes.toggle.isTalkingOn
    }

    open override fun setTalkingState(talking: Boolean) {
        inputModes.toggle.isTalkingOn = talking
        mAudioHandler?.setPttTalking(talking)
    }

    open override fun joinChannel(channel: Int) {
        moveUserToChannel(getSessionId(), channel)
    }

    open override fun moveUserToChannel(session: Int, channel: Int) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.channelId = channel
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun sendAccessTokens(tokens: List<String>) {
        requireConnection().sendAccessTokens(tokens)
    }

    open override fun requestPermissions(channel: Int) {
        val pqb = Mumble.PermissionQuery.newBuilder()
        pqb.channelId = channel
        requireConnection().sendTCPMessage(pqb.build(), HumlaTCPMessageType.PermissionQuery)
    }

    open override fun requestComment(session: Int) {
        val rbb = Mumble.RequestBlob.newBuilder()
        rbb.addSessionComment(session)
        requireConnection().sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob)
    }

    open override fun requestAvatar(session: Int) {
        val rbb = Mumble.RequestBlob.newBuilder()
        rbb.addSessionTexture(session)
        requireConnection().sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob)
    }

    open override fun requestChannelDescription(channel: Int) {
        val rbb = Mumble.RequestBlob.newBuilder()
        rbb.addChannelDescription(channel)
        requireConnection().sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob)
    }

    open override fun registerUser(session: Int) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.userId = 0
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
        val urb = Mumble.UserRemove.newBuilder()
        urb.session = session
        if (reason != null) {
            urb.reason = reason
        }
        urb.ban = ban
        requireConnection().sendTCPMessage(urb.build(), HumlaTCPMessageType.UserRemove)
    }

    open override fun sendUserTextMessage(session: Int, message: String?): Message? {
        try {
            if (!isSynchronized())
                throw NotSynchronizedException()

            val tmb = Mumble.TextMessage.newBuilder()
            tmb.addSession(session)
            if (message != null) {
                tmb.message = message
            }
            requireConnection().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

            val s = getSessionState()
            val selfSession = getSessionId()
            val selfName = s.server.users[selfSession]?.name
            val users = mutableListOf<IUser>()
            if (s.server.users.containsKey(session)) users.add(UserHandle(session, sessionState))
            return Message(selfSession, selfName, emptyList(), emptyList(), users, message)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message? {
        try {
            if (!isSynchronized())
                throw NotSynchronizedException()

            val tmb = Mumble.TextMessage.newBuilder()
            if (tree) tmb.addTreeId(channel)
            else tmb.addChannelId(channel)
            if (message != null) {
                tmb.message = message
            }
            requireConnection().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

            val s = getSessionState()
            val selfSession = getSessionId()
            val selfName = s.server.users[selfSession]?.name
            val targetChannels = mutableListOf<IChannel>()
            if (s.server.channels.containsKey(channel)) targetChannels.add(ChannelHandle(channel, sessionState))
            return Message(
                selfSession, selfName, targetChannels,
                if (tree) targetChannels else emptyList(), emptyList(), message
            )
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun setUserComment(session: Int, comment: String?) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        if (comment != null) {
            usb.comment = comment
        }
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setPrioritySpeaker(session: Int, priority: Boolean) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.prioritySpeaker = priority
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        // Mirror self-path/murmur coercion (murmur/Messages.cpp): deaf
        // implies mute, unmute implies undeafen, so the wire packet is
        // canonical even though the server would canonicalise anyway.
        var m = mute
        var d = deaf
        if (d) m = true
        if (!m) d = false
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.mute = m
        usb.deaf = d
        // Diverges from murmur/Messages.cpp (which leaves suppress untouched):
        // an admin unmute must also clear a prior suppress, otherwise the
        // target stays silently suppressed after unmute. Server permission-gates this.
        if (!m) usb.suppress = false
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
        // Intent transition coerces centrally (deaf implies mute, unmute
        // implies undeafen); the outbound packet and the optimistic state
        // derive from the same pair so no flap for one RTT.
        val result = applySelfMuteDeafIntent(_sessionState.value, mute, deaf)
        val outbound = result.userStateOutbound
        if (outbound == null) {
            // Not synced: preserve the old send-then-ignore shape (the
            // message goes out, no model to update yet).
            var m = mute
            var d = deaf
            if (d) m = true
            if (!m) d = false
            val usb = Mumble.UserState.newBuilder()
            usb.selfMute = m
            usb.selfDeaf = d
            requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
            return
        }
        requireConnection().sendTCPMessage(outbound, HumlaTCPMessageType.UserState)
        applyOptimisticSelfMuteDeaf(mute, deaf)
    }

    /**
     * Desktop-parity optimistic self-mute/deafen: apply the requested state
     * to the local model and capture gate immediately, treating the server
     * echo as the confirm. murmur applies self_mute/self_deaf from self
     * unconditionally (no permission check; spoofed writes from others are
     * silently dropped), so no rollback is needed: TCP ordering guarantees
     * echoes arrive in request order and the state converges on the last
     * request. Other users' mute (admin `mute`/`deaf`) stays
     * pessimistic in [setMuteDeafState].
     *
     * Updating the immutable state fans the change out through the existing
     * `onUserStateUpdated` path: menu icons, notification, settings
     * persistence, and TTS suppression all follow with no UI edits. The echo
     * then converges on the same value.
     *
     * Accepted transients (both convergent via the echo): the capture gate
     * is pushed before fanning out, so if the session user is not yet
     * visible the mic follows desired state up to a round trip ahead of the
     * UI; and the chat log line is best-effort (dropped if a disconnect
     * interleaves, since logging re-gates on synchronization while the state
     * change itself does not).
     */
    private fun applyOptimisticSelfMuteDeaf(mute: Boolean, deaf: Boolean) {
        val connection = mConnection
        if (connection == null || !connection.isSynchronized())
            return
        // Recompute inside update so a server echo landing between the
        // preview and the write is not clobbered; the intent is a pure
        // function of (state, mute, deaf), so the outcome converges.
        var events: List<se.lublin.humla.state.SessionEvent> = emptyList()
        _sessionState.update { current ->
            val fresh = applySelfMuteDeafIntent(current, mute, deaf)
            events = fresh.events
            fresh.state
        }
        val applied = _sessionState.value
        val selfSession = applied.server.selfSession ?: return
        val self = applied.server.users[selfSession]
        // Push the capture gate before fanning out: desired state is
        // authoritative even when the model user isn't visible yet (e.g.
        // the reconnect restore racing the initial UserState dump). Snapshot
        // the handler: disconnect nulls the field from another thread.
        val audioHandler = mAudioHandler
        if (audioHandler != null && self != null)
            audioHandler.setSelfMutedOptimistic(self.selfMuted)
        stateLogger?.log(events, applied)
        if (applied.server.users.containsKey(selfSession)) {
            mCallbacks.onUserStateUpdated(UserHandle(selfSession, sessionState))
        }
    }

    open override fun registerObserver(observer: IHumlaObserver) {
        mCallbacks.registerObserver(observer)
    }

    open override fun unregisterObserver(observer: IHumlaObserver) {
        mCallbacks.unregisterObserver(observer)
    }

    open override fun isConnected(): Boolean {
        return mConnectionState == ConnectionState.CONNECTED
    }

    open override fun linkChannels(channelA: IChannel?, channelB: IChannel?) {
        val a = channelA ?: throw NullPointerException("channelA")
        val b = channelB ?: throw NullPointerException("channelB")
        val csb = Mumble.ChannelState.newBuilder()
        csb.channelId = a.id
        csb.addLinksAdd(b.id)
        requireConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    open override fun unlinkChannels(channelA: IChannel?, channelB: IChannel?) {
        val a = channelA ?: throw NullPointerException("channelA")
        val b = channelB ?: throw NullPointerException("channelB")
        val csb = Mumble.ChannelState.newBuilder()
        csb.channelId = a.id
        csb.addLinksRemove(b.id)
        requireConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    open override fun unlinkAllChannels(channel: IChannel?) {
        val ch = channel ?: throw NullPointerException("channel")
        val csb = Mumble.ChannelState.newBuilder()
        csb.channelId = ch.id
        for (linked in ch.links) {
            csb.addLinksRemove(linked.id)
        }
        requireConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    // Whisper allocation stays on the legacy list in step 2: model targets
    // cannot convert to state targets (channel fields are opaque), so
    // state.local.whisperTargets is mirrored only on unregister/free until
    // step 3 moves allocation into Session. mWhisperTargetList is canonical.
    open override fun registerWhisperTarget(target: WhisperTarget?): Byte {
        val t = target ?: throw NullPointerException("target")
        // Resolve the live connection before allocating: the 5-bit ID space
        // is exhaustible, and a disconnect between alloc and send would leak
        // the slot. Fail fast with no side effect when offline.
        val live = mConnection ?: throw IllegalArgumentException("No active connection")
        // Liveness before alloc: sendTCPMessage silently drops when
        // !mConnected, so allocating first would leak one of only 30 5-bit
        // slots on every CONNECTING-phase call. Fail fast with no side
        // effect when offline (ISE matches requireConnection's
        // disconnected-before-connect contract; null stays IAE).
        if (!live.isConnected()) {
            throw IllegalStateException("No active connection")
        }
        val id = synchronized(whisperLock) { mWhisperTargetList.append(t) }
        if (id.toInt() < 0) {
            return (-1).toByte()
        }

        try {
            val voiceTarget = t.createTarget()
            val vtb = Mumble.VoiceTarget.newBuilder()
            vtb.id = id.toInt()
            vtb.addTargets(voiceTarget)
            live.sendTCPMessage(vtb.build(), HumlaTCPMessageType.VoiceTarget)
            // sendTCPMessage drops silently when disconnected (no throw):
            // a disconnect racing the send would leak one of 30 slots.
            // Re-validate under no lock (free is idempotent); freeing a
            // just-sent target is harmless — the next connect clears all.
            if (!live.isConnected()) {
                synchronized(whisperLock) { mWhisperTargetList.free(id) }
                throw IllegalStateException("No active connection")
            }
        } catch (e: RuntimeException) {
            synchronized(whisperLock) { mWhisperTargetList.free(id) }
            throw e
        }
        return id
    }

    open override fun unregisterWhisperTarget(targetId: Byte) {
        if (targetId < WhisperTargetList.TARGET_MIN || targetId > WhisperTargetList.TARGET_MAX) {
            return
        }
        synchronized(whisperLock) { mWhisperTargetList.free(targetId) }
        _sessionState.update { current ->
            applyUnregisterWhisperIntent(current, targetId.toInt() and 0xFF).state
        }
    }

    open override fun setVoiceTargetId(targetId: Byte) {
        // Mask, not Byte.toUnsignedInt: avoids the core-library-desugaring dependency.
        if ((targetId.toInt() and 0xFF) > 0x1F) {
            throw IllegalArgumentException(
                "Target ID must be at most 5 bits: " + (targetId.toInt() and 0xFF)
            )
        }
        mVoiceTargetId = targetId
        _sessionState.update { current ->
            applyVoiceTargetIntent(current, targetId.toInt() and 0xFF).state
        }
        mAudioHandler?.setVoiceTargetId(targetId)
        mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(targetId))
    }

    open override fun getVoiceTargetId(): Byte {
        return mVoiceTargetId
    }

    open override fun getVoiceTargetMode(): VoiceTargetMode? {
        return VoiceTargetMode.fromId(mVoiceTargetId)
    }

    open override fun getWhisperTarget(): WhisperTarget? {
        // Snapshot: the id is written from Main/binder while the list is
        // guarded by whisperLock — check-then-get on the live field could
        // return another target's entry after a mode flip.
        val targetId = mVoiceTargetId
        if (VoiceTargetMode.fromId(targetId) == VoiceTargetMode.WHISPER) {
            return synchronized(whisperLock) { mWhisperTargetList.get(targetId) }
        }
        return null
    }

    open override fun getServerSettings(): ServerSettings? {
        // ServerSettings wraps a ServerConfig message; rebuild the same
        // values from state (tests pin the field mapping, not the type).
        // TODO(step6): IServerSettings becomes a state value with the container.
        try {
            val settings = getSessionState().server.info.settings ?: return null
            val msg = se.lublin.humla.protobuf.Mumble.ServerConfig.newBuilder()
                .setAllowHtml(settings.allowHtml)
                .setMessageLength(settings.messageLength)
                .setImageMessageLength(settings.imageMessageLength)
                .setMaxBandwidth(getSessionState().server.info.maxBandwidth ?: -1)
                .setMaxUsers(settings.maxUsers)
                .setWelcomeText(getSessionState().server.info.welcomeText ?: "")
                .build()
            return ServerSettings(msg)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun setListeningChannel(channelId: Int, listening: Boolean) {
        if (!isSynchronized()) return
        val usb = Mumble.UserState.newBuilder()
        // Single TOCTOU-safe block: getSessionId() and the trailing send
        // each re-read mConnection, so a racing onDestroy/disconnect between
        // the isSynchronized check and the send must fail closed (return),
        // never throw ISE to the binder caller. getSessionId() only throws
        // ISE (requireConnection translates IAE->ISE; NotSynchronized->ISE).
        try {
            usb.session = getSessionId()
            if (listening) {
                usb.addListeningChannelAdd(channelId)
            } else {
                usb.addListeningChannelRemove(channelId)
            }
            requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
        } catch (e: IllegalStateException) {
            return
        }
    }

    open override fun setListeningVolume(channelId: Int, volumeAdjustment: Float) {
        if (!isSynchronized()) return
        // Same TOCTOU discipline as setListeningChannel: fail closed.
        try {
            val preview = applyListeningVolumeIntent(_sessionState.value, channelId, volumeAdjustment)
            val outbound = preview.userStateOutbound ?: return
            val withSession = outbound.toBuilder().setSession(getSessionId()).build()
            requireConnection().sendTCPMessage(withSession, HumlaTCPMessageType.UserState)
            // Recompute inside update so concurrent volume intents for
            // different channels do not lose each other's map entries.
            _sessionState.update { current ->
                applyListeningVolumeIntent(current, channelId, volumeAdjustment).state
            }
        } catch (e: IllegalStateException) {
            return
        }
    }

    open override fun isListeningToChannel(channelId: Int): Boolean {
        // Fail closed when disconnected.
        val s = try {
            getSessionState()
        } catch (e: NotSynchronizedException) {
            return false
        } catch (e: IllegalStateException) {
            return false
        }
        val self = s.server.self ?: return false
        return self.listeningChannels.contains(channelId)
    }

    open override fun setLocalMute(session: Int, muted: Boolean) {
        _sessionState.update { current ->
            se.lublin.humla.state.applyLocalMuteIntent(current, session, muted).state
        }
    }

    open override fun setLocalIgnore(session: Int, ignored: Boolean) {
        _sessionState.update { current ->
            se.lublin.humla.state.applyLocalIgnoreIntent(current, session, ignored).state
        }
    }

    /**
     * The current connection state of the service.
     */
    enum class ConnectionState {
        /**
         * The default state of Humla, before connection to a server and after graceful/expected
         * disconnection from a server.
         */
        DISCONNECTED,

        /**
         * A connection to the server is currently in progress.
         */
        CONNECTING,

        /**
         * Humla has received all data necessary for normal protocol communication with the server.
         */
        CONNECTED,

        /**
         * The connection was lost due to either a kick/ban or socket I/O error.
         * Humla may be reconnecting in this state.
         * See also [isReconnecting]
         * See also [cancelReconnect]
         */
        CONNECTION_LOST
    }

    // internal visibility keeps construction module-private (same-module
    // tests and outer-class access allowed); onBind remains the sole
    // production instantiation site.
    class HumlaBinder internal constructor(private val mService: IHumlaService) : Binder() {
        fun getService(): IHumlaService {
            return mService
        }
    }
}
