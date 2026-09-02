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

package se.lublin.humla.test;

import com.google.protobuf.ByteString;
import junit.framework.TestCase;

import se.lublin.humla.Constants;
import se.lublin.humla.model.Channel;
import se.lublin.humla.model.User;
import se.lublin.humla.net.Permissions;
import se.lublin.humla.protobuf.MumbleUDP;

/**
 * Unit tests for Mumble 1.5 protocol features including version_v2 packing,
 * channel listening model state, permissions, and Protobuf UDP serialization.
 */
public class Mumble15ProtocolTest extends TestCase {

    public void testVersionV2PackingAndFormatting() {
        long v150 = Constants.toVersionV2(1, 5, 0);
        assertEquals(0x0001000500000000L, v150);
        assertEquals("1.5.0", Constants.formatVersion(v150));

        long v151 = Constants.toVersionV2(1, 5, 1);
        assertEquals(0x0001000500010000L, v151);
        assertEquals("1.5.1", Constants.formatVersion(v151));

        long v152 = Constants.toVersionV2(1, 5, 2);
        assertEquals(0x0001000500020000L, v152);
        assertEquals("1.5.2", Constants.formatVersion(v152));

        long v125 = Constants.toVersionV2(1, 2, 5);
        assertEquals(0x0001000200050000L, v125);
        assertEquals("1.2.5", Constants.formatVersion(v125));

        int legacy151 = (1 << 16) | (5 << 8) | 1;
        assertEquals(legacy151, Constants.toLegacyVersion(v151));
        assertEquals(v151, Constants.toVersionV2FromLegacy(legacy151));

        assertTrue(Constants.isProtobufUdpSupported(v150, 0));
        assertTrue(Constants.isProtobufUdpSupported(v151, 0));
        assertFalse(Constants.isProtobufUdpSupported(Constants.toVersionV2(1, 4, 230), 0));
        assertFalse(Constants.isProtobufUdpSupported(0, (1 << 16) | (4 << 8)));
    }

    public void testChannelModelMumble15Fields() {
        Channel ch = new Channel(10, false);
        assertEquals(0, ch.getMaxUsers());
        assertFalse(ch.isEnterRestricted());
        assertTrue(ch.canEnter());
        assertFalse(ch.isListening());
        assertEquals(1.0f, ch.getListeningVolume(), 0.001f);

        ch.setMaxUsers(25);
        assertEquals(25, ch.getMaxUsers());

        ch.setEnterRestricted(true);
        assertTrue(ch.isEnterRestricted());

        ch.setCanEnter(false);
        assertFalse(ch.canEnter());

        ch.setListening(true);
        assertTrue(ch.isListening());

        ch.setListeningVolume(0.75f);
        assertEquals(0.75f, ch.getListeningVolume(), 0.001f);
    }

    public void testUserListeningChannels() {
        User user = new User(1, "Alice");
        assertTrue(user.getListeningChannels().isEmpty());
        assertFalse(user.isListeningTo(10));

        user.addListeningChannel(10);
        user.addListeningChannel(20);
        assertEquals(2, user.getListeningChannels().size());
        assertTrue(user.isListeningTo(10));
        assertTrue(user.isListeningTo(20));
        assertFalse(user.isListeningTo(30));

        user.removeListeningChannel(10);
        assertEquals(1, user.getListeningChannels().size());
        assertFalse(user.isListeningTo(10));
        assertTrue(user.isListeningTo(20));
    }

    public void testPermissionsConstants() {
        assertEquals(0x800, Permissions.Listen);
        assertEquals(0x100000, Permissions.ResetUserContent);

        assertTrue((Permissions.All & Permissions.Listen) != 0);
        assertTrue((Permissions.All & Permissions.ResetUserContent) != 0);
    }

