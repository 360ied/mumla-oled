/*
 * Copyright (C) 2026 Mumla OLED Contributors
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

import junit.framework.TestCase;

/**
 * Unit tests verifying that outgoing and incoming UDP pings adhere to the Mumble
 * protocol specifications for both legacy and Protobuf formats.
 */
public class HumlaUDPPingTest extends TestCase {

    /**
     * Tests legacy UDP ping packet construction using varint encoding.
     * Mumble servers (murmur) require legacy connectivity pings to be <= 10 bytes
     * (1 header byte + at most 9 varint timestamp bytes).
     */
    public void testLegacyUDPPingEncodingAndDecoding() {
        long[] testTimestamps = new long[]{
                0L,
                42L,
                127L,
                128L,
                16383L,
                16384L,
                1000000L,
                2097151L,            // 3-byte boundary (0x1FFFFF)
                2097152L,            // 4-byte boundary (0x200000)
                268435455L,          // 4-byte boundary (0x0FFFFFFF)
                268435456L,          // 5-byte boundary (0x10000000)
                2147483648L,         // 5-byte boundary with MSB set (0x80000000L - sign extension test)
                4294967295L,         // 5-byte boundary (0xFFFFFFFFL)
                4294967296L,         // 64-bit boundary (0x100000000L)
                123456789012345L,
                0x7FFFFFFFFFFFFFFFL, // Long.MAX_VALUE
                0x8000000000000000L  // Long.MIN_VALUE / unsigned 64-bit MSB set
        };

        for (long t : testTimestamps) {
            byte[] pingBuffer = new byte[10];
            pingBuffer[0] = (byte) ((HumlaUDPMessageType.UDPPing.ordinal() << 5) & 0xFF);
            PacketBuffer pb = new PacketBuffer(pingBuffer, 10);
            pb.skip(1);
            pb.writeLong(t);

            int packetSize = pb.size();
            // Header (1 byte) + varint (1-9 bytes) must be <= 10 bytes
            assertTrue("Packet size " + packetSize + " must be <= 10 for timestamp " + t, packetSize <= 10);
            assertTrue("Packet size " + packetSize + " must be >= 2 for timestamp " + t, packetSize >= 2);

            // Verify header byte indicates UDPPing (ordinal 1, 1 << 5 = 32)
            assertEquals((byte) (1 << 5), pingBuffer[0]);

            // Verify decoding roundtrip
            PacketBuffer decodeBuffer = new PacketBuffer(pingBuffer, packetSize);
            decodeBuffer.skip(1);
            long decodedTimestamp = decodeBuffer.readLong();
            assertEquals("Roundtrip timestamp must match for " + t, t, decodedTimestamp);
        }
    }

    /**
     * Tests negative varint encoding and decoding.
     * Mumble protocol uses 0xFC prefix for -1 to -4 (1 byte total),
     * and 0xF8 prefix followed by inverted varint for other negative integers.
     */
    public void testNegativeVarintEncodingAndDecoding() {
        long[] testNegatives = new long[]{
                -1L,
                -2L,
                -3L,
                -4L,
                -5L,
                -42L,
                -127L,
                -128L,
                -16383L,
                -16384L,
                -1000000L,
                -2147483648L,
                -4294967295L,
                -4294967296L
        };

        for (long t : testNegatives) {
            byte[] buffer = new byte[16];
            PacketBuffer pb = new PacketBuffer(buffer, buffer.length);
            pb.writeLong(t);

            int size = pb.size();
            assertTrue("Encoded size must be >= 1 for " + t, size >= 1);

            // -1 to -4 should use shortcase 0xFC prefix (1 byte)
            if (t >= -4L && t <= -1L) {
                assertEquals("Shortcase negative varint should be 1 byte for " + t, 1, size);
                assertEquals((byte) (0xFC | ~t), buffer[0]);
            } else {
                // -5 and beyond should use 0xF8 prefix
                assertEquals("Negative varint should start with 0xF8 for " + t, (byte) 0xF8, buffer[0]);
            }

            PacketBuffer decodeBuffer = new PacketBuffer(buffer, size);
            long decoded = decodeBuffer.readLong();
            assertEquals("Roundtrip negative value must match for " + t, t, decoded);
        }
    }
}
