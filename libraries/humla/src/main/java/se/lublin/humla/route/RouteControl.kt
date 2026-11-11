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

package se.lublin.humla.route

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import se.lublin.humla.audio.AudioPlatform
import se.lublin.humla.audio.ScoLink
import se.lublin.humla.client.RouteLifecycle
import se.lublin.humla.client.checkNotMainThread

/**
 * Target route controller. Owns a HandlerThread named route, publishes
 * RouteState and events, and keeps a private route-session counter for
 * stale-event checks. See docs/tech-debt-whales/target-architecture/route.md.
 *
 * All funnel work runs on the route dispatcher. Submit is non-blocking.
 * Voice follows state; route never waits for voice.
 */
internal class RouteControl(
    private val platform: AudioPlatform,
    private val sco: ScoLink?,
    prefs: Flow<RoutePreferences>,
    private val scope: CoroutineScope,
    private val deviceHandler: Handler,
    private val ownedThread: HandlerThread? = null,
) : RouteLifecycle {
    companion object {
        internal const val TAG = "RouteControl"

        @VisibleForTesting
        internal const val ROUTE_SETTLE_RECHECK_MS = 3000L

        @VisibleForTesting
        internal const val ROUTE_SETTLE_MAX_RECHECKS = 2

        @VisibleForTesting
        internal const val ROUTE_RECOMPUTE_DEBOUNCE_MS = 300L

        @JvmStatic
        fun computeVoiceRoute(scoActive: Boolean, mediaDevicesPresent: Boolean): Boolean {
            return scoActive || !mediaDevicesPresent
        }

        /**
         * Production factory: creates the owned route thread and its handler,
         * then builds the controller around them. Tests use the primary
         * constructor directly with a TestScope and a mock handler.
         */
        fun create(
            platform: AudioPlatform,
            sco: ScoLink?,
            prefs: Flow<RoutePreferences>,
            appScope: CoroutineScope,
        ): RouteControl {
            val thread = HandlerThread("route")
            thread.start()
            val handler = Handler(thread.looper)
            val executor = java.util.concurrent.Executor { r -> handler.post(r) }
            val dispatcher = executor.asCoroutineDispatcher()
            val parentJob = appScope.coroutineContext[Job]
            val routeScope = CoroutineScope(SupervisorJob(parentJob) + dispatcher)
            return RouteControl(platform, sco, prefs, routeScope, handler, thread)
        }
    }

    private val _state: MutableStateFlow<RouteState> = MutableStateFlow(RouteState.Idle)
    val state: StateFlow<RouteState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RouteEvent> = _events.asSharedFlow()

    private val routeSessionCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var hasSession = false

    private val scoRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private var scoRouteActive: Boolean = false
    private val appliedRoute = java.util.concurrent.atomic.AtomicReference(AppliedRoute.VOICE_HANDSET)
    private val handsetStream = java.util.concurrent.atomic.AtomicInteger(AudioManager.STREAM_MUSIC)
    private var modeHold: ModeHold = ModeHold.Released

    private var settleAttempts: Int = 0
    private var debounceJob: Job? = null
    private var settleJob: Job? = null

    private val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var deviceCallback: AudioDeviceCallback? = null

    private val prefsFlow: Flow<RoutePreferences> = prefs

    internal fun shouldDropStaleEvent(captured: Int): Boolean {
        return destroyed.get() || captured != routeSessionCounter.get()
    }

    fun submit(command: RouteCommand) {
        val captured = routeSessionCounter.get()
        scope.launch {
            if (shouldDropStaleEvent(captured) && command !is RouteCommand.SessionStarted) {
                return@launch
            }
            handleCommand(command)
        }
    }

    override fun sessionStarted() {
        submit(RouteCommand.SessionStarted)
    }

    override fun sessionEnded() {
        submit(RouteCommand.SessionEnded)
    }

    fun retrySco() {
        submit(RouteCommand.RetrySco)
    }

    private fun handleCommand(command: RouteCommand) {
        when (command) {
            is RouteCommand.SessionStarted -> onSessionStarted()
            is RouteCommand.SessionEnded -> onSessionEnded()
            is RouteCommand.RetrySco -> onRetrySco()
        }
    }

    fun start() {
        scope.launch {
            registerDeviceListener()
            try {
                prefsFlow.collect { p ->
                    onPrefsChanged(p.bluetoothSco, p.handsetStream)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Route prefs collection ended", e)
            }
        }
    }

    fun close(): Job {
        // NonCancellable: Service.onDestroy cancels the parent scope right
        // after this call; the teardown must still run to release SCO/mode.
        val teardown = scope.launch(NonCancellable) { onDestroyed() }
        teardown.invokeOnCompletion {
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
            try {
                ownedThread?.quitSafely()
            } catch (_: Exception) {
            }
        }
        return teardown
    }

    fun onInit() {
        scope.launch {
            val media = queryMediaDevicesPresent()
            appliedRoute.set(if (!media) AppliedRoute.VOICE_HANDSET else AppliedRoute.MEDIA)
        }
    }

    private fun registerDeviceListener() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>?) {
                onDevicesChanged(added, forRemoval = false)
            }

            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>?) {
                onDevicesChanged(removed, forRemoval = true)
            }
        }
        deviceCallback = callback
        try {
            platform.registerDeviceCallback(callback, deviceHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Route device listener registration failed", e)
        }
    }

    private fun unregisterDeviceListener() {
        val callback = deviceCallback
        deviceCallback = null
        if (callback != null) {
            try {
                platform.unregisterDeviceCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Route device listener removal failed", e)
            }
        }
    }

    fun onScoConnected() {
        val captured = routeSessionCounter.get()
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            onScoLinkChanged(true)
        }
    }

    fun onScoDisconnected() {
        val captured = routeSessionCounter.get()
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            onScoLinkChanged(false)
        }
    }

    fun onScoFailed(reason: String?) {
        val captured = routeSessionCounter.get()
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            handleScoFailed(reason)
        }
    }

    private fun onSessionStarted() {
        checkNotMainThread()
        routeSessionCounter.incrementAndGet()
        hasSession = false
        scoRouteActive = false
        settleAttempts = 0
        // Spec Idle->Settling: publish the pending target first so observers
        // see the settle; recompute below resolves to Ready/Fallback.
        val mediaPresent = queryMediaDevicesPresent()
        val target = when {
            scoRequested.get() -> AppliedRoute.VOICE_SCO
            !mediaPresent -> AppliedRoute.VOICE_HANDSET
            else -> AppliedRoute.MEDIA
        }
        _state.value = RouteState.Settling(target)
        hasSession = true
        recompute("sync", forceReload = false, isRecheck = false, firstCreate = true)
        updateScoRoute()
    }

    private fun onSessionEnded() {
        checkNotMainThread()
        routeSessionCounter.incrementAndGet()
        hasSession = false
        cancelRouteDebounce()
        cancelRouteSettle()
        settleAttempts = 0
        stopScoAndClearFlag()
        val liveMedia = queryMediaDevicesPresent()
        appliedRoute.set(if (liveMedia) AppliedRoute.MEDIA else AppliedRoute.VOICE_HANDSET)
        releaseVoiceMode()
        _state.value = RouteState.Idle
    }

    private fun onDestroyed() {
        destroyed.set(true)
        cancelRouteDebounce()
        cancelRouteSettle()
        unregisterDeviceListener()
        try {
            sco?.stop()
        } catch (_: Exception) {
        }
        try {
            sco?.destroy()
        } catch (_: Exception) {
        }
        releaseVoiceMode()
        _state.value = RouteState.Idle
    }

    private fun onRetrySco() {
        checkNotMainThread()
        val link = sco ?: return
        if (!scoRequested.get() || !hasSession) return
        try {
            if (!link.isIdle()) return
        } catch (e: Exception) {
            Log.w(TAG, "SCO retry idle check failed", e)
            return
        }
        try {
            link.start()
        } catch (e: Exception) {
            Log.w(TAG, "SCO retry start failed", e)
        }
    }

    fun setScoRequested(requested: Boolean) {
        val stream = handsetStream.get()
        scope.launch { onPrefsChanged(requested, stream) }
    }

    fun setHandsetStream(stream: Int) {
        val requested = scoRequested.get()
        scope.launch { onPrefsChanged(requested, stream) }
    }

    private fun onPrefsChanged(requested: Boolean, stream: Int) {
        val changed = (requested != scoRequested.get()) || (stream != handsetStream.get())
        scoRequested.set(requested)
        handsetStream.set(stream)
        if (!hasSession) return
        if (changed) {
            recompute("settings changed", forceReload = true, isRecheck = false, firstCreate = false)
            updateScoRoute()
        }
    }

    private fun onDevicesChanged(added: Array<AudioDeviceInfo>?, forRemoval: Boolean = false) {
        val captured = routeSessionCounter.get()
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            if (!hasSession) return@launch
            // Only arrivals may trigger SCO bring-up; removals just re-decide.
            if (!forRemoval) maybeStartRequestedSco(added)
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(ROUTE_RECOMPUTE_DEBOUNCE_MS)
                if (shouldDropStaleEvent(captured)) return@launch
                if (!hasSession) return@launch
                recompute("devices changed", forceReload = false, isRecheck = false, firstCreate = false)
            }
        }
    }

    private fun updateScoRoute() {
        checkNotMainThread()
        val link = sco ?: return
        if (destroyed.get()) return
        if (scoRequested.get() && hasSession) {
            try {
                link.start()
            } catch (e: Exception) {
                Log.w(TAG, "SCO start failed", e)
            }
        } else {
            try {
                if (!link.isIdle()) link.stop()
            } catch (e: Exception) {
                Log.w(TAG, "SCO stop failed", e)
            }
        }
    }

    private fun stopScoAndClearFlag() {
        checkNotMainThread()
        val link = sco ?: return
        try {
            link.stop()
        } catch (e: Exception) {
            Log.w(TAG, "SCO stop failed", e)
        }
        if (scoRouteActive) {
            scoRouteActive = false
        }
    }

    private fun onScoLinkChanged(active: Boolean) {
        checkNotMainThread()
        if (sco == null) {
            Log.d(TAG, "SCO link event with null link; ignoring.")
            return
        }
        if (active == scoRouteActive) {
            Log.d(TAG, "SCO route unchanged, skipping reload.")
            return
        }
        scoRouteActive = active
        val reason = if (active) "Bluetooth SCO connected"
        else "Bluetooth SCO disconnected, falling back to phone audio"
        recompute(reason, forceReload = false, isRecheck = false, firstCreate = false)
    }

    private fun handleScoFailed(reason: String?) {
        checkNotMainThread()
        if (destroyed.get()) return
        if (!_events.tryEmit(RouteEvent.ScoFailed(reason))) {
            Log.w(TAG, "Route event buffer full; dropped ScoFailed")
        }
        // Force publish: the route may be unchanged (voice handset before
        // and after), but the status must flip Ready->Fallback per spec.
        recompute("SCO failed", forceReload = true, isRecheck = false, firstCreate = false)
    }

    private fun recompute(reason: String, forceReload: Boolean, isRecheck: Boolean, firstCreate: Boolean) {
        checkNotMainThread()
        val update = updateRoute()
        val voice = update.voice
        val wasClaimedHold = modeHold is ModeHold.Claimed
        if (hasSession) {
            applyVoiceMode(voice)
            if (!voice) {
                counterUnrequestedSystemSco(wasClaimedHold)
            }
        } else {
            releaseVoiceMode()
        }
        val shouldPublish = hasSession && (firstCreate || forceReload || update.changed)
        if (shouldPublish) {
            val profile = if (voice) StreamProfile.VOICE else StreamProfile.MEDIA
            val next: RouteState = if (!scoRouteActive) {
                val isFallback = scoRequested.get() && voice &&
                    update.route == AppliedRoute.VOICE_HANDSET
                if (isFallback && !firstCreate) {
                    RouteState.Fallback(update.route, profile, "SCO unavailable")
                } else {
                    RouteState.Ready(update.route, profile)
                }
            } else {
                RouteState.Ready(update.route, profile)
            }
            val changedState = next != _state.value
            if (changedState || update.changed) {
                _state.value = next
                if (!isRecheck) {
                    if (!_events.tryEmit(RouteEvent.RouteChanged)) {
                        Log.w(TAG, "Route event buffer full; dropped RouteChanged")
                    }
                }
            }
            Log.i(TAG, "$reason; route now $next.")
        } else if (hasSession && _state.value is RouteState.Idle) {
            val profile = if (voice) StreamProfile.VOICE else StreamProfile.MEDIA
            _state.value = RouteState.Settling(update.route)
            Log.i(TAG, "$reason; route settling $update.")
        }
        val voiceByAbsence = update.route == AppliedRoute.VOICE_HANDSET
        val liveCall = hasSession
        val attempts = settleAttempts
        // At most ROUTE_SETTLE_MAX_RECHECKS settle rechecks: the initial pass
        // opens the budget, each recheck consumes one; exhaustion cancels
        // and resets (the final recheck already published Ready/Fallback, so
        // SCO-pending sessions land in Fallback per the emit gate above).
        if (voiceByAbsence && liveCall) {
            if (!isRecheck) {
                settleAttempts = 0
                armRouteSettle()
            } else if (attempts < ROUTE_SETTLE_MAX_RECHECKS) {
                settleAttempts = attempts + 1
                if (settleAttempts < ROUTE_SETTLE_MAX_RECHECKS) {
                    armRouteSettle()
                } else {
                    cancelRouteSettle()
                    settleAttempts = 0
                }
            } else {
                cancelRouteSettle()
                settleAttempts = 0
            }
        } else {
            cancelRouteSettle()
            settleAttempts = 0
        }
    }

    private data class RouteUpdate(
        val voice: Boolean,
        val route: AppliedRoute,
        val changed: Boolean,
    )

    private fun updateRoute(): RouteUpdate {
        val mediaPresent = queryMediaDevicesPresent()
        val voice = computeVoiceRoute(scoRouteActive, mediaPresent)
        val next = when {
            scoRouteActive -> AppliedRoute.VOICE_SCO
            voice -> AppliedRoute.VOICE_HANDSET
            else -> AppliedRoute.MEDIA
        }
        val changed = next != appliedRoute.get()
        appliedRoute.set(next)
        return RouteUpdate(voice, next, changed)
    }

    private fun applyVoiceMode(voice: Boolean) {
        if (voice) {
            ensureVoiceMode()
        } else {
            releaseVoiceMode()
        }
    }

    private fun ensureVoiceMode() {
        if (!platform.hasManager) return
        if (modeHold is ModeHold.Claimed) {
            try {
                val current = platform.mode
                if (current == AudioManager.MODE_IN_COMMUNICATION) return
                if (current == AudioManager.MODE_IN_CALL) {
                    Log.i(TAG, "Cellular call stole voice mode, releasing hold")
                    modeHold = ModeHold.Released
                    return
                }
                Log.w(TAG, "Voice mode stolen, re-asserting IN_COMM")
                platform.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (e: Exception) {
                Log.w(TAG, "Voice mode re-assert failed", e)
            }
            return
        }
        try {
            val mode = platform.mode
            if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                Log.i(TAG, "VoIP mode already held, not claiming")
                return
            }
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

    private fun releaseVoiceMode() {
        val hold = modeHold
        if (!platform.hasManager || hold !is ModeHold.Claimed) return
        try {
            if (platform.mode == AudioManager.MODE_IN_COMMUNICATION) {
                platform.mode = hold.savedMode
                Log.i(TAG, "VoIP mode released (restored ${hold.savedMode})")
            }
            modeHold = ModeHold.Released
        } catch (e: Exception) {
            Log.w(TAG, "VoIP mode release failed", e)
        }
    }

    private fun counterUnrequestedSystemSco(wasClaimedHold: Boolean) {
        val requested = scoRequested.get()
        if (!wasClaimedHold || requested || scoRouteActive) return
        if (!hasSession) return
        try {
            if (platform.mode == AudioManager.MODE_IN_CALL) return
        } catch (_: Exception) {
            return
        }
        if (!isSystemScoOn()) return
        try {
            platform.clearSystemSco()
            Log.i(TAG, "Cleared unrequested system SCO after media flip")
        } catch (e: Exception) {
            Log.w(TAG, "Unrequested SCO clear failed", e)
        }
    }

    private fun isSystemScoOn(): Boolean {
        if (!platform.hasManager) return false
        return try {
            platform.isSystemScoOn()
        } catch (_: Exception) {
            false
        }
    }

    private fun maybeStartRequestedSco(added: Array<AudioDeviceInfo>?) {
        val link = sco ?: return
        if (!scoRequested.get() || !hasSession) return
        if (!link.isIdle()) return
        if (added == null) return
        var scoInAdded = false
        var anyBtInAdded = false
        for (device in added) {
            if (device == null) continue
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> scoInAdded = true
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> anyBtInAdded = true
                else -> {}
            }
        }
        var scoAvailable = false
        if (scoInAdded || !anyBtInAdded) {
            scoAvailable = false
        } else try {
            scoAvailable = link.isScoDeviceAvailable()
        } catch (e: Exception) {
            Log.w(TAG, "SCO availability probe failed", e)
            scoAvailable = false
        }
        if (!scoInAdded && !(anyBtInAdded && scoAvailable)) return
        Log.i(TAG, "Bluetooth headset attached with SCO requested; starting bring-up")
        updateScoRoute()
    }

    private fun armRouteSettle() {
        val captured = routeSessionCounter.get()
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(ROUTE_SETTLE_RECHECK_MS)
            if (shouldDropStaleEvent(captured)) return@launch
            if (!hasSession) return@launch
            recompute("media settle recheck", forceReload = false, isRecheck = true, firstCreate = false)
        }
    }

    private fun cancelRouteSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    private fun cancelRouteDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun queryMediaDevicesPresent(): Boolean {
        if (!platform.hasManager) return false
        return try {
            val outputs = platform.getOutputDevices() ?: return false
            var found = false
            for (device in outputs) {
                if (device == null) continue
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> found = true
                    else -> {}
                }
            }
            found
        } catch (e: Exception) {
            Log.w(TAG, "Media-sink query failed", e)
            false
        }
    }

    fun decision(): AppliedRoute = appliedRoute.get()

    fun isScoActive(): Boolean {
        val link = sco ?: return false
        return try {
            link.isActive()
        } catch (_: Exception) {
            false
        }
    }

    val isScoRequested: Boolean get() = scoRequested.get()
    val isMediaRouteActive: Boolean get() = appliedRoute.get() == AppliedRoute.MEDIA

    fun handsetStream(): Int = handsetStream.get()
}

/**
 * Voice-mode ownership: one snapshot. Claimed means we set IN_COMM
 * and saved the previous mode; Released means we hold nothing.
 */
internal sealed interface ModeHold {
    data object Released : ModeHold
    data class Claimed(val savedMode: Int) : ModeHold
}
