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

package se.lublin.humla.net

import android.os.Handler
import android.util.Log
import se.lublin.humla.Constants
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.ShortBufferException

/**
 * Class to maintain and receive packets from the UDP connection to a Mumble server.
 * Public interface is not thread safe.
 */
class HumlaUDP : Runnable {
    companion object {
        private val TAG = HumlaUDP::class.java.name

        private const val BUFFER_SIZE = 2048

        /** Target audio buffer duration in milliseconds (~200ms for real-time conversational interactivity). */
        const val TARGET_BUFFER_DURATION_MS = 200

        /** Duration in milliseconds of an individual Opus audio frame in Mumble. */
        const val FRAME_DURATION_MS = Constants.FRAME_DURATION_MS

        /** Default frames per packet (2 frames @ 10ms = 20ms). */
        const val DEFAULT_FRAMES_PER_PACKET = Constants.DEFAULT_FRAMES_PER_PACKET

        /**
         * Default send queue capacity corresponding to standard 20ms frames (~200ms buffer).
         * Note: static declaration must follow TARGET_BUFFER_DURATION_MS, FRAME_DURATION_MS,
         * and DEFAULT_FRAMES_PER_PACKET to ensure correct <clinit> evaluation order.
         */
        // Widened from package to public: Kotlin has no package visibility; JVM tests pin this.
        @JvmField
        val DEFAULT_SEND_QUEUE_CAPACITY: Int = calculateQueueCapacity(DEFAULT_FRAMES_PER_PACKET)

        /** Backward-compatibility alias for tests and external callers. */
        @JvmField
        val MAX_SEND_QUEUE_CAPACITY: Int = DEFAULT_SEND_QUEUE_CAPACITY

        /**
         * Calculates the send queue capacity in packets to maintain ~200ms of real-time audio
         * buffer headroom for the given frames-per-packet setting.
         *
         * @param framesPerPacket Number of 10ms frames per audio packet (1, 2, 4, 6).
         * @return Queue capacity bounded to maintain ~200ms target latency.
         */
        @JvmStatic
        fun calculateQueueCapacity(framesPerPacket: Int): Int {
            val fpp = sanitizeFramesPerPacket(framesPerPacket)
            val packetDurationMs = fpp * FRAME_DURATION_MS
            return Math.max(2, Math.ceil(TARGET_BUFFER_DURATION_MS.toDouble() / packetDurationMs).toInt())
        }

        /**
         * Sanitizes frames-per-packet to valid Opus configurations (1, 2, 4, 6).
         */
        @JvmStatic
        fun sanitizeFramesPerPacket(fpp: Int): Int {
            return Constants.sanitizeFramesPerPacket(fpp)
        }

        /**
         * Factory method to create HumlaUDP with an explicit queue capacity (primarily for testing).
         */
        // Widened to public: Kotlin has no package visibility; JVM tests pin this.
        @JvmStatic
        fun createWithExplicitCapacity(
            cryptState: CryptState,
            listener: UDPConnectionListener,
            callbackHandler: Handler,
            explicitCapacity: Int
        ): HumlaUDP {
            if (explicitCapacity <= 0) {
                throw IllegalArgumentException("sendQueueCapacity must be > 0")
            }
            val udp = HumlaUDP(cryptState, listener, callbackHandler)
            udp.setSendQueueCapacity(explicitCapacity)
            return udp
        }
    }

    private val mCryptState: CryptState

    private var mUDPSocket: DatagramSocket? = null
    private val mListener: UDPConnectionListener
    private var mHost: String? = null

    @Volatile
    private var mPort: Int = 0

    @Volatile
    private var mResolvedHost: InetAddress? = null

    @Volatile
    private var mConnected: Boolean = false

    /** Main datagram thread hosting this runnable. */
    private val mDatagramThread: Thread

    /** Handler to invoke listener callback invocations on. */
    private val mCallbackHandler: Handler

    /** Bounded queue of outgoing packets to be sent. */
    private val mSendQueue: BlockingQueue<DatagramPacket>

    /** Lock guarding compound offer/poll queue eviction and dynamic capacity adjustments. */
    private val mSendLock = Any()

    /** Audio frames per packet (1=10ms, 2=20ms, 4=40ms, 6=60ms). */
    @Volatile
    private var mTargetFramesPerPacket: Int = 0

    /** Current send queue capacity in packets, calculated to bound latency to ~200ms. */
    @Volatile
    private var mSendQueueCapacity: Int = 0

