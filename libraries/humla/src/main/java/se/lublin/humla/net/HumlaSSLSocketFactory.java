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

import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class HumlaSSLSocketFactory {
    private static final String TAG = HumlaSSLSocketFactory.class.getName();

    /**
     * TLS versions this client negotiates (floor: TLS 1.2+). This is an
     * allow-list, intersected with what the runtime supports so pre-29
     * devices (no TLS 1.3) keep 1.2 instead of failing to connect.
     */
    private static final String[] TLS_PROTOCOLS_ALLOWED = {"TLSv1.2", "TLSv1.3"};

    private SSLContext mContext;
    private HumlaTrustManagerWrapper mTrustWrapper;

    public HumlaSSLSocketFactory(KeyStore keystore, String keystorePassword, String trustStorePath, String trustStorePassword, String trustStoreFormat) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException, NoSuchProviderException, IOException, CertificateException {
        mContext = SSLContext.getInstance("TLS");

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("X509");
        kmf.init(keystore, keystorePassword != null ? keystorePassword.toCharArray() : new char[0]);

        X509TrustManager pinnedTrustManager = null;
        KeyStore pinnedStore = null;
        if(trustStorePath != null) {
            KeyStore trustStore = KeyStore.getInstance(
                    trustStoreFormat != null ? trustStoreFormat : KeyStore.getDefaultType());
            try (FileInputStream fis = new FileInputStream(trustStorePath)) {
                trustStore.load(fis, trustStorePassword != null ? trustStorePassword.toCharArray() : null);
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            pinnedTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];
            pinnedStore = trustStore;
            Log.i(TAG, "Using custom trust store " + trustStorePath + " with system trust store");
        } else {
            Log.i(TAG, "Using system trust store");
        }
        mTrustWrapper = new HumlaTrustManagerWrapper(pinnedTrustManager, pinnedStore);

        mContext.init(kmf.getKeyManagers(), new TrustManager[] { mTrustWrapper }, null);
    }

    /**
     * Sets the hostname the next handshake is expected to identify. The factory
     * is created per {@link HumlaConnection#connect} call, so callers set this
     * once from {@link HumlaTCP} before {@code startHandshake()}.
     */
    public void setExpectedHost(String host) {
        mTrustWrapper.setExpectedHost(host);
        mTrustWrapper.setLastHandshakeFailure(HandshakeFailure.NONE);
    }

    public SSLSocket createSocket(String host, int port) throws IOException {
        return createSocket(host, port, 0);
    }

    /**
     * Intersects {@link #TLS_PROTOCOLS_ALLOWED} with the runtime's
     * supported protocols. Pure (no socket needed) so it is JVM-testable.
     *
     * @param supportedProtocols e.g. {@code SSLSocket.getSupportedProtocols()}
     * @return the preferred protocols present in {@code supportedProtocols},
     *         in preferred order; empty when none match or the input is null.
     */
    static String[] filterTlsProtocols(String[] supportedProtocols) {
        if (supportedProtocols == null) {
            return new String[0];
        }
        List<String> enabled = new ArrayList<>(TLS_PROTOCOLS_ALLOWED.length);
        for (String preferred : TLS_PROTOCOLS_ALLOWED) {
            for (String supported : supportedProtocols) {
                if (preferred.equals(supported)) {
                    enabled.add(preferred);
                    break;
                }
            }
        }
        return enabled.toArray(new String[0]);
    }

    public SSLSocket createSocket(String host, int port, int timeoutMs) throws IOException {
        // Always layer TLS over a connected plain socket so the hostname survives
        // for SNI and post-handshake verification on both paths. A direct
        // createSocket(InetAddress, port) would verify against the IP literal.
        Socket plainSocket = new Socket();
        try {
            plainSocket.connect(new InetSocketAddress(host, port), Math.max(timeoutMs, 0));
            SSLSocket sslSocket =
                    (SSLSocket) mContext.getSocketFactory().createSocket(plainSocket, host, port, true);
            // TLS 1.2+ floor. Protocols are not identity, so this
            // applies to every host including .onion (only endpoint
            // identification stays onion-exempt, below).
            String[] tlsProtocols = filterTlsProtocols(sslSocket.getSupportedProtocols());
            if (tlsProtocols.length == 0) {
                // Fail closed: never fall back to the runtime's default
                // protocol set, which may include TLS 1.0/1.1.
                throw new SSLHandshakeException("No TLS 1.2+ protocol supported by this runtime");
            }
            sslSocket.setEnabledProtocols(tlsProtocols);
            // Defense-in-depth only: the authoritative check is the manual
            // TlsHostnameVerifier pass in HumlaTCP, which honors TOFU pins and
            // the .onion pin-or-nothing path. Endpoint identification must not
            // run for .onion hosts — no public CA can vouch for them, and the
            // handshake would die before the pin check ever runs.
            if (!TlsHostnameVerifier.isOnionHost(host)) {
                SSLParameters params = sslSocket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(params);
            }
            return sslSocket;
        } catch (IOException | RuntimeException e) {
            try {
                plainSocket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * Gets the certificate chain of the remote host.
     * @return The remote server's certificate chain, or null if a connection has not reached handshake yet.
     */
    public X509Certificate[] getServerChain() {
        return mTrustWrapper.getServerChain();
    }

    /**
     * Why the last handshake failed identity verification. Valid after
     * {@code checkServerTrusted} throws; {@link HandshakeFailure#NONE} if the
     * last handshake passed. Consumed by the {@code onTLSHandshakeFailed}
     * listener's explicit {@code HandshakeFailure} parameter.
     */
    public HandshakeFailure getLastHandshakeFailure() {
        return mTrustWrapper.getLastHandshakeFailure();
    }

    /**
     * Whether the presented leaf's key matches the pin stored for the expected
     * host. Lets the post-handshake identity check honor TOFU pins without
     * demanding a SAN match on the pin path.
     */
    public boolean isPinnedLeaf(X509Certificate leaf) {
        return mTrustWrapper.isPinnedLeaf(leaf);
    }

    /** Records the handshake failure reason (e.g. post-handshake hostname mismatch). */
    public void setLastHandshakeFailure(HandshakeFailure failure) {
        mTrustWrapper.setLastHandshakeFailure(Objects.requireNonNull(failure));
    }

    /**
     * Wraps around a custom trust manager and stores the certificate chains that did not validate.
     * We can then send the chain to the user for manual validation.
     *
     * <p>The pinned trust store is scoped to the expected host: a certificate
     * pinned for host A is never accepted for host B. Acceptance on the pin
     * path compares the leaf SPKI so re-issuance with the same key survives.
     */
    private static class HumlaTrustManagerWrapper implements X509TrustManager {

        private X509TrustManager mDefaultTrustManager;
        private X509TrustManager mTrustManager;
        private KeyStore mPinnedStore;
        private volatile X509Certificate[] mServerChain;
        private String mExpectedHost;
        private HandshakeFailure mLastHandshakeFailure = HandshakeFailure.NONE;

        public HumlaTrustManagerWrapper(X509TrustManager trustManager, KeyStore pinnedStore) throws NoSuchAlgorithmException, KeyStoreException {
            TrustManagerFactory dmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            dmf.init((KeyStore) null);
            mDefaultTrustManager = (X509TrustManager) dmf.getTrustManagers()[0];
            mTrustManager = trustManager;
            mPinnedStore = pinnedStore;
        }

        public synchronized void setExpectedHost(String host) {
            mExpectedHost = host;
        }

        public synchronized void setLastHandshakeFailure(HandshakeFailure failure) {
            mLastHandshakeFailure = failure;
        }

        public synchronized HandshakeFailure getLastHandshakeFailure() {
            return mLastHandshakeFailure;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                mDefaultTrustManager.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                if(mTrustManager != null) mTrustManager.checkClientTrusted(chain, authType);
                else throw e;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            mServerChain = chain;
            try {
                mDefaultTrustManager.checkServerTrusted(chain, authType);
                setLastHandshakeFailure(HandshakeFailure.NONE);
                return;
            } catch (CertificateException defaultFailure) {
                if (mTrustManager == null || chain == null || chain.length == 0) {
                    setLastHandshakeFailure(HandshakeFailure.UNTRUSTED_ISSUER);
                    throw defaultFailure;
                }
                X509Certificate pinned = expectedPin(chain[0]);
                if (pinned == null) {
                    setLastHandshakeFailure(HandshakeFailure.UNTRUSTED_ISSUER);
                    throw defaultFailure;
                }
                if (HandshakeFailure.sameSpki(pinned, chain[0])) {
                    setLastHandshakeFailure(HandshakeFailure.NONE);
                    return;
                }
                setLastHandshakeFailure(HandshakeFailure.PIN_CHANGED);
                throw new CertificateException("Pinned certificate changed for host", defaultFailure);
            }
        }

        /**
         * Returns the certificate pinned for the expected host, or null when no
         * pin exists. The pinned store keys {@code alias = hostname}; only that
         * alias is ever consulted, so a pin for host A never authorizes host B.
         * Both sides canonicalize identically (lowercase, strip trailing dot).
         * Callers compare by SPKI, so re-issuance with the same key survives.
         */
        private X509Certificate expectedPin(X509Certificate leaf) {
            String host;
            synchronized (this) {
                host = TlsHostnameVerifier.canonicalizeHost(mExpectedHost);
            }
            if (mPinnedStore == null || host == null || leaf == null) {
                return null;
            }
            try {
                if (!mPinnedStore.containsAlias(host)) {
                    return null;
                }
                java.security.cert.Certificate pinned = mPinnedStore.getCertificate(host);
                return pinned instanceof X509Certificate ? (X509Certificate) pinned : null;
            } catch (KeyStoreException e) {
                return null;
            }
        }

        /** Whether the leaf's SPKI matches the expected host's stored pin. */
        private boolean isPinnedLeaf(X509Certificate leaf) {
            X509Certificate pinned = expectedPin(leaf);
            return pinned != null && HandshakeFailure.sameSpki(pinned, leaf);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return mDefaultTrustManager.getAcceptedIssuers();
        }

        public X509Certificate[] getServerChain() {
            return mServerChain;
        }
    }
}
