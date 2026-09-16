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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio;

/**
 * Java interface for the native C++ Audio Output Engine (libhumlaaudio.so).
 *
 * Java parses Mumble transport headers and forwards clean Opus payloads.
 * Jitter buffering, Opus decoding, loss concealment, per-user fading,
 * float mixing, and soft-knee bus saturation all run natively.
 */
public class NativeAudioOutputEngine {
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples @ 10ms

    /** Talk ordinals; must match AudioOutputEngine::OutputTalkState. */
    public static final int TALK_TALKING = 0;
    public static final int TALK_SHOUTING = 1;
    public static final int TALK_PASSIVE = 2;
    public static final int TALK_WHISPERING = 3;

    static {
        System.loadLibrary("jniopus");
        System.loadLibrary("humlaaudio");
    }

    private long mNativeHandle;
    private final AudioOutputEngineListener mListener;

    public NativeAudioOutputEngine(AudioOutputEngineListener listener) {
        mListener = listener;
        mNativeHandle = nativeCreate(listener);
    }

    public synchronized void queuePacket(int session, byte[] data, int length,
                                         int sequence, int flags,
                                         boolean isTerminator) {
        if (mNativeHandle != 0 && data != null && length > 0
                && length <= data.length) {
            nativeQueuePacket(mNativeHandle, session, data, length, sequence,
                    flags, isTerminator);
        }
    }

    public synchronized int render(short[] out, int offset, int length) {
        if (mNativeHandle != 0 && out != null && offset >= 0 && length > 0
                && offset <= out.length && length <= out.length - offset) {
            return nativeRender(mNativeHandle, out, offset, length);
        }
        return 0;
    }

    public synchronized void removeUser(int session) {
        if (mNativeHandle != 0) {
            nativeRemoveUser(mNativeHandle, session);
        }
    }

    public synchronized void reset() {
        if (mNativeHandle != 0) {
            nativeReset(mNativeHandle);
        }
    }

    public synchronized void destroy() {
        long handle = mNativeHandle;
        mNativeHandle = 0;
        if (handle != 0) {
            nativeDestroy(handle);
        }
    }

    public synchronized void setJitterMarginFrames(int frames) {
        if (mNativeHandle != 0) {
            nativeSetJitterMarginFrames(mNativeHandle, frames);
        }
    }


    public interface AudioOutputEngineListener {
        void onTalkStateChanged(int session, int talkStateOrdinal);
    }

    private static native long nativeCreate(Object listener);
    private static native void nativeDestroy(long handle);
    private static native void nativeQueuePacket(long handle, int session,
            byte[] data, int length, int sequence, int flags,
            boolean isTerminator);
    private static native int nativeRender(long handle, short[] out, int offset,
            int length);
    private static native void nativeRemoveUser(long handle, int session);
    private static native void nativeReset(long handle);
    private static native void nativeSetJitterMarginFrames(long handle, int frames);
}
