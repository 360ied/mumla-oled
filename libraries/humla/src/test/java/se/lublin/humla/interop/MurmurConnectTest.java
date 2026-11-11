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

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import se.lublin.humla.client.ClientError;
import se.lublin.humla.client.ClientState;
import se.lublin.humla.client.ConnectTarget;
import se.lublin.humla.client.HumlaClient;
import se.lublin.humla.client.Session;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.net.HandshakeFailure;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;

import java.security.cert.X509Certificate;

/**
 * Step-3 murmur interop scenarios driving {@link HumlaClient} directly.
 *
 * <p>Plain JVM (no Robolectric): the client runs on real dispatchers and
 * polls StateFlows with timeouts. Forced TCP throughout, so no UDP voice
 * is exercised here; voice between two clients arrives with step 5a.
 */
@Category(MurmurInterop.class)
public class MurmurConnectTest {
    private MurmurTestServer server;
    private final List<HumlaClientHandle> open = new ArrayList<>();
    private final List<Path> tempStores = new ArrayList<>();

    @Before
    public void startServer() throws Exception {
        server = MurmurTestServer.start();
    }

    @After
    public void tearDown() {
        for (HumlaClientHandle h : open) {
            try {
                h.disconnectAndClose();
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
        KeyStore ks = KeyStore.getInstance(InteropClient.TRUST_FORMAT);
        ks.load(null, null);
        ks.setCertificateEntry("127.0.0.1", cert);
        Path store = Files.createTempFile("murmur-interop-trust-", ".jks");
        tempStores.add(store);
        // The password String constant itself stays in memory (as does
        // TrustStoreRef's copy); zeroing only clears this transient array.
        // Accepted as test hygiene, not a security boundary.
        char[] pw = InteropClient.TRUST_PASSWORD.toCharArray();
        try (var out = Files.newOutputStream(store)) {
            ks.store(out, pw);
        } finally {
            Arrays.fill(pw, '\0');
        }
        return store.toString();
    }

    private static IHumlaObserver noopObserver() {
        return new IHumlaObserver() {
            @Override public void onConnected() {}
            @Override public void onConnecting() {}
            @Override public void onDisconnected(HumlaException e) {}
            @Override public void onTLSHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure, String verifiedHost) {}
            @Override public void onChannelAdded(IChannel channel) {}
            @Override public void onChannelStateUpdated(IChannel channel) {}
            @Override public void onChannelRemoved(IChannel channel) {}
            @Override public void onChannelPermissionsUpdated(IChannel channel) {}
            @Override public void onUserConnected(IUser user) {}
            @Override public void onUserStateUpdated(IUser user) {}
            @Override public void onUserTalkStateUpdated(IUser user) {}
            @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {}
            @Override public void onUserRemoved(IUser user, String reason) {}
            @Override public void onPermissionDenied(String reason) {}
            @Override public void onMessageLogged(IMessage message) {}
            @Override public void onVoiceTargetChanged(VoiceTargetMode mode) {}
            @Override public void onBluetoothScoChanged(boolean active) {}
            @Override public void onLogInfo(String message) {}
            @Override public void onLogWarning(String message) {}
            @Override public void onLogError(String message) {}
        };
    }

    private HumlaClient connect(String username, String password) throws Exception {
        String trustStore = trustStoreForTestCert();
        ConnectTarget target = InteropClient.target(
                server.host(), server.port(), username, password, trustStore);
        HumlaClientHandle handle = InteropClient.create(noopObserver());
        open.add(handle);
        handle.getClient().connect(target);
        return handle.getClient();
    }

    @Test
    public void connectSyncDisconnect() throws Exception {
        HumlaClient client = connect("InteropAlice", null);
        Session session = InteropClient.awaitConnected(client, "sync");
        assertTrue(session.getState().getValue().getServer().getUsers().size() >= 1);
        client.disconnect();
        InteropClient.awaitIdle(client, "clean disconnect");
    }

    @Test
    public void twoClientsSeeEachOther() throws Exception {
        HumlaClient alice = connect("InteropAlice", null);
        Session aliceSession = InteropClient.awaitConnected(alice, "alice sync");

        HumlaClient bob = connect("InteropBob", null);
        InteropClient.awaitConnected(bob, "bob sync");
        InteropClient.awaitUser(aliceSession, "InteropBob", "alice sees bob join");
    }

    @Test
    public void channelTextReachesOtherClient() throws Exception {
        HumlaClient alice = connect("InteropAlice", null);
        Session aliceSession = InteropClient.awaitConnected(alice, "alice sync");

        final CountDownLatch gotText = new CountDownLatch(1);
        IHumlaObserver bobObserver = noopObserver();
        // Wrap to capture text: delegate everything, count down on the hello.
        IHumlaObserver capturing = new IHumlaObserver() {
            @Override public void onConnected() { bobObserver.onConnected(); }
            @Override public void onConnecting() { bobObserver.onConnecting(); }
            @Override public void onDisconnected(HumlaException e) { bobObserver.onDisconnected(e); }
            @Override public void onTLSHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure, String verifiedHost) { bobObserver.onTLSHandshakeFailed(chain, failure, verifiedHost); }
            @Override public void onChannelAdded(IChannel channel) { bobObserver.onChannelAdded(channel); }
            @Override public void onChannelStateUpdated(IChannel channel) { bobObserver.onChannelStateUpdated(channel); }
            @Override public void onChannelRemoved(IChannel channel) { bobObserver.onChannelRemoved(channel); }
            @Override public void onChannelPermissionsUpdated(IChannel channel) { bobObserver.onChannelPermissionsUpdated(channel); }
            @Override public void onUserConnected(IUser user) { bobObserver.onUserConnected(user); }
            @Override public void onUserStateUpdated(IUser user) { bobObserver.onUserStateUpdated(user); }
            @Override public void onUserTalkStateUpdated(IUser user) { bobObserver.onUserTalkStateUpdated(user); }
            @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) { bobObserver.onUserJoinedChannel(user, newChannel, oldChannel); }
            @Override public void onUserRemoved(IUser user, String reason) { bobObserver.onUserRemoved(user, reason); }
            @Override public void onPermissionDenied(String reason) { bobObserver.onPermissionDenied(reason); }
            @Override public void onMessageLogged(IMessage message) {
                if (message != null && "interop-hello".equals(message.getMessage())) {
                    gotText.countDown();
                }
            }
            @Override public void onVoiceTargetChanged(VoiceTargetMode mode) { bobObserver.onVoiceTargetChanged(mode); }
            @Override public void onBluetoothScoChanged(boolean active) { bobObserver.onBluetoothScoChanged(active); }
            @Override public void onLogInfo(String message) { bobObserver.onLogInfo(message); }
            @Override public void onLogWarning(String message) { bobObserver.onLogWarning(message); }
            @Override public void onLogError(String message) { bobObserver.onLogError(message); }
        };
        String trustStore = trustStoreForTestCert();
        ConnectTarget bobTarget = InteropClient.target(
                server.host(), server.port(), "InteropBob", null, trustStore);
        HumlaClientHandle bobHandle = InteropClient.create(capturing);
        open.add(bobHandle);
        bobHandle.getClient().connect(bobTarget);
        InteropClient.awaitConnected(bobHandle.getClient(), "bob sync");

        // Both clients land in the root channel (id 0) by default.
        aliceSession.sendText(
                java.util.Collections.singletonList(0),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                "interop-hello");
        assertTrue("timed out waiting for text message",
                gotText.await(30, TimeUnit.SECONDS));
    }

    @Test
    public void connectWithServerPassword() throws Exception {
        server.stop();
        server = MurmurTestServer.start(true);

        HumlaClient client = connect("InteropAlice", server.serverPassword());
        InteropClient.awaitConnected(client, "sync");
        client.disconnect();
        InteropClient.awaitIdle(client, "clean disconnect");
    }

    @Test
    public void wrongServerPasswordIsRejected() throws Exception {
        server.stop();
        server = MurmurTestServer.start(true);

        HumlaClient client = connect("InteropAlice", "wrong-password");
        ClientState.Failed failed = InteropClient.awaitFailed(client, "reject");
        assertTrue("expected a Reject failure, got " + failed.getError(),
                failed.getError() instanceof ClientError.Rejected);
    }
}
