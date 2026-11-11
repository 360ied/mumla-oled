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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import se.lublin.humla.model.IUser;

public class AvatarCacheTest extends TestCase {

    private AvatarCache mCache;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mCache = new AvatarCache(1024);
    }

    public void testNullUserReturnsNull() {
        assertNull(mCache.get(null));
    }

    public void testUserWithoutTextureReturnsNull() {
        IUser user = mock(IUser.class);
        when(user.getSession()).thenReturn(10);
        when(user.hasTexture()).thenReturn(false);
        when(user.getTexture()).thenReturn(null);
        when(user.getTextureCacheKey()).thenReturn(0);
        assertFalse(user.hasTexture());
        assertEquals(0, user.getTextureCacheKey());
        assertNull(mCache.get(user));
    }

    public void testNegativeCachingForUnparseableTexture() {
        IUser user = mock(IUser.class);
        byte[] texture = "corrupt_png_stream".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(user.getSession()).thenReturn(20);
        when(user.hasTexture()).thenReturn(true);
        when(user.getTexture()).thenReturn(texture);
        when(user.getTextureCacheKey()).thenReturn(12345);
        assertTrue(user.hasTexture());
        assertTrue(user.getTextureCacheKey() != 0);

        // First call fails decode and puts negative entry
        assertNull(mCache.get(user));
        AvatarCache.Entry entry = mCache.getEntry(user.getSession());
        assertNotNull(entry);
        assertEquals(user.getTextureCacheKey(), entry.getCacheKey());
        assertNull(entry.getBitmap());

        // Subsequent call returns null from cache hit
        assertNull(mCache.get(user));
    }

    public void testRemoveAndClear() {
        mCache.remove(42);
        assertEquals(0, mCache.size());
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
