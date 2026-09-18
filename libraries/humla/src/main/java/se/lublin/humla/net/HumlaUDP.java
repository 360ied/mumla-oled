/*
 * Copyright (C) 2014 Andrew Comminos
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
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import se.lublin.humla.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.ShortBufferException;

/**
 * Class to maintain and receive packets from the UDP connection to a Mumble server.
 * Public interface is not thread safe.
 */
public class HumlaUDP implements Runnable {
    private static final String TAG = HumlaUDP.class.getName();

    private static final int BUFFER_SIZE = 2048;
    private final CryptState mCryptState;

    private DatagramSocket mUDPSocket;
    private final UDPConnectionListener mListener;
    private String mHost;
    private volatile int mPort;
    private volatile InetAddress mResolvedHost;
    private volatile boolean mConnected;

    /** Main datagram thread hosting this runnable. */
    private final Thread mDatagramThread;

    /** Handler to invoke listener callback invocations on. */
    private final Handler mCallbackHandler;

    /** Target audio buffer duration in milliseconds (~200ms for real-time conversational interactivity). */
    public static final int TARGET_BUFFER_DURATION_MS = 200;

    /** Duration in milliseconds of an individual Opus audio frame in Mumble. */
    public static final int FRAME_DURATION_MS = Constants.FRAME_DURATION_MS;

    /** Default frames per packet (2 frames @ 10ms = 20ms). */
    public static final int DEFAULT_FRAMES_PER_PACKET = Constants.DEFAULT_FRAMES_PER_PACKET;

    /** Default send queue capacity corresponding to standard 20ms frames (~200ms buffer). */
    public static final int DEFAULT_SEND_QUEUE_CAPACITY = calculateQueueCapacity(DEFAULT_FRAMES_PER_PACKET);

    /** Backward-compatibility alias for tests and external callers. */
    public static final int MAX_SEND_QUEUE_CAPACITY = DEFAULT_SEND_QUEUE_CAPACITY;

    /** Bounded queue of outgoing packets to be sent. */
    private final BlockingQueue<DatagramPacket> mSendQueue;

    /** Lock guarding compound offer/poll queue eviction and dynamic capacity adjustments. */
    private final Object mSendLock = new Object();

    /** Audio frames per packet (1=10ms, 2=20ms, 4=40ms, 6=60ms). */
    private volatile int mTargetFramesPerPacket;

    /** Current send queue capacity in packets, calculated to bound latency to ~200ms. */
    private volatile int mSendQueueCapacity;

    /**
     * Calculates the send queue capacity in packets to maintain ~200ms of real-time audio
     * buffer headroom for the given frames-per-packet setting.
     *
     * @param framesPerPacket Number of 10ms frames per audio packet (1, 2, 4, 6).
     * @return Queue capacity bounded to maintain ~200ms target latency.
     */
    public static int calculateQueueCapacity(int framesPerPacket) {
        int fpp = sanitizeFramesPerPacket(framesPerPacket);
        int packetDurationMs = fpp * FRAME_DURATION_MS;
        return Math.max(2, (int) Math.ceil((double) TARGET_BUFFER_DURATION_MS / packetDurationMs));
    }

    /**
     * Sanitizes frames-per-packet to valid Opus configurations (1, 2, 4, 6).
     */
    public static int sanitizeFramesPerPacket(int fpp) {
        if (fpp == 1 || fpp == 2 || fpp == 4 || fpp == 6) {
            return fpp;
        }
        return DEFAULT_FRAMES_PER_PACKET;
    }

    /**
     * Sets up a new UDP connection context with the default 20ms frames per packet.
     * @param cryptState Cryptographic state provider.
     * @param listener Callback target. Connection state callbacks will be posted on the callback handler given;
     *                 data callbacks are delivered directly on the UDP receive thread.
     * @param callbackHandler Handler to post listener invocations on.
     */
    public HumlaUDP(@NotNull CryptState cryptState, @NotNull UDPConnectionListener listener,
                     @NotNull Handler callbackHandler) {
        this(cryptState, listener, callbackHandler, DEFAULT_FRAMES_PER_PACKET);
    }

