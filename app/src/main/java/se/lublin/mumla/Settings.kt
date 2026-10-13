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

package se.lublin.mumla

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.util.HashMap
import java.util.HashSet
import java.util.Optional
import se.lublin.humla.Constants

/**
 * Singleton settings class for universal access to the app's preferences.
 *
 * Migration slice 2: backed by the Preferences DataStore via
 * [SettingsRepository] instead of SharedPreferences. The public surface
 * (keys, defaults, accessors, validation) is unchanged — fleet settings
 * survive through `SharedPreferencesMigration` with no silent resets.
 *
 * Threading (interim): the surface is synchronous, so every call bridges to
 * the store with a blocking read/write on DataStore's in-memory cache.
 *
 * Accessors are properties, so Java callers keep their `getX()`/`isX()`/
 * `setX()` signatures bit-for-bit while Kotlin callers use property syntax.
 *
 * @author morlunk
 */
class Settings {
    private val repository: SettingsRepository
    private val listenerAdapters = HashMap<OnSettingChangedListener, java.util.function.Consumer<String>>()

    // Widened to public: Kotlin has no package visibility (pre-image was package-private).
    // Prefer Settings.getInstance involved parties share the single DataStore
    // instance per process (see AppDataStore); constructing directly with a
    // second store for the same file forks the key space. createForTesting
    // remains the test seam.
    constructor(store: DataStore<Preferences>) {
        repository = SettingsRepository(store)
    }

    // One-shot DataStore read. Compound derived accessors below call this
    // twice (e.g. isOverlayPinned then overlayGravity) and can therefore
    // observe two different snapshots under a concurrent write; paired reads
    // that must agree should snapshot once into a local and derive both
    // values from it (see isOverlayPinned/overlayGravity).
    private fun snapshot(): PrefSnapshot {
        return repository.snapshotBlocking()
    }

    fun registerListener(listener: OnSettingChangedListener) {
        val adapter: java.util.function.Consumer<String> =
            java.util.function.Consumer { key -> listener.onSettingChanged(key) }
        synchronized(listenerAdapters) {
            // Re-registering the same listener refreshes nothing: without this
            // guard the old adapter would stay live in the repository and fire twice.
            if (listenerAdapters.containsKey(listener)) {
                return
            }
            listenerAdapters[listener] = adapter
        }
        repository.registerListener(adapter)
    }

    fun unregisterListener(listener: OnSettingChangedListener) {
        val adapter: java.util.function.Consumer<String>?
        synchronized(listenerAdapters) {
            adapter = listenerAdapters.remove(listener)
        }
        if (adapter != null) {
            repository.unregisterListener(adapter)
        }
    }

    var inputMethod: String
        get() {
            val method = snapshot().audioInputMethod
            if (!ARRAY_INPUT_METHODS.contains(method)) {
                // Set default method for users who used to use handset mode before removal.
                return ARRAY_INPUT_METHOD_VOICE
            }
            return method
        }
        set(inputMethod) {
            if (ARRAY_INPUT_METHOD_VOICE == inputMethod ||
                ARRAY_INPUT_METHOD_PTT == inputMethod ||
                ARRAY_INPUT_METHOD_CONTINUOUS == inputMethod
            ) {
                repository.updateBlocking { prefs -> prefs.audioInputMethod = inputMethod }
            } else {
                throw RuntimeException("Invalid input method $inputMethod")
            }
        }

    /**
     * Converts the preference input method value to the one used to connect to a server via Humla.
     * @return An input method value used to instantiate a Humla service.
     */
    val humlaInputMethod: Int
        get() {
            val inputMethod = inputMethod
            if (ARRAY_INPUT_METHOD_VOICE == inputMethod) {
                return Constants.TRANSMIT_VOICE_ACTIVITY
            } else if (ARRAY_INPUT_METHOD_PTT == inputMethod) {
                return Constants.TRANSMIT_PUSH_TO_TALK
            } else if (ARRAY_INPUT_METHOD_CONTINUOUS == inputMethod) {
                return Constants.TRANSMIT_CONTINUOUS
            }
            throw RuntimeException("Could not convert input method '$inputMethod' to a Humla input method id!")
        }

