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

package se.lublin.humla.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lublin.humla.exception.AudioException

/**
 * Owns all audio-route state and choreography.
 *
 * Slice 4 of the HumlaService decomposition: mechanical relocation of every
 * route field and member behind this interface, with the existing volatile
 * and lock discipline moved atomically onto a private lock. The four
 * choreography copies (sync, funnel, disconnect, destroy) are kept as four
 * divergent methods so the move is diffable; slice 7 unifies them behind
 * a single event funnel.
 *
 * Locking: all route fields are guarded by [lock], never the service
 * monitor. This decouples the chat log and the retry snapshot for free —
 * neither sharing provided a guarantee. All funnel callers remain
 * main-confined; the disconnect path arrives on connection threads and the
 * posted SCO notify hops to [scope] (main).
 */
internal class AudioRouteController(
    private val platform: AudioPlatform,
    private val sco: ScoLink?,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        /** True while the session is synchronized (CONNECTED). */
        val isLive: Boolean

        /** Service connection generation; re-validation identity. */
        val sessionToken: Int

        /** True when a pipeline exists and is initialized. */
        fun hasLivePipeline(): Boolean

        /**
         * Recreates the audio pipeline; may throw AudioException — the
         * caller catches, logs via [onPipelineError], keeps old pipeline.
         */
        @Throws(AudioException::class)
        fun recreatePipeline()

        /** Pipeline failure during a route recreate. */
        fun onPipelineError(message: String?)

        /** Subclass hook, then route-observer fan-out; main thread, hook first. */
        fun onRouteChanged()

        /** Existing onBluetoothScoChanged fan-out, unchanged ordering. */
        fun onScoChanged(active: Boolean)

        /**
         * Teardown notify: subclass hook plus SCO-false fan-out, with no
         * route-observer fan-out. Preserves stopScoWithNotify's exact
         * ordering (hook + sco, never route) on the disconnect/disable path.
         */
        fun onScoTeardown()

        /** SCO bring-up failure; the app layer renders user feedback. */
        fun onBluetoothScoFailed(reason: String?)
    }

    companion object {
        private val TAG = AudioRouteController::class.java.name

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

        /**
         * Route truth table: confirmed SCO forces the voice triple; otherwise a
         * present media sink (BT A2DP / wired headset) selects media, and its
         * absence keeps voice. Public for the JVM truth-table test (Kotlin has
         * no package visibility); mirrors BluetoothScoManager.shouldRetryBringUp.
         */
        @JvmStatic
        fun computeVoiceRoute(scoActive: Boolean, mediaDevicesPresent: Boolean): Boolean {
            return scoActive || !mediaDevicesPresent
        }
    }

    private val lock = Any()

    @Volatile
    private var scoRequested: Boolean = false

    @Volatile
    private var scoRouteActive: Boolean = false

    @Volatile
    private var appliedRoute: AppliedRoute = AppliedRoute.VOICE_HANDSET

    @Volatile
    private var handsetStream: Int = AudioManager.STREAM_MUSIC

    @Volatile
    private var modeHold: ModeHold = ModeHold.Released

    @Volatile
    private var settleAttempts: Int = 0

    @Volatile
    private var debounceJob: Job? = null

    @Volatile
    private var settleJob: Job? = null

    @Volatile
    private var destroyed = false

    private var deviceCallback: AudioDeviceCallback? = null

    fun setScoRequested(requested: Boolean) {
        synchronized(lock) {
            scoRequested = requested
        }
    }

    fun isScoRequested(): Boolean {
        synchronized(lock) {
            return scoRequested
        }
    }

    fun setHandsetStream(stream: Int) {
        synchronized(lock) {
            handsetStream = stream
        }
    }

    val isMediaRouteActive: Boolean
        get() = synchronized(lock) { appliedRoute } == AppliedRoute.MEDIA

    /** Current route decision, pushed into the builder at pipeline creation. */
    fun decision(): AppliedRoute {
        synchronized(lock) {
            return appliedRoute
        }
    }

    /** Handset-derived stream for VOICE_HANDSET; read when pushing the decision. */
    fun handsetStream(): Int {
        synchronized(lock) {
            return handsetStream
        }
    }

    fun isScoActive(): Boolean {
        val link = synchronized(lock) { sco }
        return link != null && link.isActive()
    }

    fun retrySco(): Boolean {
        // Copy to locals before IPC so a torn read is impossible; the copy
        // is not group-atomic versus writers (post-start re-validation +
        // manager idempotence absorb any tearing — that is the real guard).
        val requested: Boolean
        val link: ScoLink?
        synchronized(lock) {
            requested = scoRequested
            link = sco
        }
        val live = host.isLive
        val token = host.sessionToken
        if (requested && live && link != null && link.isIdle()
        ) {
            link.start()
            // Re-validate identity, state, and intent: a disable or a
            // disconnect/reconnect cycle during bring-up must not leave
            // a stray attempt running (stop() never callbacks, silent).
            // Session token replaces the old mConnection reference check:
            // generation bumps on every connect, exactly when mConnection
            // is replaced.
            synchronized(lock) {
                if (host.sessionToken != token || !host.isLive ||
                    !scoRequested
                ) {
                    link.stop()
                    return false
                }
            }
            return true
        }
        return false
    }

    /** Initial media query; called once from onCreate after wiring. */
    fun onInit() {
        val media = queryMediaDevicesPresent()
        synchronized(lock) {
            appliedRoute = if (!media) AppliedRoute.VOICE_HANDSET else AppliedRoute.MEDIA
        }
    }

    fun registerDeviceListener(handler: Handler) {
        try {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>?) {
                    logDeviceTypes("added", added)
                    onEvent(RouteEvent.DevicesChanged(added))
                }

                override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>?) {
                    logDeviceTypes("removed", removed)
                    onEvent(RouteEvent.DevicesChanged(null))
                }
            }
            synchronized(lock) {
                deviceCallback = callback
            }
            platform.registerDeviceCallback(callback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Route device listener failed", e)
            synchronized(lock) {
                deviceCallback = null
            }
        }
    }

    fun unregisterDeviceListener() {
        val callback = synchronized(lock) {
            val current = deviceCallback
            deviceCallback = null
            current
        }
        if (callback != null) {
            try {
                platform.unregisterDeviceCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Route device listener removal failed", e)
            }
        }
    }

    /**
     * Single funnel-event map (slice 7 unification):
     * onConnectionSynchronized -> SessionStarted;
     * onConnectionDisconnected / superseding connect() -> SessionEnded
     * (idempotent under the generation filter — the second delivery is a
     * no-op); onDestroy -> Destroyed; device callbacks -> DevicesChanged;
     * SCO callbacks -> ScoLinkChanged; apply() -> SettingsChanged(reload);
     * settle/debounce expiry -> SettleRecheck.
     */
    fun onEvent(event: RouteEvent) {
        when (event) {
            is RouteEvent.SessionStarted -> onSessionStarted()
            is RouteEvent.SessionEnded -> onSessionEnded()
            is RouteEvent.Destroyed -> onDestroyed()
            is RouteEvent.DevicesChanged -> onDevicesChanged(event.added)
            is RouteEvent.ScoLinkChanged -> onScoLinkChanged(event.active)
            is RouteEvent.SettingsChanged -> recompute("settings changed", event.reload, settle = false, firstCreate = false)
            is RouteEvent.SettleRecheck -> recompute("media settle recheck", forceReload = false, settle = true, firstCreate = false)
        }
    }

    /**
     * Session start through the unified funnel (slice 7): the old sync copy
     * created the pipeline before the foreign-SCO counter; the funnel orders
     * counter-before-pipeline, and firstCreate keeps the unconditional build
     * plus route notify the sync path requires. The service owns state,
     * wake-lock, and onConnected; this owns the route application.
     */
    private fun onSessionStarted() {
        recompute("sync", forceReload = false, settle = false, firstCreate = true)
        updateScoRoute()
    }

    /**
     * Route parts of onConnectionDisconnected. The service owns state,
     * locks, model teardown, and onDisconnected; this owns route teardown.
     */
    private fun onSessionEnded() {
        // Media decision goes stale here (teardown clears SCO only; the next
        // funnel recomputes): refresh from a live query so gap observers see
        // truth (A2DP still attached reads media) instead of a forced value.
        // Stored after the SCO stop below so the AppliedRoute snapshot sees
        // the cleared link; both run synchronously with no interleaving.
        val liveMedia = queryMediaDevicesPresent()

        // Never leak the SCO link across disconnects: the next sync re-arms
        // it via updateScoRoute when still requested.
        stopScoWithNotify()
        synchronized(lock) {
            appliedRoute = if (liveMedia) AppliedRoute.MEDIA else AppliedRoute.VOICE_HANDSET
        }
        releaseVoiceMode()
        // No live call remains for a settle recheck to recover.
        cancelRouteSettle()
        synchronized(lock) {
            settleAttempts = 0
        }
    }

    /** Bare teardown, no notify: observers detach in onDestroy. */
    private fun onDestroyed() {
        destroyed = true
        synchronized(lock) { sco }?.destroy()
        // Bare teardown, no builder touch: the next create pushes a fresh
        // decision, and stale builder state is never consumed in between.
        synchronized(lock) {
            scoRouteActive = false
            appliedRoute = AppliedRoute.VOICE_HANDSET
        }
        releaseVoiceMode()
        unregisterDeviceListener()
        // Unconditional: the funnel arms settle on any route edge, so teardown
        // must clear it too.
        cancelRouteDebounce()
        cancelRouteSettle()
        synchronized(lock) {
            settleAttempts = 0
        }
    }

    /** Synchronous SCO teardown with notifications (explicit disconnect, toggle disable). */
    fun stopScoWithNotify() {
        val link = synchronized(lock) { sco } ?: return
        val wasActive = link.isActive()
        link.stop()
        synchronized(lock) {
            scoRouteActive = false
        }
        if (wasActive) {
            // Posted: teardown callers (disconnect, destroy, mid-call disable)
            // may run off the main thread, but hook and observers are
            // main-thread confined. Posting also orders the event after the
            // synchronous disable-path pipeline reload.
            scope.launch {
                if (destroyed) {
                    return@launch
                }
                if (synchronized(lock) { sco }?.isActive() == true) {
                    // Re-connected since teardown queued: fresh callbacks
                    // own the UI; a stale false must not overwrite them.
                    return@launch
                }
                if (scoRouteActive) {
                    // A newer link confirmed while this teardown was
                    // queued (bring-up won the race after our flag flip):
                    // its own connected-notify carries the truth.
                    return@launch
                }
                host.onScoTeardown()
            }
        }
    }

    fun updateScoRoute() {
        val link = synchronized(lock) { sco } ?: return
        val requested = synchronized(lock) { scoRequested }
        if (requested && host.isLive) {
            link.start()
        } else if (!link.isIdle()) {
            // Skip the platform round-trip when already idle; stop() is safe
            // but needlessly releases and bumps the callback generation.
            link.stop()
        }
    }

    /**
     * Applies a confirmed route to the pipeline, cue hook, and observers in
     * that order. Skips everything when the confirmed SCO state is unchanged.
     * Only the confirmed-flag flip holds the lock; the funnel, IPC, and
     * fan-out below run outside it so a slow HAL never pins teardown.
     */
    private fun onScoLinkChanged(active: Boolean) {
        val reason = if (active) {
            "Bluetooth SCO connected"
        } else {
            "Bluetooth SCO disconnected, falling back to phone audio"
        }
        val unchanged: Boolean
        synchronized(lock) {
            unchanged = active == scoRouteActive
            if (!unchanged) {
                scoRouteActive = active
            }
        }
        if (unchanged) {
            Log.i(TAG, "SCO route unchanged, skipping reload ($reason).")
            return
        }
        recompute(reason, forceReload = false, settle = false, firstCreate = false)
        // Re-validate: a teardown racing this route may have flipped the
        // flag while the funnel ran; its own notify carries the newer state.
        synchronized(lock) {
            if (active != scoRouteActive) {
                return
            }
        }
        host.onScoChanged(active)
    }

    fun onScoFailed(reason: String?) {
        onEvent(RouteEvent.ScoLinkChanged(false))
        // onScoFailed is posted to the main thread; connected and
        // disconnected arrive on main-thread platform callbacks.
        // User-visible feedback is an observer event: the app layer renders
        // the Toast, humla owns no UI.
        host.onBluetoothScoFailed(reason)
    }

    fun onScoConnected() {
        onEvent(RouteEvent.ScoLinkChanged(true))
    }

    fun onScoDisconnected() {
        onEvent(RouteEvent.ScoLinkChanged(false))
    }

    /**
     * Debounced device-listener entry: attach/detach flurries converge into
     * one route application instead of one pipeline recreate per event.
     */
    private fun onDevicesChanged(added: Array<AudioDeviceInfo>?) {
        // Removal is intentionally payload-free: only the add path uses the
        // array (maybeStartRequestedSco); both callbacks converge into one
        // debounced recompute.
        maybeStartRequestedSco(added)
        synchronized(lock) { debounceJob }?.cancel()
        val launched = scope.launch {
            delay(ROUTE_RECOMPUTE_DEBOUNCE_MS)
            recompute("device route changed", forceReload = false, settle = false, firstCreate = false)
        }
        launched.invokeOnCompletion {
            synchronized(lock) {
                if (debounceJob === launched) debounceJob = null
            }
        }
        synchronized(lock) {
            debounceJob = launched
        }
    }

    private fun armRouteSettle() {
        synchronized(lock) { settleJob }?.cancel()
        val launched = scope.launch {
            delay(ROUTE_SETTLE_RECHECK_MS)
            onEvent(RouteEvent.SettleRecheck)
        }
        launched.invokeOnCompletion {
            synchronized(lock) {
                if (settleJob === launched) settleJob = null
            }
        }
        synchronized(lock) {
            settleJob = launched
        }
    }

    private fun cancelRouteSettle() {
        synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
        }
    }

    private fun cancelRouteDebounce() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
        }
    }

    private fun queryMediaDevicesPresent(): Boolean {
        if (!platform.hasManager) return false
        try {
            val outputs = platform.getOutputDevices()
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

    private data class RouteUpdate(
        val voice: Boolean,
        val route: AppliedRoute,
        val changed: Boolean,
    )

    private fun updateRoute(): RouteUpdate {
        val mediaPresent = queryMediaDevicesPresent()
        synchronized(lock) {
            val scoActive = scoRouteActive
            val media = !scoActive && mediaPresent
            val voice = computeVoiceRoute(scoActive, media)
            val next = when {
                scoActive -> AppliedRoute.VOICE_SCO
                voice -> AppliedRoute.VOICE_HANDSET
                else -> AppliedRoute.MEDIA
            }
            val changed = next != appliedRoute
            appliedRoute = next
            return RouteUpdate(voice, next, changed)
        }
    }

    private fun applyVoiceMode(voice: Boolean) {
        if (voice) {
            ensureVoiceMode()
        } else {
            releaseVoiceMode()
        }
    }

    private fun ensureVoiceMode() {
        synchronized(lock) {
            if (!platform.hasManager || modeHold is ModeHold.Claimed) {
                return
            }
            try {
                val mode = platform.mode
                // Externally held (phone call setup or another app): claim
                // nothing and change nothing. Deliberately stays Released so the
                // next voice pass retries the claim — recording a hold here
                // would starve the voice route of IN_COMM after the external
                // holder lets go (no release path re-arms a marker that was
                // never a real hold).
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
                platform.mode = AudioManager.MODE_IN_COMMUNICATION
                modeHold = ModeHold.Claimed(mode)
                Log.i(TAG, "VoIP mode held (was $mode)")
            } catch (e: Exception) {
                Log.w(TAG, "VoIP mode hold failed", e)
            }
        }
    }

    private fun releaseVoiceMode() {
        val hold = synchronized(lock) { modeHold }
        if (!platform.hasManager || hold !is ModeHold.Claimed) {
            return
        }
        synchronized(lock) {
            modeHold = ModeHold.Released
        }
        try {
            if (platform.mode == AudioManager.MODE_IN_COMMUNICATION) {
                platform.mode = hold.savedMode
                Log.i(TAG, "VoIP mode released (restored ${hold.savedMode})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode release failed", e)
        }
    }

    /**
     * Single funnel for every live route change (slice 7 unification): the
     * sync path now runs through this same ordering, which fixes the
     * counter-after-pipeline drift the divergent sync copy had. Recomputes
     * the decision, holds or releases the voice mode around pipeline
     * creation (mode first, foreign-SCO counter before capture, per the HAL
     * coupling requirement), then refreshes cues and observers. Skips the
     * reload when the applied route is unchanged unless forceReload
     * (settings path) or firstCreate (sync path) demands it.
     */
    private fun recompute(reason: String, forceReload: Boolean, settle: Boolean, firstCreate: Boolean) {
        // All funnel callers are main-confined (device recompute, settings,
        // SCO callbacks, posted sync), so no concurrent funnel rewrite is
        // expected; a surprise concurrent rewrite would at worst cause one
        // redundant idempotent reload.
        // Settle edges pass settle=true; every other caller is a fresh edge
        // (settle=false). Fresh edges never cancel outstanding protection
        // here — arming below re-posts when still uncovered.
        val update = updateRoute()
        val voice = update.voice
        // Capture before applyVoiceMode clears it: did WE hold the mode for
        // the episode now ending? Only our own claimed hold implicates us in
        // any system SCO raised during it (see counter method below).
        val wasClaimedHold = synchronized(lock) { modeHold is ModeHold.Claimed }
        if (host.isLive) {
            applyVoiceMode(voice)
            if (!voice) {
                // Media won: clear foreign SCO BEFORE (re)creating the
                // pipeline so no media lifetime starts while HFP is up.
                counterUnrequestedSystemSco(wasClaimedHold)
            }
        } else {
            // Disconnected (or never connected): device attach/detach must
            // not re-acquire global IN_COMM with no pipeline and no call.
            // The next SessionStarted re-holds.
            releaseVoiceMode()
        }
        // Sync always builds (first creation); live edges rebuild only over
        // a live pipeline when forced or moved. A failed sync build leaves
        // no pipeline; later edges do not retry it (preserved behavior).
        val shouldRecreate = host.isLive &&
            (firstCreate || (host.hasLivePipeline() && (forceReload || update.changed)))
        if (shouldRecreate) {
            try {
                host.recreatePipeline()
                Log.i(TAG, "$reason; audio pipeline recreated (" + (if (voice) "voice" else "media") + ").")
            } catch (e: AudioException) {
                Log.e(TAG, "Route-change audio reload failed", e)
                host.onPipelineError(e.message)
            }
        }
        val routeChanged = firstCreate || forceReload || update.changed
        if (routeChanged) {
            host.onRouteChanged()
        }
        // Else: unchanged route — observers query live state on demand and
        // the SCO bit has its own direct emit, so skip the heavy cue rebuild
        // (SoundPool/proximity) on every irrelevant device edge.
        // Voice-by-absence is exactly VOICE_HANDSET (voice, SCO down, no sink).
        val voiceByAbsence = update.route == AppliedRoute.VOICE_HANDSET
        val liveCall = host.isLive
        val attempts = synchronized(lock) { settleAttempts }
        if (voiceByAbsence && liveCall && (!settle || attempts < ROUTE_SETTLE_MAX_RECHECKS - 1)) {
            // Fresh edge (!settle, entering or already-voice) or budgeted
            // settle follow-up: exactly one check queued. Fresh edges reset
            // the budget — each real BT change deserves full cover; settle
            // runs consume it.
            synchronized(lock) {
                if (!settle) {
                    settleAttempts = 0
                } else {
                    settleAttempts += 1
                }
            }
            armRouteSettle()
        } else if (!voiceByAbsence || !liveCall) {
            cancelRouteSettle()
            synchronized(lock) {
                settleAttempts = 0
            }
        }
        // Else (settle budget exhausted): rest without cover until a fresh
        // edge re-arms.
    }

    private fun counterUnrequestedSystemSco(wasClaimedHold: Boolean) {
        if (!platform.hasManager) return
        val requested = synchronized(lock) { scoRequested }
        if (!wasClaimedHold || requested || scoRouteActive) {
            return
        }
        if (!host.isLive) {
            return
        }
        try {
            if (platform.mode == AudioManager.MODE_IN_CALL) {
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
            // device-path selection), legacy stop below on 30.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                platform.clearCommunicationDevice()
            } else {
                // Stop-then-clear, mirroring the manager's own release:
                // stopping first tears down the link, clearing the routing
                // flag second matches the documented start order in reverse.
                try {
                    platform.stopBluetoothSco()
                } catch (ignored: Exception) {
                    Log.w(TAG, "Unrequested SCO stop threw", ignored)
                }
                try {
                    platform.bluetoothScoOn = false
                } catch (ignored: Exception) {
                    Log.w(TAG, "Unrequested SCO routing clear threw", ignored)
                }
            }
            Log.i(TAG, "Cleared unrequested system SCO after media flip")
        } catch (e: Exception) {
            Log.w(TAG, "Unrequested SCO clear failed", e)
        }
    }

    private fun isSystemScoOn(): Boolean {
        if (!platform.hasManager) return false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val info = platform.getCommunicationDevice()
                return info != null && info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            return platform.bluetoothScoOn
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

    private fun maybeStartRequestedSco(added: Array<AudioDeviceInfo>?) {
        // Main-serialized with the toggle path (both main-confined) with
        // volatile reads; unlike binder-originated retrySco this needs no
        // snapshot block, only the post-start re-validation below.
        val link = synchronized(lock) { sco } ?: return
        val requested = synchronized(lock) { scoRequested }
        if (!requested || !host.isLive) {
            return
        }
        if (!link.isIdle()) {
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
        if (!scoInAdded && !(anyBtInAdded && link.isScoDeviceAvailable())) {
            return
        }
        Log.i(TAG, "Bluetooth headset attached with SCO requested; starting bring-up")
        val token = host.sessionToken
        updateScoRoute()
        // A disconnect may have raced us (state still reads CONNECTED
        // until the async teardown flips it): mirror the retry
        // re-validation so a stale start cannot outlive teardown.
        // Session token replaces the old mConnection reference check.
        synchronized(lock) {
            if (host.sessionToken != token || !host.isLive ||
                !scoRequested
            ) {
                link.stop()
            }
        }
    }
}

/**
 * Voice-mode ownership: one atomic snapshot. Claimed means we set IN_COMM
 * and saved the previous mode; Released means we hold nothing and must never
 * restore. A pre-held mode stays Released so release never clears another
 * owner's mode.
 */
internal sealed interface ModeHold {
    data object Released : ModeHold
    data class Claimed(val savedMode: Int) : ModeHold
}

/**
 * Single funnel-event map (slice 7): every lifecycle edge converges into
 * [AudioRouteController.onEvent]. Removal is intentionally payload-free:
 * only the add path uses the array (maybeStartRequestedSco).
 */
internal sealed interface RouteEvent {
    data object SessionStarted : RouteEvent
    /** Route teardown runs even when a reconnect is already scheduled. */
    data object SessionEnded : RouteEvent
    data object Destroyed : RouteEvent
    data class DevicesChanged(val added: Array<AudioDeviceInfo>?) : RouteEvent
    data class ScoLinkChanged(val active: Boolean) : RouteEvent
    data class SettingsChanged(val reload: Boolean) : RouteEvent
    data object SettleRecheck : RouteEvent
}

/**
 * Applied route decision, pushed into the builder at pipeline creation.
 * VOICE_SCO and VOICE_HANDSET both select the voice triple; MEDIA selects
 * the media triple. The handset stream for VOICE_HANDSET travels alongside
 * via handsetStream().
 */
internal enum class AppliedRoute {
    VOICE_SCO,
    VOICE_HANDSET,
    MEDIA,
}
