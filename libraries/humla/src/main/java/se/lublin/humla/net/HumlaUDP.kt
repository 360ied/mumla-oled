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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import se.lublin.humla.Constants
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.ShortBufferException

/**
 * Class to maintain and receive packets from the UDP connection to a Mumble server.
 * Public interface is not thread safe.
 *
 * Slice 4: the BlockingQueue + mSendLock head-drop becomes a
 * Channel(capacity, onBufferOverflow = DROP_OLDEST) with identical ~200ms
 * bound semantics and no explicit lock. The OutgoingConsumer thread becomes
 * an IO consumer loop; the blocking receive loop runs in the same IO scope.
 */
class HumlaUDP {
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

    /** Handler to invoke listener callback invocations on. */
    private val mCallbackHandler: Handler

    /** Bounded head-drop channel of outgoing packets (slice 4: no explicit lock). */
    @Volatile
    private var sendChannel: Channel<DatagramPacket>? = null

    /** Test-visible queued count (Channel exposes no size; incremented on send, decremented on consume). */
    private val queuedCount = AtomicInteger(0)

    /** IO scope hosting the sender consumer and the blocking receiver. */
    private var udpScope: CoroutineScope? = null
    private var udpJob: Job? = null

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
     *                 data callbacks are delivered directly on the UDP receive coroutine.
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
        setTargetFramesPerPacket(targetFramesPerPacket)
    }

    fun connect(host: String, port: Int) {
        mHost = host
        mPort = port
        udpScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        udpScope = scope
        ensureChannel()
        udpJob = scope.launch { runUdp() }
    }

    fun isRunning(): Boolean {
        return mConnected
    }

    private fun ensureChannel() {
        if (sendChannel == null) {
            val capacity = mSendQueueCapacity.coerceAtLeast(1)
            sendChannel = Channel(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }

    /**
     * Recreates the head-drop channel at a new capacity, retaining the newest
     * queued packets (same flush-newest policy as the old poll-while-over-capacity).
     */
    private fun recreateChannel(newCapacity: Int) {
        val old = sendChannel
        val retained = ArrayList<DatagramPacket>()
        if (old != null) {
            while (true) {
                val packet = old.tryReceive().getOrNull() ?: break
                retained.add(packet)
            }
        }
        val newest = if (retained.size > newCapacity) {
            retained.subList(retained.size - newCapacity, retained.size)
        } else {
            retained
        }
        sendChannel = Channel(newCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var count = 0
        for (packet in newest) {
            if (sendChannel?.trySend(packet)?.isSuccess == true) {
                count++
            }
        }
        queuedCount.set(count)
    }

    private suspend fun runUdp() {
        mConnected = true
        try {
            val host = mHost ?: throw IOException("No host set")
            mResolvedHost = InetAddress.getByName(host)
            mUDPSocket = DatagramSocket()
            val udpSocket = mUDPSocket ?: throw NullPointerException("UDP socket")
            val resolvedHost = mResolvedHost ?: throw NullPointerException("resolved host")
            udpSocket.connect(resolvedHost, mPort)
            Log.d(TAG, "Created socket")

            // Sender consumer: drains the head-drop channel (slice 4: IO loop
            // replaces the OutgoingConsumer thread).
            val channel = sendChannel ?: throw NullPointerException("send channel")
            val sender = udpScope?.launch {
                for (packet in channel) {
                    try {
                        udpSocket.send(packet)
                    } catch (e: IOException) {
                        if (udpSocket.isClosed) {
                            break
                        }
                        Log.w(TAG, "Failed to send outgoing datagram", e)
                    } finally {
                        queuedCount.decrementAndGet()
                    }
                }
                Log.d(TAG, "Datagram outbox consumer shutdown")
            }
            Log.d(TAG, "Datagram outbox consumer active")

            try {
                val packet = DatagramPacket(ByteArray(BUFFER_SIZE), BUFFER_SIZE)
                while (mConnected) {
                    udpSocket.receive(packet)
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

                        if (buffer != null) {
                            // Direct callback on UDP receiver coroutine per UDPConnectionListener contract
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
                    } catch (e: BadPaddingException) {
                        Log.d(TAG, "Discarding packet", e)
                    } catch (e: IllegalBlockSizeException) {
                        Log.d(TAG, "Discarding packet", e)
                    } catch (e: ShortBufferException) {
                        Log.d(TAG, "Discarding packet", e)
                    }
                }
            } finally {
                sender?.cancel()
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

            // Drain the head-drop channel so a reconnect starts empty.
            val channel = sendChannel
            if (channel != null) {
                while (channel.tryReceive().getOrNull() != null) {
                    // Drain only; count reset below.
                }
            }
            queuedCount.set(0)

            val socket = mUDPSocket
            if (socket != null) {
                socket.close()
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

            // Head-drop via DROP_OLDEST: trySend always succeeds while open,
            // evicting the oldest queued packet when full. No explicit lock.
            val channel = sendChannel ?: return
            val result = channel.trySend(packet)
            if (result.isSuccess) {
                queuedCount.updateAndGet { current ->
                    if (current < mSendQueueCapacity) current + 1 else current
                }
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
        mTargetFramesPerPacket = sanitizeFramesPerPacket(framesPerPacket)
        val newCapacity = calculateQueueCapacity(mTargetFramesPerPacket)
        if (sendChannel == null) {
            mSendQueueCapacity = newCapacity
            ensureChannel()
        } else if (newCapacity != mSendQueueCapacity) {
            mSendQueueCapacity = newCapacity
            recreateChannel(newCapacity)
        } else {
            mSendQueueCapacity = newCapacity
        }
        // Rare event (connect, settings change, bandwidth degradation), so
        // debug logging here cannot spam logcat.
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
        if (sendChannel == null) {
            mSendQueueCapacity = capacity
            ensureChannel()
            return
        }
        if (capacity != mSendQueueCapacity) {
            mSendQueueCapacity = capacity
            recreateChannel(capacity)
        }
    }

    /**
     * Test-visible queued count (Channel exposes no size).
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun getQueuedCount(): Int {
        return queuedCount.get()
    }

    /**
     * Test-visible poll of the oldest queued packet (null when empty).
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun pollQueuedPacket(): DatagramPacket? {
        val packet = sendChannel?.tryReceive()?.getOrNull() ?: return null
        queuedCount.decrementAndGet()
        return packet
    }

    /**
     * Test-visible emptiness check.
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun isQueueEmpty(): Boolean {
        return queuedCount.get() <= 0
    }

    /**
     * Visible for testing: configures endpoint and connected state without starting background threads.
     */
    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun setConnectedForTesting(connected: Boolean, host: InetAddress?, port: Int) {
        mConnected = connected
        mResolvedHost = host
        mPort = port
        if (connected) {
            ensureChannel()
        }
    }

    /**
     * Lazy, non-blocking idempotent disconnect.
     */
    fun disconnect() {
        mConnected = false
        udpJob?.cancel()
        udpJob = null
        udpScope?.cancel()
        udpScope = null
        // Drain so a reconnect starts empty; count reset mirrors the old clear().
        val channel = sendChannel
        if (channel != null) {
            while (channel.tryReceive().getOrNull() != null) {
                // Drain only.
            }
        }
        queuedCount.set(0)
        // Closing a socket will trigger an IOException on the receiver coroutine.
        mUDPSocket?.close()
    }

    /**
     * Note that all connection state related calls are made on the main thread.
     * onUDPDataReceived is always called on the UDP receive coroutine.
     */
    interface UDPConnectionListener {
        fun onUDPDataReceived(data: ByteArray)
        fun onUDPConnectionError(e: Exception)
        fun resyncCryptState()
    }
}
