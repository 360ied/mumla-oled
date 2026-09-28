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
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic state machine for Mumble OCB2-AES128 authenticated encryption,
 * IV synchronization, packet loss tracking, and replay detection.
 *
 * Backed by a native C++ engine (libhumlaaudio.so) with hardware SIMD acceleration
 * (ARMv8 Crypto Extensions / Intel AES-NI) when available on Android, with a pure
 * Java fallback for host JVM unit test environments.
 */
public class CryptState {
    public static final int AES_BLOCK_SIZE = 16;
    private static final String AES_TRANSFORMATION = "AES/ECB/NoPadding";

    private static final boolean sNativeAvailable;
    static {
        boolean loaded = false;
        try {
            System.loadLibrary("jniopus");
            System.loadLibrary("humlaaudio");
            loaded = nativeIsSupported();
        } catch (Throwable t) {
            loaded = false;
        }
        sNativeAvailable = loaded;
    }

    public static boolean isNativeAvailable() {
        return sNativeAvailable;
    }

    private volatile long mNativeHandle = 0;
    private final int[] mStatsBuffer = new int[4];

    byte[] mRawKey = new byte[AES_BLOCK_SIZE];
    byte[] mEncryptIV = new byte[AES_BLOCK_SIZE];
    byte[] mDecryptIV = new byte[AES_BLOCK_SIZE];
    byte[] mDecryptHistory = new byte[0x100];
    volatile int mUiGood = 0;
    volatile int mUiLate = 0;
    volatile int mUiLost = 0;
    volatile int mUiResync = 0;
    volatile int mUiRemoteGood = 0;
    volatile int mUiRemoteLate = 0;
    volatile int mUiRemoteLost = 0;
    volatile int mUiRemoteResync = 0;
    Cipher mEncryptCipher;
    Cipher mDecryptCipher;
    volatile long mLastGoodStart;
    volatile long mLastRequestStart;
    volatile boolean mInit = false;

