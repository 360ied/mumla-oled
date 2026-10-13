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

package se.lublin.humla.audio

/**
 * Java interface for the native C++ Audio Output Engine (libhumlaaudio.so).
 *
 * Java parses Mumble transport headers and forwards clean Opus payloads.
 * Jitter buffering, Opus decoding, loss concealment, per-user fading,
 * float mixing, and soft-knee bus saturation all run natively.
 */
class NativeAudioOutputEngine {
    companion object {
        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = SAMPLE_RATE / 100 // 480 samples @ 10ms

        /** Talk ordinals; must match AudioOutputEngine::OutputTalkState. */
        const val TALK_TALKING = 0
        const val TALK_SHOUTING = 1
        const val TALK_PASSIVE = 2
        const val TALK_WHISPERING = 3

        private val loadError: Throwable? = try {
            System.loadLibrary("humlaaudio")
            null
        } catch (t: Throwable) {
            t
        }

        private fun ensureLoaded() {
            val error = loadError
            if (error != null) {
                throw IllegalStateException(
                    "Native audio libraries failed to load",
                    error
                )
            }
        }

        @JvmStatic
        private external fun nativeCreate(listener: Any?): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeQueuePacket(
            handle: Long,
            session: Int,
            data: ByteArray?,
            length: Int,
            sequence: Int,
            flags: Int,
            isTerminator: Boolean
        )

        @JvmStatic
        private external fun nativeRender(handle: Long, out: ShortArray?, offset: Int, length: Int): Int

        @JvmStatic
        private external fun nativeRemoveUser(handle: Long, session: Int)

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeSetJitterMarginFrames(handle: Long, frames: Int)

        @JvmStatic
        private external fun nativeHasActiveVoices(handle: Long): Boolean
    }

    private var nativeHandle: Long = 0
    private val listener: AudioOutputEngineListener?

    constructor(listener: AudioOutputEngineListener?) {
        ensureLoaded()
        this.listener = listener
        nativeHandle = nativeCreate(listener)
        if (nativeHandle == 0L) {
            // JNI reports failure with a zero handle and no pending exception
            // (see NativeAudioOutputEngineJni); fail loudly instead of a
            // silently dead engine whose render no-ops forever.
            throw IllegalStateException("Native output engine creation failed")
        }
    }

    /** Public for unit testing handle guards without native loading (was package-private in Java; Kotlin has no package visibility). */
    constructor(handle: Long) {
        listener = null
        nativeHandle = handle
    }

    @Synchronized
    fun queuePacket(
        session: Int,
        data: ByteArray?,
        length: Int,
        sequence: Int,
        flags: Int,
        isTerminator: Boolean
    ) {
        if (nativeHandle != 0L && data != null && length >= 0 &&
            length <= data.size && (length > 0 || isTerminator)
        ) {
            nativeQueuePacket(
                nativeHandle, session, data, length, sequence,
                flags, isTerminator
            )
        }
    }

    /**
     * Renders mixed PCM. Only the handle copy is guarded; the native call
     * runs unsynchronized so network-thread queuePacket never blocks behind
     * a 60 ms decode+mix quantum. Thread safety below comes from the native
     * engine mutex plus the single-render-thread discipline. destroy() must only
     * run after the render thread is joined (AudioOutput.stopPlaying guarantees
     * this); it stays synchronized against queuePacket/removeUser.
     */
    fun render(out: ShortArray?, offset: Int, length: Int): Int {
        val handle: Long = synchronized(this) {
            nativeHandle
        }
        if (handle != 0L && out != null && offset >= 0 && length > 0 &&
            offset <= out.size && length <= out.size - offset
        ) {
            return nativeRender(handle, out, offset, length)
        }
        return 0
    }

    @Synchronized
    fun removeUser(session: Int) {
        if (nativeHandle != 0L) {
            nativeRemoveUser(nativeHandle, session)
        }
    }

    @Synchronized
    fun reset() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
    }

    @Synchronized
    fun destroy() {
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    @Synchronized
    fun setJitterMarginFrames(frames: Int) {
        if (nativeHandle != 0L) {
            nativeSetJitterMarginFrames(nativeHandle, frames)
        }
    }

    /**
     * Checks if any active voices exist in the native output engine.
     *
     * Should only be called by the audio output render thread while managing render-quantum
     * idle sleep states. Synchronized with respect to concurrent engine lifecycle methods
     * (e.g. destroy()).
     *
     * @return true if voices are actively queued, gating, or playing; false if completely idle.
     */
    @Synchronized
    fun hasActiveVoices(): Boolean {
        if (nativeHandle != 0L) {
            return nativeHasActiveVoices(nativeHandle)
        }
        return false
    }

    interface AudioOutputEngineListener {
        fun onTalkStateChanged(session: Int, talkStateOrdinal: Int)
    }
}
