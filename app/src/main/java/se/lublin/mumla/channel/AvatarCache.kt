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

package se.lublin.mumla.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import androidx.collection.LruCache
import se.lublin.humla.model.IUser
import se.lublin.mumla.util.MumbleImageGetter

/**
 * Memory-bounded LRU cache for user avatars, keyed by user session and texture cache key.
 *
 * Sized in kilobytes based on Bitmap heap footprint rather than raw entry count to prevent
 * OutOfMemory errors on devices with many users or large avatars.
 *
 * Thread-safety: androidx.collection.LruCache synchronizes get/put
 * internally, so RecyclerView bind-path reads racing talk-state updates
 * on the UI thread are memory-safe. Decode stays synchronous on the
 * caller (UI) thread: avatars render as small icons and the bounded
 * 512 px two-pass decode caps the allocation, so no async pop-in is
 * needed (bind paths ChannelAdapter/ChannelListAdapter require Bitmap
 * synchronously). Check-then-act (get-then-put) is not atomic; the worst
 * case is a redundant decode, never corruption.
 */
class AvatarCache {
    class Entry(val cacheKey: Int, val bitmap: Bitmap?)

    private val mMaxSizeKb: Int
    private val mCache: LruCache<Int, Entry>

    constructor() : this(DEFAULT_MAX_SIZE_KB)

    constructor(maxSizeKb: Int) {
        mMaxSizeKb = maxSizeKb
        mCache = object : LruCache<Int, Entry>(maxSizeKb) {
            override fun sizeOf(key: Int, value: Entry): Int {
                val bitmap = value.bitmap
                if (bitmap != null) {
                    return maxOf(1, bitmap.byteCount / 1024)
                }
                return 1
            }
        }
    }

    /**
     * Gets or decodes the avatar bitmap for a user.
     *
     * If the avatar is already cached and the user's texture cache key matches, returns the
     * cached Bitmap without reading or copying the full texture byte array.
     *
     * @param user The user whose avatar to retrieve.
     * @return Decoded avatar Bitmap, or null if user has no avatar or decoding fails.
     */
    fun get(user: IUser?): Bitmap? {
        if (user == null) {
            return null
        }

        val session = user.session
        val key = user.textureCacheKey
        if (key == 0) {
            mCache.remove(session)
            return null
        }

        val cached = mCache[session]
        if (cached != null && cached.cacheKey == key) {
            return cached.bitmap
        }

        // Cache miss or stale entry: decode new bitmap only if texture data is present
        if (user.hasTexture()) {
            val texture = user.texture
            if (texture != null && texture.isNotEmpty()) {
                val bitmap = decodeBoundedAvatar(texture)
                if (bitmap != null) {
                    mCache.put(session, Entry(key, bitmap))
                    return bitmap
                }
            }
            // Decoding failed or texture was unparseable: record negative entry to prevent repeated
            // UI-thread toByteArray() allocations and decoding attempts on every scroll or talk event.
            mCache.put(session, Entry(key, null))
            return null
        }

        mCache.remove(session)
        return null
    }

    fun remove(session: Int) {
        mCache.remove(session)
    }

    fun clear() {
        mCache.evictAll()
    }

    fun size(): Int {
        return mCache.size()
    }

    fun maxSize(): Int {
        return mMaxSizeKb
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package tests).
    @VisibleForTesting
    fun getEntry(session: Int): Entry? {
        return mCache[session]
    }

    companion object {
        /** Default 4 MB maximum heap allocation for decoded avatars. */
        const val DEFAULT_MAX_SIZE_KB = 4 * 1024

        /**
         * Avatar decode target: avatars render as small talk-state icons, so
         * 512 px longest-side is ample. Shared absolute ceiling lives in
         * MumbleImageGetter.ABSOLUTE_MAX_DIMENSION.
         */
        const val AVATAR_MAX_DIMENSION = 512
        const val AVATAR_MAX_PIXELS = 512L * 512

        /**
         * Two-pass bounded avatar decode via the shared chat-image sampler:
         * inJustDecodeBounds first, reject past the absolute ceiling, then
         * downsample to the 512 px avatar target. Pure sampler math is covered
         * by JVM tests; BitmapFactory itself is stubbed on the JVM.
         */
        private fun decodeBoundedAvatar(texture: ByteArray): Bitmap? {
            try {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(texture, 0, texture.size, bounds)
                val width = bounds.outWidth
                val height = bounds.outHeight
                if (width <= 0 ||
                    height <= 0 ||
                    MumbleImageGetter.isOversize(width, height)
                ) {
                    return null
                }
                val decode = BitmapFactory.Options()
                decode.inSampleSize = MumbleImageGetter.calculateInSampleSize(
                    width, height, AVATAR_MAX_DIMENSION, AVATAR_MAX_PIXELS
                )
                return BitmapFactory.decodeByteArray(texture, 0, texture.size, decode)
            } catch (e: OutOfMemoryError) {
                return null
            }
        }
    }
}
