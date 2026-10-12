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

import android.util.Log
import android.util.Patterns
import com.google.protobuf.MessageLite
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Class to maintain and interface with the TCP connection to a Mumble server.
 * Parses Mumble protobuf packets according to the Mumble protocol specification.
 */
class HumlaTCP(private val mSocketFactory: HumlaSSLSocketFactory) : HumlaNetworkThread() {
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

    fun setTCPConnectionListener(listener: TCPConnectionListener?) {
        mListener = listener
    }

    @Throws(ConnectException::class)
    fun connect(host: String, port: Int) {
        if (mRunning) throw ConnectException("TCP connection already established!")
        mHost = host
        mPort = port
        startThreads()
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

    override fun run() {
        mRunning = true
        try {
            if (mPort == 0) {
                val host = mHost
                if (host != null && !Patterns.IP_ADDRESS.matcher(host).matches() && !host.contains(":") &&
                    !TlsHostnameVerifier.isOnionHost(host)
                ) {
                    try {
                        val lookup = "_mumble._tcp.$host"
                        val executor = Executors.newSingleThreadExecutor()
                        val future = executor.submit(Callable {
                            ResolverApi.INSTANCE.resolveSrv(lookup)
                        })
                        var res: org.minidns.hla.SrvResolverResult? = null
                        try {
                            res = future.get(800, TimeUnit.MILLISECONDS)
                        } catch (te: TimeoutException) {
                            future.cancel(true)
                            Log.d(TAG, "SRV lookup timed out for $lookup")
                        } finally {
                            executor.shutdownNow()
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

            mTCPSocket!!.keepAlive = true
            mTCPSocket!!.tcpNoDelay = true
            mTCPSocket!!.soTimeout = CONNECT_TIMEOUT
            mTCPSocket!!.startHandshake()
            Log.v(TAG, "Handshake completed; verifying server identity")

            if (!verifyServerIdentity()) {
                return
            }

            mDataInput = DataInputStream(mTCPSocket!!.inputStream)
            mDataOutput = DataOutputStream(mTCPSocket!!.outputStream)

            mTCPSocket!!.soTimeout = READ_TIMEOUT_MS

            Log.v(TAG, "Now listening")
            mConnected = true

            if (mListener != null) {
                executeOnMainThread(Runnable {
                    mListener!!.onTCPConnectionEstablished()
                })
            }

            while (mConnected) {
                val messageType = mDataInput!!.readShort()
                val messageLength = mDataInput!!.readInt()
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
                mDataInput!!.readFully(data)

                val tcpMessageType = HumlaTCPMessageType.values()[messageType.toInt()]
                if (mListener != null) {
                    executeOnMainThread(Runnable {
                        mListener!!.onTCPMessageReceived(tcpMessageType, messageLength, data)
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
                executeOnMainThread(Runnable {
                    mListener!!.onTLSHandshakeFailed(mSocketFactory.getServerChain(), failure, verifiedHost)
                })
            } else {
                error("Could not verify host certificate", e)
            }
        } catch (e: IOException) {
            error("An error occurred when communicating with the host", e)
        } finally {
            mConnected = false
            try {
                if (mDataInput != null) mDataInput!!.close()
                if (mDataOutput != null) mDataOutput!!.close()
                if (mTCPSocket != null) mTCPSocket!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            mRunning = false

            executeOnMainThread(Runnable {
                mListener!!.onTCPConnectionDisconnect()
            })
            stopThreads()
        }
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The message to send.
     * @param messageType The type of the message to send.
     */
    fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        executeOnSendThread(Runnable {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                Log.v(TAG, "OUT: $messageType")
            try {
                mDataOutput!!.writeShort(messageType.ordinal)
                mDataOutput!!.writeInt(message.serializedSize)
                message.writeTo(mDataOutput)
                mDataOutput!!.flush()
            } catch (e: IOException) {
                e.printStackTrace()
                // TODO handle
            }
        })
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The data to send.
     * @param length The length of the byte array.
     * @param messageType The type of the message to send.
     */
    fun sendMessage(message: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        val copy = Arrays.copyOf(message, length)
        executeOnSendThread(Runnable {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                Log.v(TAG, "OUT: $messageType")
            try {
                mDataOutput!!.writeShort(messageType.ordinal)
                mDataOutput!!.writeInt(length)
                mDataOutput!!.write(copy, 0, length)
                mDataOutput!!.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        })
    }

    /**
     * Attempts to disconnect gracefully on the Tx thread.
     * Disconnects interrupt the socket listening on the Tx thread, suppressing any exceptions
     * caused by this request. Any remaining protobuf messages will be dispatched first.
     *
     * Suppresses all future errors on this connection.
     */
    fun disconnect() {
        if (!mRunning) return

        mRunning = false
        executeOnSendThread(Runnable {
            try {
                if (mTCPSocket != null)
                    mTCPSocket!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        })

        if (mListener != null) {
            executeOnMainThread(Runnable {
                mListener!!.onTCPConnectionDisconnect()
            })
        }
    }

    private fun error(desc: String, e: Exception?) {
        if (!mRunning)
            return // Don't handle errors post-disconnection.
        val ce = HumlaException(
            desc, e,
            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
        )
        if (mListener != null)
            executeOnMainThread(Runnable {
                mListener!!.onTCPConnectionFailed(ce)
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
        if (mListener != null) {
            val verifiedHost = mHost
            executeOnMainThread(Runnable {
                mListener!!.onTLSHandshakeFailed(
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
            if (mTCPSocket != null && mTCPSocket!!.session != null) {
                val peers = mTCPSocket!!.session.peerCertificates
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
            if (mTCPSocket != null) {
                mTCPSocket!!.close()
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
