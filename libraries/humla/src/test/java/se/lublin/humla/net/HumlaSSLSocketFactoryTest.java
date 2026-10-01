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

package se.lublin.humla.net;

import org.junit.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the TLS protocol floor helper.
 * Pure array in/out, no sockets, no Android APIs.
 */
public class HumlaSSLSocketFactoryTest {

    @Test
    public void modernRuntimeKeeps12And13() {
        String[] supported = {"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"};
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void pre29RuntimeKeeps12() {
        String[] supported = {"TLSv1", "TLSv1.1", "TLSv1.2"};
        assertArrayEquals(new String[]{"TLSv1.2"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void onlyTls13Supported() {
        assertArrayEquals(new String[]{"TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(new String[]{"TLSv1.3"}));
    }

    @Test
    public void legacyOnlyYieldsEmpty() {
        String[] supported = {"SSLv3", "TLSv1", "TLSv1.1"};
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(supported).length);
    }

    @Test
    public void nullAndEmptyYieldEmpty() {
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(null).length);
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(new String[0]).length);
    }

    @Test
    public void duplicateSupportedEntriesAreNotRepeated() {
        String[] supported = {"TLSv1.2", "TLSv1.2", "TLSv1.3", "TLSv1.3"};
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void resultFollowsAllowListOrder() {
        // Supported order must not leak through; output is deterministic.
        String[] supported = {"TLSv1.3", "TLSv1.2", "TLSv1.1"};
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void matchingIsCaseSensitive() {
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(new String[]{"tlsv1.2"}).length);
    }

    /** Records connect/close without touching the network. */
    private static class RecordingSocket extends Socket {
        boolean closed;

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    /** Canned TLS endpoint: supported protocols in, applied parameters out. */
    private static class CannedSslSocket extends SSLSocket {
        private final String[] mSupported;
        private String[] mEnabled = new String[0];
        private SSLParameters mParameters = new SSLParameters();
        private RecordingSocket mBacking;
        boolean closed;

        CannedSslSocket(String[] supported) {
            mSupported = supported.clone();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return new String[0];
        }

        @Override
        public void setEnabledCipherSuites(String[] strings) {
        }

        @Override
        public String[] getSupportedProtocols() {
            return mSupported.clone();
        }

        @Override
        public String[] getEnabledProtocols() {
            return mEnabled.clone();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            mEnabled = protocols.clone();
        }

        @Override
        public SSLSession getSession() {
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener handshakeCompletedListener) {
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener handshakeCompletedListener) {
        }

        @Override
        public void startHandshake() {
        }

        @Override
        public void setUseClientMode(boolean b) {
        }

        @Override
        public boolean getUseClientMode() {
            return true;
        }

        @Override
        public void setNeedClientAuth(boolean b) {
        }

        @Override
        public boolean getNeedClientAuth() {
            return false;
        }

        @Override
        public void setWantClientAuth(boolean b) {
        }

        @Override
        public boolean getWantClientAuth() {
            return false;
        }

        @Override
        public void setEnableSessionCreation(boolean b) {
        }

        @Override
        public boolean getEnableSessionCreation() {
            return true;
        }

        @Override
        public SSLParameters getSSLParameters() {
            return mParameters;
        }

        @Override
        public void setSSLParameters(SSLParameters params) {
            mParameters = params;
        }

        /** Models autoClose ownership: closing the layered socket closes the plain one. */
        void attach(RecordingSocket backing) {
            mBacking = backing;
        }

        @Override
        public void close() {
            closed = true;
            if (mBacking != null) {
                mBacking.close();
            }
        }
    }

    /** Exercises the real createSocket orchestration with canned sockets. */
    private static class TestableFactory extends HumlaSSLSocketFactory {
        final RecordingSocket plainSocket = new RecordingSocket();
        final CannedSslSocket sslSocket;

        TestableFactory(String[] supportedProtocols) throws Exception {
            super(SSLContext.getInstance("TLS"));
            sslSocket = new CannedSslSocket(supportedProtocols);
        }

        @Override
        Socket createPlainSocket(String host, int port, int timeoutMs) {
            return plainSocket;
        }

        @Override
        SSLSocket layerTlsSocket(Socket plainSocket, String host, int port) throws IOException {
            sslSocket.attach((RecordingSocket) plainSocket);
            return sslSocket;
        }
    }

    @Test
    public void createSocketFailsClosedAndClosesPlainSocketWithoutModernTls() throws Exception {
        TestableFactory factory = new TestableFactory(new String[]{"TLSv1", "TLSv1.1"});
        try {
            factory.createSocket("example.com", 64738, 0);
            fail("Expected SSLHandshakeException when no TLS 1.2+ protocol is available");
        } catch (SSLHandshakeException expected) {
        }
        assertTrue("Layered socket must be closed on the fail-closed path",
                factory.sslSocket.closed);
        assertTrue("Plain socket must be closed via the layered socket",
                factory.plainSocket.closed);
    }

    @Test
    public void createSocketAppliesProtocolFloorAndEndpointIdentification() throws Exception {
        TestableFactory factory = new TestableFactory(
                new String[]{"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"});
        SSLSocket socket = factory.createSocket("example.com", 64738, 0);
        assertSame(factory.sslSocket, socket);
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                factory.sslSocket.getEnabledProtocols());
        assertEquals("HTTPS", factory.sslSocket.getSSLParameters()
                .getEndpointIdentificationAlgorithm());
        assertFalse("Plain socket must stay open on success", factory.plainSocket.closed);
    }

    @Test
    public void createSocketSkipsEndpointIdentificationForOnion() throws Exception {
        TestableFactory factory = new TestableFactory(new String[]{"TLSv1.2", "TLSv1.3"});
        factory.createSocket("example1234567890.onion", 64738, 0);
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                factory.sslSocket.getEnabledProtocols());
        assertNull(factory.sslSocket.getSSLParameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    public void createSocketClosesPlainSocketWhenLayeringThrows() throws Exception {
        TestableFactory factory = new TestableFactory(new String[]{"TLSv1.2"}) {
            @Override
            SSLSocket layerTlsSocket(Socket plainSocket, String host, int port) throws IOException {
                throw new IOException("layer boom");
            }
        };
        try {
            factory.createSocket("example.com", 64738, 0);
            fail("Expected IOException from the TLS layer");
        } catch (IOException expected) {
        }
        assertTrue(factory.plainSocket.closed);
        assertFalse(factory.sslSocket.closed);
    }

    @Test
    public void onionHostStillFailsClosedWithoutModernTls() throws Exception {
        TestableFactory factory = new TestableFactory(new String[]{"TLSv1"});
        try {
            factory.createSocket("example1234567890.onion", 64738, 0);
            fail("Expected SSLHandshakeException even for onion hosts");
        } catch (SSLHandshakeException expected) {
        }
        assertTrue(factory.sslSocket.closed);
        assertTrue(factory.plainSocket.closed);
    }

    @Test
    public void uppercaseTrailingDotOnionSkipsEndpointIdentification() throws Exception {
        TestableFactory factory = new TestableFactory(new String[]{"TLSv1.2"});
        factory.createSocket("EXAMPLE1234567890.ONION.", 64738, 0);
        assertArrayEquals(new String[]{"TLSv1.2"}, factory.sslSocket.getEnabledProtocols());
        assertNull(factory.sslSocket.getSSLParameters().getEndpointIdentificationAlgorithm());
    }
}
