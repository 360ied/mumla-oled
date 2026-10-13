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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import org.minidns.hla.ResolverApi
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
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
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.util.CertRef
import se.lublin.humla.util.ConfigDelta
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

        /**
         * One-shot settle recheck for voice-by-absence: BT profiles attach
         * staggered (HFP/ACL first, A2DP sink seconds later), so the first
         * device edge can see no media sink and slam the call onto
         * speakerphone-voice. Entering voice without SCO arms delayed
         * recomputes to catch the lagging A2DP edge even if it posts no second
         * callback; still absent means a genuinely sink-less phone, correctly
         * left on voice. Bounded to two extra checks (~3s, ~6s) per episode —
         * never a loop — covering typical profile-stagger windows.
         */
        private const val ROUTE_SETTLE_RECHECK_MS = 3000L
        private const val ROUTE_SETTLE_MAX_RECHECKS = 2

        /** Debounce for device attach/detach flurries: one recompute per burst. */
        private const val ROUTE_RECOMPUTE_DEBOUNCE_MS = 300L

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
         * Route truth table: confirmed SCO forces the voice triple; otherwise a
         * present media sink (BT A2DP / wired headset) selects media, and its
         * absence keeps voice. Widened to public: Kotlin has no package
         * visibility; the JVM truth-table test pins this, mirroring
         * BluetoothScoManager.shouldRetryBringUp.
         */
        @JvmStatic
        fun computeVoiceRoute(scoActive: Boolean, mediaDevicesPresent: Boolean): Boolean {
            return scoActive || !mediaDevicesPresent
        }

        /**
         * Pure reconnect-backoff truth table behind [getReconnectDelay].
         * Static so the JVM test pins the switch without constructing a
         * Service (mirrors computeVoiceRoute above). Slice 4 replaces the
         * Handler postDelayed with delay() but keeps these exact delays.
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

    // Service settings
    private var mServer: Server? = null
    private var mCertificate: ByteArray? = null
    private var mCertificatePassword: String? = null
    private var mForceTcp: Boolean = false
    private var mClientName: String? = null
    private var mAccessTokens: List<String>? = null
    private var mTrustStore: String? = null
    private var mTrustStorePassword: String? = null
    private var mTrustStoreFormat: String? = null
    private var mLocalMuteHistory: List<Int>? = null
    private var mLocalIgnoreHistory: List<Int>? = null
    private lateinit var mAudioBuilder: AudioHandler.Builder
    private var mTransmitMode: Int = 0
    private var mHalfDuplex: Boolean = false

    // Volatile: written in apply, read across service threads in
    // updateBluetoothScoRoute, retryBluetoothSco, and the route callbacks.
    // (mHalfDuplex stays plain: it is only ever touched on the main thread.)
    @Volatile
    private var mScoRequested: Boolean = false

    // Assigned once in onCreate, never reassigned: safe to dereference from
    // connection threads, but volatile to match this file's discipline for
    // cross-thread route fields (a stale null is the only real hazard).
    @Volatile
    private var mScoManager: BluetoothScoManager? = null

    // Written on the main thread (applyScoRoute) and in the disconnect path
    // (stopScoWithNotify), which HumlaConnection invokes on TCP-reader
    // threads; read on both. Volatile for visibility; confirmed-flag flips
    // are serialized in fine-grained synchronized blocks (see applyScoRoute,
    // stopScoWithNotify, updateRouteBuilderLocked), while the funnel, device
    // query, IPC, and fan-out they trigger run outside the service monitor.
    @Volatile
    private var mScoRouteActive: Boolean = false

    // VoIP mode ownership (one owner: this route policy — never the pipeline
    // and never the SCO manager alone). Held for voice routes so capture-side
    // effects attach into a coupled HAL loop, released for media routes and
    // on disconnect/destroy. Claimed tracks whether WE set IN_COMM: a
    // pre-held mode (phone call, concurrent SCO) is never cleared by us.
    private var mAudioManager: AudioManager? = null

    // Route/mode ownership fields below are touched on the main thread and
    // in the disconnect path on connection threads: volatile for visibility,
    // with ensure/release/apply serialized via synchronized methods so a
    // device callback racing a disconnect cannot corrupt the restore baseline.
    @Volatile
    private var mSavedAudioMode: Int = AudioManager.MODE_NORMAL

    @Volatile
    private var mVoiceModeHeld: Boolean = false

    @Volatile
    private var mVoiceModeClaimed: Boolean = false

    // Media-route selection (BT A2DP / BLE / wired-USB outputs, gated on
    // SCO absence). Present sinks select the media triple; SCO and the bare
    // phone stay voice (computeVoiceRoute). Listener is API 23+; below that
    // voice always. Named for what it stores (the gated decision, not raw
    // presence): recomputed from the live query on every route application.
    @Volatile
    private var mMediaRouteSelected: Boolean = false

    @Volatile
    private var mAppliedVoiceRoute = true

    // Default MUSIC means speakerphone-voice until settings arrive: the voice
    // triple maps any non-VOICE_CALL stream to speakerphone (see AudioHandler
    // init), so do not "fix" this to STREAM_VOICE_CALL (that would flip the
    // default to earpiece).
    @Volatile
    private var mHandsetAudioStream: Int = AudioManager.STREAM_MUSIC
    private var mDeviceCallback: AudioDeviceCallback? = null
    // Slice 4: Handler postDelayed chains become cancellable Jobs in the
    // service scope. Disarm = job?.cancel(), replacing removeCallbacks
    // one-for-one. mHandler stays only for AudioManager device-callback
    // registration, which requires a Handler.
    private var routeDebounceJob: Job? = null
    private var routeSettleJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var mSettleAttempts: Int = 0

    @Volatile
    private var mSettleFired: Boolean = false

    // Whether a settle recheck is queued. Set only beside a launch, cleared
    // on dispatch, reset, or teardown — never restored from a snapshot —
    // so the flag can never claim protection no callback provides.
    // Single writes only (volatile suffices).
    @Volatile
    private var mSettlePending: Boolean = false

    private fun armRouteSettle() {
        routeSettleJob?.cancel()
        mSettlePending = true
        routeSettleJob = lifecycleScope.launch {
            delay(ROUTE_SETTLE_RECHECK_MS)
            // Dispatch consumes the queued protection: clear the flag so a
            // later fresh edge can arm anew (funnel top also clears fired).
            mSettlePending = false
            mSettleFired = true
            applyAudioRoute("media settle recheck", false)
        }
    }

    private fun cancelRouteSettle() {
        routeSettleJob?.cancel()
        routeSettleJob = null
        mSettlePending = false
    }

    private fun cancelRouteDebounce() {
        routeDebounceJob?.cancel()
        routeDebounceJob = null
    }

    // Set in onDestroy: posted teardowns (SCO notify) check it before
    // touching route audio on a dead service.
    @Volatile
    private var mDestroyed = false

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
    // volatile mConnectionState gate (see applyAudioRoute), never a lock.
    @Volatile
    private var mModelHandler: ModelHandler? = null

    @Volatile
    private var mAudioHandler: AudioHandler? = null

    private lateinit var mActivityInputMode: ActivityInputMode
    private lateinit var mToggleInputMode: ToggleInputMode
    private lateinit var mContinuousInputMode: ContinuousInputMode

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
        // API 30+ while this library targets API 21+, and HIGH_PERF is the
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
        try {
            mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode: no AudioManager", e)
            mAudioManager = null
        }
        mScoManager = BluetoothScoManager(this, object : BluetoothScoManager.Listener {
            override fun onScoConnected() {
                applyScoRoute(true, "Bluetooth SCO connected")
            }

            override fun onScoDisconnected() {
                applyScoRoute(false, "Bluetooth SCO disconnected, falling back to phone audio")
            }

            override fun onScoFailed(reason: String?) {
                applyScoRoute(false, "Bluetooth SCO unavailable, falling back to phone audio")
                // onScoFailed is posted to the main thread; connected and
                // disconnected arrive on main-thread platform callbacks.
                Toast.makeText(
                    this@HumlaService,
                    if (BluetoothScoManager.REASON_PERMISSION == reason)
                        R.string.bluetooth_sco_permission
                    else
                        R.string.bluetooth_sco_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        try {
            val deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>?) {
                    logDeviceTypes("added", added)
                    onRouteDevicesChanged()
                    maybeStartRequestedSco(added)
                }

                override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>?) {
                    logDeviceTypes("removed", removed)
                    onRouteDevicesChanged()
                }
            }
            mDeviceCallback = deviceCallback
            mAudioManager?.registerAudioDeviceCallback(deviceCallback, mHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Route device listener failed", e)
            mDeviceCallback = null
        }
        mMediaRouteSelected = queryMediaDevicesPresent()
        mToggleInputMode = ToggleInputMode()
        mActivityInputMode = ActivityInputMode(ActivityInputMode.DEFAULT_VAD_MAX)
        mContinuousInputMode = ContinuousInputMode()
        mWhisperTargetList = WhisperTargetList()

        // initialize minidns dns lookup mechanisms
        init(this)
    }

    open override fun onDestroy() {
        super.onDestroy()
        mDestroyed = true
        mReconnectAttempts = 0
        setReconnecting(false)
        val wakeLock = mWakeLock
        if (wakeLock != null && wakeLock.isHeld) {
            wakeLock.release()
        }
        // Bare teardown, no notify: observers detach in onDestroy and posted
        // UI work (SoundPool recreate, proximity lock) would revive audio on
        // a dead service.
        mScoManager?.stop()
        // Same builder group as the funnel/teardown paths: clear under the
        // monitor so a racing recompute cannot resurrect SCO after destroy.
        // Media/applied resets ride along: all three are one route snapshot.
        synchronized(this) {
            mAudioBuilder.setScoEnabled(false)
            mScoRouteActive = false
            mMediaRouteSelected = false
            mAppliedVoiceRoute = true
        }
        releaseVoiceMode()
        val deviceCallback = mDeviceCallback
        if (deviceCallback != null) {
            try {
                mAudioManager?.unregisterAudioDeviceCallback(deviceCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Route device listener removal failed", e)
            }
            mDeviceCallback = null
        }
        // Unconditional: the funnel arms settle on any route edge, so teardown
        // must clear it too.
        cancelRouteDebounce()
        cancelRouteSettle()
        mSettleAttempts = 0
        releaseWifiLock()
    }

    open override fun onBind(intent: Intent): IBinder {
        return HumlaBinder(this)
    }

    protected open fun connect() {
        val server = mServer
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

            val connection = HumlaConnection(this)
            mConnection = connection
            connection.setTargetFramesPerPacket(mAudioBuilder.getTargetFramesPerPacket())
            connection.setForceTCP(mForceTcp)
            connection.setKeys(mCertificate, mCertificatePassword)
            connection.setTrustStore(mTrustStore, mTrustStorePassword, mTrustStoreFormat)

            val modelHandler = ModelHandler(
                this, mCallbacks, this,
                mLocalMuteHistory, mLocalIgnoreHistory
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
        val wakeLock = mWakeLock
        if (wakeLock != null && wakeLock.isHeld) {
            wakeLock.release()
        }
        releaseWifiLock()
        // Tear down SCO synchronously: the async disconnect callback may lag,
        // and a quick reconnect must not reuse a stale link or route flag.
        stopScoWithNotify()
        mConnection?.disconnect()
    }

    open override fun isBluetoothScoActive(): Boolean {
        val scoManager = mScoManager
        return scoManager != null && scoManager.isActive()
    }

    /**
     * Whether a media sink (BT A2DP / BLE / wired headset) currently routes output.
     * While true (and SCO inactive) the pipeline runs the media triple to
     * preserve A2DP quality; platform AEC is out of scope there by design.
     * Derived from the confirmed route flag (not the live manager query) so
     * pipeline, cues, and volume observers never diverge mid-callback.
     */
    open override fun isMediaRouteActive(): Boolean {
        return mMediaRouteSelected && !mScoRouteActive
    }

    open override fun retryBluetoothSco(): Boolean {
        // Copy to locals before IPC so a torn read is impossible; the copy
        // is not group-atomic versus writers (post-start re-validation +
        // manager idempotence absorb any tearing — that is the real guard).
        val requested: Boolean
        val state: ConnectionState
        val scoManager: BluetoothScoManager?
        val connection: HumlaConnection?
        synchronized(this) {
            requested = mScoRequested
            state = mConnectionState
            scoManager = mScoManager
            connection = mConnection
        }
        if (requested && state == ConnectionState.CONNECTED &&
            scoManager != null && scoManager.isIdle()
        ) {
            scoManager.start()
            // Re-validate identity, state, and intent: a disable or a
            // disconnect/reconnect cycle during bring-up must not leave
            // a stray attempt running (stop() never callbacks, silent).
            synchronized(this) {
                if (mConnection !== connection || mConnectionState != ConnectionState.CONNECTED ||
                    !mScoRequested
                ) {
                    scoManager.stop()
                    return false
                }
            }
            return true
        }
        return false
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
        val server = mServer
        val connection = mConnection
        if (server != null && connection != null) {
            server.setResolved(connection.getHost(), connection.getPort())
        }

        // Send version information and authenticate.
        val version = Constants.createVersionMessage(mClientName)

        val auth = Mumble.Authenticate.newBuilder()
        if (server != null) {
            auth.username = server.username
            auth.password = server.password
        }
        auth.opus = true
        auth.addAllTokens(mAccessTokens ?: emptyList())

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

        try {
            val voice = updateRouteBuilder()
            // See funnel: only our own claimed hold implicates us in any
            // system SCO raised during it.
            val syncClaimedHold = mVoiceModeHeld && mVoiceModeClaimed
            applyVoiceMode(voice)
            createAudioHandler()
            if (!voice) {
                counterUnrequestedSystemSco(syncClaimedHold)
            } else if (!mScoRouteActive && !mMediaRouteSelected) {
                // Sync landed in voice-by-absence (A2DP still staggering):
                // arm the same bounded recheck the funnel uses, coalescing
                // with any funnel-armed one already queued.
                mSettleAttempts = 0
                armRouteSettle()
            }
        } catch (e: AudioException) {
            Log.e(TAG, "Audio pipeline creation failed", e)
            onConnectionWarning(e.message)
        }
        updateBluetoothScoRoute()
        // First creation bypasses the funnel: push route-dependent audio
        // (cues, proximity) explicitly so an A2DP-attached first connect
        // does not leave them on the voice stream until the next flap.
        onAudioRouteChanged()
        mCallbacks.onAudioRouteChanged()

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
        // Media decision goes stale here (teardown clears SCO only; the next
        // funnel recomputes): refresh from a live query so gap observers see
        // truth (A2DP still attached reads media) instead of a forced value.
        // Under the group monitor like every other route write; the query
        // itself (binder IPC) runs outside it.
        val liveMedia = queryMediaDevicesPresent()
        synchronized(this) {
            mMediaRouteSelected = liveMedia
        }

        // Never leak the SCO link across disconnects: the next sync re-arms
        // it via updateBluetoothScoRoute when still requested.
        stopScoWithNotify()
        releaseVoiceMode()
        // No live call remains for a settle recheck to recover.
        cancelRouteSettle()
        mSettleAttempts = 0

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
                            lifecycleScope.launch {
                                if (mReconnecting) {
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
        reconnectJob = lifecycleScope.launch {
            delay(delayMs.toLong())
            if (mReconnecting) {
                mReconnectAttempts++
                connect()
            }
        }
    }

    /**
     * Stops SCO and clears the confirmed-route flag, emitting route-change
     * notifications when a live link was up. stop() itself never callbacks,
     * so synchronous teardowns must notify explicitly or volume, cues, and
     * menu state strand on the voice route. Only the confirmed-flag/builder
     * section holds the service monitor (serialized with applyScoRoute and
     * the route builder group); manager IPC and the posted notify stay
     * outside so the lock never pins across slow work. Media selection is
     * left for the next funnel recompute, which owns it.
     */
    private fun stopScoWithNotify() {
        val scoManager = mScoManager ?: return
        val wasActive = scoManager.isActive()
        scoManager.stop()
        synchronized(this) {
            mAudioBuilder.setScoEnabled(false)
            mScoRouteActive = false
        }
        if (wasActive) {
            // Posted: teardown callers (disconnect, destroy, mid-call disable)
            // may run off the main thread, but hook and observers are
            // main-thread confined. Posting also orders the event after the
            // synchronous disable-path pipeline reload.
            lifecycleScope.launch {
                if (mDestroyed) {
                    return@launch
                }
                if (mScoManager?.isActive() == true) {
                    // Re-connected since teardown queued: fresh callbacks
                    // own the UI; a stale false must not overwrite them.
                    return@launch
                }
                if (mScoRouteActive) {
                    // A newer link confirmed while this teardown was
                    // queued (bring-up won the race after our flag flip):
                    // its own connected-notify carries the truth.
                    return@launch
                }
                onAudioRouteChanged()
                mCallbacks.onBluetoothScoChanged(false)
            }
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
     * Holds MODE_IN_COMMUNICATION for the voice route. Called before every
     * pipeline creation so capture-side effects attach into an already-coupled
     * HAL loop, never while the pipeline runs. Recreate-safe: re-holding an
     * already-held mode is idempotent and never re-saves. A pre-held mode
     * (phone call, concurrent SCO bring-up) counts as held but unclaimed,
     * so release below never clears someone else's mode.
     */
    @Synchronized
    private fun ensureVoiceMode() {
        val audioManager = mAudioManager
        if (audioManager == null || mVoiceModeHeld) {
            return
        }
        try {
            val mode = audioManager.mode
            // Externally held (phone call setup or another app): claim
            // nothing and change nothing. Deliberately leaves mVoiceModeHeld
            // unset so the next voice pass retries the claim — setting it
            // here would starve the voice route of IN_COMM after the
            // external holder lets go (no release path re-arms a marker
            // that was never a real hold).
            if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                Log.i(TAG, "VoIP mode already held, not claiming")
                return
            }
            // A genuine cellular call owns the audio path: never steal
            // MODE_IN_CALL either, for the same liveness reason.
            if (mode == AudioManager.MODE_IN_CALL) {
                Log.i(TAG, "Cellular call active, not claiming voice mode")
                return
            }
            mSavedAudioMode = mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            mVoiceModeHeld = true
            mVoiceModeClaimed = true
            Log.i(TAG, "VoIP mode held (was $mSavedAudioMode)")
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode hold failed", e)
        }
    }

    /**
     * Releases a held voice mode, guarded like BluetoothScoManager: an
     * unclaimed (pre-held) mode is never touched, and a claimed one is only
     * restored while our IN_COMM is still current, so a phone call or another
     * app that moved it since is never clobbered.
     */
    @Synchronized
    private fun releaseVoiceMode() {
        val audioManager = mAudioManager
        if (audioManager == null || !mVoiceModeHeld) {
            return
        }
        val claimed = mVoiceModeClaimed
        mVoiceModeHeld = false
        mVoiceModeClaimed = false
        if (!claimed) {
            return
        }
        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = mSavedAudioMode
                Log.i(TAG, "VoIP mode released (restored $mSavedAudioMode)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode release failed", e)
        }
    }

    /**
     * Applies a confirmed route to the pipeline, cue hook, and observers in
     * that order. All route callbacks (SCO, media-sink) converge here via
     * applyAudioRoute below; this entry keeps the SCO-specific observer push
     * truthful while the route push carries output-routing state. Skips
     * everything when the confirmed SCO state is unchanged (e.g. a failure
     * that never went active); the failure toast is the caller's job.
     * Only the confirmed-flag flip holds the service monitor (serialized
     * with the teardown path); the funnel, IPC, and fan-out below run
     * outside it so a slow HAL never pins the disconnect path.
     */
    private fun applyScoRoute(active: Boolean, reason: String) {
        val logUnchanged: Boolean
        synchronized(this) {
            if (active == mScoRouteActive) {
                // Log outside the block below: keep the critical section to
                // the flip alone.
                logUnchanged = true
            } else {
                mScoRouteActive = active
                logUnchanged = false
            }
        }
        if (logUnchanged) {
            Log.i(TAG, "SCO route unchanged, skipping reload ($reason).")
            return
        }
        applyAudioRoute(reason, false)
        // Re-validate: a teardown racing this route may have flipped the
        // flag while the funnel ran; its own notify carries the newer state.
        synchronized(this) {
            if (active != mScoRouteActive) {
                return
            }
        }
        mCallbacks.onBluetoothScoChanged(active)
    }

    /**
     * Debounced device-listener entry: attach/detach flurries converge into
     * one route application instead of one pipeline recreate per event.
     */
    private fun onRouteDevicesChanged() {
        routeDebounceJob?.cancel()
        routeDebounceJob = lifecycleScope.launch {
            delay(ROUTE_RECOMPUTE_DEBOUNCE_MS)
            applyAudioRoute("device route changed", false)
        }
    }

    /**
     * Synchronous media-sink presence: BT A2DP / BLE / wired-USB headset
     * outputs currently attached. API 23+ only; below that always false
     * (voice). BLE/LE Audio and hearing aids ride the media path like A2DP
     * per the echo-cancellation plan (accepted gap: no AEC there); only the
     * SCO path ever selects voice over Bluetooth.
     */
    private fun queryMediaDevicesPresent(): Boolean {
        val audioManager = mAudioManager ?: return false
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?: return false
            val seen = StringBuilder()
            var found = false
            for (device in outputs) {
                if (device == null) {
                    continue
                }
                val type = device.type
                if (seen.isNotEmpty()) {
                    seen.append(',')
                }
                seen.append(type)
                when (type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE ->
                        found = true
                    else -> {}
                }
            }
            // INFO, not DEBUG: recomputes are rare (edges/settings/sync) and
            // this line settles every "was A2DP visible?" question in the field.
            Log.i(TAG, "Media-sink query: outputs=[$seen] media=$found")
            return found
        } catch (e: Exception) {
            Log.w(TAG, "Media-sink query failed", e)
        }
        return false
    }

    /**
     * Pushes the current route decision into the builder: voice flag, SCO
     * flag, and effective source/stream. Voice keeps the handset-derived
     * stream (earpiece vs speakerphone intent); media is always MIC/MUSIC.
     * Returns the applied voice decision for mode handling by the caller.
     * The device query runs outside the monitor (binder IPC); only the
     * flag/builder group below holds it, mutually exclusive with the
     * teardown write in stopScoWithNotify.
     */
    private fun updateRouteBuilder(): Boolean {
        val mediaPresent = queryMediaDevicesPresent()
        synchronized(this) {
            return updateRouteBuilderLocked(mediaPresent)
        }
    }

    /** Route decision + builder group; caller holds the service monitor. */
    private fun updateRouteBuilderLocked(mediaPresent: Boolean): Boolean {
        val sco = mScoRouteActive
        val media = !sco && mediaPresent
        mMediaRouteSelected = media
        val voice = computeVoiceRoute(sco, media)
        mAppliedVoiceRoute = voice
        mAudioBuilder.setScoEnabled(sco)
        mAudioBuilder.setVoiceRouteEnabled(voice)
        if (voice) {
            mAudioBuilder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            mAudioBuilder.setAudioStream(if (sco) AudioManager.STREAM_VOICE_CALL else mHandsetAudioStream)
        } else {
            mAudioBuilder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mAudioBuilder.setAudioStream(AudioManager.STREAM_MUSIC)
        }
        return voice
    }

    private fun applyVoiceMode(voice: Boolean) {
        if (voice) {
            ensureVoiceMode()
        } else {
            releaseVoiceMode()
        }
    }

    /**
     * Single funnel for every live route change: recomputes the decision,
     * holds or releases the voice mode around pipeline creation (mode first,
     * capture after, per the HAL coupling requirement), then refreshes cues
     * and observers. Skips the reload when the applied route is unchanged
     * unless forceReload (settings path) demands it.
     */
    private fun applyAudioRoute(reason: String, forceReload: Boolean) {
        // Snapshot outside the group lock: all funnel callers are main-Looper
        // confined (device recompute, settings, SCO callbacks, posted sync),
        // so no concurrent funnel rewrite is expected; a surprise concurrent
        // rewrite would at worst cause one redundant idempotent reload.
        val before = mAppliedVoiceRoute
        // Settle bookkeeping: consume the fired flag. Fresh edges never
        // cancel outstanding protection here — arming below re-posts when
        // still uncovered — and nothing ever restores the pending flag
        // without posting, so the flag can never claim protection no
        // callback provides (no wedge). Volatile suffices: no
        // read-modify-restore spans threads, only single writes.
        val settle = mSettleFired
        mSettleFired = false
        val voice = updateRouteBuilder()
        // Capture before applyVoiceMode clears it: did WE hold the mode for
        // the episode now ending? Only our own claimed hold implicates us in
        // any system SCO raised during it (see counter method below).
        val wasClaimedHold = mVoiceModeHeld && mVoiceModeClaimed
        if (mConnectionState == ConnectionState.CONNECTED) {
            applyVoiceMode(voice)
            if (!voice) {
                // Media won: clear foreign SCO BEFORE (re)creating the
                // pipeline so no media lifetime starts while HFP is up.
                counterUnrequestedSystemSco(wasClaimedHold)
            }
        } else {
            // Disconnected (or never connected): device attach/detach must
            // not re-acquire global IN_COMM with no pipeline and no call.
            // The next sync re-holds via onConnectionSynchronized.
            releaseVoiceMode()
        }
        val audioHandler = mAudioHandler
        if ((forceReload || voice != before) && audioHandler != null && audioHandler.isInitialized() &&
            mConnectionState == ConnectionState.CONNECTED
        ) {
            try {
                createAudioHandler()
                Log.i(TAG, "$reason; audio pipeline recreated (" + (if (voice) "voice" else "media") + ").")
            } catch (e: AudioException) {
                Log.e(TAG, "Route-change audio reload failed", e)
                onConnectionWarning(e.message)
            }
        }
        val routeChanged = forceReload || voice != before
        if (routeChanged) {
            onAudioRouteChanged()
            mCallbacks.onAudioRouteChanged()
        }
        // Else: unchanged route — observers query live state on demand and
        // the SCO bit has its own direct emit, so skip the heavy cue rebuild
        // (SoundPool/proximity) on every irrelevant device edge.
        val voiceByAbsence = voice && !mScoRouteActive && !mMediaRouteSelected
        val liveCall = mConnectionState == ConnectionState.CONNECTED
        if (voiceByAbsence && liveCall && (!settle || mSettleAttempts < ROUTE_SETTLE_MAX_RECHECKS - 1)) {
            // Fresh edge (!settle, entering or already-voice) or budgeted
            // settle follow-up: exactly one check queued. Fresh edges reset
            // the budget — each real BT change deserves full cover; settle
            // runs consume it. No blind restore anywhere: the flag is set
            // only beside a post, cleared on dispatch/teardown/reset.
            if (!settle) {
                mSettleAttempts = 0
            } else {
                mSettleAttempts += 1
            }
            armRouteSettle()
        } else if (!voiceByAbsence || !liveCall) {
            cancelRouteSettle()
            mSettleAttempts = 0
        }
        // Else (settle budget exhausted): the dispatched check already
        // cleared the flag; rest without cover until a fresh edge re-arms.
    }

    /**
     * Tears down system-raised SCO audio that our transient voice hold may
     * have triggered. When HFP attaches during a voice-by-absence window,
     * the platform auto-establishes SCO (IN_COMM reads as an active call)
     * even though SCO was never requested — and that link persists after we
     * release the mode and flip to media, stranding the user on HFP instead
     * of A2DP. Runs only when leaving a claimed hold for media with SCO
     * unrequested and system SCO currently on; never touches our own
     * confirmed SCO, and never runs while disconnected. Skipped during a
     * genuine cellular call (MODE_IN_CALL): carrier SCO is never ours to
     * tear down. That check is racy in principle; accepted against the
     * certain stranding it repairs.
     */
    private fun counterUnrequestedSystemSco(wasClaimedHold: Boolean) {
        val audioManager = mAudioManager ?: return
        if (!wasClaimedHold || mScoRequested || mScoRouteActive) {
            return
        }
        if (mConnectionState != ConnectionState.CONNECTED) {
            return
        }
        try {
            if (audioManager.mode == AudioManager.MODE_IN_CALL) {
                return
            }
        } catch (e: Exception) {
            return
        }
        if (!isSystemScoOn()) {
            return
        }
        try {
            // Mirror our manager's own release: the communication-device
            // path on S+ (where legacy stop is unreliable against a
            // device-path selection), legacy stop below.
            audioManager.clearCommunicationDevice()
            Log.i(TAG, "Cleared unrequested system SCO after media flip")
        } catch (e: Exception) {
            Log.w(TAG, "Unrequested SCO clear failed", e)
        }
    }

    /** Whether platform SCO audio is currently up, independent of our manager. */
    private fun isSystemScoOn(): Boolean {
        val audioManager = mAudioManager ?: return false
        try {
            val info = audioManager.communicationDevice
            return info != null && info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } catch (e: Exception) {
            return false
        }
    }

    /** One-line field diagnostic: which device types an edge carried. */
    private fun logDeviceTypes(edge: String, devices: Array<AudioDeviceInfo>?) {
        if (devices == null) {
            Log.i(TAG, "Route devices $edge: none")
            return
        }
        val types = StringBuilder()
        for (device in devices) {
            if (types.isNotEmpty()) {
                types.append(',')
            }
            types.append(device?.type ?: -1)
        }
        Log.i(TAG, "Route devices $edge: [$types]")
    }

    /**
     * Starts or stops SCO bring-up to match the requested toggle and the
     * connection state. Safe to call redundantly: the manager ignores
     * start requests while starting or active, and stop is idempotent.
     */
    private fun updateBluetoothScoRoute() {
        val scoManager = mScoManager ?: return
        if (mScoRequested && mConnectionState == ConnectionState.CONNECTED) {
            scoManager.start()
        } else if (!scoManager.isIdle()) {
            // Skip the platform round-trip when already idle; stop() is safe
            // but needlessly releases and bumps the callback generation.
            scoManager.stop()
        }
    }

    /**
     * Starts a requested-but-idle SCO bring-up when an HFP device attaches
     * mid-call. Bring-up otherwise only runs at sync and on toggle changes,
     * so a headset powered on later (e.g. after an earlier NO_DEVICE
     * failure) would never establish SCO until the next toggle. Runs only
     * from IDLE while requested and connected; the manager itself drops
     * duplicate starts, and failure still reports through the normal toast.
     * Runs on the main Looper (device callback thread).
     *
     * <p>Trigger coverage: a classic-HFP SCO entry in the added array is
     * the fast path. Some HALs surface HFP only via A2DP-then-nothing (or
     * stagger it past the callback), so any Bluetooth output add also
     * polls the manager's own availability query before giving up — the
     * same query bring-up itself uses pre-audio. LE Audio rides the media
     * path by design and needs no trigger. Device callbacks exist on all
     * supported devices (minSdk 33): sync and toggle paths still cover those
     * devices, late power-on does not.
     *
     * <p>Each triggering edge may re-toast on persistent failure
     * (permission denied, no device): accepted as edge-driven feedback.
     */
    private fun maybeStartRequestedSco(added: Array<AudioDeviceInfo>?) {
        // Main-serialized with the toggle path (both main-Looper) with
        // volatile reads; unlike binder-originated retryBluetoothSco this
        // needs no snapshot block, only the post-start re-validation below.
        val scoManager = mScoManager
        if (!mScoRequested || mConnectionState != ConnectionState.CONNECTED || scoManager == null) {
            return
        }
        if (!scoManager.isIdle()) {
            return
        }
        if (added == null) {
            return
        }
        var scoInAdded = false
        var anyBtInAdded = false
        for (device in added) {
            if (device == null) {
                continue
            }
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    scoInAdded = true
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                    anyBtInAdded = true
                else -> {}
            }
        }
        // Fast path: HFP surfaced directly. Fallback: any BT add with an
        // SCO device actually available (A2DP-first stagger, quiet HALs).
        // Otherwise an A2DP-only sink would toast NO_DEVICE spuriously.
        if (!scoInAdded && !(anyBtInAdded && scoManager.isScoDeviceAvailable())) {
            return
        }
        Log.i(TAG, "Bluetooth headset attached with SCO requested; starting bring-up")
        val connection = mConnection
        updateBluetoothScoRoute()
        // A disconnect may have raced us (state still reads CONNECTED
        // until the async teardown flips it): mirror the retry
        // re-validation so a stale start cannot outlive teardown.
        if (mConnection !== connection || mConnectionState != ConnectionState.CONNECTED ||
            !mScoRequested
        ) {
            scoManager.stop()
        }
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
     * reconnect. Nothing inside throws; the pipeline recreate funnels through
     * applyAudioRoute, which handles AudioException internally.
     * @param delta A one-shot configuration command.
     * @return true if a reconnect is required for changes to take effect.
     * @see se.lublin.humla.HumlaService
     */
    open fun apply(delta: ConfigDelta): Boolean {
        var reconnectNeeded = false
        var suppressReload = false
        if (delta.server != null) {
            mServer = delta.server
            reconnectNeeded = true
        }
        val certificate = delta.certificate
        if (certificate != null) {
            mCertificate = certificate.data
            reconnectNeeded = true
            // A null password keeps the previous one, mirroring the old
            // bundle where an absent password key left it untouched.
            if (certificate.password != null) {
                mCertificatePassword = certificate.password
            }
        }
        val detectionThreshold = delta.detectionThreshold
        if (detectionThreshold != null) {
            mActivityInputMode.setThreshold(detectionThreshold)
            val audioHandler = mAudioHandler
            if (audioHandler != null) {
                audioHandler.setVadThresholds(
                    mActivityInputMode.vadMax,
                    mActivityInputMode.vadMin
                )
            }
        }
        val amplitudeBoost = delta.amplitudeBoost
        if (amplitudeBoost != null) {
            mAudioBuilder.setAmplitudeBoost(amplitudeBoost)
        }
        val transmitMode = delta.transmitMode
        if (transmitMode != null) {
            mTransmitMode = transmitMode
            val inputMode: IInputMode
            when (mTransmitMode) {
                Constants.TRANSMIT_PUSH_TO_TALK ->
                    inputMode = mToggleInputMode
                Constants.TRANSMIT_CONTINUOUS ->
                    inputMode = mContinuousInputMode
                Constants.TRANSMIT_VOICE_ACTIVITY ->
                    inputMode = mActivityInputMode
                else ->
                    throw IllegalArgumentException()
            }
            mAudioBuilder.setInputMode(inputMode)
        }
        val inputQuality = delta.inputQuality
        if (inputQuality != null) {
            mAudioBuilder.setTargetBitrate(inputQuality)
        }
        val forceTcp = delta.forceTcp
        if (forceTcp != null) {
            mForceTcp = forceTcp
            reconnectNeeded = true
        }
        if (delta.clientName != null) {
            mClientName = delta.clientName
            reconnectNeeded = true
        }
        if (delta.accessTokens != null) {
            mAccessTokens = delta.accessTokens
            val connection = mConnection
            if (connection != null && connection.isConnected()) {
                connection.sendAccessTokens(requireNotNull(mAccessTokens))
            }
        }
        val audioStream = delta.audioStream
        if (audioStream != null) {
            // Handset-derived intent (earpiece vs speakerphone voice); the
            // route policy re-derives the effective stream before every
            // pipeline creation, so mid-call toggles apply on recreate.
            // Funnel-only: no direct builder write here (it would be
            // overwritten by the reload below anyway).
            mHandsetAudioStream = audioStream
        }
        val framesPerPacket = delta.framesPerPacket
        if (framesPerPacket != null) {
            val fpp = framesPerPacket
            mAudioBuilder.setTargetFramesPerPacket(fpp)
            mConnection?.setTargetFramesPerPacket(fpp)
        }
        val trustStore = delta.trustStore
        if (trustStore != null) {
            val trustStorePath = trustStore.path
            if (isTrustStorePathAllowed(trustStorePath)) {
                mTrustStore = trustStorePath
                reconnectNeeded = true
                // Password and format describe the store at this path, so
                // they are only applied together with an accepted path.
                if (trustStore.password != null) {
                    mTrustStorePassword = trustStore.password
                }
                if (trustStore.format != null) {
                    mTrustStoreFormat = trustStore.format
                }
            } else {
                Log.w(
                    TAG,
                    "Rejected trust store path (must be a .bks file under the app files directory); " +
                        "keeping previous trust store settings."
                )
            }
        }
        val halfDuplex = delta.halfDuplex
        if (halfDuplex != null) {
            mHalfDuplex = halfDuplex
        }
        if (delta.halfDuplex != null || delta.transmitMode != null) {
            mAudioBuilder.setHalfDuplexEnabled(
                mTransmitMode == Constants.TRANSMIT_PUSH_TO_TALK && mHalfDuplex
            )
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
                (requested || !mScoRequested)
            mScoRequested = requested
            if (!requested) {
                // Synchronous teardown with notifications: stop() itself never
                // callbacks, and the generic reload below only refreshes the
                // pipeline, not observers or cue audio.
                stopScoWithNotify()
            }
            updateBluetoothScoRoute()
        }
        if (delta.localMuteHistory != null) {
            mLocalMuteHistory = delta.localMuteHistory
            reconnectNeeded = true
        }
        if (delta.localIgnoreHistory != null) {
            mLocalIgnoreHistory = delta.localIgnoreHistory
            reconnectNeeded = true
        }
        val adaptiveLeveler = delta.adaptiveLeveler
        if (adaptiveLeveler != null) {
            mAudioBuilder.setAdaptiveLevelerEnabled(adaptiveLeveler)
        }

        // Reload audio subsystem if initialized. Guarded on CONNECTED as well
        // as initialization: a disconnect racing a settings change must not
        // resurrect the pipeline (or trip the DEBUG assertion inside).
        // Routed through the single funnel so source/stream/mode follow the
        // current route instead of the raw bundle values.
        val audioHandler = mAudioHandler
        if (!suppressReload && audioHandler != null && audioHandler.isInitialized() &&
            mConnectionState == ConnectionState.CONNECTED
        ) {
            applyAudioRoute("settings changed", true)
            Log.i(TAG, "Audio subsystem reloaded after settings change.")
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
        val wakeLock = mWakeLock
        if (wakeLock != null && wakeLock.isHeld) {
            wakeLock.release()
        }
        releaseWifiLock()
    }

    open override fun getTargetServer(): Server? {
        return mServer
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
        return mTransmitMode
    }

    open override fun getCodec(): HumlaUDPMessageType? {
        try {
            return requireConnection().getCodec()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    open override fun isTalking(): Boolean {
        return mToggleInputMode.isTalkingOn
    }

    open override fun setTalkingState(talking: Boolean) {
        mToggleInputMode.isTalkingOn = talking
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
        val a = requireNotNull(channelA)
        val b = requireNotNull(channelB)
        val csb = Mumble.ChannelState.newBuilder()
        csb.channelId = a.id
        csb.addLinksAdd(b.id)
        requireConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    open override fun unlinkChannels(channelA: IChannel?, channelB: IChannel?) {
        val a = requireNotNull(channelA)
        val b = requireNotNull(channelB)
        val csb = Mumble.ChannelState.newBuilder()
        csb.channelId = a.id
        csb.addLinksRemove(b.id)
        requireConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    open override fun unlinkAllChannels(channel: IChannel?) {
        val ch = requireNotNull(channel)
        val csb = Mumble.ChannelState.newBuilder()
        csb.channelId = ch.id
        for (linked in ch.links) {
            csb.addLinksRemove(linked.id)
        }
        requireConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    open override fun registerWhisperTarget(target: WhisperTarget?): Byte {
        val id = mWhisperTargetList.append(requireNotNull(target))
        if (id.toInt() < 0) {
            return (-1).toByte()
        }

        val voiceTarget = requireNotNull(target).createTarget()
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
        // Mask, not Byte.toUnsignedInt: avoids the dependency (historical
        // minSdk-21 era without core-library desugaring).
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
        try {
            val ch = requireModelHandler().getChannels()[channelId]
            return ch != null && ch.isListening
        } catch (e: NotSynchronizedException) {
            return false
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