    val inputSampleRate: Int
        get() {
            try {
                return snapshot().inputQuality.toInt()
            } catch (e: NumberFormatException) {
                return DEFAULT_RATE.toInt()
            }
        }

    val inputQuality: Int
        get() = snapshot().inputBitrate

    val amplitudeBoostMultiplier: Float
        get() = snapshot().inputVolume.toFloat() / 100

    val detectionThreshold: Float
        get() = snapshot().vadThreshold.toFloat() / 100

    val pushToTalkKey: Int
        get() = snapshot().talkKey

    val hotCorner: String
        get() = snapshot().hotCorner

    /**
     * Returns whether or not the hot corner is enabled.
     *
     * <p>Strict allowlist: only the four known corner values count as enabled.
     * Corrupted or unknown stored values resolve to disabled (consistent with
     * [hotCornerGravity], which returns 0 for such values).
     * @return true if a hot corner should be shown.
     */
    val isHotCornerEnabled: Boolean
        get() {
            val hc = snapshot().hotCorner
            return ARRAY_HOT_CORNER_TOP_LEFT == hc ||
                ARRAY_HOT_CORNER_TOP_RIGHT == hc ||
                ARRAY_HOT_CORNER_BOTTOM_LEFT == hc ||
                ARRAY_HOT_CORNER_BOTTOM_RIGHT == hc
        }

    /**
     * Returns the view gravity of the hot corner, or 0 if hot corner is disabled.
     * @return A [android.view.Gravity] value, or 0 if disabled.
     */
    val hotCornerGravity: Int
        get() {
            val hc = hotCorner
            if (ARRAY_HOT_CORNER_BOTTOM_LEFT == hc) {
                return Gravity.LEFT or Gravity.BOTTOM
            } else if (ARRAY_HOT_CORNER_BOTTOM_RIGHT == hc) {
                return Gravity.RIGHT or Gravity.BOTTOM
            } else if (ARRAY_HOT_CORNER_TOP_LEFT == hc) {
                return Gravity.LEFT or Gravity.TOP
            } else if (ARRAY_HOT_CORNER_TOP_RIGHT == hc) {
                return Gravity.RIGHT or Gravity.TOP
            }
            return 0
        }

    /* @return the height of PTT button in dp */
    // JvmName keeps the Java-identical getPTTButtonHeight() signature.
    @get:JvmName("getPTTButtonHeight")
    val pttButtonHeight: Int
        get() {
            val height = snapshot().pttButtonHeight
            if (height <= 0) {
                return DEFAULT_PTT_BUTTON_HEIGHT
            }
            return Math.min(MAX_PTT_BUTTON_HEIGHT, Math.max(MIN_PTT_BUTTON_HEIGHT, height))
        }

    /**
     * Returns a database identifier for the default certificate, or a negative number if there is
     * no default certificate set.
     * @return The default certificate's ID, or a negative integer if not set.
     */
    val defaultCertificate: Long
        get() = snapshot().certificateId

    val defaultUsername: String
        get() = snapshot().defaultUsername

    val isPushToTalkToggle: Boolean
        get() = snapshot().togglePtt

    val isPushToTalkButtonShown: Boolean
        get() = !snapshot().hidePtt

    val isChatNotifyEnabled: Boolean
        get() = snapshot().chatNotify

    val isTalkBroadcastAllowed: Boolean
        get() = snapshot().allowTalkBroadcast

    val notificationStyle: String
        get() {
            val style = snapshot().notificationStyle
            if (NOTIFICATION_STYLE_MEDIA != style && NOTIFICATION_STYLE_BIGTEXT != style) {
                return DEFAULT_NOTIFICATION_STYLE
            }
            return style
        }

    // Nullability is part of the contract (null-test pins IAE, not NPE):
    // reject null up front so no unreachable Elvis lingers in the writer.
    fun setNotificationStyle(style: String?) {
        val valid = style ?: throw IllegalArgumentException("Invalid notification style: $style")
        if (NOTIFICATION_STYLE_BIGTEXT == valid || NOTIFICATION_STYLE_MEDIA == valid) {
            repository.updateBlocking { prefs ->
                prefs.notificationStyle = valid
            }
        } else {
            throw IllegalArgumentException("Invalid notification style: $style")
        }
    }