    public CryptState() {
        if (sNativeAvailable) {
            mNativeHandle = nativeCreate();
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
        if (div != null && div.length == AES_BLOCK_SIZE) {
            System.arraycopy(div, 0, mDecryptIV, 0, AES_BLOCK_SIZE);
            Arrays.fill(mDecryptHistory, (byte) 0);
            if (mNativeHandle != 0) {
                return nativeSetDecryptIV(mNativeHandle, div);
            }
            return true;
        }
        return false;
    }

    public synchronized boolean setEncryptIV(final byte[] eiv) {
        if (eiv != null && eiv.length == AES_BLOCK_SIZE) {
            System.arraycopy(eiv, 0, mEncryptIV, 0, AES_BLOCK_SIZE);
            if (mNativeHandle != 0) {
                return nativeSetEncryptIV(mNativeHandle, eiv);
            }
            return true;
        }
        return false;
    }

    public synchronized void setKeys(final byte[] rkey, final byte[] eiv, final byte[] div) throws InvalidKeyException {
        if (rkey == null || eiv == null || div == null ||
                rkey.length != AES_BLOCK_SIZE || eiv.length != AES_BLOCK_SIZE || div.length != AES_BLOCK_SIZE) {
            throw new InvalidKeyException("Keys and IVs must be 16 bytes");
        }

        mRawKey = new byte[AES_BLOCK_SIZE];
        mEncryptIV = new byte[AES_BLOCK_SIZE];
        mDecryptIV = new byte[AES_BLOCK_SIZE];

        System.arraycopy(rkey, 0, mRawKey, 0, AES_BLOCK_SIZE);
        System.arraycopy(eiv, 0, mEncryptIV, 0, AES_BLOCK_SIZE);
        System.arraycopy(div, 0, mDecryptIV, 0, AES_BLOCK_SIZE);
        Arrays.fill(mDecryptHistory, (byte) 0);

        mUiGood = 0;
        mUiLate = 0;
        mUiLost = 0;
        mUiResync = 0;
        mUiRemoteGood = 0;
        mUiRemoteLate = 0;
        mUiRemoteLost = 0;
        mUiRemoteResync = 0;

        if (sNativeAvailable && mNativeHandle == 0) {
            mNativeHandle = nativeCreate();
        }

        if (mNativeHandle != 0) {
            mInit = nativeSetKeys(mNativeHandle, rkey, eiv, div);
        } else {
            try {
                mEncryptCipher = Cipher.getInstance(AES_TRANSFORMATION);
                mDecryptCipher = Cipher.getInstance(AES_TRANSFORMATION);
            } catch (final NoSuchAlgorithmException | NoSuchPaddingException e) {
                e.printStackTrace();
                return;
            }

            final SecretKeySpec cryptKey = new SecretKeySpec(rkey, "AES");
            mEncryptCipher.init(Cipher.ENCRYPT_MODE, cryptKey);
            mDecryptCipher.init(Cipher.DECRYPT_MODE, cryptKey);
            mInit = true;
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
        if (length < 4 || !mInit || source == null) return null;

        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0) {
            byte[] decrypted = nativeDecrypt(nativeHandle, source, length);
            if (decrypted != null) {
                mLastGoodStart = System.nanoTime();
            }
            return decrypted;
        }

        synchronized (this) {
            if (!mInit) return null;

            final int plainLength = length - 4;
            byte[] dst = new byte[plainLength];

            final byte[] saveiv = new byte[AES_BLOCK_SIZE];
            final short ivbyte = (short) (source[0] & 0xFF);
            boolean restore = false;
            final byte[] tag = new byte[AES_BLOCK_SIZE];

            int lost = 0;
            int late = 0;

            System.arraycopy(mDecryptIV, 0, saveiv, 0, AES_BLOCK_SIZE);

            if (((mDecryptIV[0] + 1) & 0xFF) == ivbyte) {
                // In order as expected.
                if (ivbyte > (mDecryptIV[0] & 0xFF)) {
                    mDecryptIV[0] = (byte) ivbyte;
                } else if (ivbyte < (mDecryptIV[0] & 0xFF)) {
                    mDecryptIV[0] = (byte) ivbyte;
                    for (int i = 1; i < AES_BLOCK_SIZE; i++) {
                        if ((++mDecryptIV[i]) != 0) {
                            break;
                        }
                    }
                } else {
                    return null;
                }
            } else {
                // This is either out of order or a repeat.
                int diff = ivbyte - (mDecryptIV[0] & 0xFF);
                if (diff > 128) {
                    diff = diff - 256;
                } else if (diff < -128) {
                    diff = diff + 256;
                }

                if ((ivbyte < (mDecryptIV[0] & 0xFF)) && (diff > -30) && (diff < 0)) {
                    // Late packet, but no wraparound.
                    late = 1;
                    lost = -1;
                    mDecryptIV[0] = (byte) ivbyte;
                    restore = true;
                } else if ((ivbyte > (mDecryptIV[0] & 0xFF)) && (diff > -30) &&
                        (diff < 0)) {
                    // Last was 0x02, here comes 0xff from last round
                    late = 1;
                    lost = -1;
                    mDecryptIV[0] = (byte) ivbyte;
                    for (int i = 1; i < AES_BLOCK_SIZE; i++) {
                        if ((mDecryptIV[i]--) != 0) {
                            break;
                        }
                    }
                    restore = true;
                } else if ((ivbyte > (mDecryptIV[0] & 0xFF)) && (diff > 0)) {
                    // Lost a few packets, but beyond that we're good.
                    lost = ivbyte - (mDecryptIV[0] & 0xFF) - 1;
                    mDecryptIV[0] = (byte) ivbyte;
                } else if ((ivbyte < (mDecryptIV[0] & 0xFF)) && (diff > 0)) {
                    // Lost a few packets, and wrapped around
                    lost = 256 - (mDecryptIV[0] & 0xFF) + ivbyte - 1;
                    mDecryptIV[0] = (byte) ivbyte;
                    for (int i = 1; i < AES_BLOCK_SIZE; i++) {
                        if ((++mDecryptIV[i]) != 0) {
                            break;
                        }
                    }
                } else {
                    return null;
                }

                if (mDecryptHistory[mDecryptIV[0] & 0xFF] == mDecryptIV[1]) {
                    System.arraycopy(saveiv, 0, mDecryptIV, 0, AES_BLOCK_SIZE);
                    return null;
                }
            }

            ocbDecrypt(source, 4, dst, 0, plainLength, mDecryptIV, tag);

            if (tag[0] != source[1] || tag[1] != source[2] || tag[2] != source[3]) {
                System.arraycopy(saveiv, 0, mDecryptIV, 0, AES_BLOCK_SIZE);
                return null;
            }
            mDecryptHistory[mDecryptIV[0] & 0xff] = mDecryptIV[1];

            if (restore)
                System.arraycopy(saveiv, 0, mDecryptIV, 0, AES_BLOCK_SIZE);

            mUiGood++;
            if (late > 0) {
                mUiLate += late;
            }

            if (lost > 0) {
                mUiLost += lost;
            } else if (mUiLost >= Math.abs(lost)) {
                mUiLost -= Math.abs(lost);
            }

            mLastGoodStart = System.nanoTime();
            return dst;
        }
    }

    public int decryptInPlace(final byte[] data, final int offset, final int length) {
        if (data == null || length < 4 || offset < 0 || offset + length > data.length) {
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
        try {
            byte[] input = new byte[length];
            System.arraycopy(data, offset, input, 0, length);
            byte[] decrypted = decrypt(input, length);
            if (decrypted == null) {
                return -1;
            }
            System.arraycopy(decrypted, 0, data, offset, decrypted.length);
            return decrypted.length;
        } catch (Exception e) {
            return -1;
        }
    }

    public void ocbDecrypt(byte[] encrypted, byte[] plain, byte[] nonce, byte[] tag) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        ocbDecrypt(encrypted, 0, plain, 0, encrypted.length, nonce, tag);
    }

    public void ocbDecrypt(byte[] encrypted, int encOffset, byte[] plain, int plainOffset, int len, byte[] nonce, byte[] tag) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        final byte[] checksum = new byte[AES_BLOCK_SIZE];
        final byte[] tmp = new byte[AES_BLOCK_SIZE];
        final byte[] buffer = new byte[AES_BLOCK_SIZE];

        final byte[] delta = mEncryptCipher.doFinal(nonce);

        int offset = 0;
        int remaining = len;
        while (remaining > AES_BLOCK_SIZE) {
            CryptSupport.S2(delta);
            System.arraycopy(encrypted, encOffset + offset, buffer, 0, AES_BLOCK_SIZE);

            CryptSupport.XOR(tmp, delta, buffer);
            mDecryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tmp);

            CryptSupport.XOR(buffer, delta, tmp);
            System.arraycopy(buffer, 0, plain, plainOffset + offset, AES_BLOCK_SIZE);

            CryptSupport.XOR(checksum, checksum, buffer);
            remaining -= AES_BLOCK_SIZE;
            offset += AES_BLOCK_SIZE;
        }

