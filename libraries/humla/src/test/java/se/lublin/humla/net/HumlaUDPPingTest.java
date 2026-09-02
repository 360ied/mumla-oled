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
                123456789012345L,
                0x7FFFFFFFFFFFFFFFL
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
}
