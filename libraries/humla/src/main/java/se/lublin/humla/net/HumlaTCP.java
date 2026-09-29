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
import android.util.Patterns;
import com.google.protobuf.MessageLite;

import org.minidns.hla.ResolverApi;
import org.minidns.hla.SrvResolverResult;
import org.minidns.record.SRV;
import org.minidns.util.SrvUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

import se.lublin.humla.Constants;
import se.lublin.humla.util.HumlaException;

/**
 * Class to maintain and interface with the TCP connection to a Mumble server.
 * Parses Mumble protobuf packets according to the Mumble protocol specification.
 */
public class HumlaTCP extends HumlaNetworkThread {
    private static final String TAG = HumlaTCP.class.getName();
    public static final int CONNECT_TIMEOUT = 10000;
    /**
     * Read timeout for the established connection. The keepalive ping loop
     * (5 s bootstrap, 10 s steady state, server Ping reply to every client
     * Ping) keeps healthy connections fed well inside this window, so expiry
     * means the connection stopped producing frames past three steady-state
     * ping intervals — a stalled or malicious server, but also sleep, radio
     * stalls, or an overloaded server.
     */
    public static final int READ_TIMEOUT_MS = 30000;

    private final HumlaSSLSocketFactory mSocketFactory;
    private String mHost;
    private int mPort;
    private SSLSocket mTCPSocket;
    private DataInputStream mDataInput;
    private DataOutputStream mDataOutput;
    private boolean mRunning;
    private boolean mConnected;
    private TCPConnectionListener mListener;

    public HumlaTCP(HumlaSSLSocketFactory socketFactory) {
        mSocketFactory = socketFactory;
    }

    public void setTCPConnectionListener(TCPConnectionListener listener) {
        mListener = listener;
    }