        CryptSupport.S2(delta);
        CryptSupport.ZERO(tmp);

        final long num = remaining * 8;
        tmp[AES_BLOCK_SIZE - 2] = (byte) ((num >> 8) & 0xFF);
        tmp[AES_BLOCK_SIZE - 1] = (byte) (num & 0xFF);
        CryptSupport.XOR(tmp, tmp, delta);

        final byte[] pad = mEncryptCipher.doFinal(tmp);
        CryptSupport.ZERO(tmp);
        System.arraycopy(encrypted, encOffset + offset, tmp, 0, remaining);

        CryptSupport.XOR(tmp, tmp, pad);
        CryptSupport.XOR(checksum, checksum, tmp);

        System.arraycopy(tmp, 0, plain, plainOffset + offset, remaining);

        // Counter-cryptanalysis ePrint 2019/311
        boolean xexMatch = true;
        for (int i = 0; i < AES_BLOCK_SIZE - 1; i++) {
            if (tmp[i] != delta[i]) {
                xexMatch = false;
                break;
            }
        }

        CryptSupport.S3(delta);
        CryptSupport.XOR(tmp, delta, checksum);

        mEncryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tag);

        if (xexMatch) {
            tag[0] = (byte) ~tag[0]; // invalidate tag on XEX* attack
        }
    }

    public byte[] encrypt(final byte[] source, final int length) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        if (!mInit || source == null) return null;

        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0) {
            return nativeEncrypt(nativeHandle, source, length);
        }

        synchronized (this) {
            if (!mInit) return null;

            final byte[] tag = new byte[AES_BLOCK_SIZE];

            // First, increase our IV.
            for (int i = 0; i < AES_BLOCK_SIZE; i++) {
                if ((++mEncryptIV[i]) != 0) {
                    break;
                }
            }

            final byte[] dst = new byte[length + 4];
            ocbEncrypt(source, 0, dst, 4, length, mEncryptIV, tag);

            dst[0] = mEncryptIV[0];
            dst[1] = tag[0];
            dst[2] = tag[1];
            dst[3] = tag[2];

            return dst;
        }
    }

    public void ocbEncrypt(byte[] plain, byte[] encrypted, int plainLength, byte[] nonce, byte[] tag) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        ocbEncrypt(plain, 0, encrypted, 0, plainLength, nonce, tag);
    }

    public void ocbEncrypt(byte[] plain, int plainOffset, byte[] encrypted, int encOffset, int plainLength, byte[] nonce, byte[] tag) throws BadPaddingException, IllegalBlockSizeException, ShortBufferException {
        final byte[] checksum = new byte[AES_BLOCK_SIZE];
        final byte[] tmp = new byte[AES_BLOCK_SIZE];
        final byte[] buffer = new byte[AES_BLOCK_SIZE];

        final byte[] delta = mEncryptCipher.doFinal(nonce);

        int offset = 0;
        int remaining = plainLength;
        while (remaining > AES_BLOCK_SIZE) {
            boolean flipABit = false;
            if (remaining - AES_BLOCK_SIZE <= AES_BLOCK_SIZE) {
                int sum = 0;
                for (int i = 0; i < AES_BLOCK_SIZE - 1; ++i) {
                    sum |= plain[plainOffset + offset + i];
                }
                if (sum == 0) {
                    flipABit = true;
                }
            }

            CryptSupport.S2(delta);
            System.arraycopy(plain, plainOffset + offset, buffer, 0, AES_BLOCK_SIZE);
            CryptSupport.XOR(checksum, checksum, buffer);
            CryptSupport.XOR(tmp, delta, buffer);

            if (flipABit) {
                tmp[0] ^= 1;
                checksum[0] ^= 1;
            }

            mEncryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tmp);

            CryptSupport.XOR(buffer, delta, tmp);
            System.arraycopy(buffer, 0, encrypted, encOffset + offset, AES_BLOCK_SIZE);
            remaining -= AES_BLOCK_SIZE;
            offset += AES_BLOCK_SIZE;
        }

        CryptSupport.S2(delta);
        CryptSupport.ZERO(tmp);
        final long num = remaining * 8;
        tmp[AES_BLOCK_SIZE - 2] = (byte) ((num >> 8) & 0xFF);
        tmp[AES_BLOCK_SIZE - 1] = (byte) (num & 0xFF);
        CryptSupport.XOR(tmp, tmp, delta);

        final byte[] pad = mEncryptCipher.doFinal(tmp);

        System.arraycopy(plain, plainOffset + offset, tmp, 0, remaining);
        System.arraycopy(pad, remaining, tmp, remaining, AES_BLOCK_SIZE - remaining);
        CryptSupport.XOR(checksum, checksum, tmp);
        CryptSupport.XOR(tmp, pad, tmp);

        System.arraycopy(tmp, 0, encrypted, encOffset + offset, remaining);
        CryptSupport.S3(delta);
        CryptSupport.XOR(tmp, delta, checksum);
        mEncryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tag);
    }

    private void syncStatsFromNative() {
        final long nativeHandle = mNativeHandle;
        if (nativeHandle != 0) {
            synchronized (mStatsBuffer) {
                nativeGetStats(nativeHandle, mStatsBuffer);
                mUiGood = mStatsBuffer[0];
                mUiLate = mStatsBuffer[1];
                mUiLost = mStatsBuffer[2];
            }
        }
    }

    /**
     * Helper mathematical functions for OCB Galois field arithmetic.
     */
    private static class CryptSupport {

        private static final int SHIFTBITS = 7;

        public static void XOR(final byte[] dst, final byte[] a, final byte[] b) {
            for (int i = 0; i < AES_BLOCK_SIZE; i++) {
                dst[i] = (byte) (a[i] ^ b[i]);
            }
        }

        public static void S2(final byte[] block) {
            int carry = (block[0] >> SHIFTBITS) & 0x1;
            for (int i = 0; i < AES_BLOCK_SIZE - 1; i++) {
                block[i] = (byte) ((block[i] << 1) | ((block[i + 1] >> SHIFTBITS) & 0x1));
            }
            block[AES_BLOCK_SIZE - 1] = (byte) ((block[AES_BLOCK_SIZE - 1] << 1) ^ (carry * 0x87));
        }

        public static void S3(final byte[] block) {
            final int carry = (block[0] >> SHIFTBITS) & 0x1;
            for (int i = 0; i < AES_BLOCK_SIZE - 1; i++) {
                block[i] = (byte) (block[i] ^ ((block[i] << 1) | ((block[i + 1] >> SHIFTBITS) & 0x1)));
            }
            block[AES_BLOCK_SIZE - 1] = (byte) (block[AES_BLOCK_SIZE - 1] ^ ((block[AES_BLOCK_SIZE - 1] << 1) ^ (carry * 0x87)));
        }

        public static void ZERO(final byte[] block) {
            Arrays.fill(block, (byte) 0);
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
        return mUiResync;
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
