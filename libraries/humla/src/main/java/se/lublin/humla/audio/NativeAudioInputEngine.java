/*
 * Copyright (C) 2026 Mumla Developers
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

package se.lublin.humla.audio;

/**
 * Java interface for the native C++ Audio Input Engine (libhumlaaudio.so).
 *
 * Coordinates Pre-Speech Lookahead Ring Buffering (80ms), Neural RNNoise DSP,
 * Dual-Threshold Hysteresis VAD, Soft-Knee Saturation Limiter, and Mandatory
 * Hard Constant Bitrate (CBR) Opus encoding.
 */
public class NativeAudioInputEngine {
    public static final int INPUT_MODE_VOICE_ACTIVITY = 0;
    public static final int INPUT_MODE_PUSH_TO_TALK = 1;
    public static final int INPUT_MODE_CONTINUOUS = 2;

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples @ 10ms

    public static final float DEFAULT_VAD_MAX = 0.35f;
    public static final float DEFAULT_VAD_MIN = 0.25f;
    public static final int DEFAULT_HOLD_FRAMES = 25; // 250ms @ 10ms/frame
    public static final float DEFAULT_SQUELCH_MIN_DB = -65.0f; // Squelch noise floor in dBFS

    static {
        System.loadLibrary("jniopus");
        System.loadLibrary("humlaaudio");
    }

    private static volatile byte[] sCachedRnnoiseModel = null;

    public static synchronized void loadRnnoiseModel(android.content.Context context) {
        if (sCachedRnnoiseModel != null || context == null) {
            return;
        }
        try (java.io.InputStream is = context.getAssets().open("rnnoise_model.bin")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[16384];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            sCachedRnnoiseModel = buffer.toByteArray();
        } catch (java.io.IOException e) {
            android.util.Log.w("NativeAudioInputEngine", "Failed to load rnnoise_model.bin from assets", e);
        }
    }

    public static byte[] getCachedRnnoiseModel() {
        return sCachedRnnoiseModel;
    }

    private long mNativeHandle;
    private final AudioInputEngineListener mListener;

    public NativeAudioInputEngine(int bitrate,
                                  int framesPerPacket,
                                  float amplitudeBoost,
                                  boolean rnnoiseEnabled,
                                  int inputMode,
                                  AudioInputEngineListener listener) {
        this(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, true, inputMode, sCachedRnnoiseModel, listener);
    }

    public NativeAudioInputEngine(int bitrate,
                                  int framesPerPacket,
                                  float amplitudeBoost,
                                  boolean rnnoiseEnabled,
                                  boolean adaptiveLevelerEnabled,
                                  int inputMode,
                                  AudioInputEngineListener listener) {
        this(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, adaptiveLevelerEnabled, inputMode, sCachedRnnoiseModel, listener);
    }

    public NativeAudioInputEngine(int bitrate,
                                  int framesPerPacket,
                                  float amplitudeBoost,
                                  boolean rnnoiseEnabled,
                                  int inputMode,
                                  byte[] rnnoiseModel,
                                  AudioInputEngineListener listener) {
        this(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, true, inputMode, rnnoiseModel, listener);
    }

    public NativeAudioInputEngine(int bitrate,
                                  int framesPerPacket,
                                  float amplitudeBoost,
                                  boolean rnnoiseEnabled,
                                  boolean adaptiveLevelerEnabled,
                                  int inputMode,
                                  byte[] rnnoiseModel,
                                  AudioInputEngineListener listener) {
        mListener = listener;
        mNativeHandle = nativeCreate(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, adaptiveLevelerEnabled, inputMode, rnnoiseModel, listener);
    }

    public synchronized void processFrame(short[] pcm, int offset, int length) {
        if (mNativeHandle != 0 && pcm != null && length > 0) {
            nativeProcessFrame(mNativeHandle, pcm, offset, length);
        }
    }

    public synchronized void setInputMode(int inputMode) {
        if (mNativeHandle != 0) {
            nativeSetInputMode(mNativeHandle, inputMode);
        }
    }

    public synchronized void setPttTalking(boolean talking) {
        if (mNativeHandle != 0) {
            nativeSetPttTalking(mNativeHandle, talking);
        }
    }

    public synchronized void setMuted(boolean muted) {
        if (mNativeHandle != 0) {
            nativeSetMuted(mNativeHandle, muted);
        }
    }

    public synchronized void setBitrate(int bitrate) {
        if (mNativeHandle != 0) {
            nativeSetBitrate(mNativeHandle, bitrate);
        }
    }

    public synchronized int getBitrate() {
        if (mNativeHandle != 0) {
            return nativeGetBitrate(mNativeHandle);
        }
        return 0;
    }

