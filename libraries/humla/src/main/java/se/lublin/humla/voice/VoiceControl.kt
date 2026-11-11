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

package se.lublin.humla.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.client.Session
import se.lublin.humla.client.checkNotMainThread
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.LegacyVoiceLink
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.util.HumlaLogger

/**
 * Interim voice control. Owns the voice control thread and today's
 * AudioHandler lifecycle (create, destroy, recreate), following RouteState.
 * See docs/tech-debt-whales/target-architecture/route.md#migration-notes.
 *
 * Step 5a replaces it with the VoiceEngine. Command-only: callers post
 * and never block; every blocking pipeline method starts with
 * checkNotMainThread.
 */
class VoiceControl(
    private val context: Context,
    private val logger: HumlaLogger,
    private val encodeListener: AudioHandler.AudioEncodeListener,
    private val talkingListener: AudioOutput.AudioOutputListener,
    private val routeStates: StateFlow<RouteState>,
    private val policies: StateFlow<VoicePolicy>,
    private val settings: StateFlow<VoiceSettings>,
    private val prefs: StateFlow<RoutePreferences>,
    private val voiceLink: LegacyVoiceLink,
    appScope: CoroutineScope,
    controlDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val scope = CoroutineScope(SupervisorJob(appScope.coroutineContext[Job]) + controlDispatcher)

    private val handlerRef = AtomicReference<AudioHandler?>(null)
    private val sessionRef = AtomicReference<Session?>(null)
    private var lastBuilt: BuiltConfig? = null

    init {
        scope.launch {
            try {
                routeStates.collect { state ->
                    onRouteState(state)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logWarning("VoiceControl route collection ended: $e")
            }
        }
        scope.launch {
            try {
                settings.collect {
                    tryCreate()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logWarning("VoiceControl settings collection ended: $e")
            }
        }
        scope.launch {
            try {
                prefs.collect {
                    tryCreate()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logWarning("VoiceControl prefs collection ended: $e")
            }
        }
    }

    fun sessionStarted(session: Session) {
        scope.launch {
            sessionRef.set(session)
            tryCreate()
        }
    }

    fun sessionEnded() {
        scope.launch {
            tearDown()
            sessionRef.set(null)
        }
    }

    fun currentHandler(): AudioHandler? = handlerRef.get()

    fun close(): Job {
        // NonCancellable: see RouteControl.close; the mic/native teardown
        // must survive parent-scope cancellation during Service.onDestroy.
        val teardown = scope.launch(NonCancellable) {
            tearDown()
        }
        teardown.invokeOnCompletion {
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
        }
        return teardown
    }

    private fun onRouteState(state: RouteState) {
        when (state) {
            is RouteState.Idle -> {
                lastBuilt = null
                tearDown()
            }
            is RouteState.Settling -> {
                // Keep current streams while settling (spec): do nothing.
            }
            is RouteState.Ready -> tryCreate()
            is RouteState.Fallback -> tryCreate()
        }
    }

    private data class BuiltConfig(
        val settings: VoiceSettings,
        val route: AppliedRoute,
        val handsetStream: Int,
    )

    private fun tryCreate() {
        val session = sessionRef.get() ?: return
        val state = routeStates.value
        if (state is RouteState.Ready || state is RouteState.Fallback) {
            maybeReconfigure(session)
        }
    }

    /**
     * Live-apply cheap knobs (VAD thresholds, adaptive leveler) when the
     * core pipeline (input mode, half-duplex, amplitude boost, route,
     * handset stream) is unchanged; otherwise rebuild. Dragging the VAD
     * slider must not re-acquire the mic.
     */
    private fun maybeReconfigure(session: Session) {
        val current = settings.value
        val route = when (val state = routeStates.value) {
            is RouteState.Ready -> state.route
            is RouteState.Fallback -> state.route
            else -> return
        }
        val handset = try {
            prefs.value.handsetStream
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.logWarning("VoiceControl prefs read failed: $e")
            return
        }
        val handler = handlerRef.get()
        val built = lastBuilt
        if (handler != null && built != null &&
            built.route == route && built.handsetStream == handset &&
            built.settings.inputMode == current.inputMode &&
            built.settings.halfDuplex == current.halfDuplex &&
            built.settings.amplitudeBoost == current.amplitudeBoost
        ) {
            try {
                handler.setVadThresholds(current.vadMax, current.vadMin)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logWarning("VoiceControl live VAD apply failed: $e")
            }
            try {
                handler.setAdaptiveLevelerEnabled(current.adaptiveLeveler)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logWarning("VoiceControl live leveler apply failed: $e")
            }
            lastBuilt = built.copy(settings = current)
            return
        }
        recreate(session, route, current, handset)
    }

    private fun recreate(session: Session, route: AppliedRoute, current: VoiceSettings, handset: Int) {
        checkNotMainThread()
        val existing = handlerRef.get()
        if (existing != null) {
            try {
                session.removeTcpHandler(existing)
            } catch (e: Exception) {
                logger.logWarning("VoiceControl dropping stale TCP handler failed: $e")
            }
            try {
                voiceLink.removeUdpHandler(existing)
            } catch (e: Exception) {
                logger.logWarning("VoiceControl dropping stale UDP handler failed: $e")
            }
            try {
                existing.shutdown()
            } catch (e: Exception) {
                logger.logWarning("VoiceControl stale handler shutdown failed: $e")
            }
            handlerRef.set(null)
        }
        val created = try {
            buildHandler(session, route, current, handset)
        } catch (e: Exception) {
            logger.logWarning("VoiceControl pipeline build failed; retry on next event: $e")
            return
        }
        try {
            session.addTcpHandler(created)
        } catch (e: Exception) {
            logger.logWarning("VoiceControl TCP attach failed; shutting down orphan: $e")
            try {
                created.shutdown()
            } catch (shutdownError: Exception) {
                logger.logWarning("VoiceControl orphan shutdown failed: $shutdownError")
            }
            return
        }
        try {
            voiceLink.addUdpHandler(created)
        } catch (e: Exception) {
            logger.logWarning("VoiceControl UDP attach failed; detaching TCP side: $e")
            try {
                session.removeTcpHandler(created)
            } catch (detachError: Exception) {
                logger.logWarning("VoiceControl TCP detach after UDP failure failed: $detachError")
            }
            try {
                created.shutdown()
            } catch (shutdownError: Exception) {
                logger.logWarning("VoiceControl orphan shutdown failed: $shutdownError")
            }
            return
        }
        voiceLink.setTargetFramesPerPacket(created.getFramesPerPacket())
        created.setFramesPerPacketListener { fpp ->
            try {
                voiceLink.setTargetFramesPerPacket(fpp)
            } catch (e: Exception) {
                // Must never throw off the audio thread; counting on the
                // target setter to be non-blocking (see LegacyVoiceLink).
                if (e is CancellationException) throw e
                logger.logWarning("VoiceControl FPP forward failed: $e")
            }
        }
        created.setPolicyFlow(policies)
        handlerRef.set(created)
        lastBuilt = BuiltConfig(current, route, handset)
    }

    private fun tearDown() {
        checkNotMainThread()
        lastBuilt = null
        val existing = handlerRef.getAndSet(null) ?: return
        val session = sessionRef.get()
        try {
            session?.removeTcpHandler(existing)
        } catch (e: Exception) {
            logger.logWarning("VoiceControl TCP detach failed: $e")
        }
        try {
            voiceLink.removeUdpHandler(existing)
        } catch (e: Exception) {
            logger.logWarning("VoiceControl UDP detach failed: $e")
        }
        try {
            existing.shutdown()
        } catch (e: Exception) {
            logger.logWarning("VoiceControl handler shutdown failed: $e")
        }
    }

    private fun buildHandler(
        session: Session,
        route: AppliedRoute,
        currentSettings: VoiceSettings,
        handset: Int,
    ): AudioHandler {
        val s = session.state.value
        val self: UserInfo? = s.server.self
        val maxBandwidth: Int = s.server.info.maxBandwidth ?: -1
        val opus = s.server.info.opus
        val codec = if (opus) HumlaUDPMessageType.UDPVoiceOpus else null
        val voiceTargetId = s.local.voiceTargetId.coerceIn(0, 31).toByte()

        val builder = AudioHandler.Builder()
            .setContext(context)
            .setLogger(logger)
            .setEncodeListener(encodeListener)
            .setTalkingListener(talkingListener)

        builder.setTargetBitrate(currentSettings.targetBitrate)
        builder.setTargetFramesPerPacket(currentSettings.targetFramesPerPacket)
        builder.setAmplitudeBoost(currentSettings.amplitudeBoost)
        builder.setAdaptiveLevelerEnabled(currentSettings.adaptiveLeveler)

        val inputMode = when (currentSettings.inputMode) {
            InputMode.PUSH_TO_TALK -> se.lublin.humla.audio.inputmode.ToggleInputMode()
            InputMode.CONTINUOUS -> se.lublin.humla.audio.inputmode.ContinuousInputMode()
            InputMode.VOICE_ACTIVITY -> se.lublin.humla.audio.inputmode.ActivityInputMode(
                currentSettings.vadMax,
            )
        }
        builder.setInputMode(inputMode)
        builder.setHalfDuplexEnabled(currentSettings.halfDuplex)

        when (route) {
            AppliedRoute.VOICE_SCO -> {
                builder.setScoEnabled(true)
                builder.setVoiceRouteEnabled(true)
                builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                builder.setAudioStream(AudioManager.STREAM_VOICE_CALL)
            }
            AppliedRoute.VOICE_HANDSET -> {
                builder.setScoEnabled(false)
                builder.setVoiceRouteEnabled(true)
                builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                // Handset stream travels with the voice profile (spec): the
                // earpiece/speakerphone pref selects the stream; the handler
                // derives speakerphone from it at initialize.
                builder.setAudioStream(handset)
            }
            AppliedRoute.MEDIA -> {
                builder.setScoEnabled(false)
                builder.setVoiceRouteEnabled(false)
                builder.setAudioSource(MediaRecorder.AudioSource.MIC)
                builder.setAudioStream(AudioManager.STREAM_MUSIC)
            }
        }

        val handler = builder.initialize(self, maxBandwidth, codec, voiceTargetId)
        val v2 = s.server.info.versionV2 ?: 0L
        handler.setProtobufUdp(
            se.lublin.humla.Constants.isProtobufUdpSupported(
                v2,
                se.lublin.humla.Constants.toLegacyVersion(v2),
            ),
        )
        return handler
    }
}
