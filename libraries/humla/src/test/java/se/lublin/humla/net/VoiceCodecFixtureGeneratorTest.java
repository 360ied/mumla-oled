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

package se.lublin.humla.net;

import com.google.protobuf.ByteString;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import junit.framework.TestCase;
import se.lublin.humla.Constants;
import se.lublin.humla.protobuf.MumbleUDP;

/**
 * Generates byte-exact fixtures for the native packet codec (step 5a.2).
 *
 * Each fixture is built with today's Kotlin framing (Java protobuf classes
 * and PacketBuffer), byte-equal to what AudioHandler and LegacyVoiceLink
 * produce live. The host C++ suite (test_voice_codec) reads them and fails
 * unless the native codec reproduces the same bytes and parses them back.
 *
 * Deterministic: rewriting the same bytes on every run, so running in the
 * normal unit-test task never dirties git. Run once, check the files in
 * libraries/humla/src/test/fixtures/voice-codec/ in.
 */
public class VoiceCodecFixtureGeneratorTest extends TestCase {

    private File fixtureDir() {
        // Gradle runs module tests with the module dir as the working dir.
        File dir = new File("src/test/fixtures/voice-codec");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            fail("Cannot create fixture dir: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private void write(String name, byte[] data, int length) throws IOException {
        File out = new File(fixtureDir(), name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(data, 0, length);
        }
    }

    private static byte[] opusPattern(int length) {
        byte[] opus = new byte[length];
        for (int i = 0; i < length; i++) {
            opus[i] = (byte) (i & 0xFF);
        }
        return opus;
    }

    /** Mirrors AudioHandler.onAudioPacketEncoded legacy branch. */
    private static byte[] legacyVoiceBytes(int target, long frameNumber,
            byte[] opus, boolean isTerminator) {
        byte[] buffer = new byte[1024];
        int flags = (HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5) | (target & 0x1F);
        buffer[0] = (byte) (flags & 0xFF);
        PacketBuffer stream = new PacketBuffer(buffer, 1024);
        stream.skip(1);
        stream.writeLong(frameNumber);
        long header = ((long) opus.length) & 0x1FFFL;
        if (isTerminator) {
            header |= (1L << 13);
        }
        stream.writeLong(header);
        stream.append(opus, opus.length);
        int total = stream.size();
        byte[] out = new byte[total];
        System.arraycopy(buffer, 0, out, 0, total);
        return out;
    }

    /** Mirrors AudioHandler.onAudioPacketEncoded protobuf branch. */
    private static byte[] protobufVoiceBytes(int target, long frameNumber,
            byte[] opus, boolean isTerminator) {
        MumbleUDP.Audio.Builder builder = MumbleUDP.Audio.newBuilder();
        if (target != 0) {
            builder.setTarget(target);
        }
        builder.setFrameNumber(frameNumber);
        builder.setOpusData(ByteString.copyFrom(opus));
        if (isTerminator) {
            builder.setIsTerminator(true);
        }
        byte[] proto = builder.build().toByteArray();
        byte[] out = new byte[1 + proto.length];
        out[0] = 0x00;
        System.arraycopy(proto, 0, out, 1, proto.length);
        return out;
    }

    /** Mirrors LegacyVoiceLink.sendUdpPing legacy branch. */
    private static byte[] legacyPingBytes(long timestamp) {
        byte[] buffer = new byte[10];
        buffer[0] = (byte) ((HumlaUDPMessageType.UDPPing.ordinal() << 5) & 0xFF);
        PacketBuffer pb = new PacketBuffer(buffer, 10);
        pb.skip(1);
        pb.writeLong(timestamp);
        int total = pb.size();
        byte[] out = new byte[total];
        System.arraycopy(buffer, 0, out, 0, total);
        return out;
    }

    /** Mirrors LegacyVoiceLink.sendUdpPing protobuf branch. */
    private static byte[] protobufPingBytes(long timestamp) {
        MumbleUDP.Ping ping = MumbleUDP.Ping.newBuilder()
                .setTimestamp(timestamp).build();
        byte[] proto = ping.toByteArray();
        byte[] out = new byte[1 + proto.length];
        out[0] = 0x01;
        System.arraycopy(proto, 0, out, 1, proto.length);
        return out;
    }

    public void testGenerateFixtures() throws IOException {
        // Outbound framing (C++ frameVoice/framePing must reproduce these).
        byte[] legacyBasic = legacyVoiceBytes(0, 12345L, new byte[]{0x01, 0x02, 0x03, 0x04}, false);
        write("frame-legacy-voice-basic.bin", legacyBasic, legacyBasic.length);

        byte[] legacyWhisper = legacyVoiceBytes(5, 123456789L, opusPattern(100), true);
        write("frame-legacy-voice-whisper-terminator.bin", legacyWhisper, legacyWhisper.length);

        byte[] legacyEmpty = legacyVoiceBytes(0, 999L, new byte[0], true);
        write("frame-legacy-voice-empty-terminator.bin", legacyEmpty, legacyEmpty.length);

        byte[] protoBasic = protobufVoiceBytes(0, 12345L, new byte[]{0x01, 0x02, 0x03, 0x04}, false);
        write("frame-protobuf-voice-basic.bin", protoBasic, protoBasic.length);

        byte[] protoWhisper = protobufVoiceBytes(7, 999L, opusPattern(100), true);
        write("frame-protobuf-voice-whisper-terminator.bin", protoWhisper, protoWhisper.length);

        byte[] protoEmpty = protobufVoiceBytes(0, 999L, new byte[0], true);
        write("frame-protobuf-voice-empty-terminator.bin", protoEmpty, protoEmpty.length);

        byte[] legacyPing = legacyPingBytes(987654321L);
        write("frame-legacy-ping-basic.bin", legacyPing, legacyPing.length);

        byte[] protoPing = protobufPingBytes(987654321L);
        write("frame-protobuf-ping-basic.bin", protoPing, protoPing.length);

        // Inbound parsing (C++ parseDatagram must decode these).
        MumbleUDP.Audio positional = MumbleUDP.Audio.newBuilder()
                .setSenderSession(42)
                .setContext(2)
                .setFrameNumber(100L)
                .setOpusData(ByteString.copyFrom(new byte[]{0x0A, 0x0B, 0x0C}))
                .addPositionalData(1.0f)
                .addPositionalData(2.0f)
                .addPositionalData(3.0f)
                .setVolumeAdjustment(0.5f)
                .build();
        byte[] positionalProto = positional.toByteArray();
        byte[] positionalPacket = new byte[1 + positionalProto.length];
        positionalPacket[0] = 0x00;
        System.arraycopy(positionalProto, 0, positionalPacket, 1, positionalProto.length);
        write("parse-protobuf-voice-positional.bin", positionalPacket, positionalPacket.length);

        long v2 = Constants.toVersionV2(1, 5, 1);
        MumbleUDP.Ping extended = MumbleUDP.Ping.newBuilder()
                .setTimestamp(987654321L)
                .setServerVersionV2(v2)
                .setMaxBandwidthPerUser(128000)
                .build();
        byte[] extendedProto = extended.toByteArray();
        byte[] extendedPacket = new byte[1 + extendedProto.length];
        extendedPacket[0] = 0x01;
        System.arraycopy(extendedProto, 0, extendedPacket, 1, extendedProto.length);
        write("parse-protobuf-ping-extended.bin", extendedPacket, extendedPacket.length);

        // Legacy bundled voice: session 42, seq 100, two frames, 12-byte trailer.
        byte[] bundled = new byte[1024];
        bundled[0] = (byte) ((HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5) & 0xFF);
        PacketBuffer bundle = new PacketBuffer(bundled, 1024);
        bundle.skip(1);
        bundle.writeLong(42L);
        bundle.writeLong(100L);
        byte[] opus1 = new byte[]{0x11, 0x12, 0x13, 0x14};
        bundle.writeLong(opus1.length);
        bundle.append(opus1, opus1.length);
        byte[] opus2 = new byte[]{0x21, 0x22, 0x23, 0x24};
        bundle.writeLong(((long) opus2.length) | (1L << 13));
        bundle.append(opus2, opus2.length);
        // 12-byte positional trailer (3 floats, exactly as AudioOutput checks).
        bundle.writeFloat(1.0f);
        bundle.writeFloat(2.0f);
        bundle.writeFloat(3.0f);
        int bundledLen = bundle.size();
        byte[] bundledOut = new byte[bundledLen];
        System.arraycopy(bundled, 0, bundledOut, 0, bundledLen);
        write("parse-legacy-voice-bundled-trailer.bin", bundledOut, bundledOut.length);

        // Legacy empty terminator inbound: session 42, seq 100, size 0 + term.
        byte[] emptyTerm = new byte[64];
        emptyTerm[0] = (byte) ((HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5) & 0xFF);
        PacketBuffer emptyBuf = new PacketBuffer(emptyTerm, 64);
        emptyBuf.skip(1);
        emptyBuf.writeLong(42L);
        emptyBuf.writeLong(100L);
        emptyBuf.writeLong(1L << 13);
        int emptyLen = emptyBuf.size();
        byte[] emptyOut = new byte[emptyLen];
        System.arraycopy(emptyTerm, 0, emptyOut, 0, emptyLen);
        write("parse-legacy-voice-empty-terminator.bin", emptyOut, emptyOut.length);
    }
}
