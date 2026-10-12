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
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.Objects
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

open class HumlaSSLSocketFactory {
    companion object {
        private val TAG = HumlaSSLSocketFactory::class.java.name

        /**
         * TLS versions this client negotiates (floor: TLS 1.2+). This is an
         * allow-list, intersected with what the runtime supports so pre-29
         * devices (no TLS 1.3) keep 1.2 instead of failing to connect.
         */
        private val TLS_PROTOCOLS_ALLOWED = arrayOf("TLSv1.2", "TLSv1.3")

        /**
         * Intersects {@link #TLS_PROTOCOLS_ALLOWED} with the runtime's
         * supported protocols. Pure (no socket needed) so it is JVM-testable.
         *
         * @param supportedProtocols e.g. {@code SSLSocket.getSupportedProtocols()}
         * @return the preferred protocols present in {@code supportedProtocols},
         *         in preferred order; empty when none match or the input is null.
         */
        // Widened to public: Kotlin has no package visibility; JVM tests pin this.
        @JvmStatic
        fun filterTlsProtocols(supportedProtocols: Array<String>?): Array<String> {
            if (supportedProtocols == null) {
                return arrayOf()
            }
            val enabled = ArrayList<String>(TLS_PROTOCOLS_ALLOWED.size)
            for (preferred in TLS_PROTOCOLS_ALLOWED) {
                for (supported in supportedProtocols) {
                    if (preferred == supported) {
                        enabled.add(preferred)
                        break
                    }
                }
            }
            return enabled.toTypedArray()
        }
    }

    private var mContext: SSLContext
    private lateinit var mTrustWrapper: HumlaTrustManagerWrapper

    @Throws(
        NoSuchAlgorithmException::class,
        KeyManagementException::class,
        KeyStoreException::class,
        UnrecoverableKeyException::class,
        NoSuchProviderException::class,
        IOException::class,
        CertificateException::class
    )
    constructor(
        keystore: KeyStore?,
        keystorePassword: String?,
        trustStorePath: String?,
        trustStorePassword: String?,
        trustStoreFormat: String?
    ) {
        mContext = SSLContext.getInstance("TLS")

        val kmf = KeyManagerFactory.getInstance("X509")
        kmf.init(keystore, keystorePassword?.toCharArray() ?: CharArray(0))

        var pinnedTrustManager: X509TrustManager? = null
        var pinnedStore: KeyStore? = null
        if (trustStorePath != null) {
            val trustStore = KeyStore.getInstance(
                trustStoreFormat ?: KeyStore.getDefaultType()
            )
            FileInputStream(trustStorePath).use { fis ->
                trustStore.load(fis, trustStorePassword?.toCharArray())
            }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trustStore)
            pinnedTrustManager = tmf.trustManagers[0] as X509TrustManager
            pinnedStore = trustStore
            Log.i(TAG, "Using custom trust store $trustStorePath with system trust store")
        } else {
            Log.i(TAG, "Using system trust store")
        }
        mTrustWrapper = HumlaTrustManagerWrapper(pinnedTrustManager, pinnedStore)

