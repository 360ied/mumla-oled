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
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import se.lublin.humla.Constants
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.util.HumlaException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HumlaConnection(listener: HumlaConnectionListener?) :
    HumlaTCP.TCPConnectionListener, HumlaUDP.UDPConnectionListener {
    companion object {
        private val TAG = HumlaConnection::class.java.name

        /**
         * Message types that aren't shown in logcat.
         * For annoying types like UDPTunnel.
         */
        @JvmField
        val UNLOGGED_MESSAGES: Set<HumlaTCPMessageType> = HashSet<HumlaTCPMessageType>().apply {
            add(HumlaTCPMessageType.UDPTunnel)
            add(HumlaTCPMessageType.Ping)
        }

        // Keepalive constants
        const val BOOTSTRAP_PING_INTERVAL_SECONDS = 5
        const val STEADY_STATE_PING_INTERVAL_SECONDS = 10
        const val BOOTSTRAP_DURATION_MICROS = 30_000_000L // 30 seconds

        /**
         * Calculates the bandwidth required to send audio with the given parameters.
         * Includes packet overhead.
         * @param bitrate The bitrate in bps.
         * @param framesPerPacket The number of frames per audio packet.
         * @return The bandwidth in bps used by the given configuration.
         */
        @JvmStatic
        fun calculateAudioBandwidth(bitrate: Int, framesPerPacket: Int): Int {
            // FIXME: assumes worst-case using TCP
            var overhead = 20 + 8 + 4 + 1 + 2 + 12 + framesPerPacket
            overhead *= (800 / framesPerPacket)
            return overhead + bitrate
        }

        /**
         * Gets the protobuf message from the passed TCP data.
         * We isolate this so we can first parse the message and then inform all handlers. Saves processing power.
         * @param data Raw protobuf TCP data.
         * @param messageType Type of the message.
         * @return The parsed protobuf message.
         * @throws InvalidProtocolBufferException Called if the messageType does not match the data.
         */
        @JvmStatic
        @Throws(InvalidProtocolBufferException::class)
        fun getProtobufMessage(data: ByteArray, messageType: HumlaTCPMessageType): MessageLite {
            when (messageType) {
                HumlaTCPMessageType.Authenticate -> return Mumble.Authenticate.parseFrom(data)
                HumlaTCPMessageType.BanList -> return Mumble.BanList.parseFrom(data)
                HumlaTCPMessageType.Reject -> return Mumble.Reject.parseFrom(data)
                HumlaTCPMessageType.ServerSync -> return Mumble.ServerSync.parseFrom(data)
                HumlaTCPMessageType.ServerConfig -> return Mumble.ServerConfig.parseFrom(data)
                HumlaTCPMessageType.PermissionDenied -> return Mumble.PermissionDenied.parseFrom(data)
                HumlaTCPMessageType.UDPTunnel -> return Mumble.UDPTunnel.parseFrom(data)
                HumlaTCPMessageType.UserState -> return Mumble.UserState.parseFrom(data)
                HumlaTCPMessageType.UserRemove -> return Mumble.UserRemove.parseFrom(data)
                HumlaTCPMessageType.ChannelState -> return Mumble.ChannelState.parseFrom(data)
                HumlaTCPMessageType.ChannelRemove -> return Mumble.ChannelRemove.parseFrom(data)
                HumlaTCPMessageType.TextMessage -> return Mumble.TextMessage.parseFrom(data)
                HumlaTCPMessageType.ACL -> return Mumble.ACL.parseFrom(data)
                HumlaTCPMessageType.QueryUsers -> return Mumble.QueryUsers.parseFrom(data)
                HumlaTCPMessageType.Ping -> return Mumble.Ping.parseFrom(data)
                HumlaTCPMessageType.CryptSetup -> return Mumble.CryptSetup.parseFrom(data)
                HumlaTCPMessageType.ContextAction -> return Mumble.ContextAction.parseFrom(data)
                HumlaTCPMessageType.ContextActionModify -> return Mumble.ContextActionModify.parseFrom(data)
                HumlaTCPMessageType.Version -> return Mumble.Version.parseFrom(data)
                HumlaTCPMessageType.UserList -> return Mumble.UserList.parseFrom(data)
                HumlaTCPMessageType.PermissionQuery -> return Mumble.PermissionQuery.parseFrom(data)
                HumlaTCPMessageType.CodecVersion -> return Mumble.CodecVersion.parseFrom(data)
                HumlaTCPMessageType.UserStats -> return Mumble.UserStats.parseFrom(data)
                HumlaTCPMessageType.RequestBlob -> return Mumble.RequestBlob.parseFrom(data)
                HumlaTCPMessageType.SuggestConfig -> return Mumble.SuggestConfig.parseFrom(data)
                HumlaTCPMessageType.VoiceTarget -> return Mumble.VoiceTarget.parseFrom(data)
                HumlaTCPMessageType.PluginDataTransmission -> return Mumble.PluginDataTransmission.parseFrom(data)
                else -> throw InvalidProtocolBufferException("Unknown TCP data passed.")
            }
        }
    }

    private var mListener: HumlaConnectionListener? = listener

    // Authentication
    private var mCertificate: ByteArray? = null
    private var mCertificatePassword: String? = null
    private var mTrustStorePath: String? = null
    private var mTrustStorePassword: String? = null
    private var mTrustStoreFormat: String? = null

    // Threading: keepalive ping loop runs in a SupervisorJob + IO scope
    // (slice 4). Cancelled in disconnect(); send-after-disconnect drops via
    // the mConnected guard in sendTCP/UDPMessage, same as before. Volatile:
    // connect() mutates off the disconnect()/scheduleNextPing() monitor, so
    // a binder-thread disconnect racing a Main-thread connect needs the
    // visibility edge (worst case otherwise: one dropped ping reschedule).
    @Volatile
    private var pingScope: CoroutineScope? = null
    @Volatile
    private var pingJob: Job? = null
    private var mMainHandler: Handler = Handler(Looper.getMainLooper())

    // Networking and protocols
    // Volatile: read from audio threads in sendTCP/UDPMessage, written on
    // main in connect/disconnect (a stale read only drops one send via the
    // nulled-scope/mRunning guard in executeOnSendThread).
    @Volatile
    private var mTCP: HumlaTCP? = null

    @Volatile
    private var mUDP: HumlaUDP? = null

    @Volatile
    private var mUsingUDP = true
    private var mForceTCP = false

    @Volatile
    private var mConnected = false

    @Volatile
    private var mSynchronized = false
    private var mError: HumlaException? = null
    private var mExceptionHandled = false

    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    // Volatile: written in connect() on Main, read in getElapsed() from the
    // IO ping loop (a stale read pins the bootstrap interval or flips early).
    @JvmField
    @Volatile
    var mStartTimestamp: Long = 0 // Time that the connection was initiated in nanoseconds

    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    @JvmField
    val mCryptState: CryptState = CryptState()

    // Latency
    @Volatile
    private var mLastUDPPing: Long = 0

    @Volatile
    private var mLastTCPPing: Long = 0

    // Server
    private var mHost: String? = null
    private var mPort: Int = 0

    @Volatile
    private var mServerVersion: Int = 0

    @Volatile
    private var mServerVersionV2: Long = 0
    private var mServerRelease: String? = null
    private var mServerOSName: String? = null
    private var mServerOSVersion: String? = null

    @Volatile
    private var mMaxBandwidth: Int = 0
    private var mCodec: HumlaUDPMessageType? = null

    @Volatile
    private var mTargetFramesPerPacket: Int = Constants.DEFAULT_FRAMES_PER_PACKET

    // Session
    private var mSession: Int = 0

    // Message handlers
    private var mTCPHandlers = ConcurrentLinkedQueue<HumlaTCPMessageListener>()
    private var mUDPHandlers = ConcurrentLinkedQueue<HumlaUDPMessageListener>()

    /**
     * Handles packets received that are critical to the connection state.
     */
    private var mConnectionMessageHandler: HumlaTCPMessageListener =
        object : HumlaTCPMessageListener.Stub() {

            override fun messageServerSync(msg: Mumble.ServerSync) {
                // Protocol says we're supposed to send a dummy UDPTunnel packet here to let the server know we don't like UDP.
                if (shouldForceTCP()) {
                    enableForceTCP()
                }

                // Start TCP/UDP ping loop using adaptive self-rescheduling.
                scheduleNextPing(0)

                mSession = msg.session
                mMaxBandwidth = if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1
                mSynchronized = true

                mMainHandler.post(Runnable {
                    mListener?.onConnectionSynchronized()
                })
            }

            override fun messageCodecVersion(msg: Mumble.CodecVersion) {
                if (msg.hasOpus() && msg.opus)
                    mCodec = HumlaUDPMessageType.UDPVoiceOpus
                else if (msg.hasBeta() && !msg.preferAlpha)
                    mCodec = HumlaUDPMessageType.UDPVoiceCELTBeta
                else
                    mCodec = HumlaUDPMessageType.UDPVoiceCELTAlpha
            }

            override fun messageReject(msg: Mumble.Reject) {
                mConnected = false
                handleFatalException(HumlaException(msg))
            }

            override fun messageUserRemove(msg: Mumble.UserRemove) {
                if (msg.session == mSession) {
                    mConnected = false
                    handleFatalException(HumlaException(msg))
                }
            }

            override fun messageCryptSetup(msg: Mumble.CryptSetup) {
                try {
                    if (msg.hasKey() && msg.hasClientNonce() && msg.hasServerNonce()) {
                        val key = msg.key
                        val clientNonce = msg.clientNonce
                        val serverNonce = msg.serverNonce

                        if (key.size() == CryptState.AES_BLOCK_SIZE &&
                            clientNonce.size() == CryptState.AES_BLOCK_SIZE &&
                            serverNonce.size() == CryptState.AES_BLOCK_SIZE
                        ) {
                            mCryptState.setKeys(key.toByteArray(), clientNonce.toByteArray(), serverNonce.toByteArray())
                        } else {
                            throw InvalidKeyException("Invalid key or nonce size in CryptSetup message")
                        }
                    } else if (msg.hasServerNonce()) {
                        val serverNonce = msg.serverNonce
                        if (serverNonce.size() == CryptState.AES_BLOCK_SIZE) {
                            mCryptState.setDecryptIV(serverNonce.toByteArray())
                        } else {
                            throw InvalidKeyException("Invalid server nonce size in CryptSetup message")
                        }
                    } else {
                        val csb = Mumble.CryptSetup.newBuilder()
                        csb.clientNonce = ByteString.copyFrom(mCryptState.getEncryptIV())
                        sendTCPMessage(csb.build(), HumlaTCPMessageType.CryptSetup)
                    }
                } catch (e: InvalidKeyException) {
                    handleFatalException(
                        HumlaException(
                            "Received invalid cryptographic nonce from server", e,
                            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
                        )
                    )
                }
            }

            override fun messageVersion(msg: Mumble.Version) {
                if (msg.hasVersionV2()) {
                    mServerVersionV2 = msg.versionV2
                    mServerVersion = Constants.toLegacyVersion(mServerVersionV2)
                } else if (msg.hasVersionV1()) {
                    mServerVersion = msg.versionV1
                    mServerVersionV2 = Constants.toVersionV2FromLegacy(mServerVersion)
                }
                if (msg.hasRelease()) {
                    mServerRelease = msg.release
                }
                if (msg.hasOs()) {
                    mServerOSName = msg.os
                }
                if (msg.hasOsVersion()) {
                    mServerOSVersion = msg.osVersion
                }
            }

            override fun messagePing(msg: Mumble.Ping) {
                mCryptState.mUiRemoteGood = msg.good
                mCryptState.mUiRemoteLate = msg.late
                mCryptState.mUiRemoteLost = msg.lost
                mCryptState.mUiRemoteResync = msg.resync

                // In microseconds
                val elapsed = getElapsed()
                mLastTCPPing = elapsed - msg.timestamp

                val localGood = mCryptState.getGood()
                if ((mCryptState.mUiRemoteGood == 0 || localGood == 0) && mUsingUDP && elapsed > 20000000) {
                    mUsingUDP = false
                    enableForceTCP()
                    Log.i(TAG, "Switching to TCP mode (remoteGood=" + mCryptState.mUiRemoteGood +
                        ", localGood=" + localGood + ")")
                } else if (!mUsingUDP && mCryptState.mUiRemoteGood > 3 && localGood > 3) {
                    mUsingUDP = true
                    Log.i(TAG, "Switching back to UDP mode (remoteGood=" + mCryptState.mUiRemoteGood +
                        ", localGood=" + localGood + ")")
                }
            }
        }

    private var mUDPPingListener: HumlaUDPMessageListener =
        object : HumlaUDPMessageListener.Stub() {

            override fun messageUDPPing(data: ByteArray) {
                try {
                    val pb = PacketBuffer(data, data.size)
                    pb.skip(1)
                    val timestamp = pb.readLong()
                    val now = getElapsed()
                    mLastUDPPing = now - timestamp
                } catch (e: Exception) {
                    if (data.size >= 9) {
                        val timedata = ByteArray(8)
                        System.arraycopy(data, 1, timedata, 0, 8)
                        val buffer = ByteBuffer.allocate(8)
                        buffer.put(timedata)
                        buffer.flip()
                        val timestamp = buffer.long
                        val now = getElapsed()
                        mLastUDPPing = now - timestamp
                    }
                }
                // TODO refresh UDP?
            }

            override fun messageProtobufPing(msg: MumbleUDP.Ping) {
                val timestamp = msg.timestamp
                val now = getElapsed()
                mLastUDPPing = now - timestamp
                if (msg.serverVersionV2 != 0L) {
                    mServerVersionV2 = msg.serverVersionV2
                }
                if (msg.maxBandwidthPerUser != 0) {
                    mMaxBandwidth = msg.maxBandwidthPerUser
                }
            }
        }

    init {
        mTCPHandlers.add(mConnectionMessageHandler)
        mUDPHandlers.add(mUDPPingListener)
    }

    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    fun getNextPingIntervalSeconds(): Int {
        val elapsed = getElapsed()
        if (elapsed < BOOTSTRAP_DURATION_MICROS) {
            return BOOTSTRAP_PING_INTERVAL_SECONDS
        }
        if (!shouldForceTCP() && mUsingUDP) {
            if (mCryptState.mUiRemoteGood <= 3 || mCryptState.getGood() <= 3) {
                return BOOTSTRAP_PING_INTERVAL_SECONDS
            }
        }
        return STEADY_STATE_PING_INTERVAL_SECONDS
    }

    // Widened to public: Kotlin has no package visibility; called across the library.
    // Slice 4: self-chaining ScheduledExecutorService becomes
    // while (isActive) { sendKeepalivePing(); delay(interval) } on IO.
    @Synchronized
    fun scheduleNextPing(delaySeconds: Int) {
        val scope = pingScope ?: return
        if (!mConnected) {
            return
        }
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(delaySeconds * 1000L)
            while (isActive) {
                try {
                    sendKeepalivePing()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during keepalive ping execution: " + e.message)
                }
                delay(getNextPingIntervalSeconds() * 1000L)
            }
        }
    }

    fun sendKeepalivePing() {
        // In microseconds
        val t = getElapsed()

        if (!shouldForceTCP()) {
            if (isProtobufUdpSupported()) {
                val pb = MumbleUDP.Ping.newBuilder()
                pb.timestamp = t
                val pingBytes = pb.build().toByteArray()
                val packet = ByteArray(1 + pingBytes.size)
                packet[0] = 0x01 // Protobuf Ping type
                System.arraycopy(pingBytes, 0, packet, 1, pingBytes.size)
                sendUDPMessage(packet, packet.size, true)
            } else {
                val pingBuffer = ByteArray(10)
                pingBuffer[0] = ((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte()
                val pb = PacketBuffer(pingBuffer, 10)
                pb.skip(1)
                pb.writeLong(t)
                sendUDPMessage(pingBuffer, pb.size(), true)
            }
        }

        val pb = Mumble.Ping.newBuilder()
        pb.timestamp = t
        pb.good = mCryptState.getGood()
        pb.late = mCryptState.getLate()
        pb.lost = mCryptState.getLost()
        pb.resync = mCryptState.getResync()
        // TODO accumulate stats and send with ping
        sendTCPMessage(pb.build(), HumlaTCPMessageType.Ping)
    }

    /**
     * Creates a new HumlaConnection object to facilitate server connections.
     */
    @Throws(HumlaException::class)
    fun connect(host: String, port: Int) {
        mHost = host
        mPort = port
        mConnected = false
        mSynchronized = false
        mError = null
        mExceptionHandled = false
        mUsingUDP = !shouldForceTCP()
        mStartTimestamp = System.nanoTime()

        pingScope?.cancel()
        pingScope = null
        // The new scope orphans the old loop (already cancelled above); drop
        // the reference so disarm checks never point at the dead job.
        pingJob = null

        val socketFactory = createSocketFactory()
        // Scope created after the throwing factory call: a factory failure
        // must not leak an idle SupervisorJob (pre-image leaked the executor
        // the same way; HumlaService.connect catches HumlaException without
        // disconnecting, so nothing would reclaim it).
        pingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val tcp = HumlaTCP(socketFactory)
            mTCP = tcp
            tcp.setTCPConnectionListener(this)
            tcp.connect(host, port)
            // UDP thread is formally started after TCP connection.
        } catch (e: ConnectException) {
            // Defensive: HumlaTCP.connect only throws when already running, so
            // a fresh instance never lands here; kept so a future synchronous
            // failure still wraps as HumlaException for HumlaService.connect.
            // Reclaim the idle ping scope: HumlaService.connect catches
            // HumlaException without disconnecting, so nothing else would.
            pingScope?.cancel()
            pingScope = null
            throw HumlaException(e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        }
    }

    fun isConnected(): Boolean {
        return mConnected
    }

    fun getHost(): String? {
        return mHost
    }

    fun getPort(): Int {
        return mPort
    }

    /**
     * Returns whether or not the service is fully synchronized with the remote server- this happens when we get the ServerSync message.
     * You shouldn't log any user actions until the connection is synchronized.
     * @return true or false, depending on whether or not we have received the ServerSync message.
     */
    fun isSynchronized(): Boolean {
        return mSynchronized
    }

    fun getElapsed(): Long {
        return (System.nanoTime() - mStartTimestamp) / 1000
    }

    fun addTCPMessageHandlers(vararg handlers: HumlaTCPMessageListener) {
        Collections.addAll(mTCPHandlers, *handlers)
    }

    fun removeTCPMessageHandler(handler: HumlaTCPMessageListener) {
        mTCPHandlers.remove(handler)
    }

    fun addUDPMessageHandlers(vararg handlers: HumlaUDPMessageListener) {
        Collections.addAll(mUDPHandlers, *handlers)
    }

    fun removeUDPMessageHandler(handler: HumlaUDPMessageListener) {
        mUDPHandlers.remove(handler)
    }

    /**
     * Set whether to tunnel all voice packets over TCP, disabling the UDP thread.
     * @param forceTcp true if voice packets should tunnel over TCP.
     */
    fun setForceTCP(forceTcp: Boolean) {
        mForceTCP = forceTcp
    }

    /**
     * Sets the PKCS12 certificate data and password to use when authenticating.
     * @param certificate A PKCS12-formatted certificate.
     * @param password An optional password used to encrypt the certificate.
     */
    fun setKeys(certificate: ByteArray?, password: String?) {
        mCertificate = certificate
        mCertificatePassword = password
    }

    fun setTrustStore(path: String?, password: String?, format: String?) {
        mTrustStorePath = path
        mTrustStorePassword = password
        mTrustStoreFormat = format
    }

    fun setTargetFramesPerPacket(targetFramesPerPacket: Int) {
        mTargetFramesPerPacket = Constants.sanitizeFramesPerPacket(targetFramesPerPacket)
        val udp = mUDP
        if (udp != null) {
            udp.setTargetFramesPerPacket(mTargetFramesPerPacket)
        }
    }

    fun getTargetFramesPerPacket(): Int {
        return mTargetFramesPerPacket
    }

    @Throws(NotSynchronizedException::class)
    fun getServerVersion(): Int {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mServerVersion
    }

    @Throws(NotSynchronizedException::class)
    fun getServerVersionV2(): Long {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mServerVersionV2
    }

    fun isProtobufUdpSupported(): Boolean {
        return Constants.isProtobufUdpSupported(mServerVersionV2, mServerVersion)
    }

    @Throws(NotSynchronizedException::class)
    fun getServerRelease(): String? {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mServerRelease
    }

    @Throws(NotSynchronizedException::class)
    fun getServerOSName(): String? {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mServerOSName
    }

    @Throws(NotSynchronizedException::class)
    fun getServerOSVersion(): String? {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mServerOSVersion
    }

    @Throws(NotConnectedException::class)
    fun getTCPLatency(): Long {
        if (!isConnected())
            throw NotConnectedException()
        return mLastTCPPing
    }

    @Throws(NotConnectedException::class)
    fun getUDPLatency(): Long {
        if (!isConnected())
            throw NotConnectedException()
        return mLastUDPPing
    }

    @Throws(NotSynchronizedException::class)
    fun getSession(): Int {
        if (!isSynchronized())
            throw NotSynchronizedException("Session is set during synchronization")
        return mSession
    }

    /**
     * Returns the server-reported maximum input bandwidth, or -1 if not set.
     * @return the input bandwidth in bps, or -1 if not set.
     */
    @Throws(NotSynchronizedException::class)
    fun getMaxBandwidth(): Int {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mMaxBandwidth
    }

    @Throws(NotSynchronizedException::class)
    fun getCodec(): HumlaUDPMessageType? {
        if (!isSynchronized())
            throw NotSynchronizedException()
        return mCodec
    }

    /**
     * Return whether or not voice packets should be tunneled over TCP.
     * @return true if TCP is manually forced.
     */
    fun shouldForceTCP(): Boolean {
        return mForceTCP
    }

    /**
     * Gracefully shuts down all networking. Blocks until all network threads have stopped.
     */
    @Synchronized
    fun disconnect() {
        mConnected = false
        mSynchronized = false
        mHost = null
        mPort = 0

        // Stop running network resources
        pingJob?.cancel()
        pingJob = null
        pingScope?.cancel()
        pingScope = null
        val tcp = mTCP
        if (tcp != null) tcp.disconnect()
        val udp = mUDP
        if (udp != null) udp.disconnect()

        mTCP = null
        mUDP = null
        mCryptState.destroy()
    }

    /**
     * Handles an exception that would cause termination of the connection.
     * @param e The exception that caused termination.
     */
    private fun handleFatalException(e: HumlaException) {
        if (mExceptionHandled) return
        mExceptionHandled = true
        mError = e

        e.printStackTrace()
        mListener?.onConnectionDisconnected(e)

        disconnect()
    }

    /**
     * Attempts to create a socket factory using the HumlaConnection's certificate and trust
     * store configuration.
     * @return A socket factory set to authenticate with a certificate and trust store, if set.
     */
    @Throws(HumlaException::class)
    private fun createSocketFactory(): HumlaSSLSocketFactory {
        try {
            var keyStore: KeyStore? = null
            val certificate = mCertificate
            if (certificate != null) {
                keyStore = KeyStore.getInstance("PKCS12")
                val inputStream = ByteArrayInputStream(certificate)
                keyStore.load(inputStream, mCertificatePassword?.toCharArray() ?: CharArray(0))
            }

            return HumlaSSLSocketFactory(
                keyStore, mCertificatePassword, mTrustStorePath,
                mTrustStorePassword, mTrustStoreFormat
            )
        } catch (e: KeyManagementException) {
            throw HumlaException(
                "Could not recover keys from certificate", e,
                HumlaException.HumlaDisconnectReason.OTHER_ERROR
            )
        } catch (e: KeyStoreException) {
            throw HumlaException(
                "Could not recover keys from certificate", e,
                HumlaException.HumlaDisconnectReason.OTHER_ERROR
            )
        } catch (e: UnrecoverableKeyException) {
            throw HumlaException(
                "Could not recover keys from certificate", e,
                HumlaException.HumlaDisconnectReason.OTHER_ERROR
            )
        } catch (e: IOException) {
            throw HumlaException(
                "Could not read certificate file", e,
                HumlaException.HumlaDisconnectReason.OTHER_ERROR
            )
        } catch (e: CertificateException) {
            throw HumlaException(
                "Could not read certificate", e,
                HumlaException.HumlaDisconnectReason.OTHER_ERROR
            )
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("Bouncy Castle algorithm missing", e)
        } catch (e: NoSuchProviderException) {
            throw RuntimeException("Bouncy Castle provider missing", e)
        }
    }

    /**
     * Sends a protobuf message over TCP. Can silently fail.
     * @param message A built protobuf message.
     * @param messageType The corresponding protobuf message type.
     */
    fun sendTCPMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        val tcp = mTCP
        if (!mConnected || tcp == null) return
        tcp.sendMessage(message, messageType)
    }

    /**
     * Sends a datagram message over UDP. Can silently fail, or be tunneled through TCP unless forced.
     * @param data Raw data to send over UDP.
     * @param length Length of the data to send.
     * @param force Whether to avoid tunneling this data over TCP.
     */
    /**
     * Sends a voice packet, tunneling over TCP or via UDP per route policy.
     * The legacy-codec workaround patches `data` in place (pre-existing
     * contract, 0x10202 servers only): callers must not reuse the buffer
     * afterwards — AudioHandler's zero-alloc reuse passes ownership here.
     */
    fun sendUDPMessage(data: ByteArray, length: Int, force: Boolean) {
        if (!mConnected) return
        if (length < 0 || length > data.size) {
            throw IllegalArgumentException(
                "Requested length $length out of bounds for " +
                    "available data length " + data.size + "!"
            )
        }
        if (mServerVersion == 0x10202) applyLegacyCodecWorkaround(data)
        val tcp = mTCP
        val udp = mUDP
        if (!force && (shouldForceTCP() || !mUsingUDP) && tcp != null) {
            tcp.sendMessage(data, length, HumlaTCPMessageType.UDPTunnel)
        } else if (!shouldForceTCP() && udp != null) {
            udp.sendMessage(data, length)
        }
    }

    /**
     * Sends a message to the server, asking it to tunnel future voice packets over TCP.
     */
    private fun enableForceTCP() {
        if (!mConnected) return
        val utb = Mumble.UDPTunnel.newBuilder()
        utb.packet = ByteString.copyFrom(ByteArray(3))
        sendTCPMessage(utb.build(), HumlaTCPMessageType.UDPTunnel)
    }

    /**
     * Sends the given access tokens to the server.
     * @param tokens A list of new access tokens to send to the server.
     */
    fun sendAccessTokens(tokens: Collection<String>) {
        if (!mConnected) return
        val ab = Mumble.Authenticate.newBuilder()
        ab.addAllTokens(tokens)
        sendTCPMessage(ab.build(), HumlaTCPMessageType.Authenticate)
    }

    override fun onTCPMessageReceived(type: HumlaTCPMessageType?, length: Int, data: ByteArray?) {
        // Explicit NPE (not requireNotNull/IAE): interface entry points are
        // platform-typed and the original dereferenced unconditionally.
        val msgType = type ?: throw NullPointerException("type")
        val msgData = data ?: throw NullPointerException("data")
        if (!UNLOGGED_MESSAGES.contains(msgType))
            Log.v(TAG, "IN: $msgType")

        if (msgType == HumlaTCPMessageType.UDPTunnel) {
            onUDPDataReceived(msgData)
            return
        }

        try {
            val message = getProtobufMessage(msgData, msgType)
            for (handler in mTCPHandlers) {
                broadcastTCPMessage(handler, message, msgType)
            }
        } catch (e: InvalidProtocolBufferException) {
            e.printStackTrace()
        }
    }

    override fun onTCPConnectionEstablished() {
        mConnected = true
        val tcp = mTCP
        if (tcp != null) {
            mHost = tcp.getHost()
            mPort = tcp.getPort()
        }

        // Attempt to start UDP thread once connected.
        if (!shouldForceTCP()) {
            val host = mHost
            val port = mPort
            if (host != null) {
                val udp = HumlaUDP(mCryptState, this, mMainHandler, mTargetFramesPerPacket)
                mUDP = udp
                udp.connect(host, port)
            } else {
                // Dead path (host is set from the live TCP socket above): log
                // instead of NPE-crashing the established callback. Nothing
                // here sets mForceTCP, so with mUsingUDP=true and mUDP=null
                // the voice packet is dropped until reconnect.
                Log.w(TAG, "onTCPConnectionEstablished with null host; UDP skipped")
            }
        }

        mListener?.onConnectionEstablished()
    }

    override fun onTLSHandshakeFailed(
        chain: Array<X509Certificate>?,
        failure: HandshakeFailure?,
        verifiedHost: String?
    ) {
        disconnect()
        // Single service-level Disconnect delivery on this path: the TCP read
        // loop's finally still fires onTCPConnectionDisconnect afterwards, and
        // without this guard onConnectionDisconnected would deliver twice.
        mExceptionHandled = true
        // Explicit NPE (not requireNotNull/IAE): the original dereferenced
        // the platform-typed failure unconditionally.
        val handshakeFailure = failure ?: throw NullPointerException("failure")
        val listener = mListener
        if (listener != null) {
            listener.onConnectionHandshakeFailed(chain, handshakeFailure, verifiedHost)
            listener.onConnectionDisconnected(null)
        }
    }

    override fun onTCPConnectionFailed(e: HumlaException?) {
        handleFatalException(e ?: throw NullPointerException("e"))
    }

    override fun onTCPConnectionDisconnect() {
        val listener = mListener
        if (listener != null && !mExceptionHandled) listener.onConnectionDisconnected(mError)
        disconnect()
    }

    // Non-null by contract (guide §1): in-slice callers pass non-null (TCP-tunnel
    // msgData after its NPE check, UDP decrypt output). A null from a Java
    // caller fails fast with NPE instead of the pre-image silent return —
    // intentional null-tightening on a dead path.
    override fun onUDPDataReceived(data: ByteArray) {
        if (data.isEmpty()) return

        if (isProtobufUdpSupported()) {
            val msgType = data[0].toInt() and 0xFF
            if (msgType == 0) { // MumbleUDP.Audio
                try {
                    val audioMsg = MumbleUDP.Audio.parseFrom(ByteString.copyFrom(data, 1, data.size - 1))
                    for (handler in mUDPHandlers) {
                        handler.messageProtobufAudio(audioMsg)
                    }
                } catch (e: InvalidProtocolBufferException) {
                    Log.w(TAG, "Failed to parse Protobuf UDP Audio message", e)
                }
                return
            } else if (msgType == 1) { // MumbleUDP.Ping
                try {
                    val pingMsg = MumbleUDP.Ping.parseFrom(ByteString.copyFrom(data, 1, data.size - 1))
                    for (handler in mUDPHandlers) {
                        handler.messageProtobufPing(pingMsg)
                    }
                } catch (e: InvalidProtocolBufferException) {
                    Log.w(TAG, "Failed to parse Protobuf UDP Ping message", e)
                }
                return
            }
        }

        if (mServerVersion == 0x10202) applyLegacyCodecWorkaround(data)
        val dataType = data[0].toInt() shr 5 and 0x7
        if (dataType < 0 || dataType > HumlaUDPMessageType.values().size - 1) return // Discard invalid data types
        val udpDataType = HumlaUDPMessageType.values()[dataType]

        for (handler in mUDPHandlers) {
            broadcastUDPMessage(handler, data, udpDataType)
        }
    }

    override fun onUDPConnectionError(e: Exception) {
        e.printStackTrace()
        mListener?.onConnectionWarning("UDP connection thread failed. Falling back to TCP.")
        enableForceTCP()
        // TODO recover UDP thread automagically
    }

    override fun resyncCryptState() {
        val tcp = mTCP
        if (tcp != null) {
            // Send an empty cryptstate message to resync.
            val csb = Mumble.CryptSetup.newBuilder()
            tcp.sendMessage(csb.build(), HumlaTCPMessageType.CryptSetup)
        }
    }

    /**
     * Workaround for 1.2.2 servers that report the old types for CELT alpha and beta.
     * @param data The UDP data to be patched, if we're on a 1.2.2 server.
     * Bounds-guarded: types 5-7 have no HumlaUDPMessageType entry and empty
     * buffers have no data[0] (pre-image threw AIOOBE on both).
     */
    private fun applyLegacyCodecWorkaround(data: ByteArray) {
        if (data.isEmpty()) return
        val ordinal = data[0].toInt() shr 5 and 0x7
        if (ordinal < 0 || ordinal >= HumlaUDPMessageType.values().size) return
        var dataType = HumlaUDPMessageType.values()[ordinal]
        if (dataType == HumlaUDPMessageType.UDPVoiceCELTBeta)
            dataType = HumlaUDPMessageType.UDPVoiceCELTAlpha
        else if (dataType == HumlaUDPMessageType.UDPVoiceCELTAlpha)
            dataType = HumlaUDPMessageType.UDPVoiceCELTBeta
        data[0] = ((dataType.ordinal shl 5) and 0xFF).toByte()
    }

    /**
     * Reroutes TCP messages into the various responder methods of the handler.
     * @param handler Handler.
     * @param msg Protobuf message.
     * @param messageType The type of the message.
     */
    fun broadcastTCPMessage(handler: HumlaTCPMessageListener, msg: MessageLite, messageType: HumlaTCPMessageType) {
        when (messageType) {
            HumlaTCPMessageType.Authenticate -> handler.messageAuthenticate(msg as Mumble.Authenticate)
            HumlaTCPMessageType.BanList -> handler.messageBanList(msg as Mumble.BanList)
            HumlaTCPMessageType.Reject -> handler.messageReject(msg as Mumble.Reject)
            HumlaTCPMessageType.ServerSync -> handler.messageServerSync(msg as Mumble.ServerSync)
            HumlaTCPMessageType.ServerConfig -> handler.messageServerConfig(msg as Mumble.ServerConfig)
            HumlaTCPMessageType.PermissionDenied -> handler.messagePermissionDenied(msg as Mumble.PermissionDenied)
            HumlaTCPMessageType.UDPTunnel -> handler.messageUDPTunnel(msg as Mumble.UDPTunnel)
            HumlaTCPMessageType.UserState -> handler.messageUserState(msg as Mumble.UserState)
            HumlaTCPMessageType.UserRemove -> handler.messageUserRemove(msg as Mumble.UserRemove)
            HumlaTCPMessageType.ChannelState -> handler.messageChannelState(msg as Mumble.ChannelState)
            HumlaTCPMessageType.ChannelRemove -> handler.messageChannelRemove(msg as Mumble.ChannelRemove)
            HumlaTCPMessageType.TextMessage -> handler.messageTextMessage(msg as Mumble.TextMessage)
            HumlaTCPMessageType.ACL -> handler.messageACL(msg as Mumble.ACL)
            HumlaTCPMessageType.QueryUsers -> handler.messageQueryUsers(msg as Mumble.QueryUsers)
            HumlaTCPMessageType.Ping -> handler.messagePing(msg as Mumble.Ping)
            HumlaTCPMessageType.CryptSetup -> handler.messageCryptSetup(msg as Mumble.CryptSetup)
            HumlaTCPMessageType.ContextAction -> handler.messageContextAction(msg as Mumble.ContextAction)
            HumlaTCPMessageType.ContextActionModify -> {
                val actionModify = msg as Mumble.ContextActionModify
                if (actionModify.operation == Mumble.ContextActionModify.Operation.Add)
                    handler.messageContextActionModify(actionModify)
                else if (actionModify.operation == Mumble.ContextActionModify.Operation.Remove)
                    handler.messageRemoveContextAction(actionModify)
            }
            HumlaTCPMessageType.Version -> handler.messageVersion(msg as Mumble.Version)
            HumlaTCPMessageType.UserList -> handler.messageUserList(msg as Mumble.UserList)
            HumlaTCPMessageType.PermissionQuery -> handler.messagePermissionQuery(msg as Mumble.PermissionQuery)
            HumlaTCPMessageType.CodecVersion -> handler.messageCodecVersion(msg as Mumble.CodecVersion)
            HumlaTCPMessageType.UserStats -> handler.messageUserStats(msg as Mumble.UserStats)
            HumlaTCPMessageType.RequestBlob -> handler.messageRequestBlob(msg as Mumble.RequestBlob)
            HumlaTCPMessageType.SuggestConfig -> handler.messageSuggestConfig(msg as Mumble.SuggestConfig)
            HumlaTCPMessageType.VoiceTarget -> handler.messageVoiceTarget(msg as Mumble.VoiceTarget)
            HumlaTCPMessageType.PluginDataTransmission ->
                handler.messagePluginDataTransmission(msg as Mumble.PluginDataTransmission)
            // No else: the when is exhaustive over HumlaTCPMessageType so a new
            // server message type fails compilation instead of dropping silently.
        }
    }

    /**
     * Reroutes UDP messages into the various responder methods of the passed handler.
     * @param handler Handler to notify.
     * @param data Raw UDP data of the message.
     * @param messageType The type of the message.
     */
    fun broadcastUDPMessage(handler: HumlaUDPMessageListener, data: ByteArray, messageType: HumlaUDPMessageType) {
        when (messageType) {
            HumlaUDPMessageType.UDPPing -> handler.messageUDPPing(data)
            HumlaUDPMessageType.UDPVoiceOpus -> handler.messageVoiceData(data, messageType)
            HumlaUDPMessageType.UDPVoiceCELTAlpha,
            HumlaUDPMessageType.UDPVoiceSpeex,
            HumlaUDPMessageType.UDPVoiceCELTBeta ->
                // Silently drop unsupported legacy codec packets (parity with upstream Mumble 1.5+)
                Unit
        }
    }

    /**
     * If the connection to the server was lost due to an error, return the exception.
     * @return An exception causing disconnect, or null if no error was recorded.
     */
    fun getError(): HumlaException? {
        return mError
    }

    interface HumlaConnectionListener {
        /**
         * Called when the socket to the remote server has opened.
         */
        fun onConnectionEstablished()

        /**
         * Called when the protocol handshake completes.
         */
        fun onConnectionSynchronized()

        /**
         * Called if the host's certificate failed verification.
         * Typically you would use this callback to prompt the user to authorize the certificate.
         * Note that {@link #onConnectionDisconnected(HumlaException)} will still be called.
         * @param chain The certificate chain which failed verification.
         * @param failure Why verification failed; drives the distinct UI states.
         * @param verifiedHost The post-SRV hostname verification ran against.
         */
        fun onConnectionHandshakeFailed(
            chain: Array<X509Certificate>?,
            failure: HandshakeFailure,
            verifiedHost: String?
        )

        /**
         * Called when the connection was lost. If the connection was terminated due to an error,
         * the error will be provided.
         * @param e The exception that caused termination, or null if the disconnect was clean.
         */
        fun onConnectionDisconnected(e: HumlaException?)

        /**
         * Called if the user should be notified of a connection-related warning.
         * @param warning A user-readable warning.
         */
        fun onConnectionWarning(warning: String?)
    }
}
