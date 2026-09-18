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

package se.lublin.humla.net;

import android.os.Handler;
import com.google.protobuf.ByteString;
import junit.framework.TestCase;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import se.lublin.humla.Constants;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protobuf.MumbleUDP;
import se.lublin.humla.protocol.HumlaUDPMessageListener;

/**
 * Unit tests verifying that incoming UDP datagrams are processed and dispatched
 * directly on the background UDP receiver thread rather than posted to the main
 * UI looper (ODD-02).
 */
public class HumlaUDPReceiveThreadTest extends TestCase {

    private static final byte[] TEST_KEY = new byte[]{
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
    };

    private static final byte[] CLIENT_IV = new byte[]{
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20
    };

    private static final byte[] SERVER_IV = new byte[]{
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30
    };

    /**
     * Verifies that when a valid encrypted UDP voice datagram arrives,
     * onUDPDataReceived is invoked directly on the UDP receiver background thread
     * and NOT posted to the callback handler (ODD-02).
     */
    public void testOnUDPDataReceivedExecutedOnBackgroundThreadNotPostedToHandler() throws Exception {
        final CountDownLatch receiveLatch = new CountDownLatch(1);
        final AtomicReference<Thread> callbackThread = new AtomicReference<>();
        final AtomicReference<byte[]> receivedBuffer = new AtomicReference<>();
        final AtomicInteger handlerPostCount = new AtomicInteger(0);

        final Handler mockHandler = new Handler() {
            @Override
            public boolean sendMessageAtTime(android.os.Message msg, long uptimeMillis) {
                handlerPostCount.incrementAndGet();
                return true;
            }
        };

        // Client CryptState: encrypts with CLIENT_IV, decrypts with SERVER_IV
        CryptState clientCrypt = new CryptState();
        clientCrypt.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);

        // Server CryptState: encrypts with SERVER_IV, decrypts with CLIENT_IV
        CryptState serverCrypt = new CryptState();
        serverCrypt.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        HumlaUDP.UDPConnectionListener listener = new HumlaUDP.UDPConnectionListener() {
            @Override
            public void onUDPDataReceived(byte[] data) {
                callbackThread.set(Thread.currentThread());
                receivedBuffer.set(data);
                receiveLatch.countDown();
            }

            @Override
            public void onUDPConnectionError(Exception e) {}

            @Override
            public void resyncCryptState() {}
        };

        // Open mock loopback UDP server socket
        DatagramSocket serverSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        int serverPort = serverSocket.getLocalPort();

        HumlaUDP humlaUDP = new HumlaUDP(clientCrypt, listener, mockHandler);
        humlaUDP.connect("127.0.0.1", serverPort);

