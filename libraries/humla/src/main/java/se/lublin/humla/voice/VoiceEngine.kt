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

import com.google.protobuf.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.BuildConfig
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.client.VoiceLifecycle
import se.lublin.humla.net.CryptKeys
import se.lublin.humla.net.DecodedVoiceFrame
import se.lublin.humla.net.NativeVoiceCodec
import se.lublin.humla.net.UdpFormat
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.net.VoiceFormat
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.net.VoiceStatus

/**
 * Kotlin VoiceEngine facade (step 5a.3b).
 *
 * Implements the client's [VoiceLifecycle] and the transport's [VoiceLink].
 * Network voice runs native: the UDP socket and receive thread (recvfrom,
 * OCB2 decrypt, parseDatagram, sender filter) live in [NativeVoiceEngine];
 * render stays per-pipeline in AudioOutput's NativeVoiceIo until 5a.4, so
 * parsed frames cross to Kotlin through the listener and push into the
 * rings there. The transport keeps the fallback decision: native reports
 * pings, resyncs, and unavailability; TransportImpl switches routes.
 *
 * Takes over the service-lifetime [TalkActivity] publisher from 5a.3a (same
 * instance, new owner). `Start` carries the transport's per-session decision
 * (UDP format, legacy version); `Stop` resets the route to tunnel.
 *
 * Android-free: no android.* imports.
 */
