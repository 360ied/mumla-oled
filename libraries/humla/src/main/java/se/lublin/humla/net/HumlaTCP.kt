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
import android.os.Looper
import android.util.Log
import android.util.Patterns
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.minidns.hla.ResolverApi
import org.minidns.record.SRV
import org.minidns.util.SrvUtil
import se.lublin.humla.Constants
import se.lublin.humla.util.HumlaException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.Arrays
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Class to maintain and interface with the TCP connection to a Mumble server.
 * Parses Mumble protobuf packets according to the Mumble protocol specification.
 */
class HumlaTCP(private val mSocketFactory: HumlaSSLSocketFactory) {
    companion object {
        private val TAG = HumlaTCP::class.java.name
        const val CONNECT_TIMEOUT = 10000

        /**
         * Read timeout for the established connection. The keepalive ping loop
         * (5 s bootstrap, 10 s steady state, server Ping reply to every client
         * Ping) keeps healthy connections fed well inside this window, so expiry
         * means the connection stopped producing frames past three steady-state
         * ping intervals — a stalled or malicious server, but also sleep, radio
         * stalls, or an overloaded server.
         */
        const val READ_TIMEOUT_MS = 30000
    }

    private var mHost: String? = null
    private var mPort: Int = 0
    private var mTCPSocket: SSLSocket? = null
    private var mDataInput: DataInputStream? = null
    private var mDataOutput: DataOutputStream? = null
    private var mRunning: Boolean = false
    private var mConnected: Boolean = false
    private var mListener: TCPConnectionListener? = null

    // Slice 4: HumlaNetworkThread 3-executors-per-connection (incl. the dead
    // receive executor, never ported) becomes one ConnectionScope
    // (SupervisorJob + IO). The blocking TCP read loop runs in one child;
    // writes go through a per-connection serial dispatcher (single-writer FIFO
    // is all the TCP path needs — a Mutex alone cannot order launches across
    // the IO pool, so submissions serialize too) plus the write Mutex as
    // belt-and-braces. Created in connect(); torn down in disconnect() and in
    // runLoop()'s finally (mirroring the old stopThreads, so failed connections
    // neither leak the scope nor accept sends); in-flight sends are best-effort
    // on cancel, send-after-disconnect drops.
    private var tcpScope: CoroutineScope? = null
    private var sendDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val writeMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun executeOnMainThread(r: Runnable) {
        mainHandler.post(r)
    }

    private fun executeOnSendThread(r: Runnable) {
        val scope = tcpScope ?: return
        // Drop sends against torn-down streams (pre-handshake window or a failed
        // connection): the old executor lost these sends when its worker died on
        // the null output; drop with a log instead of crashing the scope child.
        if (!mRunning || mDataOutput == null) {
            Log.d(TAG, "Dropping send on torn-down connection")
            return
        }
        scope.launch(sendDispatcher) {
            writeMutex.withLock { r.run() }
        }
    }

    fun setTCPConnectionListener(listener: TCPConnectionListener?) {
        mListener = listener
    }

    @Throws(ConnectException::class)
    fun connect(host: String, port: Int) {
        if (mRunning) throw ConnectException("TCP connection already established!")
        mHost = host
        mPort = port
        tcpScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tcpScope = scope
        sendDispatcher = Dispatchers.IO.limitedParallelism(1)
        scope.launch { runLoop() }
    }

    fun isRunning(): Boolean {
        return mRunning
    }

    fun getHost(): String? {
        return mHost
    }

    fun getPort(): Int {
        return mPort
    }

