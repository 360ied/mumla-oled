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

package se.lublin.mumla

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import se.lublin.humla.Constants
import se.lublin.humla.HumlaRuntime
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.client.LocalFactsStore
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.humla.voice.VoiceSettings
import se.lublin.humla.voice.VoiceSettingsMapper
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.feature.AvatarRequestsFeature
import se.lublin.mumla.feature.BluetoothPermissionGuardFeature
import se.lublin.mumla.feature.ChatEventFormatter
import se.lublin.mumla.feature.ChatLogFeature
import se.lublin.mumla.feature.ConnectionNotificationFeature
import se.lublin.mumla.feature.FeaturePlatform
import se.lublin.mumla.feature.HotCornerFeature
import se.lublin.mumla.feature.MessageNotificationsFeature
import se.lublin.mumla.feature.NotificationActions
import se.lublin.mumla.feature.OverlayHost
import se.lublin.mumla.feature.OverlayLayout
import se.lublin.mumla.feature.OverlayFeature
import se.lublin.mumla.feature.PttSoundsFeature
import se.lublin.mumla.feature.ProximityFeature
import se.lublin.mumla.feature.ReconnectNoticeFeature
import se.lublin.mumla.feature.ScoFailureFeature
import se.lublin.mumla.feature.TalkBroadcastFeature
import se.lublin.mumla.feature.TalkKeysFeature
import se.lublin.mumla.feature.TextToSpeechFeature
import se.lublin.mumla.service.AndroidFeaturePlatform
import se.lublin.mumla.service.ConnectionService
import java.time.Clock

/**
 * Composition root: one instance of every application-scoped object, built
 * by hand in [MumlaApplication.onCreate] and never closed. Services, screens,
 * and features borrow these objects; a second service instance in the same
 * process gets the same ones.
 *
 * The single blocking settings read at process start also runs the legacy
 * SharedPreferences migration, so theme and locale are final before the
 * first activity draws. After that, no settings read or write blocks.
 */