        mContext.init(kmf.keyManagers, arrayOf<TrustManager>(mTrustWrapper), null)
    }

    /**
     * Test-only seam: wraps a prebuilt context, skipping keystore and trust-store
     * init (Android runtimes provide KeyManagerFactory algorithms the JVM test
     * runtime lacks). {@code createSocket} touches only {@code mContext} through
     * the {@link #layerTlsSocket} seam, which tests override; every other method
     * requires the public constructor. Never used in production.
     */
    // Widened to public: Kotlin has no package visibility; JVM tests subclass this.
    constructor(context: SSLContext) {
        mContext = context
    }

    /**
     * Sets the hostname the next handshake is expected to identify. The factory
     * is created per {@link HumlaConnection#connect} call, so callers set this
     * once from {@link HumlaTCP} before {@code startHandshake()}.
     */
    fun setExpectedHost(host: String?) {
        mTrustWrapper.setExpectedHost(host)
        mTrustWrapper.setLastHandshakeFailure(HandshakeFailure.NONE)
    }

    @Throws(IOException::class)
    fun createSocket(host: String, port: Int): SSLSocket {
        return createSocket(host, port, 0)
    }

    @Throws(IOException::class)
    fun createSocket(host: String, port: Int, timeoutMs: Int): SSLSocket {
        // Always layer TLS over a connected plain socket so the hostname survives
        // for SNI and post-handshake verification on both paths. A direct
        // createSocket(InetAddress, port) would verify against the IP literal.
        val plainSocket = createPlainSocket(host, port, timeoutMs)
        var sslSocket: SSLSocket? = null
        try {
            sslSocket = layerTlsSocket(plainSocket, host, port)
            // TLS 1.2+ floor. Protocols are not identity, so this
            // applies to every host including .onion (only endpoint
            // identification stays onion-exempt, below).
            val tlsProtocols = filterTlsProtocols(sslSocket.supportedProtocols)
            if (tlsProtocols.isEmpty()) {
                // Fail closed: never fall back to the runtime's default
                // protocol set, which may include TLS 1.0/1.1.
                throw SSLHandshakeException("No TLS 1.2+ protocol supported by this runtime")
            }
            sslSocket.enabledProtocols = tlsProtocols
            // Defense-in-depth only: the authoritative check is the manual
            // TlsHostnameVerifier pass in HumlaTCP, which honors TOFU pins and
            // the .onion pin-or-nothing path. Endpoint identification must not
            // run for .onion hosts — no public CA can vouch for them, and the
            // handshake would die before the pin check ever runs.
            if (!TlsHostnameVerifier.isOnionHost(host)) {
                val params: SSLParameters = sslSocket.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                sslSocket.sslParameters = params
            }
            return sslSocket
        } catch (e: IOException) {
            // Close the layered socket when layering succeeded: with autoClose it
            // owns the transport and cascading covers the plain socket. Close the
            // plain socket only when layering never produced one.
            try {
                if (sslSocket != null) {
                    sslSocket.close()
                } else {
                    plainSocket.close()
                }
            } catch (ignored: IOException) {
            }
            throw e
        } catch (e: RuntimeException) {
            try {
                if (sslSocket != null) {
                    sslSocket.close()
                } else {
                    plainSocket.close()
                }
            } catch (ignored: IOException) {
            }
            throw e
        }
    }

    /**
     * Creates and connects the plain socket underneath TLS. Separate seam so JVM
     * tests can exercise the fail-closed protocol floor without network I/O.
     */
    // Widened to public open: Kotlin has no package visibility; JVM tests override this.
    @Throws(IOException::class)
    open fun createPlainSocket(host: String, port: Int, timeoutMs: Int): Socket {
        val plainSocket = Socket()
        try {
            plainSocket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(0))
        } catch (e: IOException) {
            // connect() owns nothing on failure: release the FD acquired above.
            try {
                plainSocket.close()
            } catch (ignored: IOException) {
            }
            throw e
        } catch (e: RuntimeException) {
            try {
                plainSocket.close()
            } catch (ignored: IOException) {
            }
            throw e
        }
        return plainSocket
    }

    /**
     * Layers TLS over a connected plain socket. Separate seam so JVM tests can
     * supply a canned {@link SSLSocket}.
     */
    // Widened to public open: Kotlin has no package visibility; JVM tests override this.
    @Throws(IOException::class)
    open fun layerTlsSocket(plainSocket: Socket, host: String, port: Int): SSLSocket {
        return mContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
    }

    /**
     * Gets the certificate chain of the remote host.
     * @return The remote server's certificate chain, or null if a connection has not reached handshake yet.
     */
    fun getServerChain(): Array<X509Certificate>? {
        return mTrustWrapper.getServerChain()
    }

    /**
     * Why the last handshake failed identity verification. Valid after
     * {@code checkServerTrusted} throws; {@link HandshakeFailure#NONE} if the
     * last handshake passed. Consumed by the {@code onTLSHandshakeFailed}
     * listener's explicit {@code HandshakeFailure} parameter.
     */
    fun getLastHandshakeFailure(): HandshakeFailure {
        return mTrustWrapper.getLastHandshakeFailure()
    }

    /**
     * Whether the presented leaf's key matches the pin stored for the expected
     * host. Lets the post-handshake identity check honor TOFU pins without
     * demanding a SAN match on the pin path.
     */
    fun isPinnedLeaf(leaf: X509Certificate?): Boolean {
        return mTrustWrapper.isPinnedLeaf(leaf)
    }

    /** Records the handshake failure reason (e.g. post-handshake hostname mismatch). */
    fun setLastHandshakeFailure(failure: HandshakeFailure) {
        mTrustWrapper.setLastHandshakeFailure(Objects.requireNonNull(failure))
    }

    /**
     * Wraps around a custom trust manager and stores the certificate chains that did not validate.
     * We can then send the chain to the user for manual validation.
     *
     * <p>The pinned trust store is scoped to the expected host: a certificate
     * pinned for host A is never accepted for host B. Acceptance on the pin
     * path compares the leaf SPKI so re-issuance with the same key survives.
     */
    private class HumlaTrustManagerWrapper(
        private var mTrustManager: X509TrustManager?,
        private var mPinnedStore: KeyStore?
    ) : X509TrustManager {

        private var mDefaultTrustManager: X509TrustManager
        @Volatile
        private var mServerChain: Array<X509Certificate>? = null
        private var mExpectedHost: String? = null
        private var mLastHandshakeFailure: HandshakeFailure = HandshakeFailure.NONE

        init {
            val dmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            dmf.init(null as KeyStore?)
            mDefaultTrustManager = dmf.trustManagers[0] as X509TrustManager
        }

        @Synchronized
        fun setExpectedHost(host: String?) {
            mExpectedHost = host
        }

        @Synchronized
        fun setLastHandshakeFailure(failure: HandshakeFailure) {
            mLastHandshakeFailure = failure
        }

        @Synchronized
        fun getLastHandshakeFailure(): HandshakeFailure {
            return mLastHandshakeFailure
        }

        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                mDefaultTrustManager.checkClientTrusted(chain, authType)
            } catch (e: CertificateException) {
                if (mTrustManager != null) mTrustManager!!.checkClientTrusted(chain, authType)
                else throw e
            }
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            mServerChain = chain
            try {
                mDefaultTrustManager.checkServerTrusted(chain, authType)
                setLastHandshakeFailure(HandshakeFailure.NONE)
                return
            } catch (defaultFailure: CertificateException) {
                if (mTrustManager == null || chain.isEmpty()) {
                    setLastHandshakeFailure(HandshakeFailure.UNTRUSTED_ISSUER)
                    throw defaultFailure
                }
                val pinned = expectedPin(chain[0])
                if (pinned == null) {
                    setLastHandshakeFailure(HandshakeFailure.UNTRUSTED_ISSUER)
                    throw defaultFailure
                }
                if (HandshakeFailure.sameSpki(pinned, chain[0])) {
                    setLastHandshakeFailure(HandshakeFailure.NONE)
                    return
                }
                setLastHandshakeFailure(HandshakeFailure.PIN_CHANGED)
                throw CertificateException("Pinned certificate changed for host", defaultFailure)
            }
        }

        /**
         * Returns the certificate pinned for the expected host, or null when no
         * pin exists. The pinned store keys {@code alias = hostname}; only that
         * alias is ever consulted, so a pin for host A never authorizes host B.
         * Both sides canonicalize identically (lowercase, strip trailing dot).
         * Callers compare by SPKI, so re-issuance with the same key survives.
         */
        private fun expectedPin(leaf: X509Certificate?): X509Certificate? {
            val host: String?
            synchronized(this) {
                host = TlsHostnameVerifier.canonicalizeHost(mExpectedHost)
            }
            val store = mPinnedStore
            if (store == null || host == null || leaf == null) {
                return null
            }
            try {
                if (!store.containsAlias(host)) {
                    return null
                }
                val pinned = store.getCertificate(host)
                return pinned as? X509Certificate
            } catch (e: KeyStoreException) {
                return null
            }
        }

        /** Whether the leaf's SPKI matches the expected host's stored pin. */
        fun isPinnedLeaf(leaf: X509Certificate?): Boolean {
            val pinned = expectedPin(leaf)
            return pinned != null && HandshakeFailure.sameSpki(pinned, leaf)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return mDefaultTrustManager.acceptedIssuers
        }

        fun getServerChain(): Array<X509Certificate>? {
            return mServerChain
        }
    }
}