    val isNotificationStyleBigText: Boolean
        get() = NOTIFICATION_STYLE_BIGTEXT == notificationStyle

    val isMediaKeyMuteEnabled: Boolean
        get() = snapshot().mediaKeyMute

    val isMediaKeyMuteTtsEnabled: Boolean
        get() = snapshot().mediaKeyMuteTts

    val isTextToSpeechEnabled: Boolean
        get() = snapshot().useTts

    val isShortTextToSpeechMessagesEnabled: Boolean
        get() = snapshot().shortTtsMessages

    /**
     * Returns the package name of the preferred TTS engine,
     * or null to use the system default engine.
     */
    var ttsEngine: String?
        get() {
            val pkg = snapshot().ttsEngine
            if (pkg == null || pkg == TTS_ENGINE_SYSTEM_DEFAULT) {
                return null
            }
            return pkg
        }
        /**
         * Sets the preferred TTS engine by package name,
         * or the system default if pkg is null.
         */
        set(pkg) {
            repository.updateBlocking { prefs ->
                prefs.ttsEngine = if (pkg == null) Optional.empty() else Optional.of(pkg)
            }
        }

    val isTcpForced: Boolean
        get() = snapshot().forceTcp

    val isMuted: Boolean
        get() = snapshot().muted

    val isDeafened: Boolean
        get() = snapshot().deafened

    var isFirstRun: Boolean
        get() = snapshot().firstRun
        set(run) {
            repository.updateBlocking { prefs ->
                prefs.firstRun = run
            }
        }

    // JvmName keeps the Java-identical shouldX() signature (a `should`-prefixed
    // val would otherwise generate getShouldX()). Same pattern on the three below.
    @get:JvmName("shouldLoadExternalImages")
    val shouldLoadExternalImages: Boolean
        get() = snapshot().loadImages

    fun setMutedAndDeafened(muted: Boolean, deafened: Boolean) {
        repository.updateBlocking { prefs ->
            prefs.muted = muted || deafened
            prefs.deafened = deafened
        }
    }

    val framesPerPacket: Int
        get() {
            try {
                return snapshot().audioPerPacket.toInt()
            } catch (e: NumberFormatException) {
                return Constants.DEFAULT_FRAMES_PER_PACKET
            }
        }

    val isHalfDuplex: Boolean
        get() = snapshot().halfDuplex

    val isHandsetMode: Boolean
        get() = snapshot().handsetMode

    var isBluetoothHeadset: Boolean
        get() = snapshot().bluetoothHeadset
        set(enabled) {
            repository.updateBlocking { prefs ->
                prefs.bluetoothHeadset = enabled
            }
        }

    val isPttSoundEnabled: Boolean
        get() = snapshot().pttSound

    val isAdaptiveLevelerEnabled: Boolean
        get() = snapshot().adaptiveLeveler

    @get:JvmName("shouldStayAwake")
    val shouldStayAwake: Boolean
        get() = snapshot().stayAwake

    var defaultCertificateId: Long
        get() = snapshot().certificateId
        set(defaultCertificateId) {
            repository.updateBlocking { prefs ->
                prefs.certificateId = defaultCertificateId
            }
        }

    fun disableCertificate() {
        repository.updateBlocking { prefs ->
            prefs.certificateId = DEFAULT_CERT_ID
        }
    }

    val isUsingCertificate: Boolean
        get() = defaultCertificate >= 0

    /**
     * @return true if the user count should be shown next to channels.
     */
    @get:JvmName("shouldShowUserCount")
    val shouldShowUserCount: Boolean
        get() = snapshot().showUserCount

    @get:JvmName("shouldStartUpInPinnedMode")
    val shouldStartUpInPinnedMode: Boolean
        get() = snapshot().startUpInPinnedMode

    var isOverlayShown: Boolean
        get() = snapshot().overlayShown
        set(shown) {
            repository.updateBlocking { prefs ->
                prefs.overlayShown = shown
            }
        }

    var overlayPlacement: String
        get() = snapshot().overlayPlacement
        set(placement) {
            repository.updateBlocking { prefs ->
                prefs.overlayPlacement = placement
            }
        }

