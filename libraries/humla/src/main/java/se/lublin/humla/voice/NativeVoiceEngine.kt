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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.voice

import se.lublin.humla.HumlaNative

/**
 * JNI over the VoiceEngine network context (step 5a.3b): policy cell plus
 * the UDP receive half. Application-scoped; render stays per-pipeline in
 * AudioOutput's NativeVoiceIo until 5a.4, so voice frames cross to Kotlin
 * through [Listener.onVoice] (the non-realtime receive thread calling
 * Kotlin is allowed). The render wake post stays in the voice_io push
 * functions Kotlin calls after the callback.
 */
class NativeVoiceEngine {
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
        private external fun nativeSendDatagram(handle: Long, plain: ByteArray?, length: Int): Boolean

        @JvmStatic
        private external fun nativeQueueTunnelled(handle: Long, packet: ByteArray?): Boolean

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

    fun setFormat(udpFormat: Int, legacyVersion: Int) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeSetFormat(handle, udpFormat, legacyVersion)
        }
    }

    fun setPolicy(muted: IntArray?, known: IntArray?) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeSetPolicy(handle, muted, known)
        }
    }

    fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeSetKeys(handle, key, eiv, div)
    }

    fun setDecryptIv(div: ByteArray?): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeSetDecryptIv(handle, div)
    }

    fun getEncryptIv(): ByteArray? {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetEncryptIv(handle) else null
    }

    fun start(host: String, port: Int): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeStart(handle, host, port)
    }

    fun stop() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeStop(handle)
        }
    }

    fun sendDatagram(plain: ByteArray?, length: Int): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeSendDatagram(handle, plain, length)
    }

    fun queueTunnelled(packet: ByteArray?): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeQueueTunnelled(handle, packet)
    }

    /** Good, late, lost, resync from the native OCB2 engine. */
    fun getStats(): IntArray? {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetStats(handle) else null
    }

    fun destroy() {
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    interface Listener {
        fun onVoice(
            sessions: IntArray,
            payloads: Array<ByteArray?>,
            lengths: IntArray,
            sequences: IntArray,
            flags: IntArray,
            terminators: BooleanArray,
            tunnel: Boolean
        )

        fun onUdpPing(timestampMicros: Long, serverVersionV2: Long, maxBandwidth: Int)
        fun onCryptResync()
        fun onUdpUnavailable()
    }
}
