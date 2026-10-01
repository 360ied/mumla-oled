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

package se.lublin.mumla.util;

import android.os.Build;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS factory for pinned image fetches: the TCP connection goes to a
 * pre-checked IP while SNI presents — and verification checks — the
 * original hostname. Verification is always the platform default
 * verifier; only the *name* it checks is overridden. Never returns true.
 */
public final class PinnedTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory mDelegate =
            (SSLSocketFactory) SSLSocketFactory.getDefault();
    private final String mSniHostname;

    public PinnedTlsSocketFactory(String sniHostname) {
        mSniHostname = sniHostname;
    }

    /** Verifier that checks the session against the original hostname, not the pinned IP. */
    public static HostnameVerifier verifierFor(final String originalHost) {
        final HostnameVerifier platform = HttpsURLConnection.getDefaultHostnameVerifier();
        return (hostname, session) -> platform.verify(originalHost, session);
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
            throws IOException {
        SSLSocket socket = (SSLSocket) mDelegate.createSocket(s, host, port, autoClose);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(mSniHostname)));
            socket.setSSLParameters(params);
        }
        // Below N (minSdk 21): SNIHostName is unavailable, so no SNI override — the handshake
        // either succeeds on the server's default cert or fails closed. Never insecure.
        return socket;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return mDelegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return mDelegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return mDelegate.createSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return mDelegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return mDelegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return mDelegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
            int localPort) throws IOException {
        return mDelegate.createSocket(address, port, localAddress, localPort);
    }
}
