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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.BuildConfig
import se.lublin.humla.audio.RenderDriver
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.client.VoiceLifecycle
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.CryptKeys
import se.lublin.humla.net.NativeVoiceCodec
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.net.VoiceFormat
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.net.VoiceStatus
import kotlin.math.min

/**
 * Kotlin VoiceEngine facade (step 5a.3b, spec-literal).
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
 * transport's `SetVoiceRoute`; `Stop` resets to tunnel and closes the
 * socket without touching the engines (reset happens at the next Start,
 * when no render is live). Engines keep their state across route changes.
 *
 * Android-free except android.util.Log for the drop log (the audio layer
 * already logs the same way).
 */
class VoiceEngine @JvmOverloads constructor(
    private val tunnelSink: (ByteArray) -> Unit = {},
    private val talkPublisher: TalkActivityPublisher = TalkActivityPublisher(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    private val engineFactory: (NativeVoiceEngine.Listener) -> EngineNative = { NativeVoiceEngine(it) },
) : VoiceLink, VoiceLifecycle, RenderDriver, NativeVoiceEngine.Listener {
    companion object {
        private val TAG: String = VoiceEngine::class.java.name
    }

    /** Service-lifetime talk levels; the notifier feeds remote, AudioHandler feeds self. */
    val talkActivity: StateFlow<TalkActivity> = talkPublisher.talkActivity

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

    @Volatile
    private var native: EngineNative? = null
    private val scopeRef = scope

    private val _latestPolicy = MutableStateFlow<VoicePolicy?>(null)
    val latestPolicy: StateFlow<VoicePolicy?> = _latestPolicy.asStateFlow()

    private var lastLoggedDroppedInbound = 0L
    private var lastLoggedDroppedTunnel = 0L
    private var lastLoggedDroppedJitter = 0L

    /** Policy collector only: publishes the mapped VoicePolicy for the native filter. */
    fun setPolicy(policy: VoicePolicy) {
        _latestPolicy.value = policy
        val engine = native ?: return
        try {
            engine.setPolicy(
                policy.locallyMuted.toIntArray(),
                policy.knownSessions.toIntArray(),
            )
        } catch (e: Exception) {
            loggerFine("Dropping policy push: $e")
        }
    }

    /**
     * Test-only filter push (the interop harness has no PolicyCollector):
     * publishes the muted/known lists straight to the native cell.
     * Production always goes through [setPolicy].
     */
    fun setFilterForTests(muted: IntArray, known: IntArray) {
        try {
            ensureNative()
            native?.setPolicy(muted, known)
        } catch (e: Exception) {
            loggerFine("Dropping test filter push: $e")
        }
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

    /**
     * Voice started without ever opening UDP (interop harness: a network
     * that drops UDP). Fixes the format like [startVoice] but never touches
     * the socket; the transport's 20 s zero-counter giveup still applies.
     */
    fun startVoiceWithoutUdp(format: VoiceFormat) {
        formatRef.set(format)
        runningFlag.set(true)
        try {
            ensureNative()
            // Like Start: drain stale state and (re)start the
            // session-lifetime notifier while no render is live.
            try {
                native?.reset()
            } catch (e: Exception) {
                loggerFine("Dropping engine reset: $e")
            }
            native?.setFormat(
                if (format.udp == UdpFormat.PROTOBUF) 1 else 0,
                format.legacyServerVersion,
            )
        } catch (e: Exception) {
            loggerFine("Dropping format push: $e")
        }
        pushPolicy()
        refreshStatus()
    }

    override fun stopVoice() {
        submit(VoiceCommand.Stop)
    }

    override fun submit(command: VoiceCommand) {
        // Never blocks the caller: only fast atomic state lands here;
        // every blocking native call (socket connect, joins) runs on the
        // single-thread command scope, in submission order. The transport
        // reader and the client dispatcher share this entry, so inline
        // execution stalled TCP framing on DNS/connect.
        when (command) {
            is VoiceCommand.Start -> {
                formatRef.set(command.format)
                endpointRef.set(Endpoint(command.host, command.port))
                runningFlag.set(true)
                // Fast JNI only (format, keys, reset, policy): no socket,
                // no join, safe inline. The blocking connect below runs on
                // the command scope so the transport reader never stalls.
                try {
                    ensureNative()
                } catch (t: Throwable) {
                    // Debug load failure must stay loud to the submitter
                    // (the failure mode that killed the first 5a attempt).
                    refreshStatus()
                    throw t
                }
                val engine = native ?: run {
                    refreshStatus()
                    return
                }
                try {
                    engine.setFormat(
                        if (command.format.udp == UdpFormat.PROTOBUF) 1 else 0,
                        command.format.legacyServerVersion,
                    )
                } catch (e: Exception) {
                    loggerFine("Dropping format push: $e")
                }
                if (command.keys != null) {
                    try {
                        engine.setKeys(
                            command.keys.key.toByteArray(),
                            command.keys.clientNonce.toByteArray(),
                            command.keys.serverNonce.toByteArray(),
                        )
                    } catch (e: Exception) {
                        loggerFine("Dropping Start keys: $e")
                    }
                }
                // Reset while no render is live (pipelines build after
                // Start; the previous one joined in its teardown).
                try {
                    engine.reset()
                } catch (e: Exception) {
                    loggerFine("Dropping engine reset: $e")
                }
                pushPolicy()
                if (usingUdp.get()) {
                    scope.launch {
                        // Rechecked here so a Stop that landed after
                        // submission wins over this queued connect.
                        if (!usingUdp.get()) return@launch
                        val endpoint = endpointRef.get() ?: return@launch
                        if (!runningFlag.get()) return@launch
                        try {
                            if (!engine.start(endpoint.host, endpoint.port)) {
                                if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
                                    loggerFine("Dropping UdpUnavailable (event buffer full)")
                                }
                            }
                        } catch (e: Exception) {
                            loggerFine("Dropping native start: $e")
                        } finally {
                            refreshStatus()
                        }
                    }
                }
            }
            is VoiceCommand.Stop -> {
                endpointRef.set(null)
                usingUdp.set(false)
                runningFlag.set(false)
                scope.launch {
                    try {
                        native?.stop()
                    } catch (e: Exception) {
                        loggerFine("Dropping native stop: $e")
                    }
                }
            }
            is VoiceCommand.SetVoiceRoute -> {
                usingUdp.set(command.udp)
                if (command.udp) {
                    scope.launch {
                        // Rechecked here so a Stop that landed after submission
                        // wins over this queued start.
                        val endpoint = endpointRef.get()
                        if (runningFlag.get() && endpoint != null) {
                            try {
                                if (native?.start(endpoint.host, endpoint.port) == false) {
                                    if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
                                        loggerFine("Dropping UdpUnavailable (event buffer full)")
                                    }
                                }
                            } catch (e: Exception) {
                                loggerFine("Dropping native start: $e")
                            }
                        }
                    }
                }
                // The socket stays open across tunnel switches until Stop.
            }
            is VoiceCommand.SendPing -> scope.launch { sendUdpPing(command.timestampMicros) }
        }
        refreshStatus()
    }

    override fun setKeys(keys: CryptKeys) {
        if (!keys.isWellFormed()) return
        try {
            ensureNative()
            native?.setKeys(
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
            native?.setDecryptIv(iv.toByteArray())
        } catch (e: Exception) {
            loggerFine("Dropping native setDecryptIv: $e")
        }
    }

    override fun encryptIv(): ByteString {
        return try {
            val raw = native?.getEncryptIv()
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
            native?.queueTunnelled(packet)
        } catch (e: Exception) {
            loggerFine("Dropping tunnelled packet: $e")
        }
        refreshStatus()
    }

    override fun removeUser(session: Int) {
        try {
            native?.pushRemoveUser(session)
        } catch (e: Exception) {
            loggerFine("Dropping removeUser: $e")
        }
    }

    fun sendVoice(data: ByteArray, length: Int) {
        if (length < 0 || length > data.size) {
            throw IllegalArgumentException(
                "Requested length $length out of bounds for available data length ${data.size}!",
            )
        }
        if (!usingUdp.get()) {
            tunnelSink(data.copyOf(length))
            refreshStatus()
            return
        }
        if (!runningFlag.get() || endpointRef.get() == null) {
            return
        }
        try {
            native?.sendDatagram(data, length)
        } catch (e: Exception) {
            loggerFine("Dropping voice send: $e")
        }
        refreshStatus()
    }

    // RenderDriver: the render thread's only path into the owned engine.

    override fun render(out: ShortArray?, offset: Int, length: Int): Int {
        return try {
            native?.render(out, offset, length) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun awaitWork(timeoutMs: Int): Boolean {
        return try {
            native?.awaitWork(timeoutMs) ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun lastHasActiveVoices(): Boolean {
        return try {
            native?.lastHasActiveVoices() ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun wakeForStop() {
        try {
            native?.wakeForStop()
        } catch (ignored: Exception) {
        }
    }

    private fun sendUdpPing(timestampMicros: Long) {
        if (!usingUdp.get()) return
        if (!runningFlag.get()) return
        val engine = native ?: return
        val packet = try {
            if (formatRef.get().udp == UdpFormat.PROTOBUF) {
                NativeVoiceCodec.frameProtobufPing(timestampMicros)
            } else {
                NativeVoiceCodec.frameLegacyPing(timestampMicros)
            }
        } catch (e: Exception) {
            loggerFine("Dropping ping after codec failure: $e")
            null
        } ?: return
        try {
            engine.sendDatagram(packet, packet.size)
        } catch (e: Exception) {
            loggerFine("Dropping ping send: $e")
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

    override fun onTalkSnapshot(
        sessions: IntArray,
        states: IntArray,
        droppedInbound: Long,
        droppedTunnel: Long,
        droppedJitter: Long
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
        talkPublisher.updateRemote(levels)
        if (droppedInbound != lastLoggedDroppedInbound ||
            droppedTunnel != lastLoggedDroppedTunnel ||
            droppedJitter != lastLoggedDroppedJitter
        ) {
            lastLoggedDroppedInbound = droppedInbound
            lastLoggedDroppedTunnel = droppedTunnel
            lastLoggedDroppedJitter = droppedJitter
            Log.w(
                TAG, "Voice I/O drops: inbound=" + droppedInbound +
                    " tunnel=" + droppedTunnel + " jitter=" + droppedJitter
            )
        }
    }

    private fun ensureNative() {
        if (native != null) return
        try {
            native = engineFactory(this)
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
            native?.setPolicy(
                policy.locallyMuted.toIntArray(),
                policy.knownSessions.toIntArray(),
            )
        } catch (e: Exception) {
            loggerFine("Dropping policy push: $e")
        }
    }

    private fun refreshStatus() {
        val stats = try {
            native?.getStats()
        } catch (e: Exception) {
            null
        }
        val crypt = if (stats != null && stats.size >= 4) {
            CryptCounts(good = stats[0], late = stats[1], lost = stats[2], resync = stats[3])
        } else {
            _status.value.localCrypt
        }
        val prev = _status.value
        _status.value = VoiceStatus(
            localCrypt = crypt,
            bitrate = targetBitrate.get(),
            framesPerPacket = targetFramesPerPacket.get(),
            running = runningFlag.get(),
            droppedOutbound = prev.droppedOutbound,
            droppedInbound = try {
                native?.lastDroppedInbound() ?: prev.droppedInbound
            } catch (e: Exception) {
                prev.droppedInbound
            },
            droppedJitter = try {
                native?.lastDroppedJitter() ?: prev.droppedJitter
            } catch (e: Exception) {
                prev.droppedJitter
            },
            lastError = prev.lastError,
        )
    }

    private fun loggerFine(message: String) {
        java.util.logging.Logger.getLogger(TAG).fine(message)
    }

    fun close() {
        try {
            native?.stop()
        } catch (ignored: Exception) {
        }
        try {
            native?.destroy()
        } catch (ignored: Exception) {
        }
        native = null
        try {
            scopeRef.cancel()
        } catch (ignored: Exception) {
        }
    }
}
