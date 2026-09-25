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

    private long mNativeHandle;
    private final AudioOutputEngineListener mListener;

    private static final Throwable sLoadError;
    static {
        Throwable error = null;
        try {
            System.loadLibrary("jniopus");
            System.loadLibrary("humlaaudio");
        } catch (Throwable t) {
            error = t;
        }
        sLoadError = error;
    }
    private static void ensureLoaded() {
        if (sLoadError != null) {
            throw new IllegalStateException("Native audio libraries failed to load",
                    sLoadError);
        }
    }
    public NativeAudioOutputEngine(AudioOutputEngineListener listener) {
        ensureLoaded();
        mListener = listener;
        mNativeHandle = nativeCreate(listener);
        if (mNativeHandle == 0) {
            // JNI reports failure with a zero handle and no pending exception
            // (see NativeAudioOutputEngineJni); fail loudly instead of a
            // silently dead engine whose render no-ops forever.
            throw new IllegalStateException("Native output engine creation failed");
        }
    }

    /** Package-private constructor for unit testing handle guards without native loading. */
    NativeAudioOutputEngine(long handle) {
        mListener = null;
        mNativeHandle = handle;
    }

    public synchronized void queuePacket(int session, byte[] data, int length,
                                         int sequence, int flags,
                                         boolean isTerminator) {
        if (mNativeHandle != 0 && data != null && length >= 0
                && length <= data.length && (length > 0 || isTerminator)) {
            nativeQueuePacket(mNativeHandle, session, data, length, sequence,
                    flags, isTerminator);
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
    public int render(short[] out, int offset, int length) {
        final long handle;
        synchronized (this) {
            handle = mNativeHandle;
        }
        if (handle != 0 && out != null && offset >= 0 && length > 0
                && offset <= out.length && length <= out.length - offset) {
            return nativeRender(handle, out, offset, length);
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

    /**
     * Checks if any active voices exist in the native output engine.
     *
     * Should only be called by the audio output render thread while managing render-quantum
     * idle sleep states. Synchronized with respect to concurrent engine lifecycle methods
     * (e.g. destroy()).
     *
     * @return true if voices are actively queued, gating, or playing; false if completely idle.
     */
    public synchronized boolean hasActiveVoices() {
        if (mNativeHandle != 0) {
            return nativeHasActiveVoices(mNativeHandle);
        }
        return false;
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
    private static native boolean nativeHasActiveVoices(long handle);
}