    /**
     * Sets up a new UDP connection context with the default 20ms frames per packet.
     * @param cryptState Cryptographic state provider.
     * @param listener Callback target. Connection state callbacks will be posted on the callback handler given;
     *                 data callbacks are delivered directly on the UDP receive thread.
     * @param callbackHandler Handler to post listener invocations on.
     */
    constructor(cryptState: CryptState, listener: UDPConnectionListener, callbackHandler: Handler) :
        this(cryptState, listener, callbackHandler, DEFAULT_FRAMES_PER_PACKET)

    /**
     * Sets up a new UDP connection context with a specified frames-per-packet setting.
     * The send queue capacity is dynamically calculated to target ~200ms of real-time audio buffer.
     *
     * @param cryptState Cryptographic state provider.
     * @param listener Callback target.
     * @param callbackHandler Handler to post listener invocations on.
     * @param targetFramesPerPacket Audio frames per packet (1=10ms, 2=20ms, 4=40ms, 6=60ms).
     */
    constructor(
        cryptState: CryptState,
        listener: UDPConnectionListener,
        callbackHandler: Handler,
        targetFramesPerPacket: Int
    ) {
        mCryptState = cryptState
        mListener = listener
        mCallbackHandler = callbackHandler
        mDatagramThread = Thread(this)
        mSendQueue = LinkedBlockingQueue()
        setTargetFramesPerPacket(targetFramesPerPacket)
    }

    fun connect(host: String, port: Int) {
        mHost = host
        mPort = port
        mDatagramThread.start()
    }

    fun isRunning(): Boolean {
        return mConnected
    }

    override fun run() {
        var outgoingConsumerThread: Thread? = null
        mConnected = true
        try {
            val host = mHost ?: throw IOException("No host set")
            mResolvedHost = InetAddress.getByName(host)
            mUDPSocket = DatagramSocket()

            mUDPSocket!!.connect(mResolvedHost, mPort)
            Log.d(TAG, "Created socket")

            // Start outgoing consumer once the UDP socket is open, as a child thread.
            val outgoingConsumer = OutgoingConsumer(mUDPSocket!!, mSendQueue)
            outgoingConsumerThread = Thread(outgoingConsumer)
            outgoingConsumerThread.start()

            val packet = DatagramPacket(ByteArray(BUFFER_SIZE), BUFFER_SIZE)
            while (mConnected) {
                mUDPSocket!!.receive(packet)
                val data = packet.data
                val length = packet.length

                if (!mCryptState.isValid()) {
                    Log.d(TAG, "CryptState invalid, discarding packet")
                    continue
                }
                if (length < 5) {
                    Log.d(TAG, "Packet too short, discarding")
                    continue
                }

                try {
                    val buffer = mCryptState.decrypt(data, length)

                    if (mListener != null) {
                        if (buffer != null) {
                            // Direct callback on UDP receiver thread per UDPConnectionListener contract
                            mListener.onUDPDataReceived(buffer)
                        } else if (mCryptState.getLastGoodElapsed() > 5000000 &&
                            mCryptState.getLastRequestElapsed() > 5000000
                        ) {
                            mCryptState.resetLastRequestTime()
                            mCallbackHandler.post {
                                mListener.resyncCryptState()
                            }
                            Log.d(TAG, "Packet failed to decrypt, discarding and requesting crypt state resync")
                        } else {
                            Log.d(TAG, "Packet failed to decrypt, discarding")
                        }
                    }
                } catch (e: BadPaddingException) {
                    Log.d(TAG, "Discarding packet", e)
                } catch (e: IllegalBlockSizeException) {
                    Log.d(TAG, "Discarding packet", e)
                } catch (e: ShortBufferException) {
                    Log.d(TAG, "Discarding packet", e)
                }
            }
        } catch (e: IOException) {
            // If mConnected is false, then this is a user-triggered disconnection. Report no error.
            if (mConnected) {
                Log.d(TAG, "UDP socket closed unexpectedly")
                mCallbackHandler.post {
                    mListener.onUDPConnectionError(e)
                }
            } else {
                Log.d(TAG, "UDP socket closed in response to user disconnect")
            }
        } finally {
            mConnected = false
            mResolvedHost = null

            // We want to interrupt the outgoing queue consumer thread to avoid sends after socket
            // cleanup. Blocking shouldn't be necessary.
            if (outgoingConsumerThread != null) {
                outgoingConsumerThread.interrupt()
            }

            // Clear the outgoing queue, in case the caller decides to reconnect with the same socket.
            synchronized(mSendLock) {
                mSendQueue.clear()
            }

            if (mUDPSocket != null) {
                mUDPSocket!!.close()
            }
        }
    }

