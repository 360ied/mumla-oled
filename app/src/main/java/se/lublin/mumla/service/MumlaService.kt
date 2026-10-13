/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Optional
import se.lublin.humla.Constants
import se.lublin.humla.HumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.TlsHostnameVerifier
import se.lublin.humla.util.CertRef
import se.lublin.humla.util.ConfigDelta
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.TrustStoreRef
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver
import se.lublin.mumla.util.HtmlUtils
import se.lublin.mumla.util.MumlaTrustStore
import se.lublin.mumla.util.NotificationSanitizer

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
// Open: the Robolectric suite subclasses this service with test doubles.
open class MumlaService : HumlaService(),
    Settings.OnSettingChangedListener,
    MumlaConnectionNotification.OnActionListener, IMumlaService {

    // Widened to public fields: Kotlin has no package visibility, and the
    // Robolectric suite reads/assigns these as fields from a Java subclass.
    @JvmField
    var mSettings: Settings? = null
    private var mDatabase: MumlaDatabase? = null
    private var mNotification: MumlaConnectionNotification? = null
    private var mMessageNotification: MumlaMessageNotification? = null
    /** Channel view overlay. */
    private var mChannelOverlay: MumlaOverlay? = null
    /** Proximity lock for handset mode. */
    private var mProximityLock: PowerManager.WakeLock? = null
    /** Play sound when push to talk key is pressed or released */
    @JvmField
    var mPTTSoundEnabled: Boolean = false
    private var mSoundPool: SoundPool? = null
    private var mPttOnSoundId: Int = 0
    private var mPttOffSoundId: Int = 0
    @Volatile
    private var mPttOnLoaded: Boolean = false
    @Volatile
    private var mPttOffLoaded: Boolean = false
    @Volatile
    private var mActivePttStreamId: Int = 0
    @JvmField
    @Volatile
    var mSelfTalking: Boolean = false
    /** Try to shorten spoken messages when using TTS */
    private var mShortTtsMessagesEnabled: Boolean = false
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private var mErrorShown: Boolean = false
    private val mServerChatLogs: MutableMap<String, MutableList<IChatMessage>> =
        object : LinkedHashMap<String, MutableList<IChatMessage>>(MAX_CACHED_SERVERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, MutableList<IChatMessage>>): Boolean {
                return size > MAX_CACHED_SERVERS
            }
        }
    private var mActiveServerKey: String = ""
    private var mMessageLog: MutableList<IChatMessage>? = null
    private var mSuppressNotifications: Boolean = false
    private var mWasReconnecting: Boolean = false

    private var mTTS: TextToSpeech? = null

    /**
     * Helper to speak a text message using TextToSpeech with QUEUE_ADD queuing.
     */
    private fun speakTts(message: String) {
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val tts = mTTS
        if (settings.isTextToSpeechEnabled && tts != null && message.isNotEmpty()) {
            tts.speak(message, TextToSpeech.QUEUE_ADD, null, null as String?)
        }
    }

    /**
     * (Re)bind the text-to-speech engine: shuts down any existing
     * instance, then creates a new engine bound to the user-selected
     * engine, or the system default if none is selected. The init
     * listener captures the engine requested at construction time so a
     * late failure is attributed to the engine that actually failed,
     * not to whatever is selected when the callback fires.
     */
    private fun initTts() {
        val tts = mTTS
        if (tts != null) {
            tts.shutdown()
            mTTS = null
        }
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (!settings.isTextToSpeechEnabled) {
            return
        }
        val pkg = settings.ttsEngine
        val initListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.ERROR) {
                if (pkg != null) {
                    logWarning(getString(R.string.tts_failed_engine, pkg))
                } else {
                    logWarning(getString(R.string.tts_failed))
                }
            }
        }
        mTTS = if (pkg != null) {
            TextToSpeech(this, initListener, pkg)
        } else {
            TextToSpeech(this, initListener)
        }
    }

    /** The view representing the hot corner. */
    private var mHotCorner: MumlaHotCorner? = null
    private val mHotCornerListener: MumlaHotCorner.MumlaHotCornerListener =
        object : MumlaHotCorner.MumlaHotCornerListener {
            override fun onHotCornerDown() {
                onTalkKeyDown()
            }

            override fun onHotCornerUp() {
                onTalkKeyUp()
            }

            override fun onHotCornerCancel() {
                onTalkKeyCancel()
            }
        }

    private var mTalkReceiver: BroadcastReceiver? = null
    /** Tracks TALK receiver registration: registered once connected with the broadcast
     * allowed, unregistered on disconnect or opt-out, so toggles never double-register. */
    private var mTalkReceiverRegistered: Boolean = false

    private fun updateConnectedNotification() {
        val server = getTargetServer()
        val rawName = server?.name
        val serverName = if (!rawName.isNullOrEmpty()) {
            rawName
        } else {
            server?.host ?: getString(R.string.app_name)
        }
        val host = server?.host
        val hostInfo = if (host != null) {
            val port = server?.port ?: 0
            host + ":" + (if (port > 0) port else Constants.DEFAULT_PORT)
        } else {
            null
        }

        var channel: IChannel? = null
        try {
            channel = getSessionChannel()
        } catch (ignored: IllegalStateException) {
        }
        val channelName = channel?.name

        var user: IUser? = null
        try {
            user = getSessionUser()
        } catch (ignored: IllegalStateException) {
        }
        val muted = user != null && user.isSelfMuted
        val deafened = user != null && user.isSelfDeafened
        val overlayShown = isOverlayShown()

        if (mNotification == null) {
            mNotification = MumlaConnectionNotification.create(this, this)
        }
        val notification = mNotification ?: throw NullPointerException("mNotification")
        notification.showConnected(serverName, channelName, muted, deafened, overlayShown, hostInfo)
    }

    private val mObserver: HumlaObserver = object : HumlaObserver() {
        override fun onConnecting() {
            val server = getTargetServer()
            val rawName = server?.name
            val serverName = if (!rawName.isNullOrEmpty()) {
                rawName
            } else {
                server?.host ?: getString(R.string.app_name)
            }
            val host = server?.host ?: ""
            val port = if (server != null && server.port > 0) server.port else Constants.DEFAULT_PORT

            if (mNotification == null) {
                mNotification = MumlaConnectionNotification.create(this@MumlaService, this@MumlaService)
            }
            val notification = mNotification ?: throw NullPointerException("mNotification")
            notification.showConnecting(serverName, host, port)
            mErrorShown = false
            updateActiveServerLog()
        }

        override fun onConnected() {
            updateConnectedNotification()
            if (mWasReconnecting) {
                speakTts(getString(R.string.reconnected))
                logInfo(getString(R.string.reconnected))
                mWasReconnecting = false
            }
        }

        override fun onDisconnected(e: HumlaException?) {
            mSelfTalking = false
            if (isReconnecting()) {
                if (!mWasReconnecting) {
                    val cause = e?.message
                    val ttsMsg = if (cause != null && cause.trim().isNotEmpty()) {
                        getString(R.string.tts_disconnected_reason, cause.trim())
                    } else {
                        getString(R.string.disconnected)
                    }
                    speakTts(ttsMsg)
                    logWarning(ttsMsg)
                }
                mWasReconnecting = true
                val errorMsg = e?.message ?: getString(R.string.mumlaDisconnected)
                val server = getTargetServer()
                val rawName = server?.name
                val serverName = if (!rawName.isNullOrEmpty()) {
                    rawName
                } else {
                    server?.host
                }
                val host = server?.host
                val hostInfo = if (host != null) {
                    val port = server?.port ?: 0
                    host + ":" + (if (port > 0) port else Constants.DEFAULT_PORT)
                } else {
                    null
                }
                val attempt = getReconnectAttempts()
                val delaySec = getReconnectDelay() / 1000

                if (mNotification == null) {
                    mNotification = MumlaConnectionNotification.create(this@MumlaService, this@MumlaService)
                }
                val notification = mNotification ?: throw NullPointerException("mNotification")
                notification.showReconnecting(serverName, errorMsg, attempt, delaySec, hostInfo)
            } else {
                mWasReconnecting = false
                val notification = mNotification
                if (notification != null) {
                    notification.hide()
                    mNotification = null
                }
                if (e != null) {
                    val cause = e.message
                    val ttsMsg = if (cause != null && cause.trim().isNotEmpty()) {
                        getString(R.string.tts_disconnected_reason, cause.trim())
                    } else {
                        getString(R.string.disconnected)
                    }
                    speakTts(ttsMsg)
                    logWarning(ttsMsg)
                }
            }
        }

        override fun onUserConnected(user: IUser?) {
            // NPE-exact: the original dereferenced user unconditionally.
            val u = user ?: throw NullPointerException("user")
            if (u.textureHash != null && u.texture == null) {
                // Request avatar data if available.
                requestAvatar(u.session)
            }
        }

        override fun onUserStateUpdated(user: IUser?) {
            if (user == null) {
                return
            }

            val selfSession: Int
            try {
                selfSession = getSessionId()
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserStateUpdated: $e")
                return
            }

            if (user.session == selfSession) {
                // NPE-exact: the original dereferenced mSettings unconditionally.
                val settings = mSettings ?: throw NullPointerException("mSettings")
                settings.setMutedAndDeafened(user.isSelfMuted, user.isSelfDeafened) // Update settings mute/deafen state
                updateConnectedNotification()
            }

            if (user.textureHash != null && user.texture == null) {
                // Update avatar data if available.
                requestAvatar(user.session)
            }
        }

        override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) {
            var selfSession = -1
            try {
                selfSession = getSessionId()
            } catch (ignored: IllegalStateException) {
            }
            if (user != null && user.session == selfSession) {
                updateConnectedNotification()
            }
        }

        override fun onMessageLogged(message: IMessage?) {
            // NPE-exact: the original dereferenced message unconditionally.
            val msg = message ?: throw NullPointerException("message")
            // Split on / strip all HTML tags.
            val parsedMessage = Jsoup.parseBodyFragment(
                // Null bodies are type-legal per IMessage; fail fast with NPE
                // rather than Jsoup's IllegalArgumentException.
                msg.message ?: throw NullPointerException("message")
            )
            val strippedMessage = parsedMessage.text()

            val settings = mSettings ?: throw NullPointerException("mSettings")
            val ttsMessage: String
            if (mShortTtsMessagesEnabled) {
                for (anchor: Element in parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    val href = anchor.attr("href")
                    // Only shorten anchors without custom text
                    if (href != null && href == anchor.text()) {
                        val urlHostname = HtmlUtils.getHostnameFromLink(href)
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname))
                        }
                    }
                }
                ttsMessage = parsedMessage.text()
            } else {
                ttsMessage = strippedMessage
            }

            val formattedTtsMessage = getString(
                R.string.notification_message,
                NotificationSanitizer.sanitizeActor(msg.actorName),
                NotificationSanitizer.sanitizeBody(ttsMessage)
            )

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened.
            // Single guarded read: getSessionUser() throws when unsynchronized,
            // and a second call could observe a different user after a race.
            // A teardown-racing message still gets logged; only TTS is skipped.
            var selfDeafened = true
            try {
                val sessionUser = getSessionUser()
                selfDeafened = sessionUser != null && sessionUser.isSelfDeafened
            } catch (e: IllegalStateException) {
                Log.w(TAG, "TTS check after unsync; skipping speech")
            }
            if (formattedTtsMessage.length <= TTS_THRESHOLD && !selfDeafened) {
                speakTts(formattedTtsMessage)
            }

            // TODO: create a customizable notification sieve
            if (settings.isChatNotifyEnabled) {
                val messageNotification = mMessageNotification ?: throw NullPointerException("mMessageNotification")
                messageNotification.show(msg)
            }

            addChatMessageToLog(IChatMessage.TextMessage(msg, false))
        }

        override fun onLogInfo(message: String?) {
            addChatMessageToLog(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message))
        }

        override fun onLogWarning(message: String?) {
            addChatMessageToLog(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message))
        }

        override fun onLogError(message: String?) {
            addChatMessageToLog(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message))
        }

        override fun onPermissionDenied(reason: String?) {
            val notification = mNotification
            if (notification != null && !mSuppressNotifications) {
                notification.show()
            }
        }

        override fun onUserTalkStateUpdated(user: IUser?) {
            handleUserTalkStateUpdated(user)
        }
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package Robolectric suite).
    fun handleUserTalkStateUpdated(user: IUser?) {
        var selfSession = -1
        try {
            selfSession = getSessionId()
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in handleUserTalkStateUpdated: $e")
        }

        if (user != null && user.session == selfSession) {
            val ts = user.talkState
            val isTalking = ts == TalkState.TALKING || ts == TalkState.SHOUTING || ts == TalkState.WHISPERING
            val hotCorner = mHotCorner
            if (hotCorner != null) {
                hotCorner.updateTalkState(isTalking)
            }

            if (isConnectionEstablished() &&
                getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                mPTTSoundEnabled
            ) {
                if (isTalking && !mSelfTalking) {
                    playPttSound(true)
                } else if (!isTalking && mSelfTalking) {
                    playPttSound(false)
                }
            }
            mSelfTalking = isTalking
        }
    }

    /**
     * Refreshes route-dependent cue audio after a confirmed route change
     * (SCO or media-sink). The pipeline owns voice routing; this owns the
     * PTT SoundPool.
     */
    override fun onAudioRouteChanged() {
        initSoundPool()
        val settings = mSettings ?: throw NullPointerException("mSettings")
        setProximitySensorOn(shouldUseProximitySensor(settings))
    }

    /**
     * The proximity sensor blanks the screen for ear-held handset use only.
     * A live SCO link wins over handset mode: blanking the screen for a
     * headset with no ear proximity would be unexpected.
     */
    private fun shouldUseProximitySensor(): Boolean {
        val settings = mSettings ?: throw NullPointerException("mSettings")
        return shouldUseProximitySensor(settings)
    }

    private fun shouldUseProximitySensor(settings: Settings): Boolean {
        return isConnectionEstablished() && settings.isHandsetMode && !isBluetoothScoActive()
    }

    // Widened to public: Kotlin has no package visibility (was package-visible in Java).
    fun initSoundPool() {
        releaseSoundPool()
        try {
            // Voice cues follow voice output; media routes (BT A2DP/wired)
            // keep music-stream cues on A2DP volume. SCO implies voice.
            val voiceRoute = !isMediaRouteActive()
            val streamType = if (voiceRoute) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
            val usage = if (voiceRoute) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val attributes = AudioAttributes.Builder()
                .setLegacyStreamType(streamType)
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build()
            mSoundPool = pool
            // Non-null by construction (Builder.build() never returns null).
            pool.setOnLoadCompleteListener(object : SoundPool.OnLoadCompleteListener {
                override fun onLoadComplete(soundPool: SoundPool?, sampleId: Int, status: Int) {
                    if (soundPool !== mSoundPool) {
                        return
                    }
                    if (status == 0) {
                        if (sampleId == mPttOnSoundId) {
                            mPttOnLoaded = true
                        } else if (sampleId == mPttOffSoundId) {
                            mPttOffLoaded = true
                        }
                    }
                }
            })
            mPttOnSoundId = pool.load(this, R.raw.ptt_on, 1)
            mPttOffSoundId = pool.load(this, R.raw.ptt_off, 1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SoundPool: " + e.message)
        } catch (e: NoSuchMethodError) {
            Log.e(TAG, "Failed to initialize SoundPool: " + e.message)
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "Failed to initialize SoundPool: " + e.message)
        }
    }

    // Widened to public: Kotlin has no package visibility (was package-visible in Java).
    fun releaseSoundPool() {
        val pool = mSoundPool
        if (pool != null) {
            try {
                pool.setOnLoadCompleteListener(null)
                pool.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release SoundPool: " + e.message)
            }
            mSoundPool = null
        }
        mPttOnSoundId = 0
        mPttOffSoundId = 0
        mPttOnLoaded = false
        mPttOffLoaded = false
        mActivePttStreamId = 0
    }

    // Widened to public: Kotlin has no package visibility (was package-visible in Java).
    // Open: the Robolectric suite overrides the cue with a recording no-op.
    open fun playPttSound(on: Boolean) {
        val soundPool = mSoundPool
        if (soundPool == null) {
            return
        }
        val soundId = if (on) mPttOnSoundId else mPttOffSoundId
        val loaded = if (on) mPttOnLoaded else mPttOffLoaded
        if (soundId != 0 && loaded) {
            try {
                if (mActivePttStreamId != 0) {
                    soundPool.stop(mActivePttStreamId)
                }
                mActivePttStreamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play PTT cue: " + e.message)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerObserver(mObserver)

        // Register for preference changes
        mSettings = Settings.getInstance(this)
        mDatabase = MumlaRoomDatabase.getInstance(this)
        val settings = mSettings ?: throw NullPointerException("mSettings")
        mPTTSoundEnabled = settings.isPttSoundEnabled
        initSoundPool()
        mShortTtsMessagesEnabled = settings.isShortTextToSpeechMessagesEnabled
        settings.registerListener(this)

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        MumlaApplication.applyTheme(this)

        mMessageLog = ArrayList()
        mMessageNotification = MumlaMessageNotification(this)

        // Instantiate overlay view
        mChannelOverlay = MumlaOverlay(this)
        mHotCorner = MumlaHotCorner(this, settings.hotCornerGravity, mHotCornerListener)

        // Set up TTS
        initTts()

        mTalkReceiver = TalkBroadcastReceiver(this)
    }

    override fun onBind(intent: Intent): IBinder {
        return MumlaBinder(this)
    }

    override fun onDestroy() {
        val notification = mNotification
        if (notification != null) {
            notification.hide()
            mNotification = null
        }

        val hotCorner = mHotCorner
        if (hotCorner != null) {
            hotCorner.setShown(false)
        }

        val overlay = mChannelOverlay
        if (overlay != null && overlay.isShown()) {
            overlay.hide()
        }

        val settings = mSettings ?: throw NullPointerException("mSettings")
        settings.unregisterListener(this)
        unregisterTalkReceiver()

        unregisterObserver(mObserver)
        val tts = mTTS
        if (tts != null) {
            tts.shutdown()
            mTTS = null
        }
        synchronized(this) {
            mMessageLog = null
            mServerChatLogs.clear()
            mActiveServerKey = ""
        }
        val messageNotification = mMessageNotification ?: throw NullPointerException("mMessageNotification")
        messageNotification.dismiss()
        releaseSoundPool()
        mSelfTalking = false
        super.onDestroy()
    }

    /** Registers the TALK broadcast receiver if not already registered. Main thread only. */
    private fun registerTalkReceiver() {
        if (mTalkReceiverRegistered) {
            return
        }
        val receiver = mTalkReceiver ?: throw NullPointerException("mTalkReceiver")
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK),
            ContextCompat.RECEIVER_EXPORTED
        )
        mTalkReceiverRegistered = true
    }

    /** Unregisters the TALK broadcast receiver if registered. Main thread only. */
    private fun unregisterTalkReceiver() {
        if (!mTalkReceiverRegistered) {
            return
        }
        val receiver = mTalkReceiver ?: throw NullPointerException("mTalkReceiver")
        unregisterReceiver(receiver)
        mTalkReceiverRegistered = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val overlay = mChannelOverlay
        if (overlay != null && overlay.isShown()) {
            overlay.updatePosition()
        }
        val hotCorner = mHotCorner
        if (hotCorner != null && hotCorner.isShown()) {
            // Post past the rotation relayout so getWidth/getHeight reflect
            // the post-rotation size instead of silently no-opping (ODD-05).
            hotCorner.refreshGestureExclusionDeferred()
        }
    }

    override fun onConnectionSynchronized() {
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        try {
            super.onConnectionSynchronized()
        } catch (e: RuntimeException) {
            Log.d(TAG, "exception in onConnectionSynchronized: $e")
            return
        }

        val settings = mSettings ?: throw NullPointerException("mSettings")
        // Restore mute/deafen state
        if (settings.isMuted || settings.isDeafened) {
            setSelfMuteDeafState(settings.isMuted, settings.isDeafened)
        }

        updateOverlayVisibility()

        if (settings.isTalkBroadcastAllowed) {
            registerTalkReceiver()
        }

        updateHotCornerVisibility()
        // Configure proximity sensor: SCO wins over handset mode (see
        // shouldUseProximitySensor) so a headset never blanks the screen.
        setProximitySensorOn(shouldUseProximitySensor(settings))

        updateConnectedNotification()
        mSelfTalking = false
    }

    override fun onConnectionDisconnected(e: HumlaException?) {
        super.onConnectionDisconnected(e)
        mSelfTalking = false
        unregisterTalkReceiver()

        // Remove overlay if present.
        val overlay = mChannelOverlay
        if (overlay != null) {
            overlay.hide()
        }

        val hotCorner = mHotCorner
        if (hotCorner != null) {
            hotCorner.setShown(false)
        }

        setProximitySensorOn(false)

        val messageNotification = mMessageNotification
        if (messageNotification != null) {
            messageNotification.dismiss()
        }
    }

    /**
     * Called when the user makes a change to their preferences.
     * Builds a one-shot delta for the changed key, runs the app-layer side
     * effects, and applies the core fields to the service.
     */
    override fun onSettingChanged(key: String?) {
        if (key == null) {
            return
        }
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val builder = ConfigDelta.Builder()
        var requiresReconnect = false
        when (key) {
            Settings.PREF_INPUT_METHOD -> {
                /* Convert input method defined in settings to an integer format used by Humla. */
                val inputMethod = settings.humlaInputMethod
                builder.transmitMode = inputMethod
                val overlay = mChannelOverlay
                if (overlay != null) {
                    overlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK)
                }
                updateHotCornerVisibility()
            }
            Settings.PREF_HANDSET_MODE -> {
                setProximitySensorOn(shouldUseProximitySensor(settings))
                builder.audioStream = if (settings.isHandsetMode) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
                initSoundPool()
            }
            Settings.PREF_THRESHOLD -> {
                builder.detectionThreshold = settings.detectionThreshold
            }
            Settings.PREF_HOT_CORNER_KEY -> {
                val hotCorner = mHotCorner
                if (hotCorner != null) {
                    hotCorner.setGravity(settings.hotCornerGravity)
                }
                updateHotCornerVisibility()
            }
            // Intentional fall through in the original: rebind the engine either way.
            Settings.PREF_USE_TTS, Settings.PREF_TTS_ENGINE -> {
                builder.ttsEngine = Optional.ofNullable(settings.ttsEngine)
            }
            Settings.PREF_SHORT_TTS_MESSAGES -> {
                mShortTtsMessagesEnabled = settings.isShortTextToSpeechMessagesEnabled
            }
            Settings.PREF_THEME -> {
                builder.theme = settings.theme
            }
            Settings.PREF_OVERLAY_SHOWN -> {
                builder.overlayShown = settings.isOverlayShown
            }
            Settings.PREF_OVERLAY_PLACEMENT -> {
                builder.overlayPlacement = settings.overlayPlacement
            }
            Settings.PREF_NOTIFICATION_STYLE -> {
                if (isConnectionEstablished()) {
                    updateConnectedNotification()
                } else {
                    val notification = mNotification
                    if (notification != null) {
                        notification.show()
                    }
                }
            }
            Settings.PREF_AMPLITUDE_BOOST -> {
                builder.amplitudeBoost = settings.amplitudeBoostMultiplier
            }
            Settings.PREF_HALF_DUPLEX -> {
                builder.halfDuplex = settings.isHalfDuplex
            }
            Settings.PREF_BLUETOOTH_HEADSET -> {
                if (settings.isBluetoothHeadset && !hasBluetoothConnectGrant()) {
                    // Either toggle flips without the runtime grant: revert
                    // with a hint instead of pushing a doomed delta. Only
                    // the connect flow requests the grant first. The write-back
                    // re-enters onSettingChanged once; the second pass takes
                    // the non-revert branch and terminates.
                    settings.isBluetoothHeadset = false
                    Toast.makeText(this, R.string.grant_perm_bluetooth, Toast.LENGTH_LONG).show()
                    return
                }
                builder.bluetoothSco = settings.isBluetoothHeadset
                setProximitySensorOn(shouldUseProximitySensor(settings))
                initSoundPool()
            }
            Settings.PREF_ADAPTIVE_LEVELER_ENABLED -> {
                builder.adaptiveLeveler = settings.isAdaptiveLevelerEnabled
            }
            Settings.PREF_PTT_SOUND -> {
                mPTTSoundEnabled = settings.isPttSoundEnabled
            }
            Settings.PREF_INPUT_QUALITY -> {
                builder.inputQuality = settings.inputQuality
            }
            Settings.PREF_FRAMES_PER_PACKET -> {
                builder.framesPerPacket = settings.framesPerPacket
            }
            Settings.PREF_CERT_ID, Settings.PREF_FORCE_TCP -> {
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true
            }
            Settings.PREF_ALLOW_TALK_BROADCAST -> {
                if (!settings.isTalkBroadcastAllowed) {
                    unregisterTalkReceiver()
                } else if (isConnectionEstablished()) {
                    registerTalkReceiver()
                }
            }
        }
        val delta = builder.build()
        if (!applySideEffects(delta)) {
            return
        }
        if (delta.hasCore()) {
            // Reconfigure the service appropriately. apply() throws IAE on
            // unknown transmit mode (mirrors onStartCommand's guard): a
            // corrupted stored input-method value must not crash the main
            // thread here — log and keep the running configuration.
            try {
                requiresReconnect = requiresReconnect or apply(delta)
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("MumlaService", "Ignoring invalid service configuration.", e)
                return
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Runs the app-layer side effects for delta presence signals (theme,
     * overlay, TTS engine). Returns false when handling consumed the change
     * and the caller must stop (overlay permission revert skips the
     * reconnect toast, as before).
     */
    private fun applySideEffects(delta: ConfigDelta): Boolean {
        if (delta.theme != null) {
            MumlaApplication.applyTheme(this)
        }
        if (delta.overlayShown != null) {
            val settings = mSettings ?: throw NullPointerException("mSettings")
            if (settings.isOverlayShown && !checkAndRequestOverlayPermission()) {
                return false
            }
            updateOverlayVisibility()
            updateConnectedNotification()
        }
        if (delta.overlayPlacement != null) {
            val overlay = mChannelOverlay
            if (overlay != null && overlay.isShown()) {
                overlay.updatePosition()
            }
        }
        if (delta.ttsEngine != null) {
            initTts()
        }
        return true
    }

    override fun buildConnectDelta(intent: Intent?): ConfigDelta? {
        // NPE-exact: the original dereferenced intent unconditionally.
        val request = intent ?: throw NullPointerException("intent")
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        val serverId = request.getLongExtra(EXTRA_SERVER_ID, -1)
        var server: Server? = null
        if (serverId != -1L) {
            // Database is the source of truth for saved servers; the parcel
            // path below serves unsaved (ad-hoc) servers only.
            for (candidate in database.getServers()) {
                if (candidate.id == serverId) {
                    server = candidate
                    break
                }
            }
            if (server == null) {
                return null
            }
        } else {
            server = request.getParcelableExtra(EXTRA_SERVER, Server::class.java)
            if (server == null) {
                return null
            }
        }

        val builder = ConfigDelta.Builder()
        val connected = server ?: throw NullPointerException("server")
        builder.server = connected
        builder.clientName = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
        builder.transmitMode = settings.humlaInputMethod
        builder.detectionThreshold = settings.detectionThreshold
        builder.amplitudeBoost = settings.amplitudeBoostMultiplier
        builder.inputQuality = settings.inputQuality
        builder.forceTcp = settings.isTcpForced
        builder.accessTokens = database.getAccessTokens(connected.id)
        builder.audioStream = if (settings.isHandsetMode) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.STREAM_MUSIC
        }
        builder.framesPerPacket = settings.framesPerPacket
        builder.trustStore = TrustStoreRef(
            MumlaTrustStore.getTrustStorePath(this),
            MumlaTrustStore.getTrustStorePassword(),
            MumlaTrustStore.getTrustStoreFormat()
        )
        builder.halfDuplex = settings.isHalfDuplex
        builder.bluetoothSco = settings.isBluetoothHeadset
        builder.adaptiveLeveler = settings.isAdaptiveLevelerEnabled
        if (connected.isSaved()) {
            builder.localMuteHistory = database.getLocalMutedUsers(connected.id)
            builder.localIgnoreHistory = database.getLocalIgnoredUsers(connected.id)
        }
        if (settings.isUsingCertificate) {
            val certificateId = settings.defaultCertificate
            val certificate = database.getCertificateData(certificateId)
            val certificatePassword = database.getCertificatePassword(certificateId)
            if (certificate != null) {
                builder.certificate = CertRef(certificate, certificatePassword)
            }
            // TODO(acomminos): handle the case where a certificate's data is unavailable.
        }
        return builder.build()
    }

    /**
     * Whether the BLUETOOTH_CONNECT runtime grant (API 31+) is in place.
     * Below 31 the manifest flags are install-time, so always true.
     */
    private fun hasBluetoothConnectGrant(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun setProximitySensorOn(on: Boolean) {
        if (on) {
            val lock = mProximityLock
            if (lock != null && lock.isHeld) {
                return
            }
            mProximityLock = null
            val pm = getSystemService(POWER_SERVICE) as PowerManager?
            if (pm == null) {
                Log.w(TAG, "proximity sensor unavailable: no PowerManager")
                return
            }
            try {
                val newLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity")
                mProximityLock = newLock
                newLock.acquire()
            } catch (e: RuntimeException) {
                // Sensor-less devices and OEM restrictions can throw here;
                // never let it escape into sync/pref/disconnect callers.
                Log.w(TAG, "proximity lock acquire failed", e)
                mProximityLock = null
            }
        } else {
            val lock = mProximityLock
            if (lock != null) {
                try {
                    if (lock.isHeld) lock.release()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "proximity lock release failed", e)
                }
                mProximityLock = null
            }
        }
    }

    override fun onMuteToggled() {
        toggleSelfMute()
    }

    /**
     * Applies a self-mute toggle, collapsing deafen into mute.
     *
     * @return the requested muted target, or null when disconnected,
     * unsynchronized, or userless, so toggle entry points stay no-ops
     * instead of throwing on a teardown race.
     */
    private fun toggleSelfMute(): Boolean? {
        if (!isConnectionEstablished()) {
            return null
        }
        val user: IUser
        try {
            val sessionUser = getSessionUser()
            if (sessionUser == null) {
                return null
            }
            user = sessionUser
        } catch (e: IllegalStateException) {
            return null
        }
        val muted = !user.isSelfMuted
        setSelfMuteDeafState(muted, user.isSelfDeafened && muted)
        return muted
    }

    /**
     * Media-key mute toggle: same state change as {@link #onMuteToggled()},
     * plus an optional spoken confirmation so hands-free users hear the
     * resulting state. The master TTS switch still applies via
     * {@link #speakTts(String)}.
     */
    override fun onMediaKeyMuteToggled() {
        val muted = toggleSelfMute()
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (muted != null && settings.isMediaKeyMuteTtsEnabled) {
            speakTts(getString(if (muted) R.string.tts_muted else R.string.tts_unmuted))
        }
    }

    override fun onDeafenToggled() {
        if (!isConnectionEstablished()) {
            return
        }
        val user: IUser
        try {
            val sessionUser = getSessionUser()
            if (sessionUser == null) {
                return
            }
            user = sessionUser
        } catch (e: IllegalStateException) {
            return
        }
        val deafened = !user.isSelfDeafened
        setSelfMuteDeafState(deafened, deafened)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != null) {
            val action = intent.action
            if (ACTION_DISCONNECT == action) {
                onDisconnect()
                return START_NOT_STICKY
            } else if (ACTION_MUTE == action) {
                onMuteToggled()
                return START_NOT_STICKY
            } else if (ACTION_DEAFEN == action) {
                onDeafenToggled()
                return START_NOT_STICKY
            } else if (ACTION_TOGGLE_OVERLAY == action) {
                onOverlayToggled()
                return START_NOT_STICKY
            } else if (ACTION_CANCEL_RECONNECT == action) {
                onCancelReconnect()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onOverlayToggled() {
        setOverlayShown(!isOverlayShown())
    }

    override fun onCancelReconnect() {
        cancelReconnect()
    }

    override fun onDisconnect() {
        val notification = mNotification
        if (notification != null) {
            notification.hide()
            mNotification = null
        }
        disconnect()
    }

    override fun cancelReconnect() {
        mWasReconnecting = false
        val notification = mNotification
        if (notification != null) {
            notification.hide()
            mNotification = null
        }
        super.cancelReconnect()
    }

    private fun checkAndRequestOverlayPermission(): Boolean {
        if (!android.provider.Settings.canDrawOverlays(applicationContext)) {
            try {
                val showSetting = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                showSetting.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(showSetting)
                Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
            }
            val settings = mSettings ?: throw NullPointerException("mSettings")
            settings.isOverlayShown = false
            // Write-back re-enters onSettingChanged once with shown=false;
            // the second pass takes the non-revert branch and terminates.
            updateConnectedNotification()
            return false
        }
        return true
    }

    override fun setOverlayShown(showOverlay: Boolean) {
        if (showOverlay && !checkAndRequestOverlayPermission()) {
            return
        }
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (settings.isOverlayShown != showOverlay) {
            settings.isOverlayShown = showOverlay
        } else {
            updateOverlayVisibility()
            updateConnectedNotification()
        }
    }

    override fun isOverlayShown(): Boolean {
        val overlay = mChannelOverlay
        return overlay != null && overlay.isShown()
    }

    override fun clearChatNotifications() {
        val messageNotification = mMessageNotification ?: throw NullPointerException("mMessageNotification")
        messageNotification.dismiss()
    }

    override fun markErrorShown() {
        mErrorShown = true
    }

    override fun isErrorShown(): Boolean {
        return mErrorShown
    }

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    override fun onTalkKeyDown() {
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == settings.inputMethod) {
            if (!settings.isPushToTalkToggle && !isTalking()) {
                setTalkingState(true) // Start talking
            }
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    override fun onTalkKeyUp() {
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == settings.inputMethod) {
            if (settings.isPushToTalkToggle) {
                setTalkingState(!isTalking()) // Toggle talk state
            } else if (isTalking()) {
                setTalkingState(false) // Stop talking
            }
        }
    }

    /**
     * Called when a talk key gesture or focus is cancelled (e.g. gesture intercept or window blur).
     */
    override fun onTalkKeyCancel() {
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == settings.inputMethod) {
            if (!settings.isPushToTalkToggle && isTalking()) {
                setTalkingState(false)
            }
        }
    }

    private fun updateHotCornerVisibility() {
        val hotCorner = mHotCorner
        if (hotCorner != null) {
            val settings = mSettings ?: throw NullPointerException("mSettings")
            val shouldShow = isConnectionEstablished() &&
                settings.isHotCornerEnabled &&
                Settings.ARRAY_INPUT_METHOD_PTT == settings.inputMethod
            hotCorner.setShown(shouldShow)
        }
    }

    private fun updateOverlayVisibility() {
        val overlay = mChannelOverlay
        if (overlay != null) {
            val settings = mSettings ?: throw NullPointerException("mSettings")
            val shouldShow = isConnectionEstablished() && settings.isOverlayShown
            if (shouldShow) {
                if (!android.provider.Settings.canDrawOverlays(applicationContext)) {
                    return
                }
                if (!overlay.isShown()) {
                    overlay.show()
                }
            } else {
                if (overlay.isShown()) {
                    overlay.hide()
                }
            }
        }
    }

    @Synchronized
    override fun getMessageLog(): List<IChatMessage> {
        updateActiveServerLog()
        val log = mMessageLog
        if (log == null) {
            return Collections.emptyList()
        }
        return Collections.unmodifiableList(ArrayList(log))
    }

    @Synchronized
    override fun clearMessageLog() {
        val log = mMessageLog
        if (log != null) {
            log.clear()
        }
        if (mActiveServerKey.isNotEmpty()) {
            mServerChatLogs.remove(mActiveServerKey)
        }
        mActiveServerKey = ""
        mMessageLog = null
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    override fun setSuppressNotifications(suppressNotifications: Boolean) {
        mSuppressNotifications = suppressNotifications
    }

    class MumlaBinder internal constructor(private val mService: MumlaService) : Binder() {
        fun getService(): IMumlaService {
            return mService
        }
    }

    override fun sendUserTextMessage(session: Int, message: String?): Message? {
        val msg = super.sendUserTextMessage(session, message)

        addChatMessageToLog(IChatMessage.TextMessage(msg, true))
        return msg
    }

    override fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message? {
        val msg = super.sendChannelTextMessage(channel, message, tree)

        addChatMessageToLog(IChatMessage.TextMessage(msg, true))
        return msg
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // synchronized in Java for the same-package Robolectric suite).
    @Synchronized
    fun updateActiveServerLog() {
        val server = getTargetServer()
        val key = getServerKey(server)
        if (key.isNotEmpty() && key != mActiveServerKey) {
            mActiveServerKey = key
            val cached = mServerChatLogs[mActiveServerKey]
            mMessageLog = cached ?: ArrayList()
        } else if (mMessageLog == null) {
            mMessageLog = ArrayList()
        }
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // synchronized in Java for the same-package Robolectric suite).
    @Synchronized
    fun addChatMessageToLog(message: IChatMessage) {
        updateActiveServerLog()
        var log = mMessageLog
        if (log == null) {
            log = ArrayList()
            mMessageLog = log
        }
        if (mActiveServerKey.isNotEmpty()) {
            mServerChatLogs[mActiveServerKey] = log
        }
        if (log.size >= MAX_CHAT_LOG_SIZE) {
            log.removeAt(0)
        }
        log.add(message)
    }

    companion object {
        private val TAG: String = MumlaService::class.java.name

        /** CONNECT intent extra: database id of the saved server (-1 if unsaved). */
        const val EXTRA_SERVER_ID = "server_id"
        /** CONNECT intent extra: full parcel, only for unsaved (ad-hoc) servers. */
        const val EXTRA_SERVER = "server"

        /** Undocumented constant that permits a proximity-sensing wake lock. */
        const val PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32
        const val TTS_THRESHOLD = 250 // Maximum number of characters to read

        const val MAX_CHAT_LOG_SIZE = 500
        const val MAX_CACHED_SERVERS = 10

        const val ACTION_DISCONNECT = "se.lublin.mumla.action.DISCONNECT"
        const val ACTION_MUTE = "se.lublin.mumla.action.MUTE"
        const val ACTION_DEAFEN = "se.lublin.mumla.action.DEAFEN"
        const val ACTION_TOGGLE_OVERLAY = "se.lublin.mumla.action.TOGGLE_OVERLAY"
        const val ACTION_CANCEL_RECONNECT = "se.lublin.mumla.action.CANCEL_RECONNECT"

        @JvmStatic
        fun getServerKey(server: Server?): String {
            if (server == null) {
                return ""
            }
            if (server.isSaved()) {
                return "id:" + server.id
            }
            // Shared canonicalization with TLS pinning (trim + trailing-dot +
            // lowercase); IPv6 literals pass through unchanged. Password is
            // deliberately excluded: chat-log identity must survive password
            // corrections (see MumlaActivity.isSameServer, which compares the
            // password separately to force a reconnect on change).
            val rawHost = server.host?.trim() ?: ""
            val host = TlsHostnameVerifier.canonicalizeHost(rawHost) ?: ""
            val port = if (server.port > 0) server.port else Constants.DEFAULT_PORT
            val user = server.username ?: ""
            return "endpoint:$host:$port:$user"
        }
    }
}
