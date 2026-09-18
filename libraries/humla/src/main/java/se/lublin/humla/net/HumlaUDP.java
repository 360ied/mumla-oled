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
    private int mPort;
    private volatile InetAddress mResolvedHost;
    private volatile boolean mConnected;

    /** Main datagram thread hosting this runnable. */
    private final Thread mDatagramThread;

    /** Handler to invoke listener callback invocations on. */
    private final Handler mCallbackHandler;

    /** Maximum queued outgoing datagrams (~200ms of audio buffer at 20ms frames). */
    public static final int MAX_SEND_QUEUE_CAPACITY = 10;

    /** Bounded queue of outgoing packets to be sent. */
    private final BlockingQueue<DatagramPacket> mSendQueue;

    /**
     * Sets up a new UDP connection context.
     * @param cryptState Cryptographic state provider.
     * @param listener Callback target. Connection state callbacks will be posted on the callback handler given;
     *                 data callbacks are delivered directly on the UDP receive thread.
     * @param callbackHandler Handler to post listener invocations on.
     */
    public HumlaUDP(@NotNull CryptState cryptState, @NotNull UDPConnectionListener listener,
                     @NotNull Handler callbackHandler) {
        this(cryptState, listener, callbackHandler, MAX_SEND_QUEUE_CAPACITY);
    }

    /**
     * Sets up a new UDP connection context with a custom send queue capacity.
     * @param cryptState Cryptographic state provider.
     * @param listener Callback target.
     * @param callbackHandler Handler to post listener invocations on.
     * @param sendQueueCapacity Maximum number of packets queued before evicting oldest.
     */
    HumlaUDP(@NotNull CryptState cryptState, @NotNull UDPConnectionListener listener,
             @NotNull Handler callbackHandler, int sendQueueCapacity) {
        if (sendQueueCapacity <= 0) {
            throw new IllegalArgumentException("sendQueueCapacity must be > 0");
        }
        mCryptState = cryptState;
        mListener = listener;
        mCallbackHandler = callbackHandler;
        mDatagramThread = new Thread(this);
        mSendQueue = new LinkedBlockingQueue<>(sendQueueCapacity);
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
            mSendQueue.clear();

            if (mUDPSocket != null) {
                mUDPSocket.close();
            }
        }
    }

    public void sendMessage(@NotNull final byte[] data, final int length) {
        if (!mCryptState.isValid() || !mConnected || mResolvedHost == null) {
            return;
        }

        try {
            byte[] encryptedData = mCryptState.encrypt(data, length);
            final DatagramPacket packet = new DatagramPacket(encryptedData, encryptedData.length);
            packet.setAddress(mResolvedHost);
            packet.setPort(mPort);

            // Non-blocking offer; if full, evict the oldest packet (head drop / drop-oldest) to prioritize fresh audio
            while (!mSendQueue.offer(packet)) {
                mSendQueue.poll();
            }
        } catch (BadPaddingException | IllegalBlockSizeException | ShortBufferException e) {
            Log.w(TAG, "Failed to encrypt outgoing UDP packet", e);
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
