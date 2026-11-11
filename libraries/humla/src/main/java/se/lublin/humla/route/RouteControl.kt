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
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
            val routeScope = CoroutineScope(appScope.coroutineContext + SupervisorJob() + dispatcher)
            return RouteControl(platform, sco, prefs, routeScope, handler, thread)
        }
    }

    private val _state: MutableStateFlow<RouteState> = MutableStateFlow(RouteState.Idle)
    val state: StateFlow<RouteState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RouteEvent> = _events.asSharedFlow()

    private var routeSessionCounter = 0
    private var hasSession = false

    private var scoRequested: Boolean = false
    private var scoRouteActive: Boolean = false
    private var appliedRoute: AppliedRoute = AppliedRoute.VOICE_HANDSET
    private var handsetStream: Int = AudioManager.STREAM_MUSIC
    private var modeHold: ModeHold = ModeHold.Released

    private var settleAttempts: Int = 0
    private var debounceJob: Job? = null
    private var settleJob: Job? = null

    private var destroyed = false
    private var deviceCallback: AudioDeviceCallback? = null

    @VisibleForTesting
    internal var routeThreadOverride: Boolean? = null

    private val prefsFlow: Flow<RoutePreferences> = prefs

    private fun isRouteThread(): Boolean {
        routeThreadOverride?.let { return it }
        return try {
            Looper.myLooper() == deviceHandler.looper
        } catch (_: Exception) {
            false
        }
    }

    internal fun shouldDropStaleEvent(captured: Int): Boolean {
        return destroyed || captured != routeSessionCounter
    }

    fun submit(command: RouteCommand) {
        val captured = routeSessionCounter
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
        checkNotMainThread()
        scope.launch {
            registerDeviceListener()
            try {
                prefsFlow.collect { p ->
                    onPrefsChanged(p.bluetoothSco, p.handsetStream)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun close() {
        scope.launch { onDestroyed() }
        try {
            ownedThread?.quitSafely()
        } catch (_: Exception) {
        }
    }

    fun onInit() {
        scope.launch {
            val media = queryMediaDevicesPresent()
            appliedRoute = if (!media) AppliedRoute.VOICE_HANDSET else AppliedRoute.MEDIA
        }
    }

    private fun registerDeviceListener() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>?) {
                onDevicesChanged(added)
            }

            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>?) {
                onDevicesChanged(null)
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
        val captured = routeSessionCounter
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            onScoLinkChanged(true)
        }
    }

    fun onScoDisconnected() {
        val captured = routeSessionCounter
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            onScoLinkChanged(false)
        }
    }

    fun onScoFailed(reason: String?) {
        val captured = routeSessionCounter
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            handleScoFailed(reason)
        }
    }

    private fun onSessionStarted() {
        checkNotMainThread()
        routeSessionCounter += 1
        hasSession = true
        scoRouteActive = false
        settleAttempts = 0
        recompute("sync", forceReload = false, settle = false, firstCreate = true)
        updateScoRoute()
    }

    private fun onSessionEnded() {
        checkNotMainThread()
        hasSession = false
        cancelRouteDebounce()
        cancelRouteSettle()
        settleAttempts = 0
        stopScoWithNotify()
        val liveMedia = queryMediaDevicesPresent()
        appliedRoute = if (liveMedia) AppliedRoute.MEDIA else AppliedRoute.VOICE_HANDSET
        releaseVoiceMode()
        _state.value = RouteState.Idle
    }

    private fun onDestroyed() {
        destroyed = true
        cancelRouteDebounce()
        cancelRouteSettle()
        unregisterDeviceListener()
        try {
            sco?.stop()
        } catch (_: Exception) {
        }
        releaseVoiceMode()
        _state.value = RouteState.Idle
    }

    private fun onRetrySco() {
        checkNotMainThread()
        val link = sco ?: return
        if (!scoRequested || !hasSession) return
        if (!link.isIdle()) return
        link.start()
        if (!hasSession || !scoRequested) {
            try {
                link.stop()
            } catch (_: Exception) {
            }
            return
        }
    }

    fun setScoRequested(requested: Boolean) {
        scope.launch { onPrefsChanged(requested, handsetStream) }
    }

    fun setHandsetStream(stream: Int) {
        scope.launch { onPrefsChanged(scoRequested, stream) }
    }

    private fun onPrefsChanged(requested: Boolean, stream: Int) {
        val changed = (requested != scoRequested) || (stream != handsetStream)
        scoRequested = requested
        handsetStream = stream
        if (!hasSession) return
        if (changed) {
            recompute("settings changed", forceReload = true, settle = false, firstCreate = false)
            updateScoRoute()
        }
    }

    private fun onDevicesChanged(added: Array<AudioDeviceInfo>?) {
        val captured = routeSessionCounter
        scope.launch {
            if (shouldDropStaleEvent(captured)) return@launch
            if (!hasSession) return@launch
            maybeStartRequestedSco(added)
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(ROUTE_RECOMPUTE_DEBOUNCE_MS)
                if (shouldDropStaleEvent(captured)) return@launch
                recompute("devices changed", forceReload = false, settle = false, firstCreate = false)
            }
        }
    }

    fun updateScoRoute() {
        checkNotMainThread()
        val link = sco ?: return
        if (destroyed) return
        if (scoRequested && hasSession) {
            try {
                link.start()
            } catch (e: Exception) {
                Log.w(TAG, "SCO start failed", e)
            }
        } else if (!link.isIdle()) {
            try {
                link.stop()
            } catch (e: Exception) {
                Log.w(TAG, "SCO stop failed", e)
            }
        }
    }

    fun stopScoWithNotify() {
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
        recompute(reason, forceReload = false, settle = false, firstCreate = false)
        _events.tryEmit(RouteEvent.RouteChanged)
    }

    private fun handleScoFailed(reason: String?) {
        checkNotMainThread()
        if (destroyed) return
        _events.tryEmit(RouteEvent.ScoFailed(reason))
        recompute("SCO failed", forceReload = false, settle = false, firstCreate = false)
    }

    private fun recompute(reason: String, forceReload: Boolean, settle: Boolean, firstCreate: Boolean) {
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
            val next: RouteState = if (!voice || !scoRouteActive) {
                val isFallback = scoRequested && voice && !scoRouteActive &&
                    (appliedRoute == AppliedRoute.VOICE_HANDSET || update.route == AppliedRoute.VOICE_HANDSET)
                if (isFallback && !firstCreate) {
                    RouteState.Fallback(update.route, profile, "SCO unavailable")
                } else {
                    RouteState.Ready(update.route, profile)
                }
            } else {
                RouteState.Ready(update.route, profile)
            }
            val changedState = next != _state.value
            if (firstCreate || forceReload || changedState || update.changed) {
                _state.value = next
                if (!settle) {
                    _events.tryEmit(RouteEvent.RouteChanged)
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
        if (voiceByAbsence && liveCall && (!settle || attempts < ROUTE_SETTLE_MAX_RECHECKS - 1)) {
            if (!settle) {
                settleAttempts = 0
            } else {
                settleAttempts += 1
            }
            armRouteSettle()
        } else if (!voiceByAbsence || !liveCall) {
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
        val changed = next != appliedRoute
        appliedRoute = next
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
        val requested = scoRequested
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
        if (!scoRequested || !hasSession) return
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
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(ROUTE_SETTLE_RECHECK_MS)
            recompute("media settle recheck", forceReload = false, settle = true, firstCreate = false)
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

    fun decision(): AppliedRoute = appliedRoute

    fun isScoActive(): Boolean {
        val link = sco ?: return false
        return try {
            link.isActive()
        } catch (_: Exception) {
            false
        }
    }

    val isScoRequested: Boolean get() = scoRequested
    val isMediaRouteActive: Boolean get() = appliedRoute == AppliedRoute.MEDIA

    fun handsetStream(): Int = handsetStream
}

/**
 * Voice-mode ownership: one snapshot. Claimed means we set IN_COMM
 * and saved the previous mode; Released means we hold nothing.
 */
internal sealed interface ModeHold {
    data object Released : ModeHold
    data class Claimed(val savedMode: Int) : ModeHold
}
