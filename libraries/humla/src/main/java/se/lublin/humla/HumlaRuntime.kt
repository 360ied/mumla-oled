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
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import se.lublin.humla.net.DefaultTransportFactory
import se.lublin.humla.net.TransportFactory
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.route.RouteControl
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteEvent
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.RouteThread
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.EngineNative
import se.lublin.humla.voice.NativeVoiceEngine
import se.lublin.humla.voice.PolicyCollector
import se.lublin.humla.voice.TalkActivity
import se.lublin.humla.voice.TalkActivityPublisher
import se.lublin.humla.voice.VoiceEngine
import se.lublin.humla.voice.VoiceSettings
import java.time.Clock
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import org.minidns.hla.ResolverApi

/**
 * The library's public assembly point. The container builds one runtime for
 * the process; every application-scoped object it holds outlives any service
 * instance and is never closed. Only the pieces the app layer needs are
 * public; the route controller, platform adapters, policy collector, and
 * voice engine stay library-internal.
 *
 * The runtime keeps the session-state mirror: features and ViewModels collect
 * it instead of observers, and it survives reconnects.
 */
class HumlaRuntime private constructor(
    val client: HumlaClient,
    val sessionState: StateFlow<se.lublin.humla.state.SessionState>,
    val routeState: StateFlow<RouteState>,
    val routeEvents: SharedFlow<RouteEvent>,
    val voiceEvents: SharedFlow<VoiceEvent>,
    val events: SharedFlow<SessionEventRecord>,
    val talkActivity: StateFlow<TalkActivity>,
    internal val voiceEngine: VoiceEngine,
    internal val routeControl: RouteControl,
    internal val routePreferences: StateFlow<RoutePreferences>,
    private val connectivityManager: ConnectivityManager?,
    private val networkCallback: ConnectivityManager.NetworkCallback?,
) {
    data class Inputs(
        val voiceSettings: StateFlow<VoiceSettings>,
        val routePreferences: StateFlow<RoutePreferences>,
        val localFacts: LocalFactsStore,
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

    /** True until close() unregisters the process network callback. */
    var networkCallbackRegistered: Boolean = networkCallback != null
        private set

    /**
     * Release the process network callback. The runtime is app-lifetime in
     * prod and is never closed there; Robolectric tests call this in teardown
     * so each container leaves no registered callback behind. Idempotent.
     */
    fun close() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HumlaRuntime"

        /**
         * Today's MiniDNS setup (link properties and the 800 ms resolver
         * timeout), applied once when the runtime is built at process start,
         * before any lookup.
         */
        private fun initMiniDns(appContext: Context) {
            try {
                AndroidUsingLinkProperties.setup(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Could not configure MiniDNS link properties: $e")
            }
            try {
                ResolverApi.INSTANCE.client.dataSource.setTimeout(800)
            } catch (e: Exception) {
                Log.w(TAG, "Could not configure MiniDNS resolver timeout: $e")
            }
        }

        fun create(
            context: Context,
            scope: CoroutineScope,
            inputs: Inputs,
            edges: Edges = Edges.production(),
        ): HumlaRuntime {
            val appContext = context.applicationContext
            initMiniDns(appContext)
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
            // Child of the caller's scope so test teardown cancels voice
            // control coroutines with the container (application-lifetime in
            // prod, per-test in Robolectric).
            val voiceScope = CoroutineScope(
                scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + voiceThread,
            )
            val voiceEngine = VoiceEngine(
                tunnelSink = { packet ->
                    try {
                        client.currentSession.value?.transport?.sendTunnelled(packet)
                    } catch (ignored: Exception) {
                    }
                },
                talkPublisher = talkPublisher,
                scope = voiceScope,
                engineFactory = edges.engineFactory,
                context = appContext,
                routeStates = routeControl.state,
                prefs = inputs.routePreferences,
            )

            // Registered once for the process in prod; the runtime holds the
            // callback and the manager so close() can unregister it. The
            // callback lives as long as the runtime, which is process-lifetime.
            var networkCallback: ConnectivityManager.NetworkCallback? = null
            var connectivityManager: ConnectivityManager? = null
            try {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                if (cm != null) {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            networkFlow.tryEmit(Unit)
                        }
                    }
                    cm.registerDefaultNetworkCallback(callback)
                    networkCallback = callback
                    connectivityManager = cm
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering network callback: ${e.message}")
            }
            val networkMonitor = object : NetworkMonitor {
                override val available = networkFlow.asSharedFlow()
            }

            client = HumlaClient(
                parentScope = scope,
                transports = edges.transports,
                voiceLifecycle = voiceEngine,
                routeLifecycle = routeControl,
                voiceLink = voiceEngine,
                network = networkMonitor,
                localFactsStore = inputs.localFacts,
                clock = inputs.clock,
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

            return HumlaRuntime(
                client = client,
                sessionState = mirror.asStateFlow(),
                routeState = routeControl.state,
                routeEvents = routeControl.events,
                voiceEvents = voiceEngine.events,
                events = client.events,
                talkActivity = talkPublisher.talkActivity,
                voiceEngine = voiceEngine,
                routeControl = routeControl,
                routePreferences = inputs.routePreferences,
                connectivityManager = connectivityManager,
                networkCallback = networkCallback,
            )
        }
    }
}
