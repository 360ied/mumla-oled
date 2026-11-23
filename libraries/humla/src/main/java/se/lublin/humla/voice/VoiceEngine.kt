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

import android.util.Log
import com.google.protobuf.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import se.lublin.humla.BuildConfig
import se.lublin.humla.HumlaNative
import se.lublin.humla.audio.AudioInput
import se.lublin.humla.audio.InputPipeline
import se.lublin.humla.audio.OutputPipeline
import se.lublin.humla.audio.RenderDriver
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.client.VoiceLifecycle
import se.lublin.humla.client.checkNotMainThread
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RoutePreferences
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.StreamProfile
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.CryptKeys
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.net.VoiceFormat
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.net.VoiceStatus
import kotlin.math.min

/**
 * Kotlin VoiceEngine facade (step 5a.4, spec-literal).
 *
 * Implements the client's [VoiceLifecycle], the transport's [VoiceLink],
 * and the render [RenderDriver]. Owns the application-scoped native voice
 * engine (output engine, rings, policy cell, UDP socket and receive
 * thread, notifier) across sessions. Takes over the service-lifetime
 * [TalkActivity] publisher from 5a.3a (same instance, new owner).
 *
 * Voice stays native-direct throughout: the receive thread and the tunnel
 * caller push straight into the owned rings; removals arrive as control
 * items on the tunnel ring from the session message coroutine. Render is
 * driven per-pipeline through [RenderDriver] but owns nothing.
 *
 * `Start` carries the session's UDP address plus the transport's
 * per-session decisions, fixed once: keys (when CryptSetup arrived) and
 * the UDP format with the legacy version. No route: voice follows the
 * transport's `SetVoiceRoute`; `Stop` joins capture/render first, then
 * stops native, clears both engines/rings and delivers the final empty
 * talk snapshot via the notifier (the only `talkActivity` writer), and
 * resets the route to tunnel with the socket closed. Engines keep their
 * state across route changes (no clear on pipeline rebuild).
 *
 * Android-free except android.util.Log for the drop log (the audio layer
 * already logs the same way).
 */
