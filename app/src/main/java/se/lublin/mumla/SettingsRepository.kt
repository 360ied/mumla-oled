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

package se.lublin.mumla

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Optional

/**
 * Typed view over the app's Preferences DataStore (migration slice 2).
 *
 * Key space mirrors the legacy `Settings` SharedPreferences blob one-for-one
 * (41 keys; derived accessors share their getter's key) so fleet settings
 * survive via `SharedPreferencesMigration` with no silent resets. Read the
 * per-key defaults table in docs/migration/slices.md before touching a
 * default — the matching `settings_*.xml` widget default must change too.
 *
 * Threading (interim): the legacy surface is synchronous and main-thread, so
 * reads/writes block via `runBlocking(Dispatchers.IO)` on DataStore's
 * in-memory cache. Listener delivery runs on `Dispatchers.Main.immediate`
 * (same thread as legacy `apply()` delivery); the collector scope starts only
 * when the first listener registers, so plain-JVM tests (no Main dispatcher)
 * never touch it.
 */
data class PrefSnapshot(
    val audioInputMethod: String,
    val vadThreshold: Int,
    val talkKey: Int,
    val hotCorner: String,
    val hidePtt: Boolean,
    val togglePtt: Boolean,
    val inputQuality: String,
    val inputBitrate: Int,
    val inputVolume: Int,
    val chatNotify: Boolean,
    val allowTalkBroadcast: Boolean,
    val notificationStyle: String,
    val mediaKeyMute: Boolean,
    val mediaKeyMuteTts: Boolean,
    val useTts: Boolean,
    val ttsEngine: String?,
    val shortTtsMessages: Boolean,
    val theme: String,
    val language: String,
    val pttButtonHeight: Int,
    val certificateId: Long,
    val defaultUsername: String,
    val forceTcp: Boolean,
    val muted: Boolean,
    val deafened: Boolean,
    val firstRun: Boolean,
    val loadImages: Boolean,
    val audioPerPacket: String,
    val halfDuplex: Boolean,
    val handsetMode: Boolean,
    val bluetoothHeadset: Boolean,
    val pttSound: Boolean,
    val adaptiveLeveler: Boolean,
    val stayAwake: Boolean,
    val showUserCount: Boolean,
    val startUpInPinnedMode: Boolean,
    val overlayShown: Boolean,
    val overlayPlacement: String,
    val overlayPosX: Int?,
    val overlayPosY: Int?,
    val newsShownVersions: Set<String>,
) {
    /** Legacy PREF_* keys whose value differs from [other] (listener bridge). */
    fun changedKeysSince(other: PrefSnapshot): List<String> {
        val out = ArrayList<String>()
        if (audioInputMethod != other.audioInputMethod) out.add(Settings.PREF_INPUT_METHOD)
        if (vadThreshold != other.vadThreshold) out.add(Settings.PREF_THRESHOLD)
        if (talkKey != other.talkKey) out.add(Settings.PREF_PUSH_KEY)
        if (hotCorner != other.hotCorner) out.add(Settings.PREF_HOT_CORNER_KEY)
        if (hidePtt != other.hidePtt) out.add(Settings.PREF_PUSH_BUTTON_HIDE_KEY)
        if (togglePtt != other.togglePtt) out.add(Settings.PREF_PTT_TOGGLE)
        if (inputQuality != other.inputQuality) out.add(Settings.PREF_INPUT_RATE)
        if (inputBitrate != other.inputBitrate) out.add(Settings.PREF_INPUT_QUALITY)
        if (inputVolume != other.inputVolume) out.add(Settings.PREF_AMPLITUDE_BOOST)
        if (chatNotify != other.chatNotify) out.add(Settings.PREF_CHAT_NOTIFY)
        if (allowTalkBroadcast != other.allowTalkBroadcast) out.add(Settings.PREF_ALLOW_TALK_BROADCAST)
        if (notificationStyle != other.notificationStyle) out.add(Settings.PREF_NOTIFICATION_STYLE)
        if (mediaKeyMute != other.mediaKeyMute) out.add(Settings.PREF_MEDIA_KEY_MUTE)
        if (mediaKeyMuteTts != other.mediaKeyMuteTts) out.add(Settings.PREF_MEDIA_KEY_MUTE_TTS)
        if (useTts != other.useTts) out.add(Settings.PREF_USE_TTS)
        if (ttsEngine != other.ttsEngine) out.add(Settings.PREF_TTS_ENGINE)
        if (shortTtsMessages != other.shortTtsMessages) out.add(Settings.PREF_SHORT_TTS_MESSAGES)
        if (theme != other.theme) out.add(Settings.PREF_THEME)
        if (language != other.language) out.add(Settings.PREF_LANGUAGE)
        if (pttButtonHeight != other.pttButtonHeight) out.add(Settings.PREF_PTT_BUTTON_HEIGHT)
        if (certificateId != other.certificateId) out.add(Settings.PREF_CERT_ID)
        if (defaultUsername != other.defaultUsername) out.add(Settings.PREF_DEFAULT_USERNAME)
        if (forceTcp != other.forceTcp) out.add(Settings.PREF_FORCE_TCP)
        if (muted != other.muted) out.add(Settings.PREF_MUTED)
        if (deafened != other.deafened) out.add(Settings.PREF_DEAFENED)
        if (firstRun != other.firstRun) out.add(Settings.PREF_FIRST_RUN)
        if (loadImages != other.loadImages) out.add(Settings.PREF_LOAD_IMAGES)
        if (audioPerPacket != other.audioPerPacket) out.add(Settings.PREF_FRAMES_PER_PACKET)
        if (halfDuplex != other.halfDuplex) out.add(Settings.PREF_HALF_DUPLEX)
        if (handsetMode != other.handsetMode) out.add(Settings.PREF_HANDSET_MODE)
        if (bluetoothHeadset != other.bluetoothHeadset) out.add(Settings.PREF_BLUETOOTH_HEADSET)
        if (pttSound != other.pttSound) out.add(Settings.PREF_PTT_SOUND)
        if (adaptiveLeveler != other.adaptiveLeveler) out.add(Settings.PREF_ADAPTIVE_LEVELER_ENABLED)
        if (stayAwake != other.stayAwake) out.add(Settings.PREF_STAY_AWAKE)
        if (showUserCount != other.showUserCount) out.add(Settings.PREF_SHOW_USER_COUNT)
        if (startUpInPinnedMode != other.startUpInPinnedMode) out.add(Settings.PREF_START_UP_IN_PINNED_MODE)
        if (overlayShown != other.overlayShown) out.add(Settings.PREF_OVERLAY_SHOWN)
        if (overlayPlacement != other.overlayPlacement) out.add(Settings.PREF_OVERLAY_PLACEMENT)
        if (overlayPosX != other.overlayPosX) out.add(Settings.PREF_OVERLAY_POS_X)
        if (overlayPosY != other.overlayPosY) out.add(Settings.PREF_OVERLAY_POS_Y)
        if (newsShownVersions != other.newsShownVersions) out.add(Settings.PREF_NEWS_SHOWN_VERSIONS)
        return out
    }
}

