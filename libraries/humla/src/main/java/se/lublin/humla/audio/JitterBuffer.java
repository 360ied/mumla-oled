/*
 * Copyright (C) 2014 Andrew Comminos
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

import java.nio.ByteBuffer;

/**
 * Adaptive jitter buffer for reordering and smoothing incoming voice packets.
 * Backed by in-tree native adaptive jitter buffer in libhumlaaudio.so.
 */
public class JitterBuffer {
    static {
        System.loadLibrary("jniopus");
        System.loadLibrary("humlaaudio");
    }

    public static final int JITTER_BUFFER_OK = 0;
    public static final int JITTER_BUFFER_MISSING = 1;
    public static final int JITTER_BUFFER_INSERTION = 2;
    public static final int JITTER_BUFFER_INTERNAL_ERROR = -1;
    public static final int JITTER_BUFFER_BAD_ARGUMENT = -2;

    private long mNativeHandle;
    private final int mFrameSize;

    public JitterBuffer(int frameSize) {
        mFrameSize = frameSize;
        mNativeHandle = nativeInit(frameSize);
    }

    public synchronized void destroy() {
        if (mNativeHandle != 0) {
            nativeDestroy(mNativeHandle);
            mNativeHandle = 0;
        }
    }

    public synchronized void reset() {
        if (mNativeHandle != 0) {
            nativeReset(mNativeHandle);
        }
    }

    public synchronized void setMargin(int margin) {
        if (mNativeHandle != 0) {
            nativeSetMargin(mNativeHandle, margin);
        }
    }

    public synchronized int getPointerTimestamp() {
        if (mNativeHandle != 0) {
            return nativeGetPointerTimestamp(mNativeHandle);
        }
        return 0;
    }

    public synchronized int getAvailableCount() {
        if (mNativeHandle != 0) {
            return nativeGetAvailableCount(mNativeHandle);
        }
        return 0;
    }

    public synchronized void put(byte[] data, int length, int timestamp, int span, int sequence, int userData) {
        if (mNativeHandle != 0 && data != null && length > 0) {
            nativePut(mNativeHandle, data, length, timestamp, span, sequence, userData);
        }
    }

    public synchronized int get(ByteBuffer directBuffer, int[] outInfo) {
        if (mNativeHandle != 0) {
            return nativeGet(mNativeHandle, directBuffer, mFrameSize, outInfo);
        }
        return JITTER_BUFFER_INTERNAL_ERROR;
    }

    public synchronized void updateDelay() {
        if (mNativeHandle != 0) {
            nativeUpdateDelay(mNativeHandle);
        }
    }

    public synchronized void tick() {
        if (mNativeHandle != 0) {
            nativeTick(mNativeHandle);
        }
    }

    private static native long nativeInit(int frameSize);
    private static native void nativeDestroy(long handle);
    private static native void nativeReset(long handle);
    private static native void nativeSetMargin(long handle, int margin);
    private static native int nativeGetPointerTimestamp(long handle);
    private static native int nativeGetAvailableCount(long handle);
    private static native void nativePut(long handle, byte[] data, int length, int timestamp, int span, int sequence, int userData);
    private static native int nativeGet(long handle, ByteBuffer directBuffer, int desiredSpan, int[] outInfo);
    private static native void nativeUpdateDelay(long handle);
    private static native void nativeTick(long handle);
}