class VoiceEngine @JvmOverloads constructor(
    private val tunnelSink: (ByteArray) -> Unit = {},
    private val talkPublisher: TalkActivityPublisher = TalkActivityPublisher(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    private val engineFactory: (NativeVoiceEngine.Listener) -> EngineNative = { NativeVoiceEngine(it) },
    private val context: Context? = null,
    private val routeStates: StateFlow<RouteState>? = null,
    private val prefs: StateFlow<RoutePreferences>? = null,
    private val inputFactory: (AudioInput.AudioInputListener, Int) -> InputPipeline =
        { listener, source -> AudioInput(listener, source) },
    private val outputFactory: (Context?, RenderDriver) -> OutputPipeline =
        { ctx, driver -> se.lublin.humla.audio.AudioOutput(ctx, driver) },
) : VoiceLink, VoiceLifecycle, RenderDriver, NativeVoiceEngine.Listener,
    AudioInput.AudioInputListener {
    companion object {
        private val TAG: String = VoiceEngine::class.java.name
    }

    /** Service-lifetime talk levels; the native notifier is the only writer from 5a.4. */
    val talkActivity: StateFlow<TalkActivity> = talkPublisher.talkActivity

    internal data class BuiltPipeline(
        val input: InputPipeline,
        val output: OutputPipeline,
        val route: AppliedRoute,
        val profile: StreamProfile,
        val audioSource: Int,
        val audioStream: Int,
    )
    private val pipelineRef = AtomicReference<BuiltPipeline?>(null)

    /** Test-only: current pipeline for route-following assertions (issue 03). */
    internal fun pipelineForTests(): BuiltPipeline? = pipelineRef.get()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(VoiceStatus.initial())
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private val usingUdp = AtomicBoolean(false)
    private data class Endpoint(val host: String, val port: Int)
    private val endpointRef = AtomicReference<Endpoint?>(null)
    private val formatRef = AtomicReference(VoiceFormat.DEFAULT)
    private val targetFramesPerPacket = AtomicInteger(2)
    private val targetBitrate = AtomicInteger(40000)
    private val runningFlag = AtomicBoolean(false)

    private val nativeRef = AtomicReference<EngineNative?>(null)
    // Latest tunnel sink, published once at construction or via
    // setTunnelSink and read on the native send thread: no join ordering
    // covers it, so it gets the atomic the ordering-covered fields skip.
    private val tunnelSinkRef = AtomicReference(tunnelSink)
    // Set once the application ends: late Start commands are dropped instead
    // of creating an engine nobody will destroy (submit/close race).
    private val closed = AtomicBoolean(false)
    // Last Start decision plus whether its socket open succeeded: an
    // identical healthy Start is a no-op, anything else tears the live
    // pipeline down first so reset() never races in-flight capture.
    private val lastStartRef = AtomicReference<VoiceCommand.Start?>(null)
    private val startHealthy = AtomicBoolean(false)
    // Keys that arrived before the engine existed (CryptSetup can beat the
    // SYNCED Start): applied with the Start decision, so setKeys never
    // creates the engine off the control thread.
    private val pendingKeys = AtomicReference<Triple<ByteArray, ByteArray, ByteArray>?>(null)

    private val _latestPolicy = MutableStateFlow<VoicePolicy?>(null)
    val latestPolicy: StateFlow<VoicePolicy?> = _latestPolicy.asStateFlow()

    private val _latestSettings = MutableStateFlow<VoiceSettings?>(null)
    val latestSettings: StateFlow<VoiceSettings?> = _latestSettings.asStateFlow()

    // Last filter for test-only gate pushes (single native publish needs
    // both filter and gate; harness has no collector to provide the pair).
    private var lastTestMuted: IntArray = intArrayOf()
    private var lastTestKnown: IntArray = intArrayOf()

    private var lastLoggedDroppedInbound = 0L
    private var lastLoggedDroppedTunnel = 0L
    private var lastLoggedDroppedJitter = 0L
    private var lastLoggedDroppedOutbound = 0L

    init {
        // Voice follows route state (route.md): open capture and render in
        // Ready/Fallback, restart on route/profile change, stop on Idle.
        // Runs on the shared voice control thread, in command order with
        // Start/Stop, so teardown and destroy serialize without a second
        // thread. Null in unit tests and the interop harness, which drive
        // native render/capture directly without AudioRecord/Track.
        val routes = routeStates
        if (routes != null) {
            scope.launch {
                try {
                    routes.collect {
                        tryEnsurePipeline()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Voice route collection ended: $e")
                }
            }
            val prefsFlow = prefs
            if (prefsFlow != null) {
                scope.launch {
                    try {
                        prefsFlow.collect {
                            tryEnsurePipeline()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "Voice prefs collection ended: $e")
                    }
                }
            }
        }
    }

    /** Test and service hook: replaces the tunnel sink after construction. */
    fun setTunnelSink(sink: (ByteArray) -> Unit) {
        tunnelSinkRef.set(sink)
    }

    /** Policy collector only: publishes the mapped VoicePolicy for the native filter and gate. */
    fun setPolicy(policy: VoicePolicy) {
        _latestPolicy.value = policy
        // Serialize with Start/Stop on the voice control thread: the native
        // PolicyCell is single-writer, so collector emissions must not race
        // the control thread's pushPolicy. Unconfined test scopes run this
        // synchronously, keeping existing assertions order-stable.
        scope.launch {
            pushPolicy()
            refreshStatus()
        }
    }

    /** Settings flow only: publishes VoiceSettings for the native capture and render. */
    fun setSettings(settings: VoiceSettings) {
        _latestSettings.value = settings
        // Same serialization as setPolicy: native SettingsCell is
        // single-writer; schedule the push plus status refresh on the voice
        // control thread so collector emissions cannot race Start/Stop.
        scope.launch {
            pushSettings()
            refreshStatus()
        }
    }

    private fun effectiveHalfDuplex(halfDuplex: Boolean, inputMode: InputMode): Boolean =
        halfDuplex && inputMode == InputMode.PUSH_TO_TALK

    /**
     * Test-only filter push (the interop harness has no PolicyCollector):
     * publishes the muted/known lists straight to the native cell, plus an
     * open gate so a test sender transmits without a collector. Production
     * always goes through [setPolicy] and [setSettings].
     */
    fun setFilterForTests(muted: IntArray, known: IntArray) {
        lastTestMuted = muted.copyOf()
        lastTestKnown = known.copyOf()
        // Same control-thread serialization as setPolicy: the native cells
        // are single-writer. Unconfined test scopes run this synchronously.
        scope.launch {
            try {
                ensureNative()
                nativeRef.get()?.setPolicy(muted, known, true, false, 0, 40000, 2)
                nativeRef.get()?.setSettings(2, 1.0f, 0.7f, 1.0f, true, false)
            } catch (e: Exception) {
                loggerFine("Dropping test filter push: $e")
            }
        }
    }

    /** Java-friendly gate push for interop gating tests (no PersistentSet). */
    fun setGateForTests(transmitAllowed: Boolean, pttPressed: Boolean) {
        // Control-thread serialization, like setFilterForTests.
        scope.launch {
            try {
                ensureNative()
                nativeRef.get()?.setPolicy(lastTestMuted, lastTestKnown, transmitAllowed, pttPressed, 0, 40000, 2)
            } catch (e: Exception) {
                loggerFine("Dropping test gate push: $e")
            }
        }
    }

    /** Java-friendly settings push for interop PTT tests. */
    fun setSettingsForTests(inputMode: InputMode, halfDuplex: Boolean) {
        // Control-thread serialization, like setFilterForTests.
        scope.launch {
            try {
                ensureNative()
                nativeRef.get()?.setSettings(
                when (inputMode) {
                    InputMode.VOICE_ACTIVITY -> 0
                    InputMode.PUSH_TO_TALK -> 1
                    InputMode.CONTINUOUS -> 2
                },
                1.0f, 0.7f, 1.0f, true, effectiveHalfDuplex(halfDuplex, inputMode),
            )
        } catch (e: Exception) {
            loggerFine("Dropping test settings push: $e")
        }
        }
        _latestSettings.value = VoiceSettings(
            inputMode = inputMode,
            vadMax = 1.0f,
            vadMin = 0.7f,
            amplitudeBoost = 1.0f,
            targetBitrate = 40000,
            targetFramesPerPacket = 2,
            halfDuplex = halfDuplex,
            adaptiveLeveler = true,
        )
    }

    fun setTargetFramesPerPacket(fpp: Int) {
        targetFramesPerPacket.set(se.lublin.humla.Constants.sanitizeFramesPerPacket(fpp))
        refreshStatus()
    }

    fun getTargetFramesPerPacket(): Int = targetFramesPerPacket.get()

    fun setTargetBitrate(bitrate: Int) {
        targetBitrate.set(bitrate)
        refreshStatus()
    }

    /** Current session format (tests mirror sender framing to it). */
    fun udpFormat(): UdpFormat = formatRef.get().udp

    override fun startVoice(host: String, port: Int, format: VoiceFormat, keys: CryptKeys?) {
        submit(VoiceCommand.Start(host, port, keys, format))
    }

    override fun stopVoice() {
        submit(VoiceCommand.Stop)
    }

    override fun submit(command: VoiceCommand) {
        // Never blocks the caller: the whole command is applied on the
        // shared voice control thread (VoiceControl's dispatcher), in
        // queue order. The transport reader and the client dispatcher
        // share this entry, so inline execution would stall TCP framing
        // on DNS/connect. Only the library load stays inline so a debug
        // load failure still throws to the submitter; the engine itself is
        // created on the control thread (see createNativeOnControl), so no
        // caller thread ever touches the native handle's lifetime.
        if (command is VoiceCommand.Start) {
            try {
                HumlaNative.ensureLoaded()
            } catch (t: Throwable) {
                // Debug load failure must stay loud to the submitter
                // (the failure mode that killed the first 5a attempt).
                refreshStatus()
                throw t
            }
        }
        scope.launch {
            apply(command)
        }
        refreshStatus()
    }

    private fun apply(command: VoiceCommand) {
        // Runs on the voice control thread only: every command observes
        // all prior commands, so Stop-before-route needs no recheck
        // protocol and Start-vs-initial-route stays either-order.
        when (command) {
            is VoiceCommand.Start -> {
                if (closed.get()) {
                    refreshStatus()
                    return
                }
                if (!createNativeOnControl()) {
                    refreshStatus()
                    return
                }
                val engine = nativeRef.get() ?: run {
                    refreshStatus()
                    return
                }
                // A healthy session starting twice with identical decisions
                // is a no-op; anything else tears the live pipeline down
                // first, so reset() never races in-flight capture (Stop
                // always separates production sessions; this covers retries
                // and harness paths).
                val prev = lastStartRef.get()
                if (runningFlag.get() && prev != null && prev == command && startHealthy.get()) {
                    refreshStatus()
                    return
                }
                if (runningFlag.get()) {
                    try {
                        tearDownPipeline()
                    } catch (e: Exception) {
                        loggerFine("Dropping pipeline teardown on re-start: $e")
                    }
                }
                lastStartRef.set(command)
                startHealthy.set(false)
                formatRef.set(command.format)
                endpointRef.set(Endpoint(command.host, command.port))
                runningFlag.set(true)
                try {
                    engine.setFormat(
                        if (command.format.udp == UdpFormat.PROTOBUF) 1 else 0,
                        command.format.legacyServerVersion,
                    )
                } catch (e: Exception) {
                    loggerFine("Dropping format push: $e")
                }
                if (command.keys != null) {
                    pendingKeys.set(
                        Triple(
                            command.keys.key.toByteArray(),
                            command.keys.clientNonce.toByteArray(),
                            command.keys.serverNonce.toByteArray(),
                        ),
                    )
                }
                val startKeys = command.keys?.let {
                    Triple(
                        it.key.toByteArray(),
                        it.clientNonce.toByteArray(),
                        it.serverNonce.toByteArray(),
                    )
                } ?: pendingKeys.get()
                if (startKeys != null) {
                    try {
                        engine.setKeys(startKeys.first, startKeys.second, startKeys.third)
                    } catch (e: Exception) {
                        loggerFine("Dropping Start keys: $e")
                    }
                }
                // RNNoise model (control thread, while capture is stopped):
                // load once from assets and push to the native input engine.
                // Missing model leaves the denoiser bypassing (process copies
                // input, returns -1), which the continuous-mode tests pin.
                try {
                    val ctx = context
                    if (ctx != null) {
                        RnnoiseModel.load(ctx)
                        val model = RnnoiseModel.cached()
                        if (model != null) {
                            engine.setRnnoiseModel(model)
                        }
                    }
                } catch (e: Exception) {
                    loggerFine("Dropping RNNoise model push: $e")
                }
                // Reset while no render or capture is live (pipelines build
                // after Start; the previous one joined in its teardown).
                try {
                    engine.reset()
                } catch (e: Exception) {
                    loggerFine("Dropping engine reset: $e")
                }
                pushPolicy()
                pushSettings()
                try {
                    nativeRef.get()?.setVoiceRoute(usingUdp.get())
                } catch (e: Exception) {
                    loggerFine("Dropping route push: $e")
                }
                try {
                    tryEnsurePipeline()
                } catch (e: Exception) {
                    loggerFine("Dropping pipeline ensure: $e")
                }
                if (usingUdp.get()) {
                    val endpoint = endpointRef.get()
                    if (endpoint != null) {
                        try {
                            if (engine.start(endpoint.host, endpoint.port)) {
                                startHealthy.set(true)
                            } else {
                                if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
                                    loggerFine("Dropping UdpUnavailable (event buffer full)")
                                }
                            }
                        } catch (e: Exception) {
                            loggerFine("Dropping native start: $e")
                        }
                    }
                } else {
                    // Tunnelled session: no socket to fail, Start is done.
                    startHealthy.set(true)
                }
            }
            is VoiceCommand.Stop -> {
                endpointRef.set(null)
                usingUdp.set(false)
                runningFlag.set(false)
                startHealthy.set(false)
                lastStartRef.set(null)
                // Pipeline first (joins capture/render), then native stop
                // (joins send/receive/notifier, clears both engines/rings,
                // delivers the final empty snapshot via the notifier and
                // resets the route to tunnel): the talk clear is the
                // notifier's own delivery after the last render, so no
                // Kotlin writer can resurrect or duplicate it (voice.md).
                // Render/capture joins precede native destroy (in close),
                // satisfying RenderGuard/CaptureGuard.
                try {
                    tearDownPipeline()
                } catch (e: Exception) {
                    loggerFine("Dropping pipeline teardown: $e")
                }
                try {
                    nativeRef.get()?.stop()
                } catch (e: Exception) {
                    loggerFine("Dropping native stop: $e")
                }
            }
            is VoiceCommand.SetVoiceRoute -> {
                usingUdp.set(command.udp)
                try {
                    nativeRef.get()?.setVoiceRoute(command.udp)
                } catch (e: Exception) {
                    loggerFine("Dropping route push: $e")
                }
                if (command.udp) {
                    val endpoint = endpointRef.get()
                    if (runningFlag.get() && endpoint != null) {
                        try {
                            if (nativeRef.get()?.start(endpoint.host, endpoint.port) == false) {
                                if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
                                    loggerFine("Dropping UdpUnavailable (event buffer full)")
                                }
                            }
                        } catch (e: Exception) {
                            loggerFine("Dropping native start: $e")
                        }
                    }
                }
                // The socket stays open across tunnel switches until Stop.
            }
            is VoiceCommand.SendPing -> sendUdpPing(command.timestampMicros)
        }
        refreshStatus()
    }

    override fun setKeys(keys: CryptKeys) {
        if (!keys.isWellFormed()) return
        // Stash first: CryptSetup can beat the SYNCED Start, and the stash
        // is what apply(Start) falls back to. The live push is best-effort
        // (no engine yet before the first Start); Start carries the session
        // keys, so nothing is lost.
        pendingKeys.set(
            Triple(
                keys.key.toByteArray(),
                keys.clientNonce.toByteArray(),
                keys.serverNonce.toByteArray(),
            ),
        )
        try {
            nativeRef.get()?.setKeys(
                keys.key.toByteArray(),
                keys.clientNonce.toByteArray(),
                keys.serverNonce.toByteArray(),
            )
        } catch (e: Exception) {
            loggerFine("Dropping native setKeys: $e")
        }
        refreshStatus()
    }

    override fun setDecryptIv(iv: ByteString) {
        if (iv.size() != 16) return
        try {
            nativeRef.get()?.setDecryptIv(iv.toByteArray())
        } catch (e: Exception) {
            loggerFine("Dropping native setDecryptIv: $e")
        }
    }

    override fun encryptIv(): ByteString {
        return try {
            val raw = nativeRef.get()?.getEncryptIv()
            if (raw != null && raw.size == 16) ByteString.copyFrom(raw)
            else ByteString.EMPTY
        } catch (e: Exception) {
            ByteString.EMPTY
        }
    }

    override fun queueTunnelled(packet: ByteArray) {
        if (packet.isEmpty()) {
            loggerFine("Dropping empty tunnelled packet")
            return
        }
        try {
            nativeRef.get()?.queueTunnelled(packet)
        } catch (e: Exception) {
            loggerFine("Dropping tunnelled packet: $e")
        }
        refreshStatus()
    }

    override fun removeUser(session: Int) {
        try {
            nativeRef.get()?.pushRemoveUser(session)
        } catch (e: Exception) {
            loggerFine("Dropping removeUser: $e")
        }
    }

    // RenderDriver: the render thread's only path into the owned engine.

    override fun render(out: ShortArray?, offset: Int, length: Int): Int {
        return try {
            nativeRef.get()?.render(out, offset, length) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun awaitWork(timeoutMs: Int): Boolean {
        return try {
            nativeRef.get()?.awaitWork(timeoutMs) ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun lastHasActiveVoices(): Boolean {
        return try {
            nativeRef.get()?.lastHasActiveVoices() ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun wakeForStop() {
        try {
            nativeRef.get()?.wakeForStop()
        } catch (ignored: Exception) {
        }
    }

    /** Capture entry (realtime capture thread): forwards PCM to the native input engine. */
    fun captureFrame(pcm: ShortArray, offset: Int, length: Int) {
        try {
            nativeRef.get()?.captureFrame(pcm, offset, length)
        } catch (e: Exception) {
            loggerFine("Dropping capture frame: $e")
        }
        // Drop counts ride the render path (nativeRender refreshes them);
        // no status refresh here to keep the realtime thread allocation-free.
    }

    private fun sendUdpPing(timestampMicros: Long) {
        if (!usingUdp.get()) return
        if (!runningFlag.get()) return
        val engine = nativeRef.get() ?: return
        // Step 5a.4: the control thread only enqueues; the send thread
        // frames and sends. Ping framing is native, so the interim Kotlin
        // codec bridge is no longer on this path.
        try {
            engine.enqueuePing(timestampMicros)
        } catch (e: Exception) {
            loggerFine("Dropping ping enqueue: $e")
        }
    }

    override fun onUdpPing(timestampMicros: Long, serverVersionV2: Long, maxBandwidth: Int) {
        val v2 = if (serverVersionV2 != 0L) serverVersionV2 else null
        val bw = if (maxBandwidth != 0) maxBandwidth else null
        if (!_events.tryEmit(VoiceEvent.UdpPing(timestampMicros, v2, bw))) {
            loggerFine("Dropping UdpPing (event buffer full)")
        }
    }

    override fun onCryptResync() {
        if (!_events.tryEmit(VoiceEvent.CryptResyncRequested)) {
            loggerFine("Dropping CryptResyncRequested (event buffer full)")
        }
    }

    override fun onUdpUnavailable() {
        if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
            loggerFine("Dropping UdpUnavailable (event buffer full)")
        }
    }

    // Step 5b item 1: the native notifier delivers AAudio disconnects, but
    // the live 5a path has no streams yet. Item 2 posts the reopen here.
    override fun onStreamDisconnect(generation: Long) = Unit

    override fun onTunnelPacket(packet: ByteArray) {
        if (packet.isEmpty()) {
            loggerFine("Dropping empty tunnel packet from send thread")
            return
        }
        try {
            tunnelSinkRef.get()(packet)
        } catch (e: Exception) {
            loggerFine("Dropping tunnel sink: $e")
        }
        refreshStatus()
    }

    override fun onTalkSnapshot(
        sessions: IntArray,
        states: IntArray,
        droppedInbound: Long,
        droppedTunnel: Long,
        droppedJitter: Long,
        selfTalking: Boolean
    ) {
        val levels = HashMap<Int, TalkState>(sessions.size)
        val count = min(sessions.size, states.size)
        var index = 0
        while (index < count) {
            // TALK_PASSIVE (2) never arrives: the engine strips passive
            // before publish, so absence is the only passive. Unknown
            // ordinals must not insert an entry either.
            val state = when (states[index]) {
                0 -> TalkState.TALKING
                1 -> TalkState.SHOUTING
                3 -> TalkState.WHISPERING
                else -> {
                    Log.w(TAG, "Unknown talk state ordinal: " + states[index])
                    null
                }
            }
            if (state != null) {
                levels[sessions[index]] = state
            }
            index++
        }
        // Step 5a.4: the notifier is the only writer, publishing remote and
        // self atomically so collectors never see a half-applied snapshot.
        talkPublisher.updateAll(levels, selfTalking)
        val droppedOutbound = try {
            nativeRef.get()?.lastDroppedOutbound() ?: lastLoggedDroppedOutbound
        } catch (e: Exception) {
            lastLoggedDroppedOutbound
        }
        if (droppedInbound != lastLoggedDroppedInbound ||
            droppedTunnel != lastLoggedDroppedTunnel ||
            droppedJitter != lastLoggedDroppedJitter ||
            droppedOutbound != lastLoggedDroppedOutbound
        ) {
            lastLoggedDroppedInbound = droppedInbound
            lastLoggedDroppedTunnel = droppedTunnel
            lastLoggedDroppedJitter = droppedJitter
            lastLoggedDroppedOutbound = droppedOutbound
            Log.w(
                TAG, "Voice I/O drops: inbound=" + droppedInbound +
                    " tunnel=" + droppedTunnel + " jitter=" + droppedJitter +
                    " outbound=" + droppedOutbound
            )
        }
        refreshStatus()
    }

    // AudioInput capture thread only: forwards PCM to the native input engine.
    // The buffer is reused by AudioInput: native copies before returning, so
    // no copy is needed here. Partial reads (frameSize < FRAME_SIZE) are
    // carried inside VoiceSend into exact 10 ms frames.
    override fun onAudioInputReceived(frame: ShortArray?, frameSize: Int) {
        if (frame == null || frameSize <= 0) return
        try {
            nativeRef.get()?.captureFrame(frame, 0, frameSize)
        } catch (e: Exception) {
            loggerFine("Dropping capture frame: $e")
        }
    }

    private fun tryEnsurePipeline() {
        // Runs on the voice control thread only (route collects + Start).
        // Every blocking call below starts with checkNotMainThread via the
        // pipeline entries (AudioInput shutdown join, AudioOutput stop join).
        checkNotMainThread()
        if (!runningFlag.get()) {
            tearDownPipeline()
            return
        }
        val routes = routeStates ?: return
        val state = try {
            routes.value
        } catch (e: Exception) {
            loggerFine("Dropping route read: $e")
            return
        }
        // Settling keeps current streams (spec route.md: never observed in
        // practice, never tears down); Idle (and null) stops them.
        if (state is RouteState.Settling) {
            return
        }
        val (route, profile) = when (state) {
            is RouteState.Ready -> state.route to state.profile
            is RouteState.Fallback -> state.route to state.profile
            else -> {
                tearDownPipeline()
                return
            }
        }
        val handset = try {
            prefs?.value?.handsetStream ?: AudioManager.STREAM_MUSIC
        } catch (e: Exception) {
            loggerFine("Dropping prefs read: $e")
            AudioManager.STREAM_MUSIC
        }
        val (audioSource, audioStream) = when (route) {
            AppliedRoute.VOICE_SCO -> MediaRecorder.AudioSource.VOICE_COMMUNICATION to AudioManager.STREAM_VOICE_CALL
            AppliedRoute.VOICE_HANDSET -> MediaRecorder.AudioSource.VOICE_COMMUNICATION to handset
            AppliedRoute.MEDIA -> MediaRecorder.AudioSource.MIC to AudioManager.STREAM_MUSIC
        }
        val existing = pipelineRef.get()
        if (existing != null && existing.route == route && existing.profile == profile &&
            existing.audioSource == audioSource && existing.audioStream == audioStream
        ) {
            return
        }
        tearDownPipeline()
        // Build: input acquires the mic, output acquires the track (via
        // injectable factories so route-following is unit-testable without
        // AudioRecord/Track hardware; production passes the real owners).
        // Failures report PipelineError and retry on the next route event
        // (spec failures: session stays connected without audio).
        val input: InputPipeline
        try {
            input = inputFactory(this, audioSource)
        } catch (e: Exception) {
            Log.w(TAG, "Voice pipeline input build failed; retry on next event: $e")
            if (!_events.tryEmit(VoiceEvent.PipelineError("capture open failed"))) {
                loggerFine("Dropping PipelineError (event buffer full)")
            }
            return
        }
        val output: OutputPipeline = outputFactory(context, this)
        try {
            output.startPlaying(audioStream)
        } catch (e: Exception) {
            Log.w(TAG, "Voice pipeline output build failed; retry on next event: $e")
            try {
                input.shutdown()
            } catch (ignored: Exception) {
            }
            if (!_events.tryEmit(VoiceEvent.PipelineError("playback open failed"))) {
                loggerFine("Dropping PipelineError (event buffer full)")
            }
            return
        }
        // Route intent, not a literal stream: on the voice route, VOICE_CALL
        // selects earpiece, anything else speakerphone. Media routes clear
        // for hygiene (voice-call routing ignores it there).
        setSpeakerphone(route != AppliedRoute.MEDIA && route != AppliedRoute.VOICE_SCO && audioStream != AudioManager.STREAM_VOICE_CALL)
        try {
            input.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "Voice pipeline capture start failed; retry on next event: $e")
        }
        pipelineRef.set(BuiltPipeline(input, output, route, profile, audioSource, audioStream))
    }

    private fun tearDownPipeline() {
        checkNotMainThread()
        val existing = pipelineRef.getAndSet(null) ?: return
        try {
            existing.input.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Voice pipeline input shutdown failed: $e")
        }
        try {
            existing.output.stopPlaying()
        } catch (e: Exception) {
            Log.w(TAG, "Voice pipeline output shutdown failed: $e")
        }
    }

    private fun setSpeakerphone(on: Boolean) {
        val ctx = context ?: return
        try {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            if (audioManager == null) return
            try {
                audioManager.isSpeakerphoneOn = on
            } catch (e: Exception) {
                Log.w(TAG, "VoIP route: setSpeakerphoneOn threw (" + e.message + ")")
            }
        } catch (e: Exception) {
            Log.w(TAG, "VoIP route: no AudioManager (" + e.message + ")")
        }
    }

    /**
     * Control-thread-only native creation (the single writer of nativeRef).
     * Failure maps to PipelineError plus status, exactly like ensureNative's
     * release path, since a scope-thread throw could never reach the
     * submitter (see submit).
     */
    private fun createNativeOnControl(): Boolean {
        if (nativeRef.get() != null) return true
        return try {
            nativeRef.set(engineFactory(this))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Native voice engine creation failed", t)
            if (!_events.tryEmit(VoiceEvent.PipelineError("voice engine unavailable"))) {
                loggerFine("Dropping PipelineError (event buffer full)")
            }
            _status.value = _status.value.copy(lastError = "voice engine unavailable")
            false
        }
    }

    private fun ensureNative() {
        if (nativeRef.get() != null) return
        try {
            nativeRef.set(engineFactory(this))
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                throw IllegalStateException("Native voice engine failed to load", t)
            }
            if (!_events.tryEmit(VoiceEvent.PipelineError("voice engine unavailable"))) {
                loggerFine("Dropping PipelineError (event buffer full)")
            }
            _status.value = _status.value.copy(lastError = "voice engine unavailable")
        }
    }

    private fun pushPolicy() {
        val policy = _latestPolicy.value ?: return
        try {
            nativeRef.get()?.setPolicy(
                policy.locallyMuted.toIntArray(),
                policy.knownSessions.toIntArray(),
                policy.transmitAllowed,
                policy.pttPressed,
                policy.voiceTarget,
                policy.bitrate,
                policy.framesPerPacket,
            )
        } catch (e: Exception) {
            loggerFine("Dropping policy push: $e")
        }
    }

    private fun pushSettings() {
        val settings = _latestSettings.value ?: return
        try {
            nativeRef.get()?.setSettings(
                when (settings.inputMode) {
                    InputMode.VOICE_ACTIVITY -> 0
                    InputMode.PUSH_TO_TALK -> 1
                    InputMode.CONTINUOUS -> 2
                },
                settings.vadMax,
                settings.vadMin,
                settings.amplitudeBoost,
                settings.adaptiveLeveler,
                effectiveHalfDuplex(settings.halfDuplex, settings.inputMode),
            )
        } catch (e: Exception) {
            loggerFine("Dropping settings push: $e")
        }
    }

    private fun refreshStatus() {
        val stats = try {
            nativeRef.get()?.getStats()
        } catch (e: Exception) {
            null
        }
        val crypt = if (stats != null && stats.size >= 4) {
            CryptCounts(good = stats[0], late = stats[1], lost = stats[2], resync = stats[3])
        } else {
            _status.value.localCrypt
        }
        // Effective encoder values (server cap applied) when a policy is
        // published; fall back to the raw targets before the first policy.
        // Fixes 5a.4 regression where `getCurrentBandwidth` read targets and
        // over-reported under a cap (issue 15: VoiceStatus replaces handler).
        val effective = _latestPolicy.value
        val prev = _status.value
        _status.value = VoiceStatus(
            localCrypt = crypt,
            bitrate = effective?.bitrate ?: targetBitrate.get(),
            framesPerPacket = effective?.framesPerPacket ?: targetFramesPerPacket.get(),
            running = runningFlag.get(),
            droppedOutbound = try {
                nativeRef.get()?.lastDroppedOutbound() ?: prev.droppedOutbound
            } catch (e: Exception) {
                prev.droppedOutbound
            },
            droppedInbound = try {
                nativeRef.get()?.lastDroppedInbound() ?: prev.droppedInbound
            } catch (e: Exception) {
                prev.droppedInbound
            },
            droppedJitter = try {
                nativeRef.get()?.lastDroppedJitter() ?: prev.droppedJitter
            } catch (e: Exception) {
                prev.droppedJitter
            },
            lastError = prev.lastError,
        )
    }

    private fun loggerFine(message: String) {
        java.util.logging.Logger.getLogger(TAG).fine(message)
    }

    /**
     * Application end of life. Non-blocking like RouteControl: the
     * pipeline-teardown + stop-join-destroy runs on the command scope so
     * Service.onDestroy (main) never waits on thread joins, and destroy
     * runs after the queued stops. Render and capture joins precede native
     * destroy so the RenderGuard/CaptureGuard asserts never fire.
     */
    fun close(): Job {
        // Mark closed first: a Start submitted concurrently is dropped in
        // apply() instead of creating an engine this teardown will never
        // destroy (submit/close race).
        closed.set(true)
        val teardown = scope.launch(NonCancellable) {
            try {
                tearDownPipeline()
            } catch (ignored: Exception) {
            }
            try {
                nativeRef.get()?.stop()
            } catch (ignored: Exception) {
            }
            try {
                nativeRef.getAndSet(null)?.destroy()
            } catch (ignored: Exception) {
            }
        }
        teardown.invokeOnCompletion {
            try {
                scope.cancel()
            } catch (ignored: Exception) {
            }
        }
        return teardown
    }
}
