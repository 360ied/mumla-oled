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

import se.lublin.humla.HumlaNative

/**
 * Native voice engine surface (step 5a.3b, unified context).
 *
 * One application-scoped object owns the output engine, both inbound rings,
 * the sender-filter policy cell, the UDP receive half, and the talk
 * notifier. Voice stays native-direct: the receive thread and the tunnel
 * caller push straight into the owned rings. The only Kotlin calls are
 * pings, resync, unavailability (receive thread, non-realtime) and talk
 * snapshots (notifier thread). Render never calls into Kotlin.
 */
interface EngineNative {
    fun setFormat(udpFormat: Int, legacyVersion: Int)
    fun setPolicy(muted: IntArray?, known: IntArray?)
    fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean
    fun setDecryptIv(div: ByteArray?): Boolean
    fun getEncryptIv(): ByteArray?
    fun start(host: String, port: Int): Boolean
    fun stop()
    fun reset()
    fun sendDatagram(plain: ByteArray?, length: Int): Boolean
    fun queueTunnelled(packet: ByteArray?): Boolean
    fun pushRemoveUser(session: Int): Boolean
    fun render(out: ShortArray?, offset: Int, length: Int): Int
    fun awaitWork(timeoutMs: Int): Boolean
    fun wakeForStop()
    fun lastHasActiveVoices(): Boolean
    fun lastDroppedInbound(): Long
    fun lastDroppedTunnel(): Long
    fun lastDroppedJitter(): Long
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
        private external fun nativeSetFormat(handle: Long, udpFormat: Int, legacyVersion: Int)

        @JvmStatic
        private external fun nativeSetPolicy(handle: Long, muted: IntArray?, known: IntArray?)

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
        private external fun nativeSendDatagram(handle: Long, plain: ByteArray?, length: Int): Boolean

        @JvmStatic
        private external fun nativeQueueTunnelled(handle: Long, packet: ByteArray?): Boolean

        @JvmStatic
        private external fun nativePushRemoveUser(handle: Long, session: Int): Boolean

        @JvmStatic
        private external fun nativeRender(handle: Long, out: ShortArray?, offset: Int, length: Int): Int

        @JvmStatic
        private external fun nativeAwaitWork(handle: Long, timeoutMs: Int): Boolean

        @JvmStatic
        private external fun nativeWakeForStop(handle: Long)

        @JvmStatic
        private external fun nativeLastHasActiveVoices(handle: Long): Boolean

        @JvmStatic
        private external fun nativeLastDroppedInbound(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedTunnel(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedJitter(handle: Long): Long

        @JvmStatic
        private external fun nativeGetStats(handle: Long): IntArray?
    }

    private var nativeHandle: Long = 0
    private val listener: Listener?

    constructor(listener: Listener?) {
        HumlaNative.ensureLoaded()
        this.listener = listener
        nativeHandle = nativeCreate(listener as Any)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Native voice engine creation failed")
        }
    }

    /** Public for unit testing handle guards without native loading. */
    constructor(handle: Long) {
        listener = null
        nativeHandle = handle
    }

    override fun setFormat(udpFormat: Int, legacyVersion: Int) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeSetFormat(handle, udpFormat, legacyVersion)
        }
    }

    override fun setPolicy(muted: IntArray?, known: IntArray?) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeSetPolicy(handle, muted, known)
        }
    }

    override fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeSetKeys(handle, key, eiv, div)
    }

    override fun setDecryptIv(div: ByteArray?): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeSetDecryptIv(handle, div)
    }

    override fun getEncryptIv(): ByteArray? {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetEncryptIv(handle) else null
    }

    override fun start(host: String, port: Int): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeStart(handle, host, port)
    }

    override fun stop() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeStop(handle)
        }
    }

    override fun reset() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeReset(handle)
        }
    }

    override fun sendDatagram(plain: ByteArray?, length: Int): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeSendDatagram(handle, plain, length)
    }

    override fun queueTunnelled(packet: ByteArray?): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeQueueTunnelled(handle, packet)
    }

    override fun pushRemoveUser(session: Int): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativePushRemoveUser(handle, session)
    }

    override fun render(out: ShortArray?, offset: Int, length: Int): Int {
        val handle = nativeHandle
        if (handle != 0L && out != null && offset >= 0 && length > 0 &&
            offset <= out.size && length <= out.size - offset
        ) {
            return nativeRender(handle, out, offset, length)
        }
        return 0
    }

    override fun awaitWork(timeoutMs: Int): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeAwaitWork(handle, timeoutMs)
    }

    override fun wakeForStop() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeWakeForStop(handle)
        }
    }

    override fun lastHasActiveVoices(): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeLastHasActiveVoices(handle)
    }

    override fun lastDroppedInbound(): Long {
        val handle = nativeHandle
        return if (handle != 0L) nativeLastDroppedInbound(handle) else 0
    }

    override fun lastDroppedTunnel(): Long {
        val handle = nativeHandle
        return if (handle != 0L) nativeLastDroppedTunnel(handle) else 0
    }

    override fun lastDroppedJitter(): Long {
        val handle = nativeHandle
        return if (handle != 0L) nativeLastDroppedJitter(handle) else 0
    }

    override fun getStats(): IntArray? {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetStats(handle) else null
    }

    override fun destroy() {
        val handle = nativeHandle
        nativeHandle = 0
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
            droppedJitter: Long
        )
    }
}
