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

package se.lublin.humla.net

import java.security.InvalidKeyException
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.ShortBufferException

/**
 * Cryptographic state machine for Mumble OCB2-AES128 authenticated encryption,
 * IV synchronization, packet loss tracking, and replay detection.
 *
 * Fully backed by the native C++ engine (libhumlaaudio.so) with hardware SIMD
 * acceleration (ARMv8 Crypto Extensions / Intel AES-NI).
 *
 * Syntactic Kotlin conversion only: every native signature is byte-identical
 * (verified with javap), every synchronized becomes @Synchronized, threading
 * and JNI semantics frozen.
 */
class CryptState {
    companion object {
        const val AES_BLOCK_SIZE = 16

        /** OCB2 crypt header prepended to every encrypted datagram. */
        private const val CRYPT_HEADER_BYTES = 4

        private val sLoadError: Throwable? = run {
            var error: Throwable? = null
            try {
                System.loadLibrary("humlaaudio")
                if (!nativeIsSupported()) {
                    error = UnsupportedOperationException("Native CryptState is not supported")
                }
            } catch (t: Throwable) {
                error = t
            }
            error
        }

        @JvmStatic
        fun isNativeAvailable(): Boolean {
            return sLoadError == null
        }

        private fun ensureLoaded() {
            if (sLoadError != null) {
                throw IllegalStateException("Native crypto library failed to load", sLoadError)
            }
        }

        @JvmStatic
        private external fun nativeIsSupported(): Boolean
    }

    @Volatile
    private var mNativeHandle: Long = 0
    private val mStatsBuffer = IntArray(4)

    private val mEncryptIV = ByteArray(AES_BLOCK_SIZE)
    private val mDecryptIV = ByteArray(AES_BLOCK_SIZE)

    // Widened to public: Kotlin has no package visibility; HumlaConnection
    // (same library) reads remote stats directly, tests pin them.
    @JvmField
    @Volatile
    var mUiGood: Int = 0

    @Volatile
    private var mUiLate: Int = 0

    @Volatile
    private var mUiLost: Int = 0

    @Volatile
    private var mUiResync: Int = 0

    @JvmField
    @Volatile
    var mUiRemoteGood: Int = 0

    @JvmField
    @Volatile
    var mUiRemoteLate: Int = 0

    @JvmField
    @Volatile
    var mUiRemoteLost: Int = 0

    @JvmField
    @Volatile
    var mUiRemoteResync: Int = 0

    @Volatile
    private var mLastGoodStart: Long = 0

    @Volatile
    private var mLastRequestStart: Long = 0

    @JvmField
    @Volatile
    var mInit: Boolean = false

    init {
        ensureLoaded()
        mNativeHandle = nativeCreate()
        if (mNativeHandle == 0L) {
            throw IllegalStateException("Failed to create native CryptState instance")
        }
    }

    // Drops the super call: kotlin.Any exposes none and Object's is a no-op.
    protected fun finalize() {
        destroy()
    }

    @Synchronized
    fun destroy() {
        mInit = false
        Arrays.fill(mEncryptIV, 0.toByte())
        Arrays.fill(mDecryptIV, 0.toByte())
        if (mNativeHandle != 0L) {
            val handle = mNativeHandle
            mNativeHandle = 0
            nativeDestroy(handle)
        }
    }

    fun isValid(): Boolean {
        return mInit
    }

    /**
     * @return The time since the last good decrypt in microseconds.
     */
    fun getLastGoodElapsed(): Long {
        if (mNativeHandle != 0L) {
            val us = nativeGetLastGoodElapsedUs(mNativeHandle)
            if (us >= 0) return us
        }
        return (System.nanoTime() - mLastGoodStart) / 1000
    }

    /**
     * @return The time since the last request in microseconds.
     */
    fun getLastRequestElapsed(): Long {
        return (System.nanoTime() - mLastRequestStart) / 1000
    }

    /**
     * Resets the recorded time of the last request to the current time.
     */
    fun resetLastRequestTime() {
        mLastRequestStart = System.nanoTime()
    }

    @Synchronized
    fun getEncryptIV(): ByteArray {
        if (mNativeHandle != 0L) {
            nativeGetEncryptIV(mNativeHandle, mEncryptIV)
        }
        return mEncryptIV.clone()
    }

    @Synchronized
    fun getDecryptIV(): ByteArray {
        if (mNativeHandle != 0L) {
            nativeGetDecryptIV(mNativeHandle, mDecryptIV)
        }
        return mDecryptIV.clone()
    }

    @Synchronized
    fun setDecryptIV(div: ByteArray?): Boolean {
        if (div != null && div.size == AES_BLOCK_SIZE && mNativeHandle != 0L) {
            System.arraycopy(div, 0, mDecryptIV, 0, AES_BLOCK_SIZE)
            return nativeSetDecryptIV(mNativeHandle, div)
        }
        return false
    }

    @Synchronized
    fun setEncryptIV(eiv: ByteArray?): Boolean {
        if (eiv != null && eiv.size == AES_BLOCK_SIZE && mNativeHandle != 0L) {
            System.arraycopy(eiv, 0, mEncryptIV, 0, AES_BLOCK_SIZE)
            return nativeSetEncryptIV(mNativeHandle, eiv)
        }
        return false
    }