    val isOverlayPinned: Boolean
        get() = isPinnedPlacement(overlayPlacement)

    val overlayGravity: Int
        get() = gravityForPlacement(overlayPlacement)

    /** Atomic overlay-layout triple from a single snapshot (see [snapshot]):
     * layout callers must not mix [isOverlayPinned], [overlayGravity] and
     * [overlayPlacement] from separate snapshots under a concurrent write. */
    internal data class OverlayLayout(val pinned: Boolean, val gravity: Int, val placement: String)

    internal fun overlayLayout(): OverlayLayout {
        val placement = snapshot().overlayPlacement
        return OverlayLayout(isPinnedPlacement(placement), gravityForPlacement(placement), placement)
    }

    fun getOverlayPosX(defaultX: Int): Int {
        return snapshot().overlayPosX ?: defaultX
    }

    fun getOverlayPosY(defaultY: Int): Int {
        return snapshot().overlayPosY ?: defaultY
    }

    fun setOverlayPosition(x: Int, y: Int) {
        repository.updateBlocking { prefs ->
            prefs.overlayPosX = x
            prefs.overlayPosY = y
        }
    }

    var theme: String
        get() = snapshot().theme
        set(theme) {
            repository.updateBlocking { prefs ->
                prefs.theme = theme
            }
        }

    var language: String
        get() = snapshot().language
        set(language) {
            repository.updateBlocking { prefs ->
                prefs.language = language
            }
        }

    val newsShownVersions: Set<String>
        get() = snapshot().newsShownVersions

    fun addNewsShownVersions(versions: List<String?>) {
        // Making a copy; the stored set must not be modified in place.
        val shownVersions = HashSet(snapshot().newsShownVersions)
        val clean = versions.filter { !it.isNullOrEmpty() }
        if (shownVersions.addAll(clean)) {
            val updated: Set<String> = shownVersions
            repository.updateBlocking { prefs ->
                prefs.newsShownVersions = updated
            }
        }
    }

    fun resetNewsShownVersion() {
        repository.updateBlocking { prefs ->
            prefs.newsShownVersions = HashSet()
        }
    }

    /** Listener for settings changes; delivers the legacy PREF_* key strings. */
    fun interface OnSettingChangedListener {
        fun onSettingChanged(key: String?)
    }

    // Pure placement helpers shared by the single-snapshot getters above:
    // no DataStore read, so paired callers stay consistent by construction.
    private fun isPinnedPlacement(placement: String): Boolean =
        OVERLAY_PLACEMENT_TOP_LEFT == placement ||
            OVERLAY_PLACEMENT_TOP_RIGHT == placement ||
            OVERLAY_PLACEMENT_BOTTOM_LEFT == placement ||
            OVERLAY_PLACEMENT_BOTTOM_RIGHT == placement

    private fun gravityForPlacement(placement: String): Int =
        if (OVERLAY_PLACEMENT_TOP_LEFT == placement) {
            Gravity.TOP or Gravity.LEFT
        } else if (OVERLAY_PLACEMENT_TOP_RIGHT == placement) {
            Gravity.TOP or Gravity.RIGHT
        } else if (OVERLAY_PLACEMENT_BOTTOM_LEFT == placement) {
            Gravity.BOTTOM or Gravity.LEFT
        } else if (OVERLAY_PLACEMENT_BOTTOM_RIGHT == placement) {
            Gravity.BOTTOM or Gravity.RIGHT
        } else {
            Gravity.TOP or Gravity.LEFT
        }