/**
 * Pending writes: every field defaults to "unchanged" (`null`). Writeback is
 * explicit per key in [SettingsRepository.update]. `ttsEngine` is tri-state —
 * `null` means unchanged, `Optional.empty()` means system-default (remove the
 * key), present means set — mirroring `Settings.setTtsEngine`.
 */
class MutablePrefs {
    var audioInputMethod: String? = null
    var vadThreshold: Int? = null
    var talkKey: Int? = null
    var hotCorner: String? = null
    var hidePtt: Boolean? = null
    var togglePtt: Boolean? = null
    var inputQuality: String? = null
    var inputBitrate: Int? = null
    var inputVolume: Int? = null
    var chatNotify: Boolean? = null
    var allowTalkBroadcast: Boolean? = null
    var notificationStyle: String? = null
    var mediaKeyMute: Boolean? = null
    var mediaKeyMuteTts: Boolean? = null
    var useTts: Boolean? = null
    var ttsEngine: Optional<String>? = null
    var shortTtsMessages: Boolean? = null
    var theme: String? = null
    var language: String? = null
    var pttButtonHeight: Int? = null
    var certificateId: Long? = null
    var defaultUsername: String? = null
    var forceTcp: Boolean? = null
    var muted: Boolean? = null
    var deafened: Boolean? = null
    var firstRun: Boolean? = null
    var loadImages: Boolean? = null
    var audioPerPacket: String? = null
    var halfDuplex: Boolean? = null
    var handsetMode: Boolean? = null
    var bluetoothHeadset: Boolean? = null
    var pttSound: Boolean? = null
    var adaptiveLeveler: Boolean? = null
    var stayAwake: Boolean? = null
    var showUserCount: Boolean? = null
    var startUpInPinnedMode: Boolean? = null
    var overlayShown: Boolean? = null
    var overlayPlacement: String? = null
    var overlayPosX: Int? = null
    var overlayPosY: Int? = null
    var newsShownVersions: Set<String>? = null
}

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    // Java interop: java.util.function.Consumer (void methods) rather than Kotlin
    // function types or fun interfaces (Unit return) so Java lambdas and method
    // references compile without Unit adapters.
    // SCREAMING_SNAKE by convention exception: these mirror the legacy
    // PREF_* key-table style (and SettingsRepository.Keys predates the
    // Kotlin lowerCamel norm); renaming would churn every call site for no
    // behavior gain. New keys should still prefer lowerCamel.
    private object Keys {
        val AUDIO_INPUT_METHOD = stringPreferencesKey("audioInputMethod")
        val VAD_THRESHOLD = intPreferencesKey("vadThreshold")
        val TALK_KEY = intPreferencesKey("talkKey")
        val HOT_CORNER = stringPreferencesKey("hotCorner")
        val HIDE_PTT = booleanPreferencesKey("hidePtt")
        val TOGGLE_PTT = booleanPreferencesKey("togglePtt")
        val INPUT_QUALITY = stringPreferencesKey("input_quality")
        val INPUT_BITRATE = intPreferencesKey("input_bitrate")
        val INPUT_VOLUME = intPreferencesKey("inputVolume")
        val CHAT_NOTIFY = booleanPreferencesKey("chatNotify")
        val ALLOW_TALK_BROADCAST = booleanPreferencesKey("allowTalkBroadcast")
        val NOTIFICATION_STYLE = stringPreferencesKey("notification_style")
        val MEDIA_KEY_MUTE = booleanPreferencesKey("mediaKeyMute")
        val MEDIA_KEY_MUTE_TTS = booleanPreferencesKey("mediaKeyMuteTts")
        val USE_TTS = booleanPreferencesKey("useTts")
        val TTS_ENGINE = stringPreferencesKey("ttsEngine")
        val SHORT_TTS_MESSAGES = booleanPreferencesKey("shortTtsMessages")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val PTT_BUTTON_HEIGHT = intPreferencesKey("pttButtonHeight")
        val CERTIFICATE_ID = longPreferencesKey("certificateId")
        val DEFAULT_USERNAME = stringPreferencesKey("defaultUsername")
        val FORCE_TCP = booleanPreferencesKey("forceTcp")
        val MUTED = booleanPreferencesKey("muted")
        val DEAFENED = booleanPreferencesKey("deafened")
        val FIRST_RUN = booleanPreferencesKey("firstRun")
        val LOAD_IMAGES = booleanPreferencesKey("load_images")
        val AUDIO_PER_PACKET = stringPreferencesKey("audio_per_packet")
        val HALF_DUPLEX = booleanPreferencesKey("half_duplex")
        val HANDSET_MODE = booleanPreferencesKey("handset_mode")
        val BLUETOOTH_HEADSET = booleanPreferencesKey("bluetooth_headset")
        val PTT_SOUND = booleanPreferencesKey("ptt_sound")
        val ADAPTIVE_LEVELER = booleanPreferencesKey("adaptive_leveler")
        val STAY_AWAKE = booleanPreferencesKey("stay_awake")
        val SHOW_USER_COUNT = booleanPreferencesKey("show_user_count")
        val START_UP_IN_PINNED_MODE = booleanPreferencesKey("startUpInPinnedMode")
        val OVERLAY_SHOWN = booleanPreferencesKey("overlay_shown")
        val OVERLAY_PLACEMENT = stringPreferencesKey("overlay_placement")
        val OVERLAY_POS_X = intPreferencesKey("overlay_hud_pos_x")
        val OVERLAY_POS_Y = intPreferencesKey("overlay_hud_pos_y")
        val NEWS_SHOWN_VERSIONS = stringSetPreferencesKey("newsShownVersions")
    }

    val snapshot: Flow<PrefSnapshot> =
        store.data.map { it.toSnapshot() }.distinctUntilChanged()

    fun snapshotBlocking(): PrefSnapshot =
        runBlocking(Dispatchers.IO) {
            store.data.map { it.toSnapshot() }.first()
        }

    suspend fun update(transform: MutablePrefs.() -> Unit) {
        doUpdate(MutablePrefs().apply(transform))
    }

    fun updateBlocking(update: java.util.function.Consumer<MutablePrefs>) =
        runBlocking(Dispatchers.IO) {
            val pending = MutablePrefs()
            update.accept(pending)
            doUpdate(pending)
        }

    private suspend fun doUpdate(pending: MutablePrefs) {
        store.edit { prefs -> pending.writeback(prefs) }
    }

    // Legacy listener bridge: diff consecutive snapshots per key and fire the
    // legacy PREF_* key strings, preserving MumlaService/MumlaApplication
    // dispatch with zero service changes.
    private val listeners = mutableSetOf<java.util.function.Consumer<String>>()
    private var collectScope: CoroutineScope? = null

    fun registerListener(listener: java.util.function.Consumer<String>) {
        val scopeToStart: CoroutineScope? = synchronized(this) {
            listeners.add(listener)
            if (collectScope == null) {
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { collectScope = it }
            } else {
                null
            }
        }
        // Baseline captured outside the lock: snapshotBlocking() does a
        // runBlocking(Dispatchers.IO) disk/migration read that must never
        // run while holding the monitor (all callers register on Main).
        // A write landing between registration and collector start is still
        // diffed: the collector's own first emission is compared against
        // this baseline instead of being swallowed.
        if (scopeToStart != null) {
            var previous: PrefSnapshot? = snapshotBlocking()
            scopeToStart.launch {
                snapshot.collect { current ->
                    val prev = previous
                    previous = current
                    if (prev != null) {
                        for (key in current.changedKeysSince(prev)) {
                            fire(key)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun unregisterListener(listener: java.util.function.Consumer<String>) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            collectScope?.cancel()
            collectScope = null
        }
    }

    private fun fire(key: String) {
        val targets: List<java.util.function.Consumer<String>>
        synchronized(this) { targets = listeners.toList() }
        for (listener in targets) {
            listener.accept(key)
        }
    }

    companion object {
        internal fun Preferences.toSnapshot(): PrefSnapshot = PrefSnapshot(
            audioInputMethod = this[Keys.AUDIO_INPUT_METHOD] ?: Settings.ARRAY_INPUT_METHOD_VOICE,
            vadThreshold = this[Keys.VAD_THRESHOLD] ?: Settings.DEFAULT_THRESHOLD,
            talkKey = this[Keys.TALK_KEY] ?: Settings.DEFAULT_PUSH_KEY,
            hotCorner = this[Keys.HOT_CORNER] ?: Settings.DEFAULT_HOT_CORNER,
            hidePtt = this[Keys.HIDE_PTT] ?: Settings.DEFAULT_PUSH_BUTTON_HIDE,
            togglePtt = this[Keys.TOGGLE_PTT] ?: Settings.DEFAULT_PTT_TOGGLE,
            inputQuality = this[Keys.INPUT_QUALITY] ?: Settings.DEFAULT_RATE,
            inputBitrate = this[Keys.INPUT_BITRATE] ?: Settings.DEFAULT_INPUT_QUALITY,
            inputVolume = this[Keys.INPUT_VOLUME] ?: Settings.DEFAULT_AMPLITUDE_BOOST,
            chatNotify = this[Keys.CHAT_NOTIFY] ?: Settings.DEFAULT_CHAT_NOTIFY,
            allowTalkBroadcast = this[Keys.ALLOW_TALK_BROADCAST] ?: Settings.DEFAULT_ALLOW_TALK_BROADCAST,
            notificationStyle = this[Keys.NOTIFICATION_STYLE] ?: Settings.DEFAULT_NOTIFICATION_STYLE,
            mediaKeyMute = this[Keys.MEDIA_KEY_MUTE] ?: Settings.DEFAULT_MEDIA_KEY_MUTE,
            mediaKeyMuteTts = this[Keys.MEDIA_KEY_MUTE_TTS] ?: Settings.DEFAULT_MEDIA_KEY_MUTE_TTS,
            useTts = this[Keys.USE_TTS] ?: Settings.DEFAULT_USE_TTS,
            // Normalize "" to null: the getter treats both as system-default,
            // so the snapshot must too or ""↔absent convergence fires a
            // spurious diff (behavior-neutral initTts rebind).
            ttsEngine = this[Keys.TTS_ENGINE]?.ifEmpty { null },
            shortTtsMessages = this[Keys.SHORT_TTS_MESSAGES] ?: Settings.DEFAULT_SHORT_TTS_MESSAGES,
            theme = this[Keys.THEME] ?: Settings.DEFAULT_THEME,
            language = this[Keys.LANGUAGE] ?: Settings.DEFAULT_LANGUAGE,
            pttButtonHeight = this[Keys.PTT_BUTTON_HEIGHT] ?: Settings.DEFAULT_PTT_BUTTON_HEIGHT,
            certificateId = this[Keys.CERTIFICATE_ID] ?: Settings.DEFAULT_CERT_ID,
            defaultUsername = this[Keys.DEFAULT_USERNAME] ?: Settings.DEFAULT_DEFAULT_USERNAME,
            forceTcp = this[Keys.FORCE_TCP] ?: Settings.DEFAULT_FORCE_TCP,
            muted = this[Keys.MUTED] ?: Settings.DEFAULT_MUTED,
            deafened = this[Keys.DEAFENED] ?: Settings.DEFAULT_DEAFENED,
            firstRun = this[Keys.FIRST_RUN] ?: Settings.DEFAULT_FIRST_RUN,
            loadImages = this[Keys.LOAD_IMAGES] ?: Settings.DEFAULT_LOAD_IMAGES,
            audioPerPacket = this[Keys.AUDIO_PER_PACKET] ?: Settings.DEFAULT_FRAMES_PER_PACKET,
            halfDuplex = this[Keys.HALF_DUPLEX] ?: Settings.DEFAULT_HALF_DUPLEX,
            handsetMode = this[Keys.HANDSET_MODE] ?: Settings.DEFAULT_HANDSET_MODE,
            bluetoothHeadset = this[Keys.BLUETOOTH_HEADSET] ?: Settings.DEFAULT_BLUETOOTH_HEADSET,
            pttSound = this[Keys.PTT_SOUND] ?: Settings.DEFAULT_PTT_SOUND,
            adaptiveLeveler = this[Keys.ADAPTIVE_LEVELER] ?: Settings.DEFAULT_ADAPTIVE_LEVELER_ENABLED,
            stayAwake = this[Keys.STAY_AWAKE] ?: Settings.DEFAULT_STAY_AWAKE,
            showUserCount = this[Keys.SHOW_USER_COUNT] ?: Settings.DEFAULT_SHOW_USER_COUNT,
            startUpInPinnedMode = this[Keys.START_UP_IN_PINNED_MODE] ?: Settings.DEFAULT_START_UP_IN_PINNED_MODE,
            overlayShown = this[Keys.OVERLAY_SHOWN] ?: Settings.DEFAULT_OVERLAY_SHOWN,
            overlayPlacement = this[Keys.OVERLAY_PLACEMENT] ?: Settings.DEFAULT_OVERLAY_PLACEMENT,
            overlayPosX = this[Keys.OVERLAY_POS_X],
            overlayPosY = this[Keys.OVERLAY_POS_Y],
            newsShownVersions = this[Keys.NEWS_SHOWN_VERSIONS] ?: emptySet(),
        )

        private fun MutablePrefs.writeback(prefs: androidx.datastore.preferences.core.MutablePreferences) {
            audioInputMethod?.let { prefs[Keys.AUDIO_INPUT_METHOD] = it }
            vadThreshold?.let { prefs[Keys.VAD_THRESHOLD] = it }
            talkKey?.let { prefs[Keys.TALK_KEY] = it }
            hotCorner?.let { prefs[Keys.HOT_CORNER] = it }
            hidePtt?.let { prefs[Keys.HIDE_PTT] = it }
            togglePtt?.let { prefs[Keys.TOGGLE_PTT] = it }
            inputQuality?.let { prefs[Keys.INPUT_QUALITY] = it }
            inputBitrate?.let { prefs[Keys.INPUT_BITRATE] = it }
            inputVolume?.let { prefs[Keys.INPUT_VOLUME] = it }
            chatNotify?.let { prefs[Keys.CHAT_NOTIFY] = it }
            allowTalkBroadcast?.let { prefs[Keys.ALLOW_TALK_BROADCAST] = it }
            notificationStyle?.let { prefs[Keys.NOTIFICATION_STYLE] = it }
            mediaKeyMute?.let { prefs[Keys.MEDIA_KEY_MUTE] = it }
            mediaKeyMuteTts?.let { prefs[Keys.MEDIA_KEY_MUTE_TTS] = it }
            useTts?.let { prefs[Keys.USE_TTS] = it }
            ttsEngine?.let { opt ->
                if (opt.isPresent) prefs[Keys.TTS_ENGINE] = opt.get()
                else prefs.remove(Keys.TTS_ENGINE)
            }
            shortTtsMessages?.let { prefs[Keys.SHORT_TTS_MESSAGES] = it }
            theme?.let { prefs[Keys.THEME] = it }
            language?.let { prefs[Keys.LANGUAGE] = it }
            pttButtonHeight?.let { prefs[Keys.PTT_BUTTON_HEIGHT] = it }
            certificateId?.let { prefs[Keys.CERTIFICATE_ID] = it }
            defaultUsername?.let { prefs[Keys.DEFAULT_USERNAME] = it }
            forceTcp?.let { prefs[Keys.FORCE_TCP] = it }
            muted?.let { prefs[Keys.MUTED] = it }
            deafened?.let { prefs[Keys.DEAFENED] = it }
            firstRun?.let { prefs[Keys.FIRST_RUN] = it }
            loadImages?.let { prefs[Keys.LOAD_IMAGES] = it }
            audioPerPacket?.let { prefs[Keys.AUDIO_PER_PACKET] = it }
            halfDuplex?.let { prefs[Keys.HALF_DUPLEX] = it }
            handsetMode?.let { prefs[Keys.HANDSET_MODE] = it }
            bluetoothHeadset?.let { prefs[Keys.BLUETOOTH_HEADSET] = it }
            pttSound?.let { prefs[Keys.PTT_SOUND] = it }
            adaptiveLeveler?.let { prefs[Keys.ADAPTIVE_LEVELER] = it }
            stayAwake?.let { prefs[Keys.STAY_AWAKE] = it }
            showUserCount?.let { prefs[Keys.SHOW_USER_COUNT] = it }
            startUpInPinnedMode?.let { prefs[Keys.START_UP_IN_PINNED_MODE] = it }
            overlayShown?.let { prefs[Keys.OVERLAY_SHOWN] = it }
            overlayPlacement?.let { prefs[Keys.OVERLAY_PLACEMENT] = it }
            overlayPosX?.let { prefs[Keys.OVERLAY_POS_X] = it }
            overlayPosY?.let { prefs[Keys.OVERLAY_POS_Y] = it }
            newsShownVersions?.let { prefs[Keys.NEWS_SHOWN_VERSIONS] = it }
        }
    }
}
