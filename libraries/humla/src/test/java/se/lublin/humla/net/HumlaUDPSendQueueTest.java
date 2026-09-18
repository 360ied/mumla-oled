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
import junit.framework.TestCase;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests verifying that the outgoing UDP datagram queue is dynamically bounded
 * based on audio packet duration to maintain ~200ms target latency headroom, enforcing a
 * deterministic drop-oldest (head drop) eviction policy across all supported packet sizes (ODD-03).
 */
public class HumlaUDPSendQueueTest extends TestCase {

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

    private CryptState mClientCrypt;
    private CryptState mServerCrypt;
    private HumlaUDP.UDPConnectionListener mDummyListener;
    private Handler mDummyHandler;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mClientCrypt = new CryptState();
        mClientCrypt.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);

        mServerCrypt = new CryptState();
        mServerCrypt.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        mDummyListener = new HumlaUDP.UDPConnectionListener() {
            @Override
            public void onUDPDataReceived(byte[] data) {}

            @Override
            public void onUDPConnectionError(Exception e) {}

            @Override
            public void resyncCryptState() {}
        };

        mDummyHandler = new Handler();
    }

    /**
     * Verifies that calculateQueueCapacity computes correct packet capacities targeting
     * ~200ms latency across all Mumla-supported framesPerPacket settings (10ms, 20ms, 40ms, 60ms).
     */
    public void testCalculateQueueCapacityForSupportedFramesPerPacket() {
        // 10ms packets (1 frame @ 10ms) -> ceil(200 / 10) = 20 packets (200ms)
        assertEquals(20, HumlaUDP.calculateQueueCapacity(1));

        // 20ms packets (2 frames @ 10ms, default) -> ceil(200 / 20) = 10 packets (200ms)
        assertEquals(10, HumlaUDP.calculateQueueCapacity(2));

        // 40ms packets (4 frames @ 10ms) -> ceil(200 / 40) = 5 packets (200ms)
        assertEquals(5, HumlaUDP.calculateQueueCapacity(4));

        // 60ms packets (6 frames @ 10ms) -> ceil(200 / 60) = 4 packets (240ms)
        assertEquals(4, HumlaUDP.calculateQueueCapacity(6));

        // Invalid / unknown values should fall back safely to default 20ms frames (10 packets)
        assertEquals(10, HumlaUDP.calculateQueueCapacity(0));
        assertEquals(10, HumlaUDP.calculateQueueCapacity(-1));
        assertEquals(10, HumlaUDP.calculateQueueCapacity(3));
        assertEquals(10, HumlaUDP.calculateQueueCapacity(100));
    }

    /**
     * Verifies that when more packets than DEFAULT_SEND_QUEUE_CAPACITY are sent while
     * the consumer is stalled, the send queue remains bounded to DEFAULT_SEND_QUEUE_CAPACITY.
     */
    public void testSendQueueBoundsToDefaultCapacity() throws Exception {
        HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        humlaUDP.setConnectedForTesting(true, loopback, 12345);

        assertEquals("Default frames per packet should be 2", 2, humlaUDP.getTargetFramesPerPacket());
        assertEquals("Default queue capacity should be 10", 10, humlaUDP.getSendQueueCapacity());

        // Send 25 packets; queue should cap at 10
        for (int i = 0; i < 25; i++) {
            byte[] payload = new byte[]{(byte) i};
            humlaUDP.sendMessage(payload, payload.length);
        }

        assertEquals("Send queue must be bounded to default capacity (10)",
                HumlaUDP.DEFAULT_SEND_QUEUE_CAPACITY, humlaUDP.getSendQueue().size());
        assertEquals("MAX_SEND_QUEUE_CAPACITY alias must match default capacity",
                HumlaUDP.DEFAULT_SEND_QUEUE_CAPACITY, HumlaUDP.MAX_SEND_QUEUE_CAPACITY);
    }

    /**
     * Verifies that the queue enforces drop-oldest (head drop) eviction: when saturated,
     * the oldest queued packets are evicted first, preserving the freshest packets.
     */
    public void testSendQueueDropOldestOrderPreserved() throws Exception {
        HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        humlaUDP.setConnectedForTesting(true, loopback, 12345);

        // Send 15 numbered packets: 1 through 15
        for (int i = 1; i <= 15; i++) {
            byte[] payload = new byte[]{(byte) i, (byte) (i * 2)};
            humlaUDP.sendMessage(payload, payload.length);
        }

        assertEquals("Send queue size must equal capacity",
                humlaUDP.getSendQueueCapacity(), humlaUDP.getSendQueue().size());

        // With capacity 10 and 15 packets sent, packets 1-5 must have been dropped.
        // Packets 6 through 15 must be present in exact order.
        for (int expectedSeq = 6; expectedSeq <= 15; expectedSeq++) {
            DatagramPacket packet = humlaUDP.getSendQueue().poll();
            assertNotNull("Packet must not be null", packet);

            byte[] decrypted = mServerCrypt.decrypt(packet.getData(), packet.getLength());
            assertNotNull("Packet decryption must succeed", decrypted);
            assertEquals("Expected packet sequence " + expectedSeq, (byte) expectedSeq, decrypted[0]);
            assertEquals("Expected payload second byte", (byte) (expectedSeq * 2), decrypted[1]);
        }

        assertTrue("Queue must be empty after polling all 10 packets", humlaUDP.getSendQueue().isEmpty());
    }

    /**
     * Verifies that queue capacity adapts accurately to each supported packet size in Mumla,
     * maintaining ~200ms latency ceiling and FIFO drop-oldest behavior for all settings.
     */
    public void testSendQueueCapacityAdaptsToAllSupportedFramesPerPacket() throws Exception {
        int[] supportedFpp = {1, 2, 4, 6};
        int[] expectedCapacities = {20, 10, 5, 4};
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        for (int idx = 0; idx < supportedFpp.length; idx++) {
            int fpp = supportedFpp[idx];
            int expectedCap = expectedCapacities[idx];

            HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler, fpp);
            humlaUDP.setConnectedForTesting(true, loopback, 12345);

            assertEquals("Target frames per packet should match configured value", fpp, humlaUDP.getTargetFramesPerPacket());
            assertEquals("Queue capacity should match calculated expectation for fpp=" + fpp,
                    expectedCap, humlaUDP.getSendQueueCapacity());

            // Send double the capacity of numbered packets
            int totalPackets = expectedCap * 2;
            for (int i = 1; i <= totalPackets; i++) {
                byte[] payload = new byte[]{(byte) i};
                humlaUDP.sendMessage(payload, payload.length);
            }

            assertEquals("Queue must be capped at expected capacity for fpp=" + fpp,
                    expectedCap, humlaUDP.getSendQueue().size());

            // Verify freshest packets are retained in order (first expectedCap packets dropped)
            int firstRetainedSeq = totalPackets - expectedCap + 1;
            for (int expectedSeq = firstRetainedSeq; expectedSeq <= totalPackets; expectedSeq++) {
                DatagramPacket packet = humlaUDP.getSendQueue().poll();
                assertNotNull(packet);
                byte[] decrypted = mServerCrypt.decrypt(packet.getData(), packet.getLength());
                assertNotNull(decrypted);
                assertEquals((byte) expectedSeq, decrypted[0]);
            }
        }
    }

    /**
     * Verifies dynamic preference reconfiguration: changing targetFramesPerPacket at runtime
     * adjusts capacity on the fly and immediately flushes excess stale packets if capacity decreased.
     */
    public void testSendQueueDynamicResizeFlushesExcessPackets() throws Exception {
        HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler, 1); // 10ms -> capacity 20
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        humlaUDP.setConnectedForTesting(true, loopback, 12345);

        assertEquals(20, humlaUDP.getSendQueueCapacity());

        // Fill queue to 20 packets: 1 through 20
        for (int i = 1; i <= 20; i++) {
            byte[] payload = new byte[]{(byte) i};
            humlaUDP.sendMessage(payload, payload.length);
        }
        assertEquals(20, humlaUDP.getSendQueue().size());

        // Dynamically change to 60ms packets (fpp=6, capacity 4) while connected
        humlaUDP.setTargetFramesPerPacket(6);

        assertEquals(4, humlaUDP.getSendQueueCapacity());
        assertEquals("Queue should immediately shrink to 4 packets", 4, humlaUDP.getSendQueue().size());

        // The remaining 4 packets must be the 4 newest (17, 18, 19, 20)
        for (int expectedSeq = 17; expectedSeq <= 20; expectedSeq++) {
            DatagramPacket packet = humlaUDP.getSendQueue().poll();
            assertNotNull(packet);
            byte[] decrypted = mServerCrypt.decrypt(packet.getData(), packet.getLength());
            assertNotNull(decrypted);
            assertEquals((byte) expectedSeq, decrypted[0]);
        }
    }

    /**
     * Verifies that explicit custom capacity queue bounds correctly and maintains drop-oldest policy.
     */
    public void testSendQueueExplicitCapacity() throws Exception {
        int customCapacity = 3;
        HumlaUDP humlaUDP = HumlaUDP.createWithExplicitCapacity(mClientCrypt, mDummyListener, mDummyHandler, customCapacity);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        humlaUDP.setConnectedForTesting(true, loopback, 12345);

        assertEquals(customCapacity, humlaUDP.getSendQueueCapacity());

        // Send 6 packets: 1 to 6
        for (int i = 1; i <= 6; i++) {
            byte[] payload = new byte[]{(byte) i};
            humlaUDP.sendMessage(payload, payload.length);
        }

        assertEquals("Queue size must equal explicit capacity", customCapacity, humlaUDP.getSendQueue().size());

        // Packets 1-3 dropped; 4-6 remain
        for (int expectedSeq = 4; expectedSeq <= 6; expectedSeq++) {
            DatagramPacket packet = humlaUDP.getSendQueue().poll();
            assertNotNull(packet);
            byte[] decrypted = mServerCrypt.decrypt(packet.getData(), packet.getLength());
            assertNotNull(decrypted);
            assertEquals((byte) expectedSeq, decrypted[0]);
        }
    }

    /**
     * Verifies that explicit capacity <= 0 throws IllegalArgumentException.
     */
    public void testInvalidCapacityThrowsException() {
        try {
            HumlaUDP.createWithExplicitCapacity(mClientCrypt, mDummyListener, mDummyHandler, 0);
            fail("Capacity 0 should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            HumlaUDP.createWithExplicitCapacity(mClientCrypt, mDummyListener, mDummyHandler, -5);
            fail("Negative capacity should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}
    }

    /**
     * Verifies that sendMessage drops packets when disconnected, when crypt is invalid,
     * or when resolved host is null.
     */
    public void testSendMessageGuardsWhenDisconnectedOrCryptInvalid() throws Exception {
        HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        // 1. Not connected
        humlaUDP.setConnectedForTesting(false, loopback, 12345);
        byte[] payload = new byte[]{0x01, 0x02};
        humlaUDP.sendMessage(payload, payload.length);
        assertTrue("Queue must be empty when disconnected", humlaUDP.getSendQueue().isEmpty());

        // 2. Connected but invalid crypt state
        CryptState invalidCrypt = new CryptState(); // uninitialized
        HumlaUDP invalidCryptUDP = new HumlaUDP(invalidCrypt, mDummyListener, mDummyHandler);
        invalidCryptUDP.setConnectedForTesting(true, loopback, 12345);
        invalidCryptUDP.sendMessage(payload, payload.length);
        assertTrue("Queue must be empty when CryptState is invalid", invalidCryptUDP.getSendQueue().isEmpty());

        // 3. Connected with valid crypt, but null resolved host
        humlaUDP.setConnectedForTesting(true, null, 12345);
        humlaUDP.sendMessage(payload, payload.length);
        assertTrue("Queue must be empty when resolved host is null", humlaUDP.getSendQueue().isEmpty());
    }

    /**
     * Verifies that disconnect clears the send queue.
     */
    public void testDisconnectClearsSendQueue() throws Exception {
        HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        humlaUDP.setConnectedForTesting(true, loopback, 12345);

        for (int i = 0; i < 5; i++) {
            byte[] payload = new byte[]{(byte) i};
            humlaUDP.sendMessage(payload, payload.length);
        }
        assertEquals(5, humlaUDP.getSendQueue().size());

        humlaUDP.disconnect();
        assertFalse(humlaUDP.isRunning());
        assertTrue("Send queue must be empty after disconnect", humlaUDP.getSendQueue().isEmpty());
    }

    /**
     * Verifies thread safety and capacity bounding when multiple concurrent threads
     * saturate the send queue simultaneously.
     */
    public void testConcurrentSendersNeverExceedCapacity() throws Exception {
        final HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        humlaUDP.setConnectedForTesting(true, loopback, 12345);

        int threadCount = 4;
        final int packetsPerThread = 50;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger maxObservedQueueSize = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        startLatch.await();
                        for (int i = 0; i < packetsPerThread; i++) {
                            byte[] payload = new byte[]{(byte) threadId, (byte) i};
                            humlaUDP.sendMessage(payload, payload.length);

                            int currentSize = humlaUDP.getSendQueue().size();
                            int prevMax;
                            do {
                                prevMax = maxObservedQueueSize.get();
                                if (currentSize <= prevMax) break;
                            } while (!maxObservedQueueSize.compareAndSet(prevMax, currentSize));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                }
            });
        }

        startLatch.countDown();
        assertTrue("Concurrent senders must finish within timeout", doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue("Observed queue size (" + maxObservedQueueSize.get() + ") must never exceed capacity",
                maxObservedQueueSize.get() <= humlaUDP.getSendQueueCapacity());
        assertEquals("Final queue size must be capacity",
                humlaUDP.getSendQueueCapacity(), humlaUDP.getSendQueue().size());
    }

    /**
     * Verifies end-to-end integration: when connected over a real loopback UDP socket,
     * packets are consumed by OutgoingConsumer and received by the remote endpoint.
     */
    public void testEndToEndSendAndConsume() throws Exception {
        DatagramSocket serverSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        int serverPort = serverSocket.getLocalPort();
        serverSocket.setSoTimeout(1000);

        HumlaUDP humlaUDP = new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler);
        humlaUDP.connect("127.0.0.1", serverPort);

        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (!humlaUDP.isRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("HumlaUDP must be running", humlaUDP.isRunning());

            byte[] sentPayload = new byte[]{0x12, 0x34, 0x56, 0x78};
            humlaUDP.sendMessage(sentPayload, sentPayload.length);

            byte[] recvBuf = new byte[1024];
            DatagramPacket receivedPacket = new DatagramPacket(recvBuf, recvBuf.length);
            serverSocket.receive(receivedPacket);

            byte[] decrypted = mServerCrypt.decrypt(receivedPacket.getData(), receivedPacket.getLength());
            assertNotNull("Server must successfully decrypt client datagram", decrypted);
            assertTrue("Decrypted payload must match sent payload", Arrays.equals(sentPayload, decrypted));
        } finally {
            humlaUDP.disconnect();
            serverSocket.close();
        }
    }
}
