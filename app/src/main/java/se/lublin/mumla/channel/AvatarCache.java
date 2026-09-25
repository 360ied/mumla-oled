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

package se.lublin.mumla.channel;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.collection.LruCache;

import se.lublin.humla.model.IUser;

/**
 * Memory-bounded LRU cache for user avatars, keyed by user session and texture cache key.
 *
 * Sized in kilobytes based on Bitmap heap footprint rather than raw entry count to prevent
 * OutOfMemory errors on devices with many users or large avatars.
 */
public class AvatarCache {
    /** Default 4 MB maximum heap allocation for decoded avatars. */
    public static final int DEFAULT_MAX_SIZE_KB = 4 * 1024;

    public static final class Entry {
        final int cacheKey;
        final Bitmap bitmap;

        public Entry(int cacheKey, Bitmap bitmap) {
            this.cacheKey = cacheKey;
            this.bitmap = bitmap;
        }

        public int getCacheKey() {
            return cacheKey;
        }

        public Bitmap getBitmap() {
            return bitmap;
        }
    }

    private final int mMaxSizeKb;
    private final LruCache<Integer, Entry> mCache;

    public AvatarCache() {
        this(DEFAULT_MAX_SIZE_KB);
    }

    public AvatarCache(int maxSizeKb) {
        mMaxSizeKb = maxSizeKb;
        mCache = new LruCache<Integer, Entry>(maxSizeKb) {
            @Override
            protected int sizeOf(Integer key, Entry value) {
                if (value != null && value.bitmap != null) {
                    return Math.max(1, value.bitmap.getByteCount() / 1024);
                }
                return 1;
            }
        };
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
    @Nullable
    public Bitmap get(IUser user) {
        if (user == null) {
            return null;
        }

        int session = user.getSession();
        int key = user.getTextureCacheKey();
        if (key == 0) {
            mCache.remove(session);
            return null;
        }

        Entry cached = mCache.get(session);
        if (cached != null && cached.cacheKey == key) {
            return cached.bitmap;
        }

        // Cache miss or stale entry: decode new bitmap only if texture data is present
        if (user.hasTexture()) {
            byte[] texture = user.getTexture();
            if (texture != null && texture.length > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(texture, 0, texture.length);
                if (bitmap != null) {
                    mCache.put(session, new Entry(key, bitmap));
                    return bitmap;
                }
            }
            // Decoding failed or texture was unparseable: record negative entry to prevent repeated
            // UI-thread toByteArray() allocations and decoding attempts on every scroll or talk event.
            mCache.put(session, new Entry(key, null));
            return null;
        }

        mCache.remove(session);
        return null;
    }

    public void remove(int session) {
        mCache.remove(session);
    }

    public void clear() {
        mCache.evictAll();
    }

    public int size() {
        return mCache.size();
    }

    public int maxSize() {
        return mMaxSizeKb;
    }

    @Nullable
    @VisibleForTesting
    Entry getEntry(int session) {
        return mCache.get(session);
    }
}
