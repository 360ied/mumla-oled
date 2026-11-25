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

package se.lublin.mumla.channel;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;

/**
 * The cache is value-keyed: session, the UserInfo texture cache key, and the
 * texture bytes. No live handle takes part.
 */
public class AvatarCacheTest extends TestCase {

    private AvatarCache mCache;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mCache = new AvatarCache(1024);
    }

    public void testZeroCacheKeyReturnsNull() {
        // Zero key short-circuits before texture is read.
        assertNull(mCache.get(10, 0, null));
        assertNull(mCache.getEntry(10));
    }

    public void testNoTextureReturnsNull() {
        assertNull(mCache.get(11, 4242, null));
        // No-texture path evicts instead of recording a negative entry.
        assertNull(mCache.getEntry(11));
    }

    public void testEmptyTextureReturnsNull() {
        assertNull(mCache.get(12, 4242, new byte[0]));
        assertNull(mCache.getEntry(12));
    }

    public void testNegativeCachingForUnparseableTexture() {
        byte[] texture = "corrupt_png_stream".getBytes(StandardCharsets.UTF_8);
        // First call fails decode and puts a negative entry.
        assertNull(mCache.get(20, 12345, texture));
        AvatarCache.Entry entry = mCache.getEntry(20);
        assertNotNull(entry);
        assertEquals(12345, entry.getCacheKey());
        assertNull(entry.getBitmap());

        // Subsequent call returns null from the cache hit.
        assertNull(mCache.get(20, 12345, texture));
    }

    public void testRemoveClearsOneSession() {
        mCache.remove(42);
        assertNull(mCache.getEntry(42));
        assertEquals(0, mCache.size());
    }

    public void testClearAndMaxSize() {
        mCache.clear();
        assertEquals(0, mCache.size());
        assertEquals(1024, mCache.maxSize());
    }

    public void testEntryAccessors() {
        AvatarCache.Entry entry = new AvatarCache.Entry(12345, null);
        assertEquals(12345, entry.getCacheKey());
        assertNull(entry.getBitmap());
    }
}