        try {
            DatagramPacket clientDatagram = discoverClientAddress(humlaUDP, serverSocket);

            // Now simulate incoming audio data from server to client
            byte[] plainAudioPayload = new byte[]{0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60};
            byte[] encryptedPacket = serverCrypt.encrypt(plainAudioPayload, plainAudioPayload.length);

            DatagramPacket sendToClient = new DatagramPacket(
                    encryptedPacket,
                    encryptedPacket.length,
                    clientDatagram.getAddress(),
                    clientDatagram.getPort()
            );
            serverSocket.send(sendToClient);

            // Await receive on client
            assertTrue("Should receive UDP packet within timeout", receiveLatch.await(3, TimeUnit.SECONDS));

            // Verify payload integrity
            assertNotNull("Received buffer must not be null", receivedBuffer.get());
            assertTrue("Decrypted payload must match sent audio",
                    Arrays.equals(plainAudioPayload, receivedBuffer.get()));

            // Verify threading: callback must execute on background datagram thread
            Thread thread = callbackThread.get();
            assertNotNull("Callback thread must not be null", thread);
            assertFalse("Callback thread must NOT be the test main thread",
                    thread == Thread.currentThread());

            // Verify ODD-02 fix: packet reception must NOT post runnables to mCallbackHandler
            assertEquals("Incoming UDP voice packets must not post to UI callback handler",
                    0, handlerPostCount.get());
        } finally {
            humlaUDP.disconnect();
            serverSocket.close();
        }
    }

    /**
     * Verifies that HumlaConnection.onUDPDataReceived processes Protobuf Audio and Ping
     * messages directly on the calling background thread without posting to Looper.getMainLooper(),
     * and updates volatile connection fields atomically (ODD-02).
     */
    public void testHumlaConnectionUDPDispatchOnCallingThreadAndVolatileFields() throws Exception {
        final HumlaConnection connection = new HumlaConnection(null);

        // Set server version to Mumble 1.5.0 so Protobuf UDP is supported
        connection.onTCPMessageReceived(
                HumlaTCPMessageType.Version,
                0,
                Mumble.Version.newBuilder()
                        .setVersionV2(Constants.toVersionV2(1, 5, 0))
                        .build()
                        .toByteArray()
        );
        assertTrue("Protobuf UDP should be supported for 1.5.0", connection.isProtobufUdpSupported());

        final AtomicReference<Thread> audioDispatchThread = new AtomicReference<>();
        final AtomicReference<MumbleUDP.Audio> receivedAudio = new AtomicReference<>();
        final AtomicReference<Thread> pingDispatchThread = new AtomicReference<>();
        final AtomicReference<MumbleUDP.Ping> receivedPing = new AtomicReference<>();

        connection.addUDPMessageHandlers(new HumlaUDPMessageListener.Stub() {
            @Override
            public void messageProtobufAudio(MumbleUDP.Audio msg) {
                audioDispatchThread.set(Thread.currentThread());
                receivedAudio.set(msg);
            }

            @Override
            public void messageProtobufPing(MumbleUDP.Ping msg) {
                pingDispatchThread.set(Thread.currentThread());
                receivedPing.set(msg);
            }
        });

        // Construct Protobuf Audio packet: byte 0 is 0 (Audio), followed by serialized MumbleUDP.Audio
        MumbleUDP.Audio audioMsg = MumbleUDP.Audio.newBuilder()
                .setSenderSession(42)
                .setFrameNumber(100)
                .setOpusData(ByteString.copyFrom(new byte[]{0x0A, 0x0B, 0x0C}))
                .build();
        byte[] audioBytes = audioMsg.toByteArray();
        final byte[] audioPacket = new byte[audioBytes.length + 1];
        audioPacket[0] = 0; // msgType == 0
        System.arraycopy(audioBytes, 0, audioPacket, 1, audioBytes.length);

        // Construct Protobuf Ping packet: byte 0 is 1 (Ping), followed by serialized MumbleUDP.Ping
        long testTimestamp = 987654321L;
        long testVersionV2 = Constants.toVersionV2(1, 5, 1);
        int testBandwidth = 128000;
        MumbleUDP.Ping pingMsg = MumbleUDP.Ping.newBuilder()
                .setTimestamp(testTimestamp)
                .setServerVersionV2(testVersionV2)
                .setMaxBandwidthPerUser(testBandwidth)
                .build();
        byte[] pingBytes = pingMsg.toByteArray();
        final byte[] pingPacket = new byte[pingBytes.length + 1];
        pingPacket[0] = 1; // msgType == 1
        System.arraycopy(pingBytes, 0, pingPacket, 1, pingBytes.length);

        // Dispatch from a background worker thread (simulating the UDP receive thread)
        Thread workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connection.onUDPDataReceived(audioPacket);
                connection.onUDPDataReceived(pingPacket);
            }
        }, "UDP-Worker-Thread");
        workerThread.start();
        workerThread.join(3000);

        // Verify audio was dispatched synchronously on the background worker thread
        assertSame("Audio should be dispatched on the background worker thread",
                workerThread, audioDispatchThread.get());
        assertNotNull("Received audio must not be null", receivedAudio.get());
        assertEquals(42, receivedAudio.get().getSenderSession());
        assertEquals(100, receivedAudio.get().getFrameNumber());

        // Verify ping was dispatched synchronously on the background worker thread
        assertSame("Ping should be dispatched on the background worker thread",
                workerThread, pingDispatchThread.get());
        assertNotNull("Received ping must not be null", receivedPing.get());
        assertEquals(testTimestamp, receivedPing.get().getTimestamp());
        assertEquals(testVersionV2, receivedPing.get().getServerVersionV2());
        assertEquals(testBandwidth, receivedPing.get().getMaxBandwidthPerUser());
    }

    private DatagramPacket discoverClientAddress(HumlaUDP humlaUDP, DatagramSocket serverSocket) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!humlaUDP.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(30);

        byte[] dummySend = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04};
        byte[] serverRecvBuf = new byte[1024];
        DatagramPacket clientDatagram = new DatagramPacket(serverRecvBuf, serverRecvBuf.length);
        serverSocket.setSoTimeout(150);

        for (int i = 0; i < 20; i++) {
            humlaUDP.sendMessage(dummySend, dummySend.length);
            try {
                serverSocket.receive(clientDatagram);
                return clientDatagram;
            } catch (java.net.SocketTimeoutException ignored) {
                Thread.sleep(20);
            }
        }
        throw new java.net.SocketTimeoutException("Failed to discover client address from server");
    }
}