    public synchronized void setFramesPerPacket(int framesPerPacket) {
        if (mNativeHandle != 0) {
            nativeSetFramesPerPacket(mNativeHandle, framesPerPacket);
        }
    }

    public synchronized void setAmplitudeBoost(float boost) {
        if (mNativeHandle != 0) {
            nativeSetAmplitudeBoost(mNativeHandle, boost);
        }
    }

    public synchronized void setRnnoiseEnabled(boolean enabled) {
        if (mNativeHandle != 0) {
            nativeSetRnnoiseEnabled(mNativeHandle, enabled);
        }
    }

    public synchronized boolean isRnnoiseEnabled() {
        if (mNativeHandle != 0) {
            return nativeIsRnnoiseEnabled(mNativeHandle);
        }
        return false;
    }

    public synchronized void setAdaptiveLevelerEnabled(boolean enabled) {
        if (mNativeHandle != 0) {
            nativeSetAdaptiveLevelerEnabled(mNativeHandle, enabled);
        }
    }

    public synchronized boolean isAdaptiveLevelerEnabled() {
        if (mNativeHandle != 0) {
            return nativeIsAdaptiveLevelerEnabled(mNativeHandle);
        }
        return false;
    }

    public synchronized void setRnnoiseModel(byte[] modelData) {
        if (mNativeHandle != 0) {
            nativeSetRnnoiseModel(mNativeHandle, modelData);
        }
    }

    public synchronized boolean hasRnnoiseModel() {
        if (mNativeHandle != 0) {
            return nativeHasRnnoiseModel(mNativeHandle);
        }
        return false;
    }

    public synchronized void setVadThresholds(float vadMax, float vadMin) {
        if (mNativeHandle != 0) {
            nativeSetVadThresholds(mNativeHandle, vadMax, vadMin);
        }
    }

    public synchronized void setVadHoldFrames(int holdFrames) {
        if (mNativeHandle != 0) {
            nativeSetVadHoldFrames(mNativeHandle, holdFrames);
        }
    }

    public synchronized void setVadSquelchFloor(float minDb) {
        if (mNativeHandle != 0) {
            nativeSetVadSquelchFloor(mNativeHandle, minDb);
        }
    }

    public synchronized float getVadSquelchFloor() {
        if (mNativeHandle != 0) {
            return nativeGetVadSquelchFloor(mNativeHandle);
        }
        return DEFAULT_SQUELCH_MIN_DB;
    }

    public synchronized void reset() {
        if (mNativeHandle != 0) {
            nativeReset(mNativeHandle);
        }
    }

    public synchronized void destroy() {
        if (mNativeHandle != 0) {
            nativeDestroy(mNativeHandle);
            mNativeHandle = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    public interface AudioInputEngineListener {
        void onAudioPacketEncoded(byte[] data, int length, int frames, boolean isTerminator, long frameNumber);
        void onTalkingStateChanged(boolean isTalking, float peakEnergy);
    }

    // Native JNI Methods
    private static native long nativeCreate(int bitrate, int framesPerPacket, float amplitudeBoost, boolean rnnoiseEnabled, boolean adaptiveLevelerEnabled, int inputMode, byte[] rnnoiseModel, Object listener);
    private static native void nativeDestroy(long handle);
    private static native void nativeProcessFrame(long handle, short[] pcm, int offset, int length);
    private static native void nativeSetInputMode(long handle, int inputMode);
    private static native void nativeSetPttTalking(long handle, boolean talking);
    private static native void nativeSetMuted(long handle, boolean muted);
    private static native void nativeSetBitrate(long handle, int bitrate);
    private static native int nativeGetBitrate(long handle);
    private static native void nativeSetFramesPerPacket(long handle, int framesPerPacket);
    private static native void nativeSetAmplitudeBoost(long handle, float boost);
    private static native void nativeSetRnnoiseEnabled(long handle, boolean enabled);
    private static native boolean nativeIsRnnoiseEnabled(long handle);
    private static native void nativeSetAdaptiveLevelerEnabled(long handle, boolean enabled);
    private static native boolean nativeIsAdaptiveLevelerEnabled(long handle);
    private static native void nativeSetRnnoiseModel(long handle, byte[] modelData);
    private static native boolean nativeHasRnnoiseModel(long handle);
    private static native void nativeSetVadThresholds(long handle, float vadMax, float vadMin);
    private static native void nativeSetVadHoldFrames(long handle, int holdFrames);
    private static native void nativeSetVadSquelchFloor(long handle, float minDb);
    private static native float nativeGetVadSquelchFloor(long handle);
    private static native void nativeReset(long handle);
}