    public void connect(String host, int port) throws ConnectException {
        if(mRunning) throw new ConnectException("TCP connection already established!");
        mHost = host;
        mPort = port;
        startThreads();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    public void run() {
        mRunning = true;
        try {
            if (mPort == 0) {
                if (!Patterns.IP_ADDRESS.matcher(mHost).matches() && !mHost.contains(":") && !TlsHostnameVerifier.isOnionHost(mHost)) {
                    try {
                        final String lookup = "_mumble._tcp." + mHost;
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        Future<SrvResolverResult> future = executor.submit(new Callable<SrvResolverResult>() {
                            @Override
                            public SrvResolverResult call() throws Exception {
                                return ResolverApi.INSTANCE.resolveSrv(lookup);
                            }
                        });
                        SrvResolverResult res = null;
                        try {
                            res = future.get(800, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException te) {
                            future.cancel(true);
                            Log.d(TAG, "SRV lookup timed out for " + lookup);
                        } finally {
                            executor.shutdownNow();
                        }
                        if (res != null && res.wasSuccessful()) {
                            Set<SRV> answers = res.getAnswersOrEmptySet();
                            if (answers != null && !answers.isEmpty()) {
                                List<SRV> srvs = SrvUtil.sortSrvRecords(answers);
                                for (SRV srv : srvs) {
                                    Log.d(TAG, "resolved " + lookup + " SRV: " + srv.toString());
                                    String target = srv.target.toString();
                                    if (target.endsWith(".")) {
                                        target = target.substring(0, target.length() - 1);
                                    }
                                    mHost = target;
                                    mPort = srv.port;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "SRV resolution failed for " + mHost + ": " + e);
                    }
                }
                if (mPort == 0) {
                    mPort = Constants.DEFAULT_PORT;
                }
            }

            Log.i(TAG, "Connecting to " + mHost + ":" + mPort);

            mSocketFactory.setExpectedHost(mHost);
            mTCPSocket = mSocketFactory.createSocket(mHost, mPort, CONNECT_TIMEOUT);

            mTCPSocket.setKeepAlive(true);
            mTCPSocket.setTcpNoDelay(true);
            mTCPSocket.setSoTimeout(CONNECT_TIMEOUT);
            mTCPSocket.startHandshake();
            Log.v(TAG, "Handshake completed; verifying server identity");

            if (!verifyServerIdentity()) {
                return;
            }


            mDataInput = new DataInputStream(mTCPSocket.getInputStream());
            mDataOutput = new DataOutputStream(mTCPSocket.getOutputStream());

            mTCPSocket.setSoTimeout(READ_TIMEOUT_MS);

            Log.v(TAG, "Now listening");
            mConnected = true;

            if(mListener != null) {
                executeOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onTCPConnectionEstablished();
                    }
                });
            }

            while(mConnected) {
                final short messageType = mDataInput.readShort();
                final int messageLength = mDataInput.readInt();
                // Validate before allocating: a malicious server controls both
                // fields. Violations abort the connection — the stream position
                // is mid-frame, so skipping would desync the parser.
                FrameValidator.FrameError frameError = FrameValidator.validateFrame(messageType, messageLength);
                if (frameError != FrameValidator.FrameError.NONE) {
                    Log.w(TAG, "Dropping connection on invalid frame: type=" + messageType
                            + " length=" + messageLength + " error=" + frameError);
                    error("Server sent an invalid protocol frame (" + frameError + ")", null);
                    return;
                }
                final byte[] data = new byte[messageLength];
                mDataInput.readFully(data);

                final HumlaTCPMessageType tcpMessageType = HumlaTCPMessageType.values()[messageType];
                if (mListener != null) {
                    executeOnMainThread(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onTCPMessageReceived(tcpMessageType, messageLength, data);
                        }
                    });
                }
            }
        } catch (SocketException e) {
            error("Could not open a connection to the host", e);
        } catch (SSLHandshakeException e) {
            // Try and verify certificate manually.
            if(mSocketFactory.getServerChain() != null && mListener != null) {
                if(!mRunning) return;
                final HandshakeFailure recorded = mSocketFactory.getLastHandshakeFailure();
                final HandshakeFailure failure = classifyHandshakeFailure(recorded);
                final String verifiedHost = mHost;
                executeOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onTLSHandshakeFailed(mSocketFactory.getServerChain(), failure, verifiedHost);
                    }
                });
            } else {
                error("Could not verify host certificate", e);
            }
        } catch (IOException e) {
            error("An error occurred when communicating with the host", e);
        } finally {
            mConnected = false;
            try {
                if (mDataInput != null) mDataInput.close();
                if (mDataOutput != null) mDataOutput.close();
                if (mTCPSocket != null) mTCPSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mRunning = false;

            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTCPConnectionDisconnect();
                }
            });
            stopThreads();
        }
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The message to send.
     * @param messageType The type of the message to send.
     */
    public void sendMessage(final MessageLite message, final HumlaTCPMessageType messageType) {
        executeOnSendThread(new Runnable() {
            @Override
            public void run() {
                if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                    Log.v(TAG, "OUT: " + messageType);
                try {
                    mDataOutput.writeShort(messageType.ordinal());
                    mDataOutput.writeInt(message.getSerializedSize());
                    message.writeTo(mDataOutput);
                    mDataOutput.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                    // TODO handle
                }
            }
        });
    }
    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The data to send.
     * @param length The length of the byte array.
     * @param messageType The type of the message to send.
     */
    public void sendMessage(final byte[] message, final int length, final HumlaTCPMessageType messageType) {
        final byte[] copy = Arrays.copyOf(message, length);
        executeOnSendThread(new Runnable() {
            @Override
            public void run() {
                if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                    Log.v(TAG, "OUT: " + messageType);
                try {
                    mDataOutput.writeShort(messageType.ordinal());
                    mDataOutput.writeInt(length);
                    mDataOutput.write(copy, 0, length);
                    mDataOutput.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Attempts to disconnect gracefully on the Tx thread.
     * Disconnects interrupt the socket listening on the Tx thread, suppressing any exceptions
     * caused by this request. Any remaining protobuf messages will be dispatched first.
     *
     * Suppresses all future errors on this connection.
     */
    public void disconnect() {
        if (!mRunning) return;

        mRunning = false;
        executeOnSendThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mTCPSocket != null)
                        mTCPSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if(mListener != null) {
            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTCPConnectionDisconnect();
                }
            });
        }
    }

    private void error(String desc, Exception e) {
        if (!mRunning)
            return; // Don't handle errors post-disconnection.
        final HumlaException ce = new HumlaException(desc, e,
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR);
        if(mListener != null)
            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTCPConnectionFailed(ce);
                }
            });
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
    private boolean verifyServerIdentity() {
        X509Certificate leaf = presentedLeaf();
        if (leaf == null) {
            Log.w(TAG, "No server certificate available; refusing connection");
            error("Could not verify server identity", null);
            closeSocket();
            return false;
        }
        String host = mHost;
        if (TlsHostnameVerifier.isOnionHost(host)) {
            return true;
        }
        if (mSocketFactory.isPinnedLeaf(leaf)) {
            return true;
        }
        if (TlsHostnameVerifier.verifyHostname(host, leaf)) {
            return true;
        }
        mSocketFactory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH);
        Log.w(TAG, "TLS hostname mismatch for " + host + "; refusing connection");
        closeSocket();
        if (mListener != null) {
            final String verifiedHost = mHost;
            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTLSHandshakeFailed(mSocketFactory.getServerChain(),
                            HandshakeFailure.HOSTNAME_MISMATCH, verifiedHost);
                }
            });
        }
        return false;
    }

    /** Best-effort leaf: wrapper chain first, then session peer certificates. */
    private X509Certificate presentedLeaf() {
        X509Certificate[] chain = mSocketFactory.getServerChain();
        if (chain != null && chain.length > 0 && chain[0] != null) {
            return chain[0];
        }
        try {
            if (mTCPSocket != null && mTCPSocket.getSession() != null) {
                java.security.cert.Certificate[] peers = mTCPSocket.getSession().getPeerCertificates();
                if (peers != null) {
                    for (java.security.cert.Certificate peer : peers) {
                        if (peer instanceof X509Certificate) {
                            return (X509Certificate) peer;
                        }
                    }
                }
            }
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
        return null;
    }

    private void closeSocket() {
        try {
            if (mTCPSocket != null) {
                mTCPSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Maps a recorded trust-manager outcome to the dialog reason. A CA-valid
     * leaf that fails the SAN check (e.g. platform endpoint-ID rejection, or a
     * handshake that never reached the post-handshake verifier) is a
     * HOSTNAME_MISMATCH with no pin bypass — never an Allow-and-pin prompt.
     */
    private HandshakeFailure classifyHandshakeFailure(HandshakeFailure recorded) {
        if (recorded != null && recorded != HandshakeFailure.NONE) {
            return recorded;
        }
        X509Certificate[] chain = mSocketFactory.getServerChain();
        X509Certificate leaf = chain != null && chain.length > 0 ? chain[0] : null;
        if (leaf != null && !TlsHostnameVerifier.isOnionHost(mHost)
                && !TlsHostnameVerifier.verifyHostname(mHost, leaf)) {
            mSocketFactory.setLastHandshakeFailure(HandshakeFailure.HOSTNAME_MISMATCH);
            return HandshakeFailure.HOSTNAME_MISMATCH;
        }
        return HandshakeFailure.UNTRUSTED_ISSUER;
    }

    public interface TCPConnectionListener {
        public void onTCPConnectionEstablished();
        public void onTLSHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure, String verifiedHost);
        public void onTCPConnectionFailed(HumlaException e);
        public void onTCPConnectionDisconnect();
        public void onTCPMessageReceived(HumlaTCPMessageType type, int length, byte[] data);
    }
}
