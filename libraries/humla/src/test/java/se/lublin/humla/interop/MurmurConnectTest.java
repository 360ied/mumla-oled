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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.interop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import se.lublin.humla.Constants;
import se.lublin.humla.net.HandshakeFailure;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaTCPMessageType;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protocol.HumlaTCPMessageListener;
import se.lublin.humla.util.HumlaException;

/**
 * Step-1 murmur interop scenarios against today's {@link HumlaConnection}.
 *
 * <p>Runs under Robolectric's paused main looper: {@link HumlaConnection}
 * posts all callbacks through the main handler, so each wait pumps the
 * looper. From step 3 on this class drives {@code HumlaClient} directly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@Category(MurmurInterop.class)
public class MurmurConnectTest {
    private MurmurTestServer server;
    private final List<HumlaConnection> open = new ArrayList<>();
    private final List<Path> tempStores = new ArrayList<>();

    @Before
    public void startServer() throws Exception {
        server = MurmurTestServer.start();
    }

    @After
    public void tearDown() {
        for (HumlaConnection c : open) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        open.clear();
        if (server != null) {
            server.stop();
            server = null;
        }
        for (Path p : tempStores) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }
        tempStores.clear();
    }

    /** Trusts the committed loopback test cert via a temp JKS. */
    private String trustStoreForTestCert() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream in = getClass().getResourceAsStream("/murmur/test-cert.pem")) {
            if (in == null) {
                throw new IllegalStateException("test resource missing: /murmur/test-cert.pem");
            }
            cert = cf.generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        // The app trust store keys pins by hostname (alias = host), so the
        // loopback cert must be stored under the host we connect to.
        ks.setCertificateEntry("127.0.0.1", cert);
        Path store = Files.createTempFile("murmur-interop-trust-", ".jks");
        char[] pw = "interop".toCharArray();
        try (var out = Files.newOutputStream(store)) {
            ks.store(out, pw);
        } finally {
            Arrays.fill(pw, '\0');
        }
        tempStores.add(store);
        return store.toString();
    }

    private static final class ConnectionState {
        final CountDownLatch established = new CountDownLatch(1);
        final CountDownLatch synced = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicReference<HumlaException> disconnectError = new AtomicReference<>();
        final AtomicReference<HandshakeFailure> handshakeFailure = new AtomicReference<>();
    }

    private HumlaConnection connect(String username, String password, ConnectionState state) throws Exception {
        String trustStore = trustStoreForTestCert();
        AtomicReference<HumlaConnection> slot = new AtomicReference<>();
        HumlaConnection.HumlaConnectionListener listener = new HumlaConnection.HumlaConnectionListener() {
            @Override
            public void onConnectionEstablished() {
                state.established.countDown();
                // Mimic HumlaService.onConnectionEstablished (Version +
                // Authenticate with username, password, and opus; no access
                // tokens are configured against the interop server).
                // Diverges deliberately from the service in one way: the
                // service also resolves the server row and forwards tokens.
                HumlaConnection conn = slot.get();
                if (conn != null) {
                    conn.sendTCPMessage(Constants.createVersionMessage("MumlaOLED-interop").build(),
                            HumlaTCPMessageType.Version);
                    Mumble.Authenticate.Builder auth = Mumble.Authenticate.newBuilder()
                            .setUsername(username)
                            .setOpus(true);
                    if (password != null) {
                        auth.setPassword(password);
                    }
                    conn.sendTCPMessage(auth.build(), HumlaTCPMessageType.Authenticate);
                }
            }

            @Override
            public void onConnectionSynchronized() {
                state.synced.countDown();
            }

            @Override
            public void onConnectionHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure,
                    String verifiedHost) {
                state.handshakeFailure.set(failure);
            }

            @Override
            public void onConnectionDisconnected(HumlaException e) {
                state.disconnectError.set(e);
                state.disconnected.countDown();
            }

            @Override
            public void onConnectionWarning(String warning) {
            }
        };
        HumlaConnection conn = new HumlaConnection(listener);
        slot.set(conn);
        conn.setTrustStore(trustStore, "interop", "JKS");
        conn.setForceTCP(true);
        open.add(conn);
        conn.connect(server.host(), server.port());
        return conn;
    }

    private static void await(CountDownLatch latch, String what, ConnectionState... states) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (latch.getCount() > 0) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (latch.await(100, TimeUnit.MILLISECONDS)) {
                return;
            }
            // A peer-dependent latch (both clients' states) fails fast with
            // whichever side broke, instead of burning the deadline and
            // reporting the wrong connection's empty state.
            for (ConnectionState s : states) {
                if (s.handshakeFailure.get() != null) {
                    fail("handshake failed while waiting for " + what + ": " + s.handshakeFailure.get());
                }
                if (s.disconnected.getCount() == 0 && latch != s.disconnected) {
                    fail("disconnected while waiting for " + what + ": " + s.disconnectError.get());
                }
            }
            if (System.nanoTime() > deadline) {
                StringBuilder detail = new StringBuilder();
                for (ConnectionState s : states) {
                    detail.append("[handshakeFailure=").append(s.handshakeFailure.get())
                            .append(" disconnectError=").append(s.disconnectError.get()).append("]");
                }
                fail("timed out waiting for " + what + "; " + detail);
            }
        }
    }

    @Test
    public void connectSyncDisconnect() throws Exception {
        ConnectionState state = new ConnectionState();
        HumlaConnection conn = connect("InteropAlice", null, state);
        await(state.established, "established", state);
        await(state.synced, "sync", state);
        assertTrue(conn.isSynchronized());
        conn.disconnect();
        await(state.disconnected, "clean disconnect", state);
    }

    @Test
    public void twoClientsSeeEachOther() throws Exception {
        ConnectionState a = new ConnectionState();
        HumlaConnection alice = connect("InteropAlice", null, a);
        await(a.synced, "alice sync", a);

        CountDownLatch sawBob = new CountDownLatch(1);
        AtomicReference<String> seenName = new AtomicReference<>();
        alice.addTCPMessageHandlers(new HumlaTCPMessageListener.Stub() {
            @Override
            public void messageUserState(Mumble.UserState msg) {
                if (msg.hasName() && msg.getName().equals("InteropBob")) {
                    seenName.set(msg.getName());
                    sawBob.countDown();
                }
            }
        });

        ConnectionState b = new ConnectionState();
        HumlaConnection bob = connect("InteropBob", null, b);
        await(b.synced, "bob sync", a, b);
        await(sawBob, "alice sees bob join", a, b);
        assertEquals("InteropBob", seenName.get());
    }

    @Test
    public void channelTextReachesOtherClient() throws Exception {
        ConnectionState a = new ConnectionState();
        HumlaConnection alice = connect("InteropAlice", null, a);
        await(a.synced, "alice sync", a);

        ConnectionState b = new ConnectionState();
        HumlaConnection bob = connect("InteropBob", null, b);
        await(b.synced, "bob sync", b);

        CountDownLatch gotText = new CountDownLatch(1);
        AtomicReference<String> gotMessage = new AtomicReference<>();
        bob.addTCPMessageHandlers(new HumlaTCPMessageListener.Stub() {
            @Override
            public void messageTextMessage(Mumble.TextMessage msg) {
                if ("interop-hello".equals(msg.getMessage())) {
                    gotMessage.set(msg.getMessage());
                    gotText.countDown();
                }
            }
        });

        // Both clients land in the root channel (id 0) by default.
        Mumble.TextMessage text = Mumble.TextMessage.newBuilder()
                .addChannelId(0)
                .setMessage("interop-hello")
                .build();
        alice.sendTCPMessage(text, HumlaTCPMessageType.TextMessage);
        await(gotText, "text message", a, b);
        assertEquals("interop-hello", gotMessage.get());
    }

    @Test
    public void connectWithServerPassword() throws Exception {
        server.stop();
        server = MurmurTestServer.start(true);

        ConnectionState state = new ConnectionState();
        HumlaConnection conn = connect("InteropAlice", server.serverPassword(), state);
        await(state.established, "established", state);
        await(state.synced, "sync", state);
        assertTrue(conn.isSynchronized());
        conn.disconnect();
        await(state.disconnected, "clean disconnect", state);
    }

    @Test
    public void wrongServerPasswordIsRejected() throws Exception {
        server.stop();
        server = MurmurTestServer.start(true);

        ConnectionState state = new ConnectionState();
        connect("InteropAlice", "wrong-password", state);
        await(state.disconnected, "reject disconnect", state);
        HumlaException error = state.disconnectError.get();
        assertTrue("expected a Reject failure, got " + error, error != null
                && error.getReason() == HumlaException.HumlaDisconnectReason.REJECT);
    }
}
