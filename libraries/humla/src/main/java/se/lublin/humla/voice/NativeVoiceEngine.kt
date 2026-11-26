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

import java.util.concurrent.atomic.AtomicLong
import se.lublin.humla.HumlaNative

/**
 * Native voice engine surface (step 5a.3b facade, 5a.4 send half).
 *
 * One application-scoped object owns the output engine, both inbound rings,
 * the sender-filter policy cell, the capture gate and settings cells, the
 * UDP receive half, the capture/send half, and the talk notifier. Voice
 * stays native-direct: the receive thread and the tunnel caller push
 * straight into the owned rings, capture pushes into the outbound ring. The
 * only Kotlin calls are pings, resync, unavailability (receive thread),
 * tunnelled packets (send thread), and talk snapshots with self-talking
 * (notifier thread). Render and capture never call into Kotlin.
 */
interface EngineNative {
    fun setFormat(udpFormat: Int)
    fun setPolicy(muted: IntArray?, known: IntArray?, transmitAllowed: Boolean, pttPressed: Boolean, voiceTarget: Int, bitrate: Int, framesPerPacket: Int)
    fun setSettings(inputMode: Int, vadMax: Float, vadMin: Float, amplitudeBoost: Float, adaptiveLeveler: Boolean, halfDuplex: Boolean)
    fun setVoiceRoute(udp: Boolean)
    fun setRnnoiseModel(model: ByteArray?): Boolean
    fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean
    fun setDecryptIv(div: ByteArray?): Boolean
    fun getEncryptIv(): ByteArray?
    fun start(host: String, port: Int): Boolean
    fun stop()
    fun reset()
    fun enqueuePing(timestampMicros: Long): Boolean
    fun captureFrame(pcm: ShortArray?, offset: Int, length: Int)
    fun queueTunnelled(packet: ByteArray?): Boolean
    fun pushRemoveUser(session: Int): Boolean
    fun render(out: ShortArray?, offset: Int, length: Int): Int
    // Step 5b AAudio streams. Open returns the input session ID and the new
    // stream generation, or null when either stream fails to open.
    fun openStreams(inputPreset: Int, usage: Int): LongArray?
    fun startStreams(): Boolean
    fun stopStreams()
    fun closeStreams()
    fun lastDroppedInbound(): Long
    fun lastDroppedTunnel(): Long
    fun lastDroppedJitter(): Long
    fun lastDroppedOutbound(): Long
    fun getStats(): IntArray?
    fun destroy()
}

/**
 * JNI over the unified native voice engine context.
 */
class NativeVoiceEngine : EngineNative {
    companion object {
        @JvmStatic
        private external fun nativeCreate(listener: Any): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeSetFormat(handle: Long, udpFormat: Int)

        @JvmStatic
        private external fun nativeSetPolicy(handle: Long, muted: IntArray?, known: IntArray?, transmitAllowed: Boolean, pttPressed: Boolean, voiceTarget: Int, bitrate: Int, framesPerPacket: Int)

        @JvmStatic
        private external fun nativeSetSettings(handle: Long, inputMode: Int, vadMax: Float, vadMin: Float, amplitudeBoost: Float, adaptiveLeveler: Boolean, halfDuplex: Boolean)

        @JvmStatic
        private external fun nativeSetVoiceRoute(handle: Long, udp: Boolean)

        @JvmStatic
        private external fun nativeSetRnnoiseModel(handle: Long, model: ByteArray?): Boolean

        @JvmStatic
        private external fun nativeEnqueuePing(handle: Long, timestampMicros: Long): Boolean

        @JvmStatic
        private external fun nativeCaptureFrame(handle: Long, pcm: ShortArray?, offset: Int, length: Int)

        @JvmStatic
        private external fun nativeSetKeys(
            handle: Long,
            key: ByteArray?,
            eiv: ByteArray?,
            div: ByteArray?
        ): Boolean

        @JvmStatic
        private external fun nativeSetDecryptIv(handle: Long, div: ByteArray?): Boolean

        @JvmStatic
        private external fun nativeGetEncryptIv(handle: Long): ByteArray?

        @JvmStatic
        private external fun nativeStart(handle: Long, host: String, port: Int): Boolean

        @JvmStatic
        private external fun nativeStop(handle: Long)

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeQueueTunnelled(handle: Long, packet: ByteArray?): Boolean

        @JvmStatic
        private external fun nativePushRemoveUser(handle: Long, session: Int): Boolean

        @JvmStatic
        private external fun nativeRender(handle: Long, out: ShortArray?, offset: Int, length: Int): Int

        @JvmStatic
        private external fun nativeOpenStreams(handle: Long, inputPreset: Int, usage: Int): LongArray?

        @JvmStatic
        private external fun nativeStartStreams(handle: Long): Boolean

        @JvmStatic
        private external fun nativeStopStreams(handle: Long)

        @JvmStatic
        private external fun nativeCloseStreams(handle: Long)

        @JvmStatic
        private external fun nativeLastDroppedInbound(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedTunnel(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedJitter(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedOutbound(handle: Long): Long

        @JvmStatic
        private external fun nativeGetStats(handle: Long): IntArray?
    }

    private val nativeHandle = AtomicLong(0)
    private val listener: Listener?

    constructor(listener: Listener?) {
        HumlaNative.ensureLoaded()
        this.listener = listener
        nativeHandle.set(nativeCreate(listener as Any))
        if (nativeHandle.get() == 0L) {
            throw IllegalStateException("Native voice engine creation failed")
        }
    }

    /** Public for unit testing handle guards without native loading. */
    constructor(handle: Long) {
        listener = null
        nativeHandle.set(handle)
    }

    override fun setFormat(udpFormat: Int) {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeSetFormat(handle, udpFormat)
        }
    }

    override fun setPolicy(muted: IntArray?, known: IntArray?, transmitAllowed: Boolean, pttPressed: Boolean, voiceTarget: Int, bitrate: Int, framesPerPacket: Int) {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeSetPolicy(handle, muted, known, transmitAllowed, pttPressed, voiceTarget, bitrate, framesPerPacket)
        }
    }

    override fun setSettings(inputMode: Int, vadMax: Float, vadMin: Float, amplitudeBoost: Float, adaptiveLeveler: Boolean, halfDuplex: Boolean) {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeSetSettings(handle, inputMode, vadMax, vadMin, amplitudeBoost, adaptiveLeveler, halfDuplex)
        }
    }

