/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.net;

import java.security.InvalidKeyException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.ShortBufferException;

/**
 * Cryptographic state machine for Mumble OCB2-AES128 authenticated encryption,
 * IV synchronization, packet loss tracking, and replay detection.
 *
 * Fully backed by the native C++ engine (libhumlaaudio.so) with hardware SIMD
 * acceleration (ARMv8 Crypto Extensions / Intel AES-NI).
 */
public class CryptState {
    public static final int AES_BLOCK_SIZE = 16;
    /** OCB2 crypt header prepended to every encrypted datagram. */
    static final int CRYPT_HEADER_BYTES = 4;

    private static final Throwable sLoadError;
    static {
        Throwable error = null;
        try {
            System.loadLibrary("humlaaudio");
            if (!nativeIsSupported()) {
                error = new UnsupportedOperationException("Native CryptState is not supported");
            }
        } catch (Throwable t) {
            error = t;
        }
        sLoadError = error;
    }

    public static boolean isNativeAvailable() {
        return sLoadError == null;
    }

    private static void ensureLoaded() {
        if (sLoadError != null) {
            throw new IllegalStateException("Native crypto library failed to load", sLoadError);
        }
    }

    private volatile long mNativeHandle = 0;
    private final int[] mStatsBuffer = new int[4];

    private final byte[] mEncryptIV = new byte[AES_BLOCK_SIZE];
    private final byte[] mDecryptIV = new byte[AES_BLOCK_SIZE];
    volatile int mUiGood = 0;
    private volatile int mUiLate = 0;
    private volatile int mUiLost = 0;
    private volatile int mUiResync = 0;
    volatile int mUiRemoteGood = 0;
    volatile int mUiRemoteLate = 0;
    volatile int mUiRemoteLost = 0;
    volatile int mUiRemoteResync = 0;
    private volatile long mLastGoodStart;
    private volatile long mLastRequestStart;
    volatile boolean mInit = false;

    public CryptState() {
        ensureLoaded();
        mNativeHandle = nativeCreate();
        if (mNativeHandle == 0) {
            throw new IllegalStateException("Failed to create native CryptState instance");
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

    public synchronized void destroy() {
        mInit = false;
        Arrays.fill(mEncryptIV, (byte) 0);
        Arrays.fill(mDecryptIV, (byte) 0);
        if (mNativeHandle != 0) {
            long handle = mNativeHandle;
            mNativeHandle = 0;
            nativeDestroy(handle);
        }
    }

    public boolean isValid() {
        return mInit;
    }

    /**
     * @return The time since the last good decrypt in microseconds.
     */
    public long getLastGoodElapsed() {
        if (mNativeHandle != 0) {
            long us = nativeGetLastGoodElapsedUs(mNativeHandle);
            if (us >= 0) return us;
        }
        return (System.nanoTime() - mLastGoodStart) / 1000;
    }

    /**
     * @return The time since the last request in microseconds.
     */
    public long getLastRequestElapsed() {
        return (System.nanoTime() - mLastRequestStart) / 1000;
    }

    /**
     * Resets the recorded time of the last request to the current time.
     */
    public void resetLastRequestTime() {
        mLastRequestStart = System.nanoTime();
    }

    public synchronized byte[] getEncryptIV() {
        if (mNativeHandle != 0) {
            nativeGetEncryptIV(mNativeHandle, mEncryptIV);
        }
        return mEncryptIV.clone();
    }

    public synchronized byte[] getDecryptIV() {
        if (mNativeHandle != 0) {
            nativeGetDecryptIV(mNativeHandle, mDecryptIV);
        }
        return mDecryptIV.clone();
    }

    public synchronized boolean setDecryptIV(final byte[] div) {
        if (div != null && div.length == AES_BLOCK_SIZE && mNativeHandle != 0) {
            System.arraycopy(div, 0, mDecryptIV, 0, AES_BLOCK_SIZE);
            return nativeSetDecryptIV(mNativeHandle, div);
        }
        return false;
    }

    public synchronized boolean setEncryptIV(final byte[] eiv) {
        if (eiv != null && eiv.length == AES_BLOCK_SIZE && mNativeHandle != 0) {
            System.arraycopy(eiv, 0, mEncryptIV, 0, AES_BLOCK_SIZE);
            return nativeSetEncryptIV(mNativeHandle, eiv);
        }
        return false;
    }

    public synchronized void setKeys(final byte[] rkey, final byte[] eiv, final byte[] div) throws InvalidKeyException {
        if (rkey == null || eiv == null || div == null ||
                rkey.length != AES_BLOCK_SIZE || eiv.length != AES_BLOCK_SIZE || div.length != AES_BLOCK_SIZE) {
            throw new InvalidKeyException("Keys and IVs must be 16 bytes");
        }

        System.arraycopy(eiv, 0, mEncryptIV, 0, AES_BLOCK_SIZE);
        System.arraycopy(div, 0, mDecryptIV, 0, AES_BLOCK_SIZE);

        mUiGood = 0;
        mUiLate = 0;
        mUiLost = 0;
        mUiResync = 0;
        mUiRemoteGood = 0;
        mUiRemoteLate = 0;
        mUiRemoteLost = 0;
        mUiRemoteResync = 0;

        if (mNativeHandle == 0) {
            mNativeHandle = nativeCreate();
        }

        if (mNativeHandle != 0) {
            mInit = nativeSetKeys(mNativeHandle, rkey, eiv, div);
        } else {
            mInit = false;
        }

        if (mInit) {
            mLastGoodStart = System.nanoTime();
        }
    }

    /**
     * Decrypts data using the OCB-AES128 standard.
     * @param source The encoded audio data.
     * @param length The length of the source array.
     */
    public byte[] decrypt(final byte[] source, final int length) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        if (source == null || length < CRYPT_HEADER_BYTES || !mInit || length > source.length) return null;

        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0) {
            byte[] decrypted = nativeDecrypt(nativeHandle, source, length);
            if (decrypted != null) {
                mLastGoodStart = System.nanoTime();
            }
            return decrypted;
        }
        return null;
    }

