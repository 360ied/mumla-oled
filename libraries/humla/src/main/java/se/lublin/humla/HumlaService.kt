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
import se.lublin.humla.exception.AudioException
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.Channel
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.net.HandshakeFailure
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.InputModes
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.protocol.applyAudioDelta
import se.lublin.humla.protocol.validateAudioDelta
import se.lublin.humla.util.CertRef
import se.lublin.humla.util.ConfigDelta
import se.lublin.humla.util.ConnectionParams
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.TrustStoreRef
import se.lublin.humla.util.VoiceTargetMode
import java.io.File
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.ArrayList

open class HumlaService : LifecycleService(), IHumlaService, IHumlaSession,
    HumlaConnection.HumlaConnectionListener, HumlaLogger {

    companion object {
        private val TAG = HumlaService::class.java.name

        /**
         * An action to immediately connect to a given Mumble server.
         * Requires a server (see {@link #buildConnectDelta}).
         */
        const val ACTION_CONNECT = "se.lublin.humla.CONNECT"

        const val RECONNECT_DELAY_INITIAL = 1000
        const val RECONNECT_DELAY_MAX = 25000

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
    // monitor group moved atomically onto the controller's private lock —
    // no route field remains under the service monitor.
    private lateinit var route: AudioRouteController

    private val routeHost = object : AudioRouteController.Host {
        override val isLive: Boolean
            get() = mConnectionState == ConnectionState.CONNECTED
        override val sessionToken: Int
            get() = connectionGeneration
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
    // flag in this file. The connection generation below is bumped on main
    // (connect is main-confined); only onConnectionSynchronized arrives via
    // a Main Handler post (HumlaConnection posts sync), while
    // onConnectionDisconnected runs directly on connection threads, so
    // volatile here is required for the disconnect path, not belt-and-braces.
    @Volatile
    private var connectionGeneration = 0
    @Volatile
    private var reconnectJob: Job? = null




    @Volatile
    private var mVoiceTargetId: Byte = 0
    private lateinit var mWhisperTargetList: WhisperTargetList

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
    @Volatile
    private var mModelHandler: ModelHandler? = null

    @Volatile
    private var mAudioHandler: AudioHandler? = null

    // Written from main (start/connect UI) and connection threads
    // (disconnect path): volatile so reconnect UI never strands on stale state.
    @Volatile
    private var mReconnecting: Boolean = false

    @Volatile
    private var mReconnectAttempts = 0
    private var mNetworkCallback: ConnectivityManager.NetworkCallback? = null
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

                        val modelHandler = mModelHandler
                        val connection = mConnection
                        if (modelHandler == null || connection == null) {
                            return@launch
                        }
                        val currentUser = modelHandler.getUser(connection.getSession())
                            ?: return@launch

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
            override fun onUserTalkStateUpdated(user: User) {
                mCallbacks.onUserTalkStateUpdated(user)
            }

            override fun getUser(session: Int): User? {
                return mModelHandler?.getUser(session)
            }
        }

    /**
     * Stops the service for a start request that was ignored, unless a
     * connection attempt or established connection is in progress.
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
                    // apply/connect() on the malformed intent.
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
                setReconnecting(false)
                connect()
            }
        }

        return START_NOT_STICKY
    }

    open override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager?
        val wakeLock = requireNotNull(powerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService")
        wakeLock.setReferenceCounted(false)
        mWakeLock = wakeLock

        // Doze shield: keep the WiFi radio out of firmware power-save for the
        // lifetime of the connection. Without a WifiLock (dumpsys wifi showed
        // zero locks acquired), on battery + screen-off the radio slept between
        // Doze maintenance windows, stalling keepalives past Murmur's 30 s
        // timeout. Created here, acquired in connect(), released on final
        // teardown. Uses the application context so the lock outlives config
        // changes; reference-counted false to mirror mWakeLock semantics.
        // WIFI_MODE_FULL_HIGH_PERF (not FULL_LOW_LATENCY): LOW_LATENCY needs
        // API 30+ (at the minSdk 30 floor, so always available), and HIGH_PERF is the
        // documented VoIP-compatible mode available on all supported devices.
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
        // before any callback fires.
        val scoLink = BluetoothScoManager(this, object : BluetoothScoManager.Listener {
            override fun onScoConnected() {
                route.onScoConnected()
            }

            override fun onScoDisconnected() {
                route.onScoDisconnected()
            }

            override fun onScoFailed(reason: String?) {
                route.onScoFailed(reason)
            }
        })
        route = AudioRouteController(audioPlatform, scoLink, lifecycleScope, routeHost)
        route.registerDeviceListener(mHandler)
        route.onInit()
        mWhisperTargetList = WhisperTargetList()

        // initialize minidns dns lookup mechanisms
        init(this)
    }

    open override fun onDestroy() {
        super.onDestroy()
        mReconnectAttempts = 0
        setReconnecting(false)
        val wakeLock = mWakeLock
        if (wakeLock != null && wakeLock.isHeld) {
            wakeLock.release()
        }
        // Route teardown (link, mode, device listener, timers) lives in the
        // controller; bare teardown, no notify by design.
        route.onEvent(RouteEvent.Destroyed)
        releaseWifiLock()
    }

    // Non-null Intent: matches Service.onBind's platform signature exactly; a
    // nullable parameter does not override (the pre-image Java was non-null).
    open override fun onBind(intent: Intent): IBinder {
        return HumlaBinder(this)
    }

    protected open fun connect() {
        // One atomic snapshot for the whole attempt; the server reference
        // travels by reference (mutable, resolved in onConnectionEstablished).
        val params = connectionParams
        val server = params.server
        if (server == null) {
            Log.w(TAG, "connect() called with null mServer, cancelling reconnect")
            setReconnecting(false)
            return
        }

        try {
            mConnectionState = ConnectionState.DISCONNECTED
            mVoiceTargetId = 0
            mWhisperTargetList.clear()

            val wakeLock = mWakeLock
            if (wakeLock != null) {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                wakeLock.acquire(15000)
            }
            acquireWifiLock()

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
            mModelHandler = null
            mAudioHandler = null
            route.onEvent(RouteEvent.SessionEnded)
            mConnection?.disconnect()
            mConnection = null
            // Generation-scoped listener: the old connection's TCP read-loop
            // finally still posts onTCPConnectionDisconnect to the shared
            // listener afterwards; without an identity check the dead
            // connection's async post would wipe the live attempt's handlers
            // (model/audio null, route reset, phantom observer disconnect).
            // Interface delegation forwards every other callback untouched.
            val generation = ++connectionGeneration
            val connectionListener = object : HumlaConnection.HumlaConnectionListener by this {
                // All five callbacks filter on generation: a stale connection's
                // async posts (established/warning/handshake/disconnect/sync)
                // must never drive the live attempt's state machine. The
                // CONNECTING guards make stale established/handshake
                // unreachable today, but the filter is airtight regardless.
                private fun isStale(): Boolean {
                    if (generation != connectionGeneration) {
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

            val modelHandler = ModelHandler(
                this, mCallbacks, this,
                params.localMuteHistory, params.localIgnoreHistory
            )
            mModelHandler = modelHandler
            connection.addTCPMessageHandlers(modelHandler)

            mConnectionState = ConnectionState.CONNECTING

            mCallbacks.onConnecting()

            connection.connect(requireNotNull(server.host), server.port)
        } catch (e: HumlaException) {
            Log.e(TAG, "Connect failed", e)
            onConnectionDisconnected(e)
        }
    }

    open override fun disconnect() {
        mReconnectAttempts = 0
        setReconnecting(false)
        // Synchronous state: the async read-loop finally still delivers the
        // formal onConnectionDisconnected, but observers must see
        // DISCONNECTED from the explicit call, not a lingering CONNECTING.
        // Harmless under the generation + CONNECTING guards (a quick
        // reconnect bumps generation; the stale post is filtered).
        mConnectionState = ConnectionState.DISCONNECTED
        val wakeLock = mWakeLock
        if (wakeLock != null && wakeLock.isHeld) {
            wakeLock.release()
        }
        releaseWifiLock()
        // Tear down SCO synchronously: the async disconnect callback may lag,
        // and a quick reconnect must not reuse a stale link or route flag.
        route.stopScoWithNotify()
        mConnection?.disconnect()
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
     * @return true if Humla has received the ServerSync message, indicating synchronization with
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

        // TODO hackish, but this seems to happen?!
        if (mModelHandler == null) {
            Log.e(TAG, "onConnectionSynchronized: mModelHandler is null")
            return
        }

        mReconnectAttempts = 0
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
        // First creation bypasses the funnel but still pushes
        // route-dependent audio explicitly (see controller).
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

        mModelHandler = null
        mAudioHandler = null
        mVoiceTargetId = 0
        mWhisperTargetList.clear()
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

    // No @Throws: these are private helpers whose requireNotNull hardening
    // throws IllegalArgumentException, not the historical checked type.
    private fun requireConnection(): HumlaConnection {
        return requireNotNull(mConnection) { "No active connection" }
    }

    private fun requireModelHandler(): ModelHandler {
        return requireNotNull(mModelHandler) { "No active model handler" }
    }

    open override fun getReconnectDelay(): Int {
        return getReconnectDelayForAttempts(mReconnectAttempts)
    }

    private fun registerNetworkCallback() {
        if (mNetworkCallbackRegistered) return
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
                                    reconnectJob?.cancel()
                                    reconnectJob = null
                                    mReconnectAttempts++
                                    connect()
                                }
                            }
                        }
                    }
                }
                cm.registerDefaultNetworkCallback(requireNotNull(mNetworkCallback))
                mNetworkCallbackRegistered = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback: " + e.message)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!mNetworkCallbackRegistered) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            val callback = mNetworkCallback
            if (cm != null && callback != null) {
                cm.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback: " + e.message)
        } finally {
            mNetworkCallbackRegistered = false
        }
    }

    open fun setReconnecting(reconnecting: Boolean) {
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
        Log.v(TAG, "Scheduling reconnect in $delayMs ms (attempt $mReconnectAttempts)")
        val wakeLock = mWakeLock
        if (wakeLock != null) {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            wakeLock.acquire((delayMs + 15000).toLong())
        }
        reconnectJob?.cancel()
        val launchedReconnect = lifecycleScope.launch {
            delay(delayMs.toLong())
            // Mirror the onAvailable CONNECTING guard: the timer can resume
            // after onAvailable already connected (cancel is best-effort),
            // and an unguarded connect() would double-increment attempts and
            // tear down a just-started handshake.
            if (mReconnecting && mConnectionState != ConnectionState.CONNECTING) {
                mReconnectAttempts++
                connect()
            }
        }
        launchedReconnect.invokeOnCompletion {
            if (reconnectJob === launchedReconnect) reconnectJob = null
        }
        reconnectJob = launchedReconnect
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
        if (BuildConfig.DEBUG && mConnectionState != ConnectionState.CONNECTED) {
            throw AssertionError("Attempted to instantiate audio handler when not connected!")
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
            val modelHandler = requireModelHandler()
            mAudioHandler = mAudioBuilder.initialize(
                modelHandler.getUser(connection.getSession()),
                connection.getMaxBandwidth(), connection.getCodec(),
                mVoiceTargetId
            )
            val handler = requireNotNull(mAudioHandler)
            handler.setProtobufUdp(connection.isProtobufUdpSupported())
            connection.addTCPMessageHandlers(handler)
            connection.addUDPMessageHandlers(handler)
        } catch (e: NotSynchronizedException) {
            throw RuntimeException("Attempted to create audio handler when not synchronized!")
        }
    }

    /**
     * Validates a trust-store path for {@link #apply}. The null
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
        if (!path.endsWith(".bks")) {
            return false
        }
        try {
            val filesDir = filesDir ?: return false
            val base = filesDir.canonicalFile.path
            val candidate = File(path).canonicalFile.path
            return candidate.startsWith(base + File.separator)
        } catch (e: IOException) {
            return false
        }
    }

    /**
     * Loads all defined settings from the given delta into the HumlaService.
     * Null fields are unchanged. Some settings may only take effect after a
     * reconnect. Throws IllegalArgumentException on unknown transmit mode;
     * the pipeline recreate funnels through the route controller, which handles
     * AudioException internally.
     * @param delta A one-shot configuration command.
     * @return true if a reconnect is required for changes to take effect.
     * @see se.lublin.humla.HumlaService
     */
    open fun apply(delta: ConfigDelta): Boolean {
        // Pure validation first: the only throwing input (unknown transmit
        // mode) must fail before any commit, so a bad delta cannot publish
        // params/push tokens and then abort the audio/route steps.
        inputModes.validateAudioDelta(delta)
        var reconnectNeeded = false
        var suppressReload = false
        // 1. Merge connection params; frozen atomically below. Presence-based
        // reconnect: any delta carrying a reconnect-relevant key returns true.
        var params = connectionParams
        if (delta.server != null) {
            params = params.copy(server = delta.server)
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
            params = params.copy(accessTokens = delta.accessTokens?.toList())
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
            params = params.copy(localMuteHistory = delta.localMuteHistory?.toList())
            reconnectNeeded = true
        }
        if (delta.localIgnoreHistory != null) {
            params = params.copy(localIgnoreHistory = delta.localIgnoreHistory?.toList())
            reconnectNeeded = true
        }
        synchronized(this) {
            connectionParams = params
        }
        // 2. Push access tokens live.
        if (delta.accessTokens != null) {
            val connection = mConnection
            if (connection != null && connection.isConnected()) {
                connection.sendAccessTokens(requireNotNull(params.accessTokens))
            }
        }
        // 3. Apply audio settings (builder knobs, input modes, leveler).
        inputModes.applyAudioDelta(delta, mAudioBuilder, mAudioHandler, mConnection)
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
            // A fresh enable skips the end-of-method reload only when the
            // bundle holds nothing else audio-affecting: the confirmed
            // callback then performs the single pipeline recreate on the
            // SCO route. Bundled audio changes reload immediately (and the
            // callback reloads once more on confirm); the connect-time
            // bundle never reloads here since no handler exists yet.
            suppressReload = !delta.hasOtherAudioKeys() &&
                (requested || !route.isScoRequested())
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
     * @return The active {@link HumlaConnection}.
     */
    open fun getConnection(): HumlaConnection? {
        return mConnection
    }

    /**
     * Return the current {@link AudioHandler}. An AudioHandler is instantiated upon connection
     * to a server, and destroyed upon disconnection.
     * @return the active AudioHandler, or null if there is no active connection.
     */
    @Throws(NotSynchronizedException::class)
    private fun getAudioHandler(): AudioHandler {
        if (!isSynchronized())
            throw NotSynchronizedException()
        val handler = mAudioHandler
        if (handler == null)
            throw RuntimeException("Audio handler should always be instantiated while connected!")
        return handler
    }

    /**
     * Returns the current {@link ModelHandler}, containing the channel tree. A model handler is
     * valid for the lifetime of a connection.
     * @return the active ModelHandler, or null if there is no active connection.
     */
    @Throws(NotSynchronizedException::class)
    private fun getModelHandler(): ModelHandler {
        if (!isSynchronized())
            throw NotSynchronizedException()
        val handler = mModelHandler
        if (handler == null)
            throw RuntimeException("Model handler should always be instantiated while connected!")
        return handler
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
        return mReconnectAttempts
    }

    open override fun cancelReconnect() {
        mReconnectAttempts = 0
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

    open override fun getTargetServer(): Server? {
        return connectionParams.server
    }

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
            return requireModelHandler().getUser(getSessionId())
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
            return requireModelHandler().getUser(session)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getChannel(id: Int): IChannel? {
        try {
            return requireModelHandler().getChannel(id)
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun getRootChannel(): IChannel? {
        return getChannel(0)
    }

    open override fun getPermissions(): Int {
        try {
            return requireModelHandler().getPermissions()
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
        urb.reason = reason
        urb.ban = ban
        requireConnection().sendTCPMessage(urb.build(), HumlaTCPMessageType.UserRemove)
    }

    open override fun sendUserTextMessage(session: Int, message: String?): Message? {
        try {
            if (!isSynchronized())
                throw NotSynchronizedException()

            val tmb = Mumble.TextMessage.newBuilder()
            tmb.addSession(session)
            tmb.message = message
            requireConnection().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

            val modelHandler = getModelHandler()
            val self = modelHandler.getUser(getSessionId())
            val user = modelHandler.getUser(session)
            val users = ArrayList<User>(1)
            if (user != null) users.add(user)
            return Message(getSessionId(), self?.name, ArrayList(0), ArrayList(0), users, message)
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
            tmb.message = message
            requireConnection().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

            val modelHandler = getModelHandler()
            val self = modelHandler.getUser(getSessionId())
            val targetChannel = modelHandler.getChannel(channel)
            val targetChannels = ArrayList<Channel>()
            if (targetChannel != null) targetChannels.add(targetChannel)
            return Message(
                getSessionId(), self?.name, targetChannels,
                if (tree) targetChannels else ArrayList(0), ArrayList(0), message
            )
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun setUserComment(session: Int, comment: String?) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.comment = comment
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setPrioritySpeaker(session: Int, priority: Boolean) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.prioritySpeaker = priority
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        val usb = Mumble.UserState.newBuilder()
        usb.session = session
        usb.mute = mute
        usb.deaf = deaf
        if (!mute) usb.suppress = false
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
        // Coerce centrally to mirror murmur (murmur/Messages.cpp) and
        // desktop: deaf implies mute, unmute implies undeafen. The packet
        // and the optimistic mirror below derive from the same pair so a
        // coercion mismatch can never flap for one RTT.
        var m = mute
        var d = deaf
        if (d) m = true
        if (!m) d = false
        val usb = Mumble.UserState.newBuilder()
        usb.selfMute = m
        usb.selfDeaf = d
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
        applyOptimisticSelfMuteDeaf(m, d)
    }

    /**
     * Desktop-parity optimistic self-mute/deafen: apply the requested state
     * to the local model and capture gate immediately, treating the server
     * echo as the confirm. murmur applies self_mute/self_deaf from self
     * unconditionally (no permission check; spoofed writes from others are
     * silently dropped), so no rollback is needed: TCP ordering guarantees
     * echoes arrive in request order and the state converges on the last
     * request. Other users' mute (admin {@code mute}/{@code deaf}) stays
     * pessimistic in {@link #setMuteDeafState}.
     *
     * <p>Mirroring into the {@link User} object (rather than rebinding every
     * UI reader) fans the update out through the existing
     * {@code onUserStateUpdated} path: menu icons, notification, settings
     * persistence, and TTS suppression all follow with no UI edits. The echo
     * then becomes a no-op via the unchanged guard in
     * {@link ModelHandler#messageUserState}.
     *
     * <p>Accepted transients (both convergent via the echo): the capture gate
     * is pushed before the model write, so if the session user is not yet
     * visible the mic follows desired state up to a round trip ahead of the
     * UI; and the chat log line is best-effort (dropped if a disconnect
     * interleaves, since logging re-gates on synchronization while the state
     * change itself does not).
     */
    private fun applyOptimisticSelfMuteDeaf(mute: Boolean, deaf: Boolean) {
        val connection = mConnection
        val modelHandler = mModelHandler
        // sendTCPMessage silently drops when disconnected; never show a
        // state the server never saw.
        if (connection == null || modelHandler == null || !connection.isSynchronized())
            return
        // Push the capture gate before the model: desired state is
        // authoritative even when the model user isn't visible yet (e.g.
        // the reconnect restore racing the initial UserState dump). Snapshot
        // the handler: disconnect nulls the field from another thread.
        val audioHandler = mAudioHandler
        if (audioHandler != null)
            audioHandler.setSelfMutedOptimistic(mute)
        val session: Int
        try {
            session = connection.getSession()
        } catch (e: NotSynchronizedException) {
            return
        }
        // Same monitor as ModelHandler.messageUserState, which writes these
        // fields on the TCP reader thread.
        synchronized(modelHandler) {
            val self = modelHandler.getUser(session)
            if (self == null)
                return
            var changed = false
            if (self.isSelfMuted != mute) {
                self.isSelfMuted = mute
                changed = true
            }
            if (self.isSelfDeafened != deaf) {
                self.isSelfDeafened = deaf
                changed = true
            }
            if (changed) {
                // Log here, at the toggle site like desktop's MainWindow: the
                // echo's log is suppressed as unchanged (see ModelHandler).
                if (mute && deaf)
                    logInfo(getString(R.string.chat_notify_muted_deafened))
                else if (mute)
                    logInfo(getString(R.string.chat_notify_muted))
                else
                    logInfo(getString(R.string.chat_notify_unmuted))
                mCallbacks.onUserStateUpdated(self)
            }
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

    open override fun registerWhisperTarget(target: WhisperTarget?): Byte {
        val id = mWhisperTargetList.append(target ?: throw NullPointerException("target"))
        if (id.toInt() < 0) {
            return (-1).toByte()
        }

        val voiceTarget = (target ?: throw NullPointerException("target")).createTarget()
        val vtb = Mumble.VoiceTarget.newBuilder()
        vtb.id = id.toInt()
        vtb.addTargets(voiceTarget)
        requireConnection().sendTCPMessage(vtb.build(), HumlaTCPMessageType.VoiceTarget)
        return id
    }

    open override fun unregisterWhisperTarget(targetId: Byte) {
        mWhisperTargetList.free(targetId)
    }

    open override fun setVoiceTargetId(targetId: Byte) {
        // Mask, not Byte.toUnsignedInt: avoids the core-library-desugaring dependency.
        if ((targetId.toInt() and 0xFF) > 0x1F) {
            throw IllegalArgumentException(
                "Target ID must be at most 5 bits: " + (targetId.toInt() and 0xFF)
            )
        }
        mVoiceTargetId = targetId
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
        if (VoiceTargetMode.fromId(mVoiceTargetId) == VoiceTargetMode.WHISPER) {
            return mWhisperTargetList.get(mVoiceTargetId)
        }
        return null
    }

    open override fun getServerSettings(): ServerSettings? {
        try {
            return getModelHandler().getServerSettings()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun setListeningChannel(channelId: Int, listening: Boolean) {
        if (!isSynchronized()) return
        val usb = Mumble.UserState.newBuilder()
        usb.session = getSessionId()
        if (listening) {
            usb.addListeningChannelAdd(channelId)
        } else {
            usb.addListeningChannelRemove(channelId)
        }
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun setListeningVolume(channelId: Int, volumeAdjustment: Float) {
        if (!isSynchronized()) return
        val usb = Mumble.UserState.newBuilder()
        usb.session = getSessionId()
        val vab = Mumble.UserState.VolumeAdjustment.newBuilder()
        vab.listeningChannel = channelId
        vab.volumeAdjustment = volumeAdjustment
        usb.addListeningVolumeAdjustment(vab)
        requireConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    open override fun isListeningToChannel(channelId: Int): Boolean {
        // Fail closed when disconnected: no exception-driven control flow —
        // requireModelHandler throws IAE on null, never NotSynchronizedException.
        val handler = mModelHandler ?: return false
        val ch = handler.getChannels()[channelId]
        return ch != null && ch.isListening
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
         * @see #isReconnecting()
         * @see #cancelReconnect()
         */
        CONNECTION_LOST
    }

    // Internal visibility restores the original's instantiate-only-in-onBind
    // posture (a private constructor is not expressible for outer access in
    // Kotlin); the class stays final with no in-repo subclasses.
    class HumlaBinder internal constructor(private val mService: IHumlaService) : Binder() {
        fun getService(): IHumlaService {
            return mService
        }
    }
}