class VoiceEngine @JvmOverloads constructor(
    private val tunnelSink: (ByteArray) -> Unit = {},
    private val talkPublisher: TalkActivityPublisher = TalkActivityPublisher(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : VoiceLink, VoiceLifecycle, NativeVoiceEngine.Listener {
    companion object {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(VoiceEngine::class.java.name)
    }

    /** Service-lifetime talk levels; the notifier feeds remote, AudioHandler feeds self. */
    val talkActivity: StateFlow<TalkActivity> = talkPublisher.talkActivity

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(VoiceStatus.initial())
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    /** Frames sink for the current pipeline (VoiceControl sets per build). */
    var framesSink: ((List<DecodedVoiceFrame>, Boolean) -> Unit)? = null

    /** Drops provider for status (VoiceControl sets per build; AudioOutput owns the rings). */
    var dropProvider: (() -> Triple<Long, Long, Long>)? = null

    private val usingUdp = AtomicBoolean(false)
    private data class Endpoint(val host: String, val port: Int)
    private val endpointRef = AtomicReference<Endpoint?>(null)
    private val formatRef = AtomicReference(VoiceFormat.DEFAULT)
    private val targetFramesPerPacket = AtomicInteger(2)
    private val targetBitrate = AtomicInteger(40000)
    private val runningFlag = AtomicBoolean(false)

    private var native: NativeVoiceEngine? = null
    private val scopeRef = scope

    private val _latestPolicy = MutableStateFlow<VoicePolicy?>(null)
    val latestPolicy: StateFlow<VoicePolicy?> = _latestPolicy.asStateFlow()

    fun setPolicy(policy: VoicePolicy) {
        _latestPolicy.value = policy
        val nativeCopy = native ?: return
        try {
            nativeCopy.setPolicy(
                policy.locallyMuted.toIntArray(),
                policy.knownSessions.toIntArray(),
            )
        } catch (e: Exception) {
            logger.fine("Dropping policy push: $e")
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
            logger.fine("Dropping test filter push: $e")
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

    /** Current session format (test interop mirrors sender framing to it). */
    fun udpFormat(): UdpFormat = formatRef.get().udp

    override fun startVoice(host: String, port: Int, format: VoiceFormat) {
        formatRef.set(format)
        endpointRef.set(Endpoint(host, port))
        ensureNative()
        val engine = native ?: run {
            refreshStatus()
            return
        }
        try {
            engine.setFormat(
                if (format.udp == UdpFormat.PROTOBUF) 1 else 0,
                format.legacyServerVersion,
            )
        } catch (e: Exception) {
            logger.fine("Dropping format push: $e")
        }
        pushPolicy()
        runningFlag.set(true)
        if (usingUdp.get()) {
            if (!engine.start(host, port)) {
                if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
                    logger.fine("Dropping UdpUnavailable (event buffer full)")
                }
            }
        }
        refreshStatus()
    }

    override fun stopVoice() {
        endpointRef.set(null)
        usingUdp.set(false)
        runningFlag.set(false)
        try {
            native?.stop()
        } catch (e: Exception) {
            logger.fine("Dropping native stop: $e")
        }
        refreshStatus()
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
            native?.setFormat(
                if (format.udp == UdpFormat.PROTOBUF) 1 else 0,
                format.legacyServerVersion,
            )
        } catch (e: Exception) {
            logger.fine("Dropping format push: $e")
        }
        pushPolicy()
        refreshStatus()
    }

    override fun submit(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Start -> startVoice(command.host, command.port, command.format)
            is VoiceCommand.Stop -> stopVoice()
            is VoiceCommand.SetVoiceRoute -> {
                usingUdp.set(command.udp)
                if (command.udp) {
                    val endpoint = endpointRef.get()
                    if (runningFlag.get() && endpoint != null) {
                        try {
                            if (native?.start(endpoint.host, endpoint.port) == false) {
                                if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
                                    logger.fine("Dropping UdpUnavailable (event buffer full)")
                                }
                            }
                        } catch (e: Exception) {
                            logger.fine("Dropping native start: $e")
                        }
                    }
                }
            }
            is VoiceCommand.SendPing -> sendUdpPing(command.timestampMicros)
        }
        refreshStatus()
    }

    override fun setKeys(keys: CryptKeys) {
        if (!keys.isWellFormed()) return
        ensureNative()
        try {
            native?.setKeys(
                keys.key.toByteArray(),
                keys.clientNonce.toByteArray(),
                keys.serverNonce.toByteArray(),
            )
        } catch (e: Exception) {
            logger.fine("Dropping native setKeys: $e")
        }
        refreshStatus()
    }

    override fun setDecryptIv(iv: ByteString) {
        if (iv.size() != 16) return
        try {
            native?.setDecryptIv(iv.toByteArray())
        } catch (e: Exception) {
            logger.fine("Dropping native setDecryptIv: $e")
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
            logger.fine("Dropping empty tunnelled packet")
            return
        }
        try {
            native?.queueTunnelled(packet)
        } catch (e: Exception) {
            logger.fine("Dropping tunnelled packet: $e")
        }
        refreshStatus()
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
            logger.fine("Dropping voice send: $e")
        }
        refreshStatus()
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
            logger.fine("Dropping ping after codec failure: $e")
            null
        } ?: return
        try {
            engine.sendDatagram(packet, packet.size)
        } catch (e: Exception) {
            logger.fine("Dropping ping send: $e")
        }
    }

    override fun onVoice(
        sessions: IntArray,
        payloads: Array<ByteArray?>,
        lengths: IntArray,
        sequences: IntArray,
        flags: IntArray,
        terminators: BooleanArray,
        tunnel: Boolean
    ) {
        val count = minOf(sessions.size, lengths.size, sequences.size, flags.size)
        if (count <= 0) return
        val frames = ArrayList<DecodedVoiceFrame>(count)
        var i = 0
        while (i < count) {
            val opus = payloads.getOrNull(i)
            val len = lengths.getOrNull(i) ?: 0
            val isTerm = terminators.getOrNull(i) ?: false
            if (len > 0 && opus != null && len <= opus.size) {
                frames.add(
                    DecodedVoiceFrame(
                        session = sessions[i],
                        sequence = sequences[i],
                        flags = flags[i],
                        opus = opus.copyOf(len),
                        isTerminator = isTerm,
                    )
                )
            } else if (isTerm) {
                frames.add(
                    DecodedVoiceFrame(
                        session = sessions[i],
                        sequence = sequences[i],
                        flags = flags[i],
                        opus = ByteArray(0),
                        isTerminator = true,
                    )
                )
            }
            i++
        }
        if (frames.isEmpty()) return
        try {
            framesSink?.invoke(frames, tunnel)
        } catch (e: Exception) {
            logger.fine("Dropping voice frames: $e")
        }
        refreshStatus()
    }

    override fun onUdpPing(timestampMicros: Long, serverVersionV2: Long, maxBandwidth: Int) {
        val v2 = if (serverVersionV2 != 0L) serverVersionV2 else null
        val bw = if (maxBandwidth != 0) maxBandwidth else null
        if (!_events.tryEmit(VoiceEvent.UdpPing(timestampMicros, v2, bw))) {
            logger.fine("Dropping UdpPing (event buffer full)")
        }
    }

    override fun onCryptResync() {
        if (!_events.tryEmit(VoiceEvent.CryptResyncRequested)) {
            logger.fine("Dropping CryptResyncRequested (event buffer full)")
        }
    }

    override fun onUdpUnavailable() {
        if (!_events.tryEmit(VoiceEvent.UdpUnavailable)) {
            logger.fine("Dropping UdpUnavailable (event buffer full)")
        }
    }

    private fun ensureNative() {
        if (native != null) return
        try {
            native = NativeVoiceEngine(this)
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) {
                throw IllegalStateException("Native voice engine failed to load", e)
            }
            if (!_events.tryEmit(VoiceEvent.PipelineError("voice engine unavailable"))) {
                logger.fine("Dropping PipelineError (event buffer full)")
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
            logger.fine("Dropping policy push: $e")
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
        val drops = try {
            dropProvider?.invoke()
        } catch (e: Exception) {
            null
        }
        val prev = _status.value
        _status.value = VoiceStatus(
            localCrypt = crypt,
            bitrate = targetBitrate.get(),
            framesPerPacket = targetFramesPerPacket.get(),
            running = runningFlag.get(),
            droppedOutbound = prev.droppedOutbound,
            droppedInbound = drops?.first ?: prev.droppedInbound,
            droppedJitter = drops?.third ?: prev.droppedJitter,
            lastError = prev.lastError,
        )
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