    public int decryptInPlace(final byte[] data, final int offset, final int length) {
        if (!mInit || data == null || length < CRYPT_HEADER_BYTES || offset < 0 || (long) offset + length > data.length) {
            return -1;
        }
        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0) {
            int plainLength = nativeDecryptInPlace(nativeHandle, data, offset, length);
            if (plainLength >= 0) {
                mLastGoodStart = System.nanoTime();
            }
            return plainLength;
        }
        return -1;
    }

    public byte[] encrypt(final byte[] source, final int length) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        if (source == null || length < 0 || !mInit || length > source.length) return null;

        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0) {
            return nativeEncrypt(nativeHandle, source, length);
        }
        return null;
    }

    private void syncStatsFromNative() {
        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0 && mInit) {
            synchronized (mStatsBuffer) {
                nativeGetStats(nativeHandle, mStatsBuffer);
                mUiGood = mStatsBuffer[0];
                mUiLate = mStatsBuffer[1];
                mUiLost = mStatsBuffer[2];
                mUiResync = mStatsBuffer[3];
            }
        }
    }


    public int getGood() {
        syncStatsFromNative();
        return mUiGood;
    }

    public int getLate() {
        syncStatsFromNative();
        return mUiLate;
    }

    public int getLost() {
        syncStatsFromNative();
        return mUiLost;
    }

    public int getResync() {
        syncStatsFromNative();
        return mUiResync;
    }

    public int getRemoteGood() {
        return mUiRemoteGood;
    }

    public int getRemoteLate() {
        return mUiRemoteLate;
    }

    public int getRemoteLost() {
        return mUiRemoteLost;
    }

    public int getRemoteResync() {
        return mUiRemoteResync;
    }

    private static native boolean nativeIsSupported();
    private native long nativeCreate();
    private native void nativeDestroy(long handle);
    private native boolean nativeSetKeys(long handle, byte[] rkey, byte[] eiv, byte[] div);
    private native boolean nativeSetDecryptIV(long handle, byte[] div);
    private native boolean nativeSetEncryptIV(long handle, byte[] eiv);
    private native boolean nativeGetEncryptIV(long handle, byte[] out);
    private native boolean nativeGetDecryptIV(long handle, byte[] out);
    private native byte[] nativeEncrypt(long handle, byte[] source, int length);
    private native byte[] nativeDecrypt(long handle, byte[] source, int length);
    private native int nativeDecryptInPlace(long handle, byte[] buffer, int offset, int cryptedLength);
    private native void nativeGetStats(long handle, int[] statsOut);
    private native long nativeGetLastGoodElapsedUs(long handle);
}