    /**
     * Sets up a new UDP connection context with a specified frames-per-packet setting.
     * The send queue capacity is dynamically calculated to target ~200ms of real-time audio buffer.
     *
     * @param cryptState Cryptographic state provider.
     * @param listener Callback target.
     * @param callbackHandler Handler to post listener invocations on.
     * @param targetFramesPerPacket Audio frames per packet (1=10ms, 2=20ms, 4=40ms, 6=60ms).
     */
    public HumlaUDP(@NotNull CryptState cryptState, @NotNull UDPConnectionListener listener,
                    @NotNull Handler callbackHandler, int targetFramesPerPacket) {
        mCryptState = cryptState;
        mListener = listener;
        mCallbackHandler = callbackHandler;
        mDatagramThread = new Thread(this);
        mSendQueue = new LinkedBlockingQueue<>();
        setTargetFramesPerPacket(targetFramesPerPacket);
    }

    /**
     * Factory method to create HumlaUDP with an explicit queue capacity (primarily for testing).
     */
    static HumlaUDP createWithExplicitCapacity(@NotNull CryptState cryptState,
                                               @NotNull UDPConnectionListener listener,
                                               @NotNull Handler callbackHandler,
                                               int explicitCapacity) {
        if (explicitCapacity <= 0) {
            throw new IllegalArgumentException("sendQueueCapacity must be > 0");
        }
        HumlaUDP udp = new HumlaUDP(cryptState, listener, callbackHandler);
        udp.setSendQueueCapacity(explicitCapacity);
        return udp;
    }

    public void connect(@NotNull String host, @NotNull int port) {
        mHost = host;
        mPort = port;
        mDatagramThread.start();
    }

    public boolean isRunning() {
        return mConnected;
    }