    private suspend fun runLoop() {
        mRunning = true
        try {
            if (mPort == 0) {
                val host = mHost
                if (host != null && !Patterns.IP_ADDRESS.matcher(host).matches() && !host.contains(":") &&
                    !TlsHostnameVerifier.isOnionHost(host)
                ) {
                    try {
                        val lookup = "_mumble._tcp.$host"
                        // Slice 4: ad-hoc SRV single-thread executor becomes a
                        // shared IO call with an 800ms timeout.
                        var res: org.minidns.hla.SrvResolverResult? = null
                        res = withTimeoutOrNull(800) {
                            ResolverApi.INSTANCE.resolveSrv(lookup)
                        }
                        if (res == null) {
                            Log.d(TAG, "SRV lookup timed out for $lookup")
                        }
                        if (res != null && res.wasSuccessful()) {
                            val answers = res.getAnswersOrEmptySet()
                            if (answers != null && answers.isNotEmpty()) {
                                val srvs = SrvUtil.sortSrvRecords(answers)
                                for (srv in srvs) {
                                    Log.d(TAG, "resolved $lookup SRV: $srv")
                                    var target = srv.target.toString()
                                    if (target.endsWith(".")) {
                                        target = target.substring(0, target.length - 1)
                                    }
                                    mHost = target
                                    mPort = srv.port
                                    break
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // Scope torn down mid-lookup (disconnect): never swallow
                        // our own cancellation as a resolution failure.
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "SRV resolution failed for $mHost: $e")
                    }
                }
                if (mPort == 0) {
                    mPort = Constants.DEFAULT_PORT
                }
            }

            Log.i(TAG, "Connecting to $mHost:$mPort")

            val host = mHost
            val port = mPort
            mSocketFactory.setExpectedHost(host)
            mTCPSocket = mSocketFactory.createSocket(requireNotNull(host), port, CONNECT_TIMEOUT)
            val tcpSocket = mTCPSocket ?: throw NullPointerException("TCP socket")

            tcpSocket.keepAlive = true
            tcpSocket.tcpNoDelay = true
            tcpSocket.soTimeout = CONNECT_TIMEOUT
            tcpSocket.startHandshake()
            Log.v(TAG, "Handshake completed; verifying server identity")

            if (!verifyServerIdentity()) {
                return
            }

            mDataInput = DataInputStream(tcpSocket.inputStream)
            mDataOutput = DataOutputStream(tcpSocket.outputStream)

            tcpSocket.soTimeout = READ_TIMEOUT_MS

            Log.v(TAG, "Now listening")
            mConnected = true

            val establishedListener = mListener
            if (establishedListener != null) {
                executeOnMainThread(Runnable {
                    establishedListener.onTCPConnectionEstablished()
                })
            }

            val dataInput = mDataInput ?: throw NullPointerException("TCP input")
            val dataOutput = mDataOutput ?: throw NullPointerException("TCP output")
            while (mConnected) {
                val messageType = dataInput.readShort()
                val messageLength = dataInput.readInt()
                // Validate before allocating: a malicious server controls both
                // fields. Violations abort the connection — the stream position
                // is mid-frame, so skipping would desync the parser.
                val frameError = FrameValidator.validateFrame(messageType, messageLength)
                if (frameError != FrameValidator.FrameError.NONE) {
                    Log.w(TAG, "Dropping connection on invalid frame: type=$messageType" +
                        " length=$messageLength error=$frameError")
                    error("Server sent an invalid protocol frame ($frameError)", null)
                    return
                }
                val data = ByteArray(messageLength)
                dataInput.readFully(data)

                val tcpMessageType = HumlaTCPMessageType.values()[messageType.toInt()]
                val messageListener = mListener
                if (messageListener != null) {
                    executeOnMainThread(Runnable {
                        messageListener.onTCPMessageReceived(tcpMessageType, messageLength, data)
                    })
                }
            }
        } catch (e: SocketException) {
            error("Could not open a connection to the host", e)
        } catch (e: SSLHandshakeException) {
            // Try and verify certificate manually.
            if (mSocketFactory.getServerChain() != null && mListener != null) {
                if (!mRunning) return
                val recorded = mSocketFactory.getLastHandshakeFailure()
                val failure = classifyHandshakeFailure(recorded)
                val verifiedHost = mHost
                val handshakeListener = mListener ?: throw NullPointerException("TCP listener")
                executeOnMainThread(Runnable {
                    handshakeListener.onTLSHandshakeFailed(mSocketFactory.getServerChain(), failure, verifiedHost)
                })
            } else {
                error("Could not verify host certificate", e)
            }
        } catch (e: IOException) {
            error("An error occurred when communicating with the host", e)
        } finally {
            mConnected = false
            try {
                val dataInput = mDataInput
                if (dataInput != null) dataInput.close()
                val dataOutput = mDataOutput
                if (dataOutput != null) dataOutput.close()
                val tcpSocket = mTCPSocket
                if (tcpSocket != null) tcpSocket.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            mRunning = false
            // Tear down the ConnectionScope on every exit (mirrors the old
            // stopThreads): a failed connection must not leak the scope, and
            // send-after-failure must drop via the nulled scope instead of
            // launching against torn-down streams.
            tcpScope?.cancel()
            tcpScope = null

            // Single delivery point for onTCPConnectionDisconnect (pre-image
            // semantics): disconnect() stays silent and lets this finally post.
            val disconnectListener = mListener
            if (disconnectListener != null) {
                executeOnMainThread(Runnable {
                    disconnectListener.onTCPConnectionDisconnect()
                })
            }
        }
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, serialized
     * via the per-connection serial dispatcher plus the write Mutex (slice 4:
     * single-writer FIFO replaces the send executor).
     * @param message The message to send.
     * @param messageType The type of the message to send.
     */
    fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        executeOnSendThread(Runnable {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                Log.v(TAG, "OUT: $messageType")
            val dataOutput = mDataOutput ?: throw NullPointerException("TCP output")
            try {
                dataOutput.writeShort(messageType.ordinal)
                dataOutput.writeInt(message.serializedSize)
                message.writeTo(dataOutput)
                dataOutput.flush()
            } catch (e: IOException) {
                e.printStackTrace()
                // TODO handle
            }
        })
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, serialized
     * via the per-connection serial dispatcher plus the write Mutex (slice 4:
     * single-writer FIFO replaces the send executor).
     * @param message The data to send.
     * @param length The length of the byte array.
     * @param messageType The type of the message to send.
     */
    fun sendMessage(message: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        val copy = Arrays.copyOf(message, length)
        executeOnSendThread(Runnable {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                Log.v(TAG, "OUT: $messageType")
            val dataOutput = mDataOutput ?: throw NullPointerException("TCP output")
            try {
                dataOutput.writeShort(messageType.ordinal)
                dataOutput.writeInt(length)
                dataOutput.write(copy, 0, length)
                dataOutput.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        })
    }

    /**
     * Disconnects gracefully. Closes the socket synchronously to unblock the
     * read loop (suppressing post-disconnect errors via mRunning) and cancels
     * the ConnectionScope (in-flight sends are best-effort); send-after-
     * disconnect drops via the nulled scope. Stays silent: the read loop's
     * finally block is the single delivery point for
     * onTCPConnectionDisconnect. Suppresses all future errors on this connection.
     */
    fun disconnect() {
        if (!mRunning) return

        mRunning = false
        try {
            val tcpSocket = mTCPSocket
            if (tcpSocket != null)
                tcpSocket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        tcpScope?.cancel()
        tcpScope = null
    }

    private fun error(desc: String, e: Exception?) {
        if (!mRunning)
            return // Don't handle errors post-disconnection.
        val ce = HumlaException(
            desc, e,
            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
        )
        val failureListener = mListener
        if (failureListener != null)
            executeOnMainThread(Runnable {
                failureListener.onTCPConnectionFailed(ce)
            })
    }

    /**
     * Verifies the presented leaf identifies the post-SRV host. A TOFU pin
     * match establishes identity without a SAN check (private servers commonly
     * use SAN-less self-signed certs); otherwise CA trust from the handshake
     * plus a SAN/CN match is required. `.onion` hosts skip SAN matching and
     * rely on pins alone. Returns false after dispatching the failure path;
     * the caller must return without reading any frames. Note the
     * {@code finally} block in {@code run()} still delivers
     * {@code onTCPConnectionDisconnect} afterwards (pre-existing double
     * delivery, also true of the {@code SSLHandshakeException} path).
     */
    private fun verifyServerIdentity(): Boolean {
        val leaf = presentedLeaf()
        if (leaf == null) {
            Log.w(TAG, "No server certificate available; refusing connection")
            error("Could not verify server identity", null)
            closeSocket()
            return false
        }
        val host = mHost
        if (host != null && TlsHostnameVerifier.isOnionHost(host)) {
            return true
        }
        if (mSocketFactory.isPinnedLeaf(leaf)) {
            return true
        }
        if (host != null && TlsHostnameVerifier.verifyHostname(host, leaf)) {
            return true
        }
        mSocketFactory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH)
        Log.w(TAG, "TLS hostname mismatch for $host; refusing connection")
        closeSocket()
        val verifyListener = mListener
        if (verifyListener != null) {
            val verifiedHost = mHost
            executeOnMainThread(Runnable {
                verifyListener.onTLSHandshakeFailed(
                    mSocketFactory.getServerChain(),
                    HandshakeFailure.HOSTNAME_MISMATCH, verifiedHost
                )
            })
        }
        return false
    }

    /** Best-effort leaf: wrapper chain first, then session peer certificates. */
    private fun presentedLeaf(): X509Certificate? {
        val chain = mSocketFactory.getServerChain()
        if (chain != null && chain.isNotEmpty() && chain[0] != null) {
            return chain[0]
        }
        try {
            val socket = mTCPSocket
            // Null-tolerant session: pre-handshake has no session; the
            // original dereferenced unconditionally (NPE). Best-effort leaf
            // lookup returns null instead so verification takes the error path.
            val session = socket?.session
            if (socket != null && session != null) {
                val peers = session.peerCertificates
                if (peers != null) {
                    for (peer in peers) {
                        if (peer is X509Certificate) {
                            return peer
                        }
                    }
                }
            }
        } catch (e: SSLPeerUnverifiedException) {
            return null
        }
        return null
    }

    private fun closeSocket() {
        try {
            val tcpSocket = mTCPSocket
            if (tcpSocket != null) {
                tcpSocket.close()
            }
        } catch (ignored: IOException) {
        }
    }

    /**
     * Maps a recorded trust-manager outcome to the dialog reason. A CA-valid
     * leaf that fails the SAN check (e.g. platform endpoint-ID rejection, or a
     * handshake that never reached the post-handshake verifier) is a
     * HOSTNAME_MISMATCH with no pin bypass — never an Allow-and-pin prompt.
     */
    private fun classifyHandshakeFailure(recorded: HandshakeFailure?): HandshakeFailure {
        if (recorded != null && recorded != HandshakeFailure.NONE) {
            return recorded
        }
        val chain = mSocketFactory.getServerChain()
        val leaf = if (chain != null && chain.isNotEmpty()) chain[0] else null
        val host = mHost
        if (leaf != null && host != null && !TlsHostnameVerifier.isOnionHost(host) &&
            !TlsHostnameVerifier.verifyHostname(host, leaf)
        ) {
            mSocketFactory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH)
            return HandshakeFailure.HOSTNAME_MISMATCH
        }
        return HandshakeFailure.UNTRUSTED_ISSUER
    }

    interface TCPConnectionListener {
        fun onTCPConnectionEstablished()
        fun onTLSHandshakeFailed(chain: Array<X509Certificate>?, failure: HandshakeFailure?, verifiedHost: String?)
        fun onTCPConnectionFailed(e: HumlaException?)
        fun onTCPConnectionDisconnect()
        fun onTCPMessageReceived(type: HumlaTCPMessageType?, length: Int, data: ByteArray?)
    }
}
