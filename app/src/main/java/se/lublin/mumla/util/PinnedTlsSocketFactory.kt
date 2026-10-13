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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.util

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.Objects
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS factory for pinned image fetches: the TCP connection goes to a
 * pre-checked IP while SNI presents — and verification checks — the
 * original hostname. Verification is always the platform default
 * verifier; only the *name* it checks is overridden. It never blindly
 * returns true; every verdict is the platform verifier's.
 */
class PinnedTlsSocketFactory(sniHostname: String?) : SSLSocketFactory() {
    private val mDelegate: SSLSocketFactory =
        SSLSocketFactory.getDefault() as SSLSocketFactory
    // NPE-exact: the original dereferenced sniHostname unconditionally.
    private val mSniHostname: String = sniHostname ?: throw NullPointerException("sniHostname")

    companion object {
        /** Verifier that checks the session against the original hostname, not the pinned IP. */
        @JvmStatic
        fun verifierFor(originalHost: String?): HostnameVerifier {
            Objects.requireNonNull(originalHost, "originalHost")
            val platform = HttpsURLConnection.getDefaultHostnameVerifier()
            return HostnameVerifier { _, session -> platform.verify(originalHost, session) }
        }
    }

    @Throws(IOException::class)
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val socket = mDelegate.createSocket(s, host, port, autoClose) as SSLSocket
        try {
            val params: SSLParameters = socket.sslParameters
            params.serverNames = Collections.singletonList(SNIHostName(mSniHostname))
            socket.sslParameters = params
        } catch (ignored: IllegalArgumentException) {
            // IP-literal or otherwise invalid SNI name: send no SNI. The handshake
            // then succeeds only on a matching default cert or fails closed.
        }
        return socket
    }

    // Non-layered overloads delegate without SNI: only the layered overload above
    // carries it. HttpsURLConnection always uses the layered path; any other use
    // succeeds only on a matching default cert, else fails.
    override fun getDefaultCipherSuites(): Array<String> {
        return mDelegate.defaultCipherSuites
    }

    override fun getSupportedCipherSuites(): Array<String> {
        return mDelegate.supportedCipherSuites
    }

    @Throws(IOException::class)
    override fun createSocket(): Socket {
        return mDelegate.createSocket()
    }

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int): Socket {
        return mDelegate.createSocket(host, port)
    }

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return mDelegate.createSocket(host, port, localHost, localPort)
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return mDelegate.createSocket(host, port)
    }

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return mDelegate.createSocket(address, port, localAddress, localPort)
    }
}