    override fun setVoiceRoute(udp: Boolean) {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeSetVoiceRoute(handle, udp)
        }
    }

    override fun setRnnoiseModel(model: ByteArray?): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeSetRnnoiseModel(handle, model)
    }

    override fun enqueuePing(timestampMicros: Long): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeEnqueuePing(handle, timestampMicros)
    }

    override fun captureFrame(pcm: ShortArray?, offset: Int, length: Int) {
        val handle = nativeHandle.get()
        if (handle != 0L && pcm != null && offset >= 0 && length > 0 &&
            offset <= pcm.size && length <= pcm.size - offset
        ) {
            nativeCaptureFrame(handle, pcm, offset, length)
        }
    }

    override fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeSetKeys(handle, key, eiv, div)
    }

    override fun setDecryptIv(div: ByteArray?): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeSetDecryptIv(handle, div)
    }

    override fun getEncryptIv(): ByteArray? {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeGetEncryptIv(handle) else null
    }

    override fun start(host: String, port: Int): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeStart(handle, host, port)
    }

    override fun stop() {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeStop(handle)
        }
    }

    override fun reset() {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeReset(handle)
        }
    }

    override fun queueTunnelled(packet: ByteArray?): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeQueueTunnelled(handle, packet)
    }

    override fun pushRemoveUser(session: Int): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativePushRemoveUser(handle, session)
    }

    override fun render(out: ShortArray?, offset: Int, length: Int): Int {
        val handle = nativeHandle.get()
        if (handle != 0L && out != null && offset >= 0 && length > 0 &&
            offset <= out.size && length <= out.size - offset
        ) {
            return nativeRender(handle, out, offset, length)
        }
        return 0
    }

    override fun openStreams(inputPreset: Int, usage: Int): LongArray? {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeOpenStreams(handle, inputPreset, usage) else null
    }

    override fun startStreams(): Boolean {
        val handle = nativeHandle.get()
        return handle != 0L && nativeStartStreams(handle)
    }

    override fun stopStreams() {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeStopStreams(handle)
        }
    }

    override fun closeStreams() {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeCloseStreams(handle)
        }
    }

    override fun lastDroppedInbound(): Long {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeLastDroppedInbound(handle) else 0
    }

    override fun lastDroppedTunnel(): Long {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeLastDroppedTunnel(handle) else 0
    }

    override fun lastDroppedJitter(): Long {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeLastDroppedJitter(handle) else 0
    }

    override fun lastDroppedOutbound(): Long {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeLastDroppedOutbound(handle) else 0
    }

    override fun getStats(): IntArray? {
        val handle = nativeHandle.get()
        return if (handle != 0L) nativeGetStats(handle) else null
    }

    override fun destroy() {
        val handle = nativeHandle.get()
        nativeHandle.set(0)
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    interface Listener {
        fun onUdpPing(timestampMicros: Long, serverVersionV2: Long, maxBandwidth: Int)
        fun onCryptResync()
        fun onUdpUnavailable()
        fun onTalkSnapshot(
            sessions: IntArray,
            states: IntArray,
            droppedInbound: Long,
            droppedTunnel: Long,
            droppedJitter: Long,
            selfTalking: Boolean
        )
        fun onTunnelPacket(packet: ByteArray)
        fun onStreamDisconnect(generation: Long)
    }
}