    @Override
    public void run() {
        Thread outgoingConsumerThread = null;
        mConnected = true;
        try {
            mResolvedHost = InetAddress.getByName(mHost);
            mUDPSocket = new DatagramSocket();

            mUDPSocket.connect(mResolvedHost, mPort);
            Log.d(TAG, "Created socket");

            // Start outgoing consumer once the UDP socket is open, as a child thread.
            final OutgoingConsumer outgoingConsumer = new OutgoingConsumer(mUDPSocket, mSendQueue);
            outgoingConsumerThread = new Thread(outgoingConsumer);
            outgoingConsumerThread.start();

            final DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);
            while (mConnected) {
                mUDPSocket.receive(packet);
                final byte[] data = packet.getData();
                final int length = packet.getLength();

                if (!mCryptState.isValid()) {
                    Log.d(TAG, "CryptState invalid, discarding packet");
                    continue;
                }
                if (length < 5) {
                    Log.d(TAG, "Packet too short, discarding");
                    continue;
                }

                try {
                    final byte[] buffer = mCryptState.decrypt(data, length);

                    if (mListener != null) {
                        if (buffer != null) {
                            // Direct callback on UDP receiver thread per UDPConnectionListener contract
                            mListener.onUDPDataReceived(buffer);
                        } else if (mCryptState.getLastGoodElapsed() > 5000000 &&
                                mCryptState.getLastRequestElapsed() > 5000000) {
                            mCryptState.resetLastRequestTime();
                            mCallbackHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mListener.resyncCryptState();
                                }
                            });
                            Log.d(TAG, "Packet failed to decrypt, discarding and requesting crypt state resync");
                        } else {
                            Log.d(TAG, "Packet failed to decrypt, discarding");
                        }
                    }
                } catch (BadPaddingException | IllegalBlockSizeException | ShortBufferException e) {
                    Log.d(TAG, "Discarding packet", e);
                }
            }
        } catch (final IOException e) {
            // If mConnected is false, then this is a user-triggered disconnection. Report no error.
            if (mConnected) {
                Log.d(TAG, "UDP socket closed unexpectedly");
                mCallbackHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onUDPConnectionError(e);
                    }
                });
            } else {
                Log.d(TAG, "UDP socket closed in response to user disconnect");
            }
        } finally {
            mConnected = false;
            mResolvedHost = null;

            // We want to interrupt the outgoing queue consumer thread to avoid sends after socket
            // cleanup. Blocking shouldn't be necessary.
            if (outgoingConsumerThread != null) {
                outgoingConsumerThread.interrupt();
            }

            // Clear the outgoing queue, in case the caller decides to reconnect with the same socket.
            synchronized (mSendLock) {
                mSendQueue.clear();
            }

            if (mUDPSocket != null) {
                mUDPSocket.close();
            }
        }
    }

    public void sendMessage(@NotNull final byte[] data, final int length) {
        final InetAddress resolvedHost = mResolvedHost;
        if (!mCryptState.isValid() || !mConnected || resolvedHost == null) {
            return;
        }

        try {
            byte[] encryptedData = mCryptState.encrypt(data, length);
            final DatagramPacket packet = new DatagramPacket(encryptedData, encryptedData.length);
            packet.setAddress(resolvedHost);
            packet.setPort(mPort);

            // Synchronized block guarantees atomic head-drop eviction and strict capacity bounding
            synchronized (mSendLock) {
                while (mSendQueue.size() >= mSendQueueCapacity) {
                    mSendQueue.poll();
                }
                mSendQueue.offer(packet);
            }
        } catch (BadPaddingException | IllegalBlockSizeException | ShortBufferException e) {
            Log.w(TAG, "Failed to encrypt outgoing UDP packet", e);
        }
    }

    /**
     * Dynamically updates the frames per packet, recalculating the send queue capacity
     * to preserve ~200ms target latency, and flushing excess stale packets if capacity decreased.
     *
     * @param framesPerPacket Audio frames per packet (1=10ms, 2=20ms, 4=40ms, 6=60ms).
     */
    public void setTargetFramesPerPacket(int framesPerPacket) {
        synchronized (mSendLock) {
            mTargetFramesPerPacket = sanitizeFramesPerPacket(framesPerPacket);
            mSendQueueCapacity = calculateQueueCapacity(mTargetFramesPerPacket);
            while (mSendQueue.size() > mSendQueueCapacity) {
                mSendQueue.poll();
            }
        }
    }

    public int getTargetFramesPerPacket() {
        return mTargetFramesPerPacket;
    }

    public int getSendQueueCapacity() {
        return mSendQueueCapacity;
    }

    /**
     * Explicitly sets send queue capacity (package-private for testing).
     */
    void setSendQueueCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("sendQueueCapacity must be > 0");
        }
        synchronized (mSendLock) {
            mSendQueueCapacity = capacity;
            while (mSendQueue.size() > mSendQueueCapacity) {
                mSendQueue.poll();
            }
        }
    }

    /**
     * Visible for testing: returns the outgoing packet queue.
     */
    BlockingQueue<DatagramPacket> getSendQueue() {
        return mSendQueue;
    }

    /**
     * Visible for testing: configures endpoint and connected state without starting background threads.
     */
    void setConnectedForTesting(boolean connected, InetAddress host, int port) {
        mConnected = connected;
        mResolvedHost = host;
        mPort = port;
    }

    /**
     * Lazy, non-blocking idempotent disconnect.
     */
    public void disconnect() {
        mConnected = false;
        synchronized (mSendLock) {
            mSendQueue.clear();
        }
        // Closing a socket will trigger an IOException on the consumer thread.
        if (mUDPSocket != null) {
            mUDPSocket.close();
        }
    }

    /**
     * Note that all connection state related calls are made on the main thread.
     * onUDPDataReceived is always called on the UDP receive thread.
     */
    public interface UDPConnectionListener {
        void onUDPDataReceived(byte[] data);
        void onUDPConnectionError(Exception e);
        void resyncCryptState();
    }

    /**
     * Runnable that reads from a shared blocking queue, dispatching datagrams when available.
     */
    private static class OutgoingConsumer implements Runnable {
        private final DatagramSocket mSocket;
        private final BlockingQueue<DatagramPacket> mQueue;

        public OutgoingConsumer(@NotNull DatagramSocket socket,
                                @NotNull BlockingQueue<DatagramPacket> queue) {
            mSocket = socket;
            mQueue = queue;
        }

        @Override
        public void run() {
            Log.d(TAG, "Datagram outbox consumer active");
            boolean interrupted = false;
            while (!interrupted) {
                try {
                    DatagramPacket packet = mQueue.take();
                    mSocket.send(packet);
                } catch (IOException e) {
                    if (mSocket.isClosed()) {
                        break;
                    }
                    Log.w(TAG, "Failed to send outgoing datagram", e);
                } catch (InterruptedException e) {
                    // Our datagram thread interrupted us. We should stop reading.
                    Thread.currentThread().interrupt();
                    interrupted = true;
                }
            }
            Log.d(TAG, "Datagram outbox consumer shutdown");
        }
    }
}