    public void testProtobufUDPMessages() throws Exception {
        byte[] opusPayload = new byte[]{0x78, (byte) 0x9c, 0x01, 0x02};
        MumbleUDP.Audio audioMsg = MumbleUDP.Audio.newBuilder()
                .setSenderSession(42)
                .setFrameNumber(12345)
                .setOpusData(ByteString.copyFrom(opusPayload))
                .setContext(3) // LISTEN
                .setIsTerminator(true)
                .setVolumeAdjustment(0.8f)
                .build();

        byte[] serializedAudio = audioMsg.toByteArray();
        MumbleUDP.Audio parsedAudio = MumbleUDP.Audio.parseFrom(serializedAudio);
        assertEquals(42, parsedAudio.getSenderSession());
        assertEquals(12345, parsedAudio.getFrameNumber());
        assertEquals(3, parsedAudio.getContext());
        assertTrue(parsedAudio.getIsTerminator());
        assertEquals(0.8f, parsedAudio.getVolumeAdjustment(), 0.001f);
        assertEquals(4, parsedAudio.getOpusData().size());

        MumbleUDP.Ping pingMsg = MumbleUDP.Ping.newBuilder()
                .setTimestamp(987654321L)
                .setRequestExtendedInformation(true)
                .setServerVersionV2(Constants.toVersionV2(1, 5, 0))
                .setUserCount(5)
                .setMaxUserCount(50)
                .setMaxBandwidthPerUser(72000)
                .build();

        byte[] serializedPing = pingMsg.toByteArray();
        MumbleUDP.Ping parsedPing = MumbleUDP.Ping.parseFrom(serializedPing);
        assertEquals(987654321L, parsedPing.getTimestamp());
        assertTrue(parsedPing.getRequestExtendedInformation());
        assertEquals(Constants.toVersionV2(1, 5, 0), parsedPing.getServerVersionV2());
        assertEquals(5, parsedPing.getUserCount());
        assertEquals(50, parsedPing.getMaxUserCount());
        assertEquals(72000, parsedPing.getMaxBandwidthPerUser());
    }

    public void testClientProtobufUDPAudioPacketFormat() throws Exception {
        byte[] opusBytes = new byte[]{0x10, 0x20, 0x30};
        byte targetId = 2;
        long frameNumber = 100;
        boolean isTerminator = true;

        MumbleUDP.Audio.Builder audioBuilder = MumbleUDP.Audio.newBuilder();
        if (targetId != 0) {
            audioBuilder.setTarget(targetId & 0xFF);
        }
        audioBuilder.setFrameNumber(frameNumber);
        audioBuilder.setOpusData(ByteString.copyFrom(opusBytes));
        if (isTerminator) {
            audioBuilder.setIsTerminator(true);
        }

        byte[] protoBytes = audioBuilder.build().toByteArray();
        byte[] packet = new byte[1 + protoBytes.length];
        packet[0] = 0x00; // Protobuf Audio type
        System.arraycopy(protoBytes, 0, packet, 1, protoBytes.length);

        // Verify packet header byte indicates Protobuf Audio type (0x00)
        assertEquals(0x00, packet[0]);

        // Verify deserialization matches outgoing packet data
        MumbleUDP.Audio parsed = MumbleUDP.Audio.parseFrom(ByteString.copyFrom(packet, 1, packet.length - 1));
        assertEquals(2, parsed.getTarget());
        assertEquals(100L, parsed.getFrameNumber());
        assertTrue(parsed.getIsTerminator());
        assertEquals(3, parsed.getOpusData().size());
        assertEquals(0x10, parsed.getOpusData().byteAt(0));
    }

    public void testProtobufUDPConnectivityPingFormat() throws Exception {
        long timestamp = 1234567890L;
        MumbleUDP.Ping.Builder pb = MumbleUDP.Ping.newBuilder();
        pb.setTimestamp(timestamp);
        byte[] pingBytes = pb.build().toByteArray();
        byte[] packet = new byte[1 + pingBytes.length];
        packet[0] = 0x01; // Protobuf Ping type
        System.arraycopy(pingBytes, 0, packet, 1, pingBytes.length);

        assertEquals(0x01, packet[0]);
        MumbleUDP.Ping parsed = MumbleUDP.Ping.parseFrom(ByteString.copyFrom(packet, 1, packet.length - 1));
        assertEquals(timestamp, parsed.getTimestamp());
        assertFalse(parsed.getRequestExtendedInformation());
    }
}