    companion object {
        const val PREF_INPUT_METHOD = "audioInputMethod"
        @JvmStatic
        val ARRAY_INPUT_METHODS: Set<String> = HashSet<String>().apply {
            add(ARRAY_INPUT_METHOD_VOICE)
            add(ARRAY_INPUT_METHOD_PTT)
            add(ARRAY_INPUT_METHOD_CONTINUOUS)
        }
        /** Voice activity transmits depending on the amplitude of user input. */
        const val ARRAY_INPUT_METHOD_VOICE = "voiceActivity"
        /** Push to talk transmits on command. */
        const val ARRAY_INPUT_METHOD_PTT = "ptt"
        /** Continuous transmits always. */
        const val ARRAY_INPUT_METHOD_CONTINUOUS = "continuous"

        // NOTE: When changing DEFAULTs, the default value in the corresponding
        // widget in settings_PAGE.xml must also be changed. It doesn't pick this
        // up itself...

        const val PREF_THRESHOLD = "vadThreshold"
        const val DEFAULT_THRESHOLD = 35

        const val PREF_PUSH_KEY = "talkKey"
        const val DEFAULT_PUSH_KEY = -1

        const val PREF_HOT_CORNER_KEY = "hotCorner"
        const val ARRAY_HOT_CORNER_NONE = "none"
        const val ARRAY_HOT_CORNER_TOP_LEFT = "topLeft"
        const val ARRAY_HOT_CORNER_BOTTOM_LEFT = "bottomLeft"
        const val ARRAY_HOT_CORNER_TOP_RIGHT = "topRight"
        const val ARRAY_HOT_CORNER_BOTTOM_RIGHT = "bottomRight"
        const val DEFAULT_HOT_CORNER = ARRAY_HOT_CORNER_NONE

        const val PREF_PUSH_BUTTON_HIDE_KEY = "hidePtt"
        const val DEFAULT_PUSH_BUTTON_HIDE = false

        const val PREF_PTT_TOGGLE = "togglePtt"
        const val DEFAULT_PTT_TOGGLE = false

        const val PREF_INPUT_RATE = "input_quality"
        const val DEFAULT_RATE = "48000"

        const val PREF_INPUT_QUALITY = "input_bitrate"
        const val DEFAULT_INPUT_QUALITY = 40000

        const val PREF_AMPLITUDE_BOOST = "inputVolume"
        const val DEFAULT_AMPLITUDE_BOOST = 100

        const val PREF_CHAT_NOTIFY = "chatNotify"
        const val DEFAULT_CHAT_NOTIFY = true

        const val PREF_ALLOW_TALK_BROADCAST = "allowTalkBroadcast"
        const val DEFAULT_ALLOW_TALK_BROADCAST = true

        const val PREF_NOTIFICATION_STYLE = "notification_style"
        const val NOTIFICATION_STYLE_BIGTEXT = "bigtext"
        const val NOTIFICATION_STYLE_MEDIA = "media"
        const val DEFAULT_NOTIFICATION_STYLE = NOTIFICATION_STYLE_BIGTEXT

        const val PREF_MEDIA_KEY_MUTE = "mediaKeyMute"
        const val DEFAULT_MEDIA_KEY_MUTE = true

        const val PREF_MEDIA_KEY_MUTE_TTS = "mediaKeyMuteTts"
        const val DEFAULT_MEDIA_KEY_MUTE_TTS = true

        const val PREF_USE_TTS = "useTts"
        const val DEFAULT_USE_TTS = true

        /** Package name of the TTS engine to use, or null for the system default. */
        const val PREF_TTS_ENGINE = "ttsEngine"
        /** Preference value identifying the "System default" engine entry. */
        const val TTS_ENGINE_SYSTEM_DEFAULT = ""

        const val PREF_SHORT_TTS_MESSAGES = "shortTtsMessages"
        const val DEFAULT_SHORT_TTS_MESSAGES = true

        const val PREF_THEME = "theme"
        const val DEFAULT_THEME = "forceBlack"
        /** Theme value selecting follow-system night mode (distinct from language). */
        const val DEFAULT_THEME_SYSTEM = "system"
        const val PREF_LANGUAGE = "language"
        const val DEFAULT_LANGUAGE = "system"

        const val PREF_PTT_BUTTON_HEIGHT = "pttButtonHeight"
        const val DEFAULT_PTT_BUTTON_HEIGHT = 50
        const val MIN_PTT_BUTTON_HEIGHT = 40
        const val MAX_PTT_BUTTON_HEIGHT = 400

        /**
         * The DB identifier for the default certificate.
         * @see se.lublin.mumla.db.DatabaseCertificate
         */
        const val PREF_CERT_ID = "certificateId"
        /** No certificate selected (also the DataStore fallback). */
        const val DEFAULT_CERT_ID = -1L

        const val PREF_DEFAULT_USERNAME = "defaultUsername"
        // Pre-existing quirk, inherited: the XML default is locale-dependent
        // (e.g. fr -> "Utilisateur_Mumla") while this const fallback is not.
        // Fleet prefs already migrated keep their stored value either way.
        const val DEFAULT_DEFAULT_USERNAME = "Mumla_User" // funny var name

        const val PREF_FORCE_TCP = "forceTcp"
        const val DEFAULT_FORCE_TCP = false

        const val PREF_MUTED = "muted"
        const val DEFAULT_MUTED = false

        const val PREF_DEAFENED = "deafened"
        const val DEFAULT_DEAFENED = false

        const val PREF_FIRST_RUN = "firstRun"
        const val DEFAULT_FIRST_RUN = true

        const val PREF_LOAD_IMAGES = "load_images"
        const val DEFAULT_LOAD_IMAGES = false

        const val PREF_FRAMES_PER_PACKET = "audio_per_packet"
        @JvmStatic
        val DEFAULT_FRAMES_PER_PACKET: String = Integer.toString(Constants.DEFAULT_FRAMES_PER_PACKET)

        const val PREF_HALF_DUPLEX = "half_duplex"
        const val DEFAULT_HALF_DUPLEX = false

        const val PREF_HANDSET_MODE = "handset_mode"
        const val DEFAULT_HANDSET_MODE = false

        const val PREF_BLUETOOTH_HEADSET = "bluetooth_headset"
        const val DEFAULT_BLUETOOTH_HEADSET = false

        const val PREF_PTT_SOUND = "ptt_sound"
        const val DEFAULT_PTT_SOUND = false

        const val PREF_ADAPTIVE_LEVELER_ENABLED = "adaptive_leveler"
        const val DEFAULT_ADAPTIVE_LEVELER_ENABLED = true

        const val PREF_STAY_AWAKE = "stay_awake"
        const val DEFAULT_STAY_AWAKE = true

        const val PREF_SHOW_USER_COUNT = "show_user_count"
        const val DEFAULT_SHOW_USER_COUNT = false

        const val PREF_START_UP_IN_PINNED_MODE = "startUpInPinnedMode"
        const val DEFAULT_START_UP_IN_PINNED_MODE = false

        const val PREF_OVERLAY_SHOWN = "overlay_shown"
        const val DEFAULT_OVERLAY_SHOWN = false

        const val PREF_OVERLAY_POS_X = "overlay_hud_pos_x"
        const val PREF_OVERLAY_POS_Y = "overlay_hud_pos_y"

        const val PREF_OVERLAY_PLACEMENT = "overlay_placement"
        const val OVERLAY_PLACEMENT_FLOATING = "floating"
        const val OVERLAY_PLACEMENT_TOP_LEFT = "topLeft"
        const val OVERLAY_PLACEMENT_TOP_RIGHT = "topRight"
        const val OVERLAY_PLACEMENT_BOTTOM_LEFT = "bottomLeft"
        const val OVERLAY_PLACEMENT_BOTTOM_RIGHT = "bottomRight"
        const val DEFAULT_OVERLAY_PLACEMENT = OVERLAY_PLACEMENT_FLOATING

        const val PREF_NEWS_SHOWN_VERSIONS = "newsShownVersions"

        /**
         * Returns whether the given key event matches the configured push-to-talk key.
         *
         * <p>An unconfigured key ([DEFAULT_PUSH_KEY], `-1`) and
         * [KeyEvent.KEYCODE_UNKNOWN] (`0`, also the legacy pre-ODD-07 reset value)
         * never match, so unknown or unbound keys cannot trigger transmission (ODD-07).
         */
        @JvmStatic
        fun isPttKeyBound(pttKey: Int, keyCode: Int): Boolean {
            return pttKey > KeyEvent.KEYCODE_UNKNOWN && keyCode == pttKey
        }

        @JvmStatic
        fun getInstance(context: Context): Settings {
            return Settings(AppDataStore.get(context))
        }

        @JvmStatic
        fun createForTesting(store: DataStore<Preferences>): Settings {
            return Settings(store)
        }
    }
}
