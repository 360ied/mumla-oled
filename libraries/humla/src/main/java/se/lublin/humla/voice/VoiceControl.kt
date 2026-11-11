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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val voiceLink: LegacyVoiceLink,
    appScope: CoroutineScope,
    controlDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob() + controlDispatcher)

    private val handlerRef = AtomicReference<AudioHandler?>(null)
    private val sessionRef = AtomicReference<Session?>(null)
    private val builderRef = AtomicReference<AudioHandler.Builder?>(null)

    init {
        scope.launch {
            routeStates.collect { state ->
                onRouteState(state)
            }
        }
        scope.launch {
            settings.collect {
                onSettingsChanged()
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

    fun close() {
        scope.launch {
            tearDown()
        }
        scope.cancel()
    }

    private fun onRouteState(state: RouteState) {
        when (state) {
            is RouteState.Idle -> tearDown()
            is RouteState.Settling -> {
                // Keep current streams while settling (spec): do nothing.
            }
            is RouteState.Ready -> tryCreate()
            is RouteState.Fallback -> tryCreate()
        }
    }

    private fun onSettingsChanged() {
        val session = sessionRef.get() ?: return
        val state = routeStates.value
        if (state is RouteState.Ready || state is RouteState.Fallback) {
            recreate(session, state)
        }
    }

    private fun tryCreate() {
        val session = sessionRef.get() ?: return
        val state = routeStates.value
        if (state is RouteState.Ready || state is RouteState.Fallback) {
            recreate(session, state)
        }
    }

    private fun recreate(session: Session, state: RouteState) {
        checkNotMainThread()
        val existing = handlerRef.get()
        if (existing != null) {
            try {
                session.removeTcpHandler(existing)
            } catch (_: Exception) {
            }
            try {
                voiceLink.removeUdpHandler(existing)
            } catch (_: Exception) {
            }
            try {
                existing.shutdown()
            } catch (_: Exception) {
            }
            handlerRef.set(null)
        }
        val created = try {
            buildHandler(session, state)
        } catch (_: Exception) {
            return
        }
        try {
            session.addTcpHandler(created)
        } catch (_: Exception) {
            try {
                created.shutdown()
            } catch (_: Exception) {
            }
            return
        }
        voiceLink.addUdpHandler(created)
        voiceLink.setTargetFramesPerPacket(created.getFramesPerPacket())
        created.setFramesPerPacketListener { fpp ->
            try {
                voiceLink.setTargetFramesPerPacket(fpp)
            } catch (_: Exception) {
            }
        }
        created.setPolicyFlow(policies)
        handlerRef.set(created)
    }

    private fun tearDown() {
        checkNotMainThread()
        val existing = handlerRef.getAndSet(null) ?: return
        val session = sessionRef.get()
        try {
            session?.removeTcpHandler(existing)
        } catch (_: Exception) {
        }
        try {
            voiceLink.removeUdpHandler(existing)
        } catch (_: Exception) {
        }
        try {
            existing.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun buildHandler(session: Session, state: RouteState): AudioHandler {
        val s = session.state.value
        val self: UserInfo? = s.server.self
        val maxBandwidth: Int = s.server.info.maxBandwidth ?: -1
        val opus = s.server.info.opus
        val codec = if (opus) HumlaUDPMessageType.UDPVoiceOpus else null
        val voiceTargetId = s.local.voiceTargetId.toByte()

        val route: AppliedRoute = when (state) {
            is RouteState.Ready -> state.route
            is RouteState.Fallback -> state.route
            else -> AppliedRoute.VOICE_HANDSET
        }
        val builder = AudioHandler.Builder()
            .setContext(context)
            .setLogger(logger)
            .setEncodeListener(encodeListener)
            .setTalkingListener(talkingListener)

        val currentSettings = settings.value
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
                builder.setAudioStream(AudioManager.STREAM_VOICE_CALL)
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
        builderRef.set(builder)
        return handler
    }
}
