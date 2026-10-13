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

package se.lublin.humla.audio

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Java interface for the native C++ Audio Input Engine (libhumlaaudio.so).
 *
 * Coordinates Pre-Speech Lookahead Ring Buffering (80ms), Neural RNNoise DSP,
 * Dual-Threshold Hysteresis VAD, Soft-Knee Saturation Limiter, and Mandatory
 * Hard Constant Bitrate (CBR) Opus encoding.
 */
class NativeAudioInputEngine {
    companion object {
        const val INPUT_MODE_VOICE_ACTIVITY = 0
        const val INPUT_MODE_PUSH_TO_TALK = 1
        const val INPUT_MODE_CONTINUOUS = 2

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = SAMPLE_RATE / 100 // 480 samples @ 10ms

        const val DEFAULT_VAD_MAX = 0.35f
        const val DEFAULT_VAD_MIN = 0.25f
        const val DEFAULT_HOLD_FRAMES = 25 // 250ms @ 10ms/frame
        const val DEFAULT_SQUELCH_MIN_DB = -65.0f // Squelch noise floor in dBFS

        init {
            System.loadLibrary("humlaaudio")
        }

        @Volatile
        private var cachedModel: ByteArray? = null

        @JvmStatic
        @Synchronized
        fun loadRnnoiseModel(context: Context?) {
            if (cachedModel != null || context == null) {
                return
            }
            try {
                context.assets.open("rnnoise_model.bin").use { ins ->
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(16384)
                    var nRead: Int
                    while (ins.read(data, 0, data.size).also { nRead = it } != -1) {
                        buffer.write(data, 0, nRead)
                    }
                    cachedModel = buffer.toByteArray()
                }
            } catch (e: IOException) {
                Log.w("NativeAudioInputEngine", "Failed to load rnnoise_model.bin from assets", e)
            }
        }

        @JvmStatic
        fun getCachedRnnoiseModel(): ByteArray? {
            return cachedModel
        }

        // Native JNI Methods. Static so the mangled JNI names
        // (Java_se_lublin_humla_audio_NativeAudioInputEngine_*) keep resolving.
        @JvmStatic
        private external fun nativeCreate(
            bitrate: Int,
            framesPerPacket: Int,
            amplitudeBoost: Float,
            rnnoiseEnabled: Boolean,
            adaptiveLevelerEnabled: Boolean,
            inputMode: Int,
            rnnoiseModel: ByteArray?,
            listener: Any?
        ): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeProcessFrame(handle: Long, pcm: ShortArray?, offset: Int, length: Int)

        @JvmStatic
        private external fun nativeSetInputMode(handle: Long, inputMode: Int)

        @JvmStatic
        private external fun nativeSetPttTalking(handle: Long, talking: Boolean)

        @JvmStatic
        private external fun nativeSetMuted(handle: Long, muted: Boolean)

        @JvmStatic
        private external fun nativeSetBitrate(handle: Long, bitrate: Int)

        @JvmStatic
        private external fun nativeGetBitrate(handle: Long): Int

        @JvmStatic
        private external fun nativeSetFramesPerPacket(handle: Long, framesPerPacket: Int)

        @JvmStatic
        private external fun nativeSetAmplitudeBoost(handle: Long, boost: Float)

        @JvmStatic
        private external fun nativeSetRnnoiseEnabled(handle: Long, enabled: Boolean)

        @JvmStatic
        private external fun nativeIsRnnoiseEnabled(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSetAdaptiveLevelerEnabled(handle: Long, enabled: Boolean)

        @JvmStatic
        private external fun nativeIsAdaptiveLevelerEnabled(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSetRnnoiseModel(handle: Long, modelData: ByteArray?)

        @JvmStatic
        private external fun nativeHasRnnoiseModel(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSetVadThresholds(handle: Long, vadMax: Float, vadMin: Float)

        @JvmStatic
        private external fun nativeSetVadHoldFrames(handle: Long, holdFrames: Int)

        @JvmStatic
        private external fun nativeSetVadSquelchFloor(handle: Long, minDb: Float)

        @JvmStatic
        private external fun nativeGetVadSquelchFloor(handle: Long): Float

        @JvmStatic
        private external fun nativeReset(handle: Long)
    }

    @Volatile
    private var nativeHandle: Long = 0
    private val listener: AudioInputEngineListener?

    constructor(
        bitrate: Int,
        framesPerPacket: Int,
        amplitudeBoost: Float,
        rnnoiseEnabled: Boolean,
        inputMode: Int,
        listener: AudioInputEngineListener?
    ) : this(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, true, inputMode, cachedModel, listener)

    constructor(
        bitrate: Int,
        framesPerPacket: Int,
        amplitudeBoost: Float,
        rnnoiseEnabled: Boolean,
        adaptiveLevelerEnabled: Boolean,
        inputMode: Int,
        listener: AudioInputEngineListener?
    ) : this(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, adaptiveLevelerEnabled, inputMode, cachedModel, listener)

    constructor(
        bitrate: Int,
        framesPerPacket: Int,
        amplitudeBoost: Float,
        rnnoiseEnabled: Boolean,
        inputMode: Int,
        rnnoiseModel: ByteArray?,
        listener: AudioInputEngineListener?
    ) : this(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, true, inputMode, rnnoiseModel, listener)

    constructor(
        bitrate: Int,
        framesPerPacket: Int,
        amplitudeBoost: Float,
        rnnoiseEnabled: Boolean,
        adaptiveLevelerEnabled: Boolean,
        inputMode: Int,
        rnnoiseModel: ByteArray?,
        listener: AudioInputEngineListener?
    ) {
        this.listener = listener
        nativeHandle = nativeCreate(
            bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled,
            adaptiveLevelerEnabled, inputMode, rnnoiseModel, listener
        )
    }

    // Synchronized with destroy()/setters on the same monitor: processFrame
    // runs on the capture thread while teardown runs on main, and the input
    // JNI resolves the handle with a raw reinterpret_cast (no registry
    // guard like CryptState), so only the monitor pins the lifetime here.
    // In-tree AudioHandler.shutdown() joins the capture thread first, so
    // this is defense-in-depth; per-frame uncontended cost is negligible.
    @Synchronized
    fun processFrame(pcm: ShortArray?, offset: Int, length: Int) {
        val handle = nativeHandle
        if (handle != 0L && pcm != null && offset >= 0 && length > 0 &&
            offset <= pcm.size && length <= pcm.size - offset
        ) {
            nativeProcessFrame(handle, pcm, offset, length)
        }
    }

    @Synchronized
    fun setInputMode(inputMode: Int) {
        if (nativeHandle != 0L) {
            nativeSetInputMode(nativeHandle, inputMode)
        }
    }

    @Synchronized
    fun setPttTalking(talking: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetPttTalking(nativeHandle, talking)
        }
    }

    @Synchronized
    fun setMuted(muted: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetMuted(nativeHandle, muted)
        }
    }

    @Synchronized
    fun setBitrate(bitrate: Int) {
        if (nativeHandle != 0L) {
            nativeSetBitrate(nativeHandle, bitrate)
        }
    }

    @Synchronized
    fun getBitrate(): Int {
        if (nativeHandle != 0L) {
            return nativeGetBitrate(nativeHandle)
        }
        return 0
    }

    @Synchronized
    fun setFramesPerPacket(framesPerPacket: Int) {
        if (nativeHandle != 0L) {
            nativeSetFramesPerPacket(nativeHandle, framesPerPacket)
        }
    }

    @Synchronized
    fun setAmplitudeBoost(boost: Float) {
        if (nativeHandle != 0L) {
            nativeSetAmplitudeBoost(nativeHandle, boost)
        }
    }

    @Synchronized
    fun setRnnoiseEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetRnnoiseEnabled(nativeHandle, enabled)
        }
    }

    @Synchronized
    fun isRnnoiseEnabled(): Boolean {
        if (nativeHandle != 0L) {
            return nativeIsRnnoiseEnabled(nativeHandle)
        }
        return false
    }

    @Synchronized
    fun setAdaptiveLevelerEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetAdaptiveLevelerEnabled(nativeHandle, enabled)
        }
    }

    @Synchronized
    fun isAdaptiveLevelerEnabled(): Boolean {
        if (nativeHandle != 0L) {
            return nativeIsAdaptiveLevelerEnabled(nativeHandle)
        }
        return false
    }

    @Synchronized
    fun setRnnoiseModel(modelData: ByteArray?) {
        if (nativeHandle != 0L) {
            nativeSetRnnoiseModel(nativeHandle, modelData)
        }
    }

    @Synchronized
    fun hasRnnoiseModel(): Boolean {
        if (nativeHandle != 0L) {
            return nativeHasRnnoiseModel(nativeHandle)
        }
        return false
    }

    @Synchronized
    fun setVadThresholds(vadMax: Float, vadMin: Float) {
        if (nativeHandle != 0L) {
            nativeSetVadThresholds(nativeHandle, vadMax, vadMin)
        }
    }

    @Synchronized
    fun setVadHoldFrames(holdFrames: Int) {
        if (nativeHandle != 0L) {
            nativeSetVadHoldFrames(nativeHandle, holdFrames)
        }
    }

    @Synchronized
    fun setVadSquelchFloor(minDb: Float) {
        if (nativeHandle != 0L) {
            nativeSetVadSquelchFloor(nativeHandle, minDb)
        }
    }

    @Synchronized
    fun getVadSquelchFloor(): Float {
        if (nativeHandle != 0L) {
            return nativeGetVadSquelchFloor(nativeHandle)
        }
        return DEFAULT_SQUELCH_MIN_DB
    }

    @Synchronized
    fun reset() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
    }

    @Synchronized
    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    // Finalizer backstop for a never-destroyed engine. kotlin.Any exposes no
    // finalize to call through to (Object's is a no-op), so only destroy() runs.
    @Throws(Throwable::class)
    protected fun finalize() {
        destroy()
    }

    interface AudioInputEngineListener {
        fun onAudioPacketEncoded(data: ByteArray?, length: Int, frames: Int, isTerminator: Boolean, frameNumber: Long)
        fun onTalkingStateChanged(isTalking: Boolean, peakEnergy: Float)
    }
}
