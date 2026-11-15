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
 * Java interface for the native voice I/O layer (libhumlaaudio.so), step
 * 5a.3a native render.
 *
 * Kotlin parses transport datagrams and pushes the surviving voice frames
 * into the inbound ring (UDP path) or the tunnel ring (tunnel path);
 * removals travel as control items on the tunnel ring. The render thread
 * this object is handed to drains both rings into the output engine it
 * alone drives and mixes PCM; talk snapshots reach Kotlin through the
 * notifier thread's single onTalkSnapshot call per change, never through
 * the render path.
 *
 * Locking: none inside. Every push and the teardown in
 * AudioOutput.stopPlaying run under AudioOutput's existing monitor, which
 * keeps each ring to one producer at a time and serializes pushes against
 * destroy; the render thread never takes that monitor, and destroy runs
 * only after the render thread is joined. A plain field is enough because
 * every access is under the caller's monitor or after the join.
 */
class NativeVoiceIo {
    companion object {
        /** Talk ordinals; must match AudioOutputEngine::OutputTalkState. */
        const val TALK_TALKING = 0
        const val TALK_SHOUTING = 1
        const val TALK_PASSIVE = 2
        const val TALK_WHISPERING = 3

        @JvmStatic
        private external fun nativeCreate(listener: Any?): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativePushInbound(
            handle: Long,
            session: Int,
            data: ByteArray?,
            length: Int,
            sequence: Int,
            flags: Int,
            isTerminator: Boolean
        ): Boolean

        @JvmStatic
        private external fun nativePushTunnel(
            handle: Long,
            session: Int,
            data: ByteArray?,
            length: Int,
            sequence: Int,
            flags: Int,
            isTerminator: Boolean
        ): Boolean

        @JvmStatic
        private external fun nativePushRemoveUser(handle: Long, session: Int): Boolean

        @JvmStatic
        private external fun nativeRender(handle: Long, out: ShortArray?, offset: Int, length: Int): Int

        @JvmStatic
        private external fun nativeLastHasActiveVoices(handle: Long): Boolean

        @JvmStatic
        private external fun nativeLastDroppedInbound(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedTunnel(handle: Long): Long

        @JvmStatic
        private external fun nativeLastDroppedJitter(handle: Long): Long

        @JvmStatic
        private external fun nativeClear(handle: Long)
    }

    private var nativeHandle: Long = 0
    private val listener: VoiceIoListener?

    constructor(listener: VoiceIoListener?) {
        HumlaNative.ensureLoaded()
        this.listener = listener
        nativeHandle = nativeCreate(listener)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Native voice I/O creation failed")
        }
    }

    /** Public for unit testing handle guards without native loading. */
    constructor(handle: Long) {
        listener = null
        nativeHandle = handle
    }

    fun pushInbound(
        session: Int,
        data: ByteArray?,
        length: Int,
        sequence: Int,
        flags: Int,
        isTerminator: Boolean
    ): Boolean {
        val handle: Long = nativeHandle
        if (handle != 0L && data != null && length >= 0 &&
            length <= data.size && (length > 0 || isTerminator)
        ) {
            return nativePushInbound(
                handle, session, data, length, sequence,
                flags, isTerminator
            )
        }
        return false
    }

    fun pushTunnel(
        session: Int,
        data: ByteArray?,
        length: Int,
        sequence: Int,
        flags: Int,
        isTerminator: Boolean
    ): Boolean {
        val handle: Long = nativeHandle
        if (handle != 0L && data != null && length >= 0 &&
            length <= data.size && (length > 0 || isTerminator)
        ) {
            return nativePushTunnel(
                handle, session, data, length, sequence,
                flags, isTerminator
            )
        }
        return false
    }

    fun pushRemoveUser(session: Int): Boolean {
        val handle: Long = nativeHandle
        if (handle != 0L) {
            return nativePushRemoveUser(handle, session)
        }
        return false
    }

    /**
     * Renders mixed PCM. Only the handle copy is read here; the native call
     * itself needs no monitor. The render thread is the sole caller while it
     * runs; destroy() must only run after the render thread is joined
     * (AudioOutput.stopPlaying guarantees this), serialized against pushes
     * by the caller's monitor.
     */
    fun render(out: ShortArray?, offset: Int, length: Int): Int {
        val handle: Long = nativeHandle
        if (handle != 0L && out != null && offset >= 0 && length > 0 &&
            offset <= out.size && length <= out.size - offset
        ) {
            return nativeRender(handle, out, offset, length)
        }
        return 0
    }

    /**
     * Whether the last render found active voices. Read by the render
     * thread's idle wait instead of a separate engine call.
     */
    fun lastHasActiveVoices(): Boolean {
        val handle: Long = nativeHandle
        return handle != 0L && nativeLastHasActiveVoices(handle)
    }

    fun lastDroppedInbound(): Long {
        val handle: Long = nativeHandle
        return if (handle != 0L) nativeLastDroppedInbound(handle) else 0
    }

    fun lastDroppedTunnel(): Long {
        val handle: Long = nativeHandle
        return if (handle != 0L) nativeLastDroppedTunnel(handle) else 0
    }

    fun lastDroppedJitter(): Long {
        val handle: Long = nativeHandle
        return if (handle != 0L) nativeLastDroppedJitter(handle) else 0
    }

    fun clear() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeClear(handle)
        }
    }

    fun destroy() {
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    interface VoiceIoListener {
        fun onTalkSnapshot(
            sessions: IntArray,
            states: IntArray,
            droppedInbound: Long,
            droppedTunnel: Long,
            droppedJitter: Long
        )
    }
}
