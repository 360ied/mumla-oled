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

package se.lublin.humla

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import se.lublin.humla.audio.AndroidAudioPlatform
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.humla.audio.ScoLink
import se.lublin.humla.client.HumlaClient
import se.lublin.humla.client.LocalFactsStore
import se.lublin.humla.client.NetworkMonitor
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.DefaultTransportFactory
import se.lublin.humla.net.TransportFactory
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RouteControl
import se.lublin.humla.route.RouteEvent
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.RouteThread
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.voice.EngineNative
import se.lublin.humla.voice.NativeVoiceEngine
import se.lublin.humla.voice.PolicyCollector
import se.lublin.humla.voice.TalkActivity
import se.lublin.humla.voice.TalkActivityPublisher
import se.lublin.humla.voice.VoiceEngine
import se.lublin.humla.voice.VoiceSettings
import java.time.Clock

/**
 * The library's public assembly point. The container builds one runtime for
 * the process; every application-scoped object it holds outlives any service
 * instance and is never closed. Only the pieces the app layer needs are
 * public; the route controller, platform adapters, policy collector, and
 * voice engine stay library-internal.
 *
 * Interim (until step 7): the runtime owns HumlaCallbacks, the main-thread
 * poster, the session-state mirror, and the observer-bridge collectors,
 * because the client outlives every service instance.
 */