open class AppContainer(
    private val context: Context,
    val runtimeEdges: HumlaRuntime.Edges = HumlaRuntime.Edges.production(),
) {
    /** The in-flight connect-path service waiter; a later connect replaces it. */
    private var serviceWaiter: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        // Fail loudly in debug; report and keep the process alive in release.
        if (BuildConfig.DEBUG) throw RuntimeException(error)
        Log.e(TAG, "Unhandled app-scope failure", error)
    }

    open val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    open val settingsStore: DataStore<Preferences> = createSettingsStore(context)

    private val startupPreferences: Preferences = runBlocking { settingsStore.data.first() }

    open val settingsRepository = SettingsRepository(settingsStore, appScope, startupPreferences)

    open val settings = Settings(settingsRepository)

    open val database: MumlaRoomDatabase = MumlaRoomDatabase.create(context)

    open val localFactsStore: LocalFactsStore = AppLocalFactsStore(database, settings)

    /** Interim deny formatter, now over the app's moved resources. */
    open val chatFormatter = ChatEventFormatter(context)
    open val denyFormatter: (SessionEvent.PermissionDenied) -> String =
        { chatFormatter.denyReason(it) }

    // A snapshot the mapper rejects keeps the previous value and logs, so a
    // corrupt preference cannot strand the running voice configuration.
    open val voiceSettings: StateFlow<VoiceSettings> = settingsRepository.state
        .map { it.snapshot }
        .runningFold(initialVoiceSettings) { previous, snapshot ->
            try {
                snapshot.toVoiceSettings()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Rejecting invalid voice settings snapshot", e)
                previous
            }
        }
        .stateIn(appScope, SharingStarted.Eagerly, initialVoiceSettings)

    open val routePreferences: StateFlow<RoutePreferences> = settingsRepository.state
        .map { it.snapshot }
        .runningFold(initialRoutePreferences) { _, snapshot -> snapshot.toRoutePreferences() }
        .stateIn(appScope, SharingStarted.Eagerly, initialRoutePreferences)

    open val runtime: HumlaRuntime = HumlaRuntime.create(
        context = context,
        scope = appScope,
        inputs = HumlaRuntime.Inputs(
            voiceSettings = voiceSettings,
            routePreferences = routePreferences,
            localFacts = localFactsStore,
            clock = Clock.systemUTC(),
        ),
        edges = runtimeEdges,
    )

    /** One current-value snapshot flow, shared by every feature. */
    open val settingsSnapshot: StateFlow<PrefSnapshot> = settingsRepository.state
        .map { it.snapshot }
        .stateIn(appScope, SharingStarted.Eagerly, settingsRepository.state.value.snapshot)

    // Hand-off flows: one feature's output reaches another without a call.
    // Emitted with tryEmit on the main thread and collected on the main
    // thread without slow work inline.
    private val _talkKeys = MutableSharedFlow<TalkKeyEvent>(extraBufferCapacity = 64)
    val talkKeys: SharedFlow<TalkKeyEvent> = _talkKeys.asSharedFlow()

    private val _speech = MutableSharedFlow<SpeechLine>(extraBufferCapacity = 64)
    val speech: SharedFlow<SpeechLine> = _speech.asSharedFlow()

    private val _appLog = MutableSharedFlow<AppLogLine>(extraBufferCapacity = 64)
    val appLog: SharedFlow<AppLogLine> = _appLog.asSharedFlow()

    fun emitTalkKey(event: TalkKeyEvent) = emitHandoff(_talkKeys, event)
    fun emitSpeech(line: SpeechLine) = emitHandoff(_speech, line)
    fun emitAppLog(line: AppLogLine) = emitHandoff(_appLog, line)

    private fun <T> emitHandoff(flow: MutableSharedFlow<T>, value: T) {
        if (!flow.tryEmit(value)) {
            val error = IllegalStateException("hand-off flow is full")
            if (BuildConfig.DEBUG) throw error
            Log.e(TAG, "Dropping a hand-off emission", error)
        }
    }

    private val _configuration = MutableStateFlow(Configuration(context.resources.configuration))
    open val configuration: StateFlow<Configuration> = _configuration.asStateFlow()

    open fun updateConfiguration(next: Configuration) {
        _configuration.value = Configuration(next)
    }

    open val featurePlatform: FeaturePlatform =
        AndroidFeaturePlatform(context.applicationContext, settings)

    /** The overlay HUD reads the process-lifetime session-state mirror. */
    open val overlayHost: OverlayHost = object : OverlayHost {
        private val settings get() = this@AppContainer.settings
        override fun overlayPinned() = settings.isOverlayPinned
        override fun overlayLayout(): OverlayLayout {
            val layout = settings.overlayLayout()
            return OverlayLayout(layout.pinned, layout.gravity, layout.placement)
        }
        override fun overlayPosX(defaultX: Int) = settings.getOverlayPosX(defaultX)
        override fun overlayPosY(defaultY: Int) = settings.getOverlayPosY(defaultY)
        override fun setOverlayPosition(x: Int, y: Int) =
            settings.setOverlayPosition(x, y)
        override fun sessionState(): StateFlow<SessionState> = runtime.sessionState
        override fun talkActivity(): StateFlow<TalkActivity> = runtime.talkActivity
    }

    // Intent functions: features take functions, never the client itself.
    private fun currentSession() = runtime.client.currentSession.value
    private val setPtt: (Boolean) -> Unit = { currentSession()?.setPtt(it) }
    private val togglePtt: () -> Unit = { currentSession()?.togglePtt() }
    private val isTalking: () -> Boolean =
        { currentSession()?.state?.value?.local?.pttPressed ?: false }
    private val setSelfMuteDeaf: (Boolean, Boolean) -> Unit =
        { muted, deafened -> currentSession()?.setSelfMuteDeaf(muted, deafened) }
    private val requestTexture: (Int) -> Unit = { currentSession()?.requestTexture(it) }

    private fun toggleSelfMute(): Boolean? {
        if (runtime.client.state.value !is ClientState.Connected) return null
        val self = runtime.sessionState.value.server.self ?: return null
        val muted = !self.selfMuted
        setSelfMuteDeaf(muted, self.selfDeafened && muted)
        return muted
    }

    private fun toggleDeafen() {
        if (runtime.client.state.value !is ClientState.Connected) return
        val self = runtime.sessionState.value.server.self ?: return
        val deafened = !self.selfDeafened
        setSelfMuteDeaf(deafened, deafened)
    }

    open val notificationActions: NotificationActions = object : NotificationActions {
        override fun onMuteToggled() {
            toggleSelfMute()
        }
        override fun onMediaKeyMuteToggled() {
            val muted = toggleSelfMute()
            if (muted != null && settings.isMediaKeyMuteTtsEnabled) {
                emitSpeech(
                    SpeechLine(
                        context.getString(if (muted) R.string.tts_muted else R.string.tts_unmuted),
                    ),
                )
            }
        }
        override fun onDeafenToggled() = toggleDeafen()
        override fun onOverlayToggled() = overlay.setShown(!overlay.isShown())
        override fun onCancelReconnect() = runtime.client.cancelReconnect()
        override fun onDisconnect() = runtime.client.disconnect()
    }

    // Features: built once, started with appScope, never closed.
    open val chatLog: ChatLogFeature = ChatLogFeature(
        context = context,
        clientState = runtime.client.state,
        events = runtime.events,
        voiceEvents = runtime.voiceEvents,
        appLog = appLog,
        formatter = chatFormatter,
    )

    open val connectionNotification = ConnectionNotificationFeature(
        context = context,
        clientState = runtime.client.state,
        sessionState = runtime.sessionState,
        events = runtime.events,
        talkActivity = runtime.talkActivity,
        settings = settingsSnapshot,
        platform = featurePlatform,
        actions = notificationActions,
        overlayShown = { overlay.isShown() },
    )

    open val messageNotifications = MessageNotificationsFeature(
        events = runtime.events,
        clientState = runtime.client.state,
        settings = settingsSnapshot,
        platform = featurePlatform,
    )

    open val tts = TextToSpeechFeature(
        context = context,
        events = runtime.events,
        clientState = runtime.client.state,
        sessionState = runtime.sessionState,
        speech = speech,
        settings = settingsSnapshot,
        platform = featurePlatform,
        emitAppLog = this::emitAppLog,
    )

    open val pttSounds = PttSoundsFeature(
        clientState = runtime.client.state,
        talkActivity = runtime.talkActivity,
        routeState = runtime.routeState,
        settings = settingsSnapshot,
        platform = featurePlatform,
    )

    open val talkKeysFeature = TalkKeysFeature(
        talkKeys = talkKeys,
        clientState = runtime.client.state,
        settings = settingsSnapshot,
        setPtt = setPtt,
        togglePtt = togglePtt,
        isTalking = isTalking,
    )

    open val hotCorner = HotCornerFeature(
        clientState = runtime.client.state,
        talkActivity = runtime.talkActivity,
        configuration = configuration,
        settings = settingsSnapshot,
        platform = featurePlatform,
        emitTalkKey = this::emitTalkKey,
    )

    open val overlay = OverlayFeature(
        clientState = runtime.client.state,
        sessionState = runtime.sessionState,
        talkActivity = runtime.talkActivity,
        configuration = configuration,
        settings = settingsSnapshot,
        platform = featurePlatform,
        overlayHost = overlayHost,
        setOverlaySetting = { enabled -> featurePlatform.setOverlayShown(enabled) },
    )

    open val proximity = ProximityFeature(
        clientState = runtime.client.state,
        routeState = runtime.routeState,
        settings = settingsSnapshot,
        platform = featurePlatform,
    )

    open val talkBroadcast = TalkBroadcastFeature(
        clientState = runtime.client.state,
        settings = settingsSnapshot,
        platform = featurePlatform,
        setPtt = setPtt,
        togglePtt = togglePtt,
    )

    open val bluetoothPermissionGuard = BluetoothPermissionGuardFeature(
        settings = settingsSnapshot,
        platform = featurePlatform,
    )

    open val scoFailure = ScoFailureFeature(
        routeEvents = runtime.routeEvents,
        platform = featurePlatform,
    )

    open val avatarRequests = AvatarRequestsFeature(
        sessionState = runtime.sessionState,
        requestTexture = requestTexture,
    )

    open val reconnectNotice = ReconnectNoticeFeature(
        clientState = runtime.client.state,
        settings = settingsSnapshot,
        platform = featurePlatform,
    )

    /**
     * The UI's connect path. It starts the client, then a waiter starts
     * [ConnectionService] at the first non-terminal client state while the
     * app is still in the foreground (Android 14+ generally refuses to start
     * a microphone foreground service from the background). The waiter ends
     * without starting anything at a Failed that is not the instance present
     * before the call, so a stale failure starts nothing, and a later connect
     * cancels an earlier waiter.
     */
    open fun connect(target: ConnectTarget) {
        val client = runtime.client
        serviceWaiter?.cancel()
        val priorFailed = client.state.value as? ClientState.Failed
        serviceWaiter = appScope.launch {
            val next = client.state.first { state ->
                when (state) {
                    is ClientState.Connecting,
                    is ClientState.Connected,
                    is ClientState.Reconnecting,
                    -> true
                    is ClientState.Failed -> state !== priorFailed
                    is ClientState.Idle -> false
                }
            }
            if (next !is ClientState.Failed) {
                startConnectionService()
            }
        }
        client.connect(target)
    }

    private fun startConnectionService() {
        try {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        } catch (e: Exception) {
            // Android can refuse once the app is in the background; the
            // connection continues without the service.
            Log.w(TAG, "ConnectionService start refused; continuing without it", e)
        }
    }

    /** Explicit notification actions reach the process-lifetime feature. */
    open fun onNotificationAction(action: String) {
        connectionNotification.handleAction(action)
    }

    init {
        chatLog.start(appScope)
        connectionNotification.start(appScope)
        messageNotifications.start(appScope)
        tts.start(appScope)
        pttSounds.start(appScope)
        talkKeysFeature.start(appScope)
        hotCorner.start(appScope)
        overlay.start(appScope)
        proximity.start(appScope)
        talkBroadcast.start(appScope)
        bluetoothPermissionGuard.start(appScope)
        scoFailure.start(appScope)
        avatarRequests.start(appScope)
        reconnectNotice.start(appScope)
    }

    private val initialVoiceSettings: VoiceSettings
        get() = try {
            settingsRepository.state.value.snapshot.toVoiceSettings()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejecting invalid startup voice settings", e)
            VoiceSettingsMapper.initialFromDefaults()
        }

    private val initialRoutePreferences: RoutePreferences
        get() = settingsRepository.state.value.snapshot.toRoutePreferences()

    private fun PrefSnapshot.toVoiceSettings(): VoiceSettings {
        val transmitMode = when (audioInputMethod) {
            Settings.ARRAY_INPUT_METHOD_PTT -> Constants.TRANSMIT_PUSH_TO_TALK
            Settings.ARRAY_INPUT_METHOD_CONTINUOUS -> Constants.TRANSMIT_CONTINUOUS
            else -> Constants.TRANSMIT_VOICE_ACTIVITY
        }
        return VoiceSettingsMapper.initialFromDefaults(
            transmitMode = transmitMode,
            detectionThreshold = vadThreshold / 100f,
            amplitudeBoost = inputVolume / 100f,
            inputQuality = inputBitrate,
            framesPerPacket = audioPerPacket.toIntOrNull()
                ?: Constants.DEFAULT_FRAMES_PER_PACKET,
            halfDuplex = halfDuplex,
            adaptiveLeveler = adaptiveLeveler,
        )
    }

    private fun PrefSnapshot.toRoutePreferences(): RoutePreferences = RoutePreferences(
        bluetoothSco = bluetoothHeadset && hasBluetoothConnectGrant(),
        handsetStream = if (handsetMode) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.STREAM_MUSIC
        },
    )

    private fun hasBluetoothConnectGrant(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AppContainer"
        private const val SETTINGS_FILE = "settings"

        // Process singleton without concurrency primitives (startup is
        // single-threaded; see MumlaRoomDatabase for the ratchet note).
        private var settingsInstance: DataStore<Preferences>? = null

        // Exactly one DataStore per file per process: Room and DataStore both
        // forbid concurrent instances on one file.
        private fun createSettingsStore(context: Context): DataStore<Preferences> {
            val existing = settingsInstance
            if (existing != null) return existing
            val appContext = context.applicationContext
            val defaultPrefsName = appContext.packageName + "_preferences"
            val created = PreferenceDataStoreFactory.create(
                migrations = listOf(SharedPreferencesMigration(appContext, defaultPrefsName)),
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { appContext.preferencesDataStoreFile(SETTINGS_FILE) },
            )
            settingsInstance = created
            return created
        }
    }
}

/** Gestures from talk keys, the hot corner, and the service's binder bridge. */
sealed interface TalkKeyEvent {
    data object Down : TalkKeyEvent
    data object Up : TalkKeyEvent
    data object Cancel : TalkKeyEvent
}

/** A line one feature wants spoken by TTS. */
data class SpeechLine(val text: String)

/** An info or warning line one feature wants in the chat log. */
data class AppLogLine(val text: String, val warning: Boolean = false)

/** Every component reaches the same composition root through its context. */
val Context.container: AppContainer
    get() = (applicationContext as MumlaApplication).container