    fun sendMessage(data: ByteArray, length: Int) {
        val resolvedHost = mResolvedHost
        if (!mCryptState.isValid() || !mConnected || resolvedHost == null) {
            return
        }

        try {
            val encryptedData = mCryptState.encrypt(data, length)
            if (encryptedData == null) {
                return
            }
            val packet = DatagramPacket(encryptedData, encryptedData.size)
            packet.address = resolvedHost
            packet.port = mPort

            // Synchronized block guarantees atomic head-drop eviction and strict capacity bounding
            synchronized(mSendLock) {
                while (mSendQueue.size >= mSendQueueCapacity) {
                    mSendQueue.poll()
                }
                mSendQueue.offer(packet)
            }
        } catch (e: BadPaddingException) {
            Log.w(TAG, "Failed to encrypt outgoing UDP packet", e)
        } catch (e: IllegalBlockSizeException) {
            Log.w(TAG, "Failed to encrypt outgoing UDP packet", e)
        } catch (e: ShortBufferException) {
            Log.w(TAG, "Failed to encrypt outgoing UDP packet", e)
        }
    }

    /**
     * Dynamically updates the frames per packet, recalculating the send queue capacity
     * to preserve ~200ms target latency, and flushing excess stale packets if capacity decreased.
     *
     * @param framesPerPacket Audio frames per packet (1=10ms, 2=20ms, 4=40ms, 6=60ms).
     */
    fun setTargetFramesPerPacket(framesPerPacket: Int) {
        synchronized(mSendLock) {
            mTargetFramesPerPacket = sanitizeFramesPerPacket(framesPerPacket)
            mSendQueueCapacity = calculateQueueCapacity(mTargetFramesPerPacket)
            while (mSendQueue.size > mSendQueueCapacity) {
                mSendQueue.poll()
            }
        }
        // Volatile reads outside the lock: rare event (connect, settings change,
        // bandwidth degradation), so debug logging here cannot spam logcat.
        Log.d(TAG, "Rescaled send queue: " + (getTargetFramesPerPacket() * FRAME_DURATION_MS) +
            "ms packets, capacity=" + getSendQueueCapacity())
    }

    fun getTargetFramesPerPacket(): Int {
        return mTargetFramesPerPacket
    }

    fun getSendQueueCapacity(): Int {
        return mSendQueueCapacity
    }

    /**
     * Explicitly sets send queue capacity (package-private for testing).
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun setSendQueueCapacity(capacity: Int) {
        if (capacity <= 0) {
            throw IllegalArgumentException("sendQueueCapacity must be > 0")
        }
        synchronized(mSendLock) {
            mSendQueueCapacity = capacity
            while (mSendQueue.size > mSendQueueCapacity) {
                mSendQueue.poll()
            }
        }
    }

    /**
     * Visible for testing: returns the outgoing packet queue.
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun getSendQueue(): BlockingQueue<DatagramPacket> {
        return mSendQueue
    }

    /**
     * Visible for testing: configures endpoint and connected state without starting background threads.
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun setConnectedForTesting(connected: Boolean, host: InetAddress?, port: Int) {
        mConnected = connected
        mResolvedHost = host
        mPort = port
    }

    /**
     * Lazy, non-blocking idempotent disconnect.
     */
    fun disconnect() {
        mConnected = false
        synchronized(mSendLock) {
            mSendQueue.clear()
        }
        // Closing a socket will trigger an IOException on the consumer thread.
        if (mUDPSocket != null) {
            mUDPSocket!!.close()
        }
    }

    /**
     * Note that all connection state related calls are made on the main thread.
     * onUDPDataReceived is always called on the UDP receive thread.
     */
    interface UDPConnectionListener {
        fun onUDPDataReceived(data: ByteArray)
        fun onUDPConnectionError(e: Exception)
        fun resyncCryptState()
    }

    /**
     * Runnable that reads from a shared blocking queue, dispatching datagrams when available.
     */
    private class OutgoingConsumer(
        private val mSocket: DatagramSocket,
        private val mQueue: BlockingQueue<DatagramPacket>
    ) : Runnable {

        override fun run() {
            Log.d(TAG, "Datagram outbox consumer active")
            var interrupted = false
            while (!interrupted) {
                try {
                    val packet = mQueue.take()
                    mSocket.send(packet)
                } catch (e: IOException) {
                    if (mSocket.isClosed) {
                        break
                    }
                    Log.w(TAG, "Failed to send outgoing datagram", e)
                } catch (e: InterruptedException) {
                    // Our datagram thread interrupted us. We should stop reading.
                    Thread.currentThread().interrupt()
                    interrupted = true
                }
            }
            Log.d(TAG, "Datagram outbox consumer shutdown")
        }
    }
}
