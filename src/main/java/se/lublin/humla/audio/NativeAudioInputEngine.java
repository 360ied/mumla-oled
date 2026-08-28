/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

    static {
        System.loadLibrary("jniopus");
        System.loadLibrary("humlaaudio");
    }

    private long mNativeHandle;
    private final AudioInputEngineListener mListener;

    public NativeAudioInputEngine(int bitrate,
                                  int framesPerPacket,
                                  float amplitudeBoost,
                                  boolean rnnoiseEnabled,
                                  int inputMode,
                                  AudioInputEngineListener listener) {
        mListener = listener;
        mNativeHandle = nativeCreate(bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, inputMode, listener);
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
    private static native long nativeCreate(int bitrate, int framesPerPacket, float amplitudeBoost, boolean rnnoiseEnabled, int inputMode, Object listener);
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
    private static native void nativeSetVadThresholds(long handle, float vadMax, float vadMin);
    private static native void nativeSetVadHoldFrames(long handle, int holdFrames);
    private static native void nativeReset(long handle);
}