class HumlaRuntime private constructor(
    val client: HumlaClient,
    val sessionState: StateFlow<se.lublin.humla.state.SessionState>,
    val callbacks: HumlaCallbacks,
    val routeState: StateFlow<RouteState>,
    val routeEvents: SharedFlow<RouteEvent>,
    val voiceEvents: SharedFlow<VoiceEvent>,
    val events: SharedFlow<SessionEventRecord>,
    val talkActivity: StateFlow<TalkActivity>,
    internal val voiceEngine: VoiceEngine,
    internal val routeControl: RouteControl,
    internal val routePreferences: StateFlow<RoutePreferences>,
) {
    data class Inputs(
        val voiceSettings: StateFlow<VoiceSettings>,
        val routePreferences: StateFlow<RoutePreferences>,
        val localFacts: LocalFactsStore,
        val denyFormatter: (SessionEvent.PermissionDenied) -> String,
        val clock: Clock = Clock.systemUTC(),
    )

    data class Edges(
        val engineFactory: (NativeVoiceEngine.Listener) -> EngineNative = { NativeVoiceEngine(it) },
        val transports: TransportFactory = DefaultTransportFactory(),
    ) {
        companion object {
            fun production(): Edges = Edges()
        }
    }

    fun retrySco() = routeControl.retrySco()

    val isMediaRouteActive: Boolean get() = routeControl.isMediaRouteActive

    companion object {
        private const val TAG = "HumlaRuntime"

        fun create(
            context: Context,
            scope: CoroutineScope,
            inputs: Inputs,
            edges: Edges = Edges.production(),
        ): HumlaRuntime {
            val appContext = context.applicationContext
            val callbacks = HumlaCallbacks()
            val talkPublisher = TalkActivityPublisher()
            val mirror = MutableStateFlow(emptySessionState())
            val networkFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

            val audioPlatform = try {
                AndroidAudioPlatform(appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager?)
            } catch (e: Exception) {
                Log.w(TAG, "VoIP mode: no AudioManager", e)
                AndroidAudioPlatform(null)
            }
            val scoFactory: (RouteThread, ScoLink.Listener) -> ScoLink? = { routeThread, listener ->
                try {
                    BluetoothScoManager(appContext, listener, routeThread)
                } catch (e: Exception) {
                    Log.w(TAG, "SCO: no AudioManager", e)
                    null
                }
            }
            val routeControl = RouteControl.create(
                audioPlatform, scoFactory, inputs.routePreferences, scope,
            )
            routeControl.onInit()
            routeControl.start()

            lateinit var client: HumlaClient
            val voiceThread = Dispatchers.IO.limitedParallelism(1)
            val voiceEngine = VoiceEngine(
                tunnelSink = { packet ->
                    try {
                        client.currentSession.value?.transport?.sendTunnelled(packet)
                    } catch (ignored: Exception) {
                    }
                },
                talkPublisher = talkPublisher,
                scope = CoroutineScope(SupervisorJob() + voiceThread),
                engineFactory = edges.engineFactory,
                context = appContext,
                routeStates = routeControl.state,
                prefs = inputs.routePreferences,
            )

            // Registered once for the process; the client collects only while
            // Reconnecting. The callback is never unregistered: this runtime
            // lives as long as the process and the system holds binder refs.
            try {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                if (cm != null) {
                    cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            networkFlow.tryEmit(Unit)
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering network callback: ${e.message}")
            }
            val networkMonitor = object : NetworkMonitor {
                override val available = networkFlow.asSharedFlow()
            }

            val mainHandler = Handler.createAsync(Looper.getMainLooper())
            client = HumlaClient(
                parentScope = scope,
                transports = edges.transports,
                voiceLifecycle = voiceEngine,
                routeLifecycle = routeControl,
                voiceLink = voiceEngine,
                network = networkMonitor,
                localFactsStore = inputs.localFacts,
                talkActivity = talkPublisher.talkActivity,
                clock = inputs.clock,
                observer = callbacks,
                mainPoster = { r -> mainHandler.post(r) },
                denyFormatter = inputs.denyFormatter,
            )

            // Mirror: one flow across reconnects so live handles survive.
            scope.launch {
                try {
                    client.currentSession.collectLatest { session ->
                        if (session == null) {
                            mirror.value = emptySessionState()
                        } else {
                            try {
                                session.state.collect { mirror.value = it }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            val policyCollector = PolicyCollector(
                sessionStates = PolicyCollector.FlowOfStates { mirror },
                settings = inputs.voiceSettings,
                appScope = scope,
            )
            scope.launch {
                try {
                    policyCollector.policy.collect { voiceEngine.setPolicy(it) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            scope.launch {
                try {
                    inputs.voiceSettings.collect { voiceEngine.setSettings(it) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            // Observer-bridge collectors: route, SCO, and talk hints. These
            // live with the runtime, not a service instance.
            scope.launch {
                try {
                    var lastScoActive: Boolean? = null
                    routeControl.state.collect { rs ->
                        val scoActive = rs is RouteState.Ready && rs.route == AppliedRoute.VOICE_SCO
                        when (rs) {
                            is RouteState.Ready, is RouteState.Fallback, is RouteState.Idle ->
                                callbacks.onAudioRouteChanged()
                            is RouteState.Settling -> Unit
                        }
                        if (lastScoActive != null && lastScoActive != scoActive) {
                            callbacks.onBluetoothScoChanged(scoActive)
                        }
                        lastScoActive = scoActive
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            scope.launch {
                try {
                    talkPublisher.talkActivity.collect { activity ->
                        val selfSession = try {
                            client.currentSession.value?.getSessionId()
                        } catch (e: IllegalStateException) {
                            null
                        }
                        for (session in mirror.value.server.users.keys) {
                            val hint = if (session == selfSession) {
                                UserHandle(
                                    session,
                                    mirror.asStateFlow(),
                                    talkPublisher.talkActivity,
                                    talkStateOverride = if (activity.selfTalking) {
                                        TalkState.TALKING
                                    } else {
                                        TalkState.PASSIVE
                                    },
                                )
                            } else {
                                UserHandle(session, mirror.asStateFlow(), talkPublisher.talkActivity)
                            }
                            callbacks.onUserTalkStateUpdated(hint)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            scope.launch {
                try {
                    routeControl.events.collect { ev ->
                        if (ev is RouteEvent.ScoFailed) callbacks.onBluetoothScoFailed(ev.reason)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            return HumlaRuntime(
                client = client,
                sessionState = mirror.asStateFlow(),
                callbacks = callbacks,
                routeState = routeControl.state,
                routeEvents = routeControl.events,
                voiceEvents = voiceEngine.events,
                events = client.events,
                talkActivity = talkPublisher.talkActivity,
                voiceEngine = voiceEngine,
                routeControl = routeControl,
                routePreferences = inputs.routePreferences,
            )
        }
    }
}