    @Synchronized
    @Throws(InvalidKeyException::class)
    fun setKeys(rkey: ByteArray?, eiv: ByteArray?, div: ByteArray?) {
        if (rkey == null || eiv == null || div == null ||
            rkey.size != AES_BLOCK_SIZE || eiv.size != AES_BLOCK_SIZE || div.size != AES_BLOCK_SIZE
        ) {
            throw InvalidKeyException("Keys and IVs must be 16 bytes")
        }

        System.arraycopy(eiv, 0, mEncryptIV, 0, AES_BLOCK_SIZE)
        System.arraycopy(div, 0, mDecryptIV, 0, AES_BLOCK_SIZE)

        mUiGood = 0
        mUiLate = 0
        mUiLost = 0
        mUiResync = 0
        mUiRemoteGood = 0
        mUiRemoteLate = 0
        mUiRemoteLost = 0
        mUiRemoteResync = 0

        if (mNativeHandle == 0L) {
            mNativeHandle = nativeCreate()
        }

        if (mNativeHandle != 0L) {
            mInit = nativeSetKeys(mNativeHandle, rkey, eiv, div)
        } else {
            mInit = false
        }

        if (mInit) {
            mLastGoodStart = System.nanoTime()
        }
    }

    /**
     * Decrypts data using the OCB-AES128 standard.
     * @param source The encoded audio data.
     * @param length The length of the source array.
     */
    @Throws(BadPaddingException::class, IllegalBlockSizeException::class, ShortBufferException::class)
    fun decrypt(source: ByteArray?, length: Int): ByteArray? {
        if (source == null || length < CRYPT_HEADER_BYTES || !mInit || length > source.size) return null

        val nativeHandle = mNativeHandle
        if (nativeHandle != 0L) {
            val decrypted = nativeDecrypt(nativeHandle, source, length)
            if (decrypted != null) {
                mLastGoodStart = System.nanoTime()
            }
            return decrypted
        }
        return null
    }

    fun decryptInPlace(data: ByteArray?, offset: Int, length: Int): Int {
        if (!mInit || data == null || length < CRYPT_HEADER_BYTES || offset < 0 ||
            offset.toLong() + length > data.size
        ) {
            return -1
        }
        val nativeHandle = mNativeHandle
        if (nativeHandle != 0L) {
            val plainLength = nativeDecryptInPlace(nativeHandle, data, offset, length)
            if (plainLength >= 0) {
                mLastGoodStart = System.nanoTime()
            }
            return plainLength
        }
        return -1
    }

    @Throws(BadPaddingException::class, IllegalBlockSizeException::class, ShortBufferException::class)
    fun encrypt(source: ByteArray?, length: Int): ByteArray? {
        if (source == null || length < 0 || !mInit || length > source.size) return null

        val nativeHandle = mNativeHandle
        if (nativeHandle != 0L) {
            return nativeEncrypt(nativeHandle, source, length)
        }
        return null
    }

    private fun syncStatsFromNative() {
        val nativeHandle = mNativeHandle
        if (nativeHandle != 0L && mInit) {
            synchronized(mStatsBuffer) {
                nativeGetStats(nativeHandle, mStatsBuffer)
                mUiGood = mStatsBuffer[0]
                mUiLate = mStatsBuffer[1]
                mUiLost = mStatsBuffer[2]
                mUiResync = mStatsBuffer[3]
            }
        }
    }

    fun getGood(): Int {
        syncStatsFromNative()
        return mUiGood
    }

    fun getLate(): Int {
        syncStatsFromNative()
        return mUiLate
    }

    fun getLost(): Int {
        syncStatsFromNative()
        return mUiLost
    }

    fun getResync(): Int {
        syncStatsFromNative()
        return mUiResync
    }

    fun getRemoteGood(): Int {
        return mUiRemoteGood
    }

    fun getRemoteLate(): Int {
        return mUiRemoteLate
    }

    fun getRemoteLost(): Int {
        return mUiRemoteLost
    }

    fun getRemoteResync(): Int {
        return mUiRemoteResync
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetKeys(handle: Long, rkey: ByteArray, eiv: ByteArray, div: ByteArray): Boolean
    private external fun nativeSetDecryptIV(handle: Long, div: ByteArray): Boolean
    private external fun nativeSetEncryptIV(handle: Long, eiv: ByteArray): Boolean
    private external fun nativeGetEncryptIV(handle: Long, out: ByteArray): Boolean
    private external fun nativeGetDecryptIV(handle: Long, out: ByteArray): Boolean
    private external fun nativeEncrypt(handle: Long, source: ByteArray, length: Int): ByteArray?
    private external fun nativeDecrypt(handle: Long, source: ByteArray, length: Int): ByteArray?
    private external fun nativeDecryptInPlace(handle: Long, buffer: ByteArray, offset: Int, cryptedLength: Int): Int
    private external fun nativeGetStats(handle: Long, statsOut: IntArray)
    private external fun nativeGetLastGoodElapsedUs(handle: Long): Long
}
