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

package se.lublin.mumla.servers;

import junit.framework.TestCase;

import java.nio.ByteBuffer;

public class ServerInfoResponseTest extends TestCase {

    private static byte[] validResponse() {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.putInt(0x010203);
        buffer.putLong(12345L);
        buffer.putInt(10);
        buffer.putInt(100);
        buffer.putInt(72000);
        return buffer.array();
    }

    public void testValidResponse_ParsesFields() {
        ServerInfoResponse response = new ServerInfoResponse(null, validResponse(), 5);
        assertFalse(response.isDummy());
        assertEquals(0x010203, response.getVersion());
        assertEquals(12345L, response.getIdentifier());
        assertEquals(10, response.getCurrentUsers());
        assertEquals(100, response.getMaximumUsers());
        assertEquals(72000, response.getAllowedBandwidth());
        assertEquals(5, response.getLatency());
    }

    public void testShortBuffer_Throws() {
        try {
            new ServerInfoResponse(null, new byte[10], 5);
            fail("Short UDP reply must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Caller funnels this to the dummy response path.
        }
    }

    public void testEmptyBuffer_Throws() {
        try {
            new ServerInfoResponse(null, new byte[0], 5);
            fail("Empty UDP reply must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testNullBuffer_Throws() {
        try {
            new ServerInfoResponse(null, null, 5);
            fail("Null UDP reply must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testOneByteShort_Throws() {
        try {
            new ServerInfoResponse(null, new byte[23], 5);
            fail("23-byte UDP reply must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testDefaultConstructor_IsDummy() {
        assertTrue(new ServerInfoResponse().isDummy());
    }
}
