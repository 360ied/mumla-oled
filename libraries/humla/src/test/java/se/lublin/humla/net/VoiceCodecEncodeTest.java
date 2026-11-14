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

import android.content.Context;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import junit.framework.TestCase;
import org.mockito.Mockito;
import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.audio.inputmode.ToggleInputMode;
import se.lublin.humla.model.IUser;
import se.lublin.humla.protocol.AudioHandler;
import se.lublin.humla.protobuf.MumbleUDP;
import se.lublin.humla.util.HumlaLogger;

/**
 * Live-path check for step 5a.2: the encode framing entry point
 * (AudioHandler.onAudioPacketEncoded, device-free construction) produces
 * bytes byte-equal to the Java-generated classes and PacketBuffer, failing
 * when the native codec is absent or wrong.
 *
 * Inbound framing is pinned by the codec goldens (test_voice_codec) and by
 * 5a.3's voice scenarios; a device-free parse entry point here would be
 * deleted one sub-step later, so only encode is driven live.
 */
public class VoiceCodecEncodeTest extends TestCase {

    private static class CaptureEncode implements AudioHandler.AudioEncodeListener {
        final AtomicReference<byte[]> last = new AtomicReference<>();
        int calls = 0;

        @Override
        public void onAudioEncoded(byte[] data, int length) {
            calls++;
            last.set(Arrays.copyOf(data, length));
        }

        @Override
        public void onTalkingStateChanged(boolean talking) {}
    }

    private static class DummyOutput implements AudioOutput.AudioOutputListener {
        @Override
        public void onUserTalkStateUpdated(IUser user) {}

        @Override
        public IUser getUser(int session) {
            return null;
        }
    }

    private static class DummyLogger implements HumlaLogger {
        @Override public void logInfo(String message) {}
        @Override public void logWarning(String message) {}
        @Override public void logError(String message) {}
    }

    private AudioHandler newHandler(byte targetId, CaptureEncode encode) throws Exception {
        Context context = Mockito.mock(Context.class);
        return new AudioHandler(
                context,
                new DummyLogger(),
                0, 0, 48000,
                40000, 2,
                new ToggleInputMode(),
                targetId,
                1.0f,
                false,
                false,
                true,
                encode,
                new DummyOutput());
    }

    public void testLegacyEncodeMatchesPacketBuffer() throws Exception {
        byte[] opus = new byte[]{0x01, 0x02, 0x03, 0x04};
        CaptureEncode encode = new CaptureEncode();
        AudioHandler handler = newHandler((byte) 3, encode);
        handler.setProtobufUdp(false);
        handler.onAudioPacketEncoded(opus, opus.length, 1, false, 12345L);
        assertEquals("encode must emit exactly once", 1, encode.calls);
        assertNotNull("encode must produce bytes (native codec absent?)", encode.last.get());

        // Expected via today's Kotlin framing (PacketBuffer, same as the fixture generator).
        byte[] buffer = new byte[1024];
        buffer[0] = (byte) (((HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5) | (3 & 0x1F)) & 0xFF);
        PacketBuffer stream = new PacketBuffer(buffer, 1024);
        stream.skip(1);
        stream.writeLong(12345L);
        stream.writeLong(opus.length);
        stream.append(opus, opus.length);
        byte[] expected = Arrays.copyOf(buffer, stream.size());
        assertTrue("native legacy framing must equal PacketBuffer framing",
                Arrays.equals(expected, encode.last.get()));
    }

    public void testProtobufEncodeMatchesGeneratedClasses() throws Exception {
        byte[] opus = new byte[100];
        for (int i = 0; i < opus.length; i++) opus[i] = (byte) i;
        CaptureEncode encode = new CaptureEncode();
        AudioHandler handler = newHandler((byte) 7, encode);
        handler.setProtobufUdp(true);
        handler.onAudioPacketEncoded(opus, opus.length, 1, true, 999L);
        assertEquals("encode must emit exactly once", 1, encode.calls);
        assertNotNull("encode must produce bytes (native codec absent?)", encode.last.get());

        MumbleUDP.Audio expectedMsg = MumbleUDP.Audio.newBuilder()
                .setTarget(7)
                .setFrameNumber(999L)
                .setOpusData(ByteString.copyFrom(opus))
                .setIsTerminator(true)
                .build();
        byte[] proto = expectedMsg.toByteArray();
        byte[] expected = new byte[1 + proto.length];
        expected[0] = 0x00;
        System.arraycopy(proto, 0, expected, 1, proto.length);
        assertTrue("native protobuf framing must equal the generated classes",
                Arrays.equals(expected, encode.last.get()));
    }
}
