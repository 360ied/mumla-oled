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

import static org.junit.Assert.assertNotNull;
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
import se.lublin.humla.net.HumlaTCPMessageType;
import se.lublin.humla.protobuf.Mumble;
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
 * polls StateFlows with timeouts. Most scenarios force TCP; the ones that
 * pin the UDP/TCP route decision ({@code forcedTcpUsesTunnel},
 * {@code udpEnabledUsesUdp}, {@code udpBlockedFallsBackToTunnel},
 * {@code udpSocketFailureTunnelsImmediately}) ask for an explicit voice mode
 * instead. Voice media itself (codec, native pipeline) arrives with step 5a.
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
        return connect(username, password, InteropClient.Voice.FORCED_TCP);
    }

    private HumlaClientHandle connectHandle(
            String username, String password, InteropClient.Voice voice) throws Exception {
        String trustStore = trustStoreForTestCert();
        ConnectTarget target = InteropClient.target(
                server.host(), server.port(), username, password, trustStore,
                voice == InteropClient.Voice.FORCED_TCP);
        HumlaClientHandle handle = InteropClient.create(noopObserver(), voice);
        open.add(handle);
        handle.getClient().connect(target);
        return handle;
    }

    private HumlaClient connect(
            String username, String password, InteropClient.Voice voice) throws Exception {
        return connectHandle(username, password, voice).getClient();
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

    @Test
    public void kickLeadsToRemovedWithoutReconnect() throws Exception {
        HumlaClient alice = connect("InteropAlice", null);
        InteropClient.awaitConnected(alice, "alice sync");
        HumlaClient bob = connect("InteropBob", null);
        InteropClient.awaitConnected(bob, "bob sync");

        HumlaClient superuser = connect("SuperUser", server.superuserPassword());
        Session suSession = InteropClient.awaitConnected(superuser, "superuser sync");
        int bobSession = -1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (bobSession < 0 && System.nanoTime() < deadline) {
            for (var e : suSession.getState().getValue().getServer().getUsers().entrySet()) {
                if ("InteropBob".equals(e.getValue().getName())) {
                    bobSession = e.getKey();
                    break;
                }
            }
            if (bobSession < 0) Thread.sleep(100);
        }
        assertTrue("timed out waiting for superuser to see bob", bobSession >= 0);
        // Under load Bob's connection can drop and reconnect with a new
        // session id, or a single kick can go unprocessed; re-discover the
        // live id and re-kick until Bob reaches Failed (idempotent: a kick
        // for a stale id touches nobody).
        long kickDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < kickDeadline
                && !(bob.getState().getValue() instanceof ClientState.Failed)) {
            if (bob.getState().getValue() instanceof ClientState.Connected) {
                int liveBobSession = -1;
                for (var e : suSession.getState().getValue().getServer().getUsers().entrySet()) {
                    if ("InteropBob".equals(e.getValue().getName())) {
                        liveBobSession = e.getKey();
                        break;
                    }
                }
                if (liveBobSession >= 0) {
                    suSession.kickBanUser(liveBobSession, "interop-kick", false);
                }
            }
            Thread.sleep(1000);
        }

        ClientState.Failed failed = InteropClient.awaitFailed(bob, "bob kicked");
        assertTrue("expected Removed, got " + failed.getError(),
                failed.getError() instanceof ClientError.Removed);
        // Kicks never reconnect: the client must stay Failed across the
        // first reconnect slot. The policy retries 1 s after a failure (then
        // 3 s, 6 s, …), so a resurrect-after-Removed regression would surface
        // inside this 3 s window; later attempts only widen the margin.
        Thread.sleep(3000);
        assertTrue("kicked client must not reconnect, got " + bob.getState().getValue(),
                bob.getState().getValue() instanceof ClientState.Failed);
    }

    @Test
    public void serverRestartReconnects() throws Exception {
        HumlaClientHandle aliceHandle = connectHandle(
                "InteropAlice", null, InteropClient.Voice.FORCED_TCP);
        HumlaClient alice = aliceHandle.getClient();
        InteropClient.awaitConnected(alice, "alice sync");
        server.restart();
        InteropClient.awaitReconnecting(alice, "restart triggers reconnect");
        Session recovered = InteropClient.awaitConnected(alice, "reconnect after restart");
        assertTrue(recovered.getState().getValue().getServer().getUsers().size() >= 1);
        // Done-when (d): the restarted session carries voice afterward.
        // Bob joins after the restart and sends synthetic PCM through the
        // production encode + tunnel path; Alice renders it through the
        // shared native engine. Mirrors tunnelVoicePlaysThroughNativeRender.
        HumlaClientHandle bobHandle = connectHandle(
                "InteropBob", null, InteropClient.Voice.FORCED_TCP);
        Session bobSession = InteropClient.awaitConnected(
                bobHandle.getClient(), "bob sync after restart");
        final int bobId = bobSession.getSessionId();
        InteropClient.awaitUser(recovered, "InteropBob", "receiver sees sender after restart");
        final se.lublin.humla.voice.VoiceEngine receiverVoice = aliceHandle.getVoice();
        receiverVoice.setFilterForTests(new int[0], new int[]{bobId});
        // Step 5a.4: the sender drives the native capture entry directly;
        // the send thread tunnels through the live session transport (wired
        // in InteropClient), and the receiver renders the shared engine.
        final se.lublin.humla.voice.VoiceEngine senderVoice = bobHandle.getVoice();
        senderVoice.setFilterForTests(new int[0], new int[0]);
        short[] sine = new short[480];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = (short) (10000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0));
        }
        for (int i = 0; i < 30; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        short[] pcm = new short[960];
        int peak = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (peak <= 100 && System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > peak) peak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("restarted session must carry voice after reconnect, peak=" + peak,
                peak > 100);
        boolean sawSender = false;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sawSender && System.nanoTime() < deadline) {
            if (receiverVoice.getTalkActivity().getValue().getUsers().containsKey(bobId)) {
                sawSender = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue("notifier must report the sender talking after restart", sawSender);
    }

    @Test
    public void selfMuteDeafenEchoWithCoercion() throws Exception {
        HumlaClient alice = connect("InteropAlice", null);
        Session session = InteropClient.awaitConnected(alice, "alice sync");
        session.setSelfMuteDeaf(true, false);
        InteropClient.awaitSelfMute(session, "InteropAlice", true, false, "self-mute echo");
        // Deafen implies mute: a deaf-only request coerces to mute+deaf.
        session.setSelfMuteDeaf(false, true);
        InteropClient.awaitSelfMute(session, "InteropAlice", true, true, "deafen coercion echo");
        session.setSelfMuteDeaf(false, false);
        InteropClient.awaitSelfMute(session, "InteropAlice", false, false, "unmute echo");
    }

    @Test
    public void channelJoinAndMove() throws Exception {
        HumlaClient alice = connect("InteropAlice", null);
        Session aliceSession = InteropClient.awaitConnected(alice, "alice sync");
        HumlaClient bob = connect("InteropBob", null);
        Session bobSession = InteropClient.awaitConnected(bob, "bob sync");
        HumlaClient superuser = connect("SuperUser", server.superuserPassword());
        Session suSession = InteropClient.awaitConnected(superuser, "superuser sync");
        // SuperUser creates a subchannel under root; the server assigns its id.
        Mumble.ChannelState create = Mumble.ChannelState.newBuilder()
                .setParent(0).setName("interop-room").build();
        suSession.getTransport().send(create, HumlaTCPMessageType.ChannelState);
        int roomId = -1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (roomId < 0 && System.nanoTime() < deadline) {
            for (var e : aliceSession.getState().getValue().getServer().getChannels().entrySet()) {
                if ("interop-room".equals(e.getValue().getName())) {
                    roomId = e.getKey();
                    break;
                }
            }
            if (roomId < 0) Thread.sleep(100);
        }
        assertTrue("timed out waiting for interop-room", roomId >= 0);
        InteropClient.awaitChannel(bobSession, roomId, "bob sees room");
        // Alice joins; both clients must observe her in the room.
        aliceSession.joinChannel(roomId);
        InteropClient.awaitUserInChannel(aliceSession, "InteropAlice", roomId, "alice joined");
        InteropClient.awaitUserInChannel(bobSession, "InteropAlice", roomId, "bob sees alice move");
        // SuperUser moves Alice back to root.
        int aliceSessionId = -1;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (aliceSessionId < 0 && System.nanoTime() < deadline) {
            for (var e : suSession.getState().getValue().getServer().getUsers().entrySet()) {
                if ("InteropAlice".equals(e.getValue().getName())) {
                    aliceSessionId = e.getKey();
                    break;
                }
            }
            if (aliceSessionId < 0) Thread.sleep(100);
        }
        assertTrue("timed out waiting for superuser to see alice", aliceSessionId >= 0);
        suSession.moveUserToChannel(aliceSessionId, 0);
        InteropClient.awaitUserInChannel(aliceSession, "InteropAlice", 0, "alice moved back");
        InteropClient.awaitUserInChannel(bobSession, "InteropAlice", 0, "bob sees alice back");
    }

    @Test
    public void forcedTcpUsesTunnel() throws Exception {
        HumlaClient alice = connect("InteropAlice", null, InteropClient.Voice.FORCED_TCP);
        Session session = InteropClient.awaitConnected(alice, "alice sync");
        // Forced TCP never leaves tunnel mode: no flip to UDP while pings flow.
        InteropClient.awaitUsingUdp(session, false, "forced TCP uses tunnel");
        Thread.sleep(3000);
        assertTrue("forced TCP must stay tunnelled, got usingUdp=true",
                !session.getStats().getValue().getUsingUdp());
    }

    @Test
    public void udpEnabledUsesUdp() throws Exception {
        HumlaClient alice = connect("InteropAlice", null, InteropClient.Voice.UDP);
        Session session = InteropClient.awaitConnected(alice, "alice sync");
        InteropClient.awaitUsingUdp(session, true, "UDP enabled");
        // A measured UDP latency proves real ping round-trips, not just the seed.
        InteropClient.awaitUdpLatency(session, "UDP pings flow");
    }

    @Test
    public void udpBlockedFallsBackToTunnel() throws Exception {
        // Alice's voice never opens UDP, so the session never exchanges a good
        // packet: the only case the transport's 20 s zero-counter giveup covers
        // (once a counter is above zero the giveup is disabled by design, in
        // parity with upstream). Loopback offers no firewall seam and murmur
        // binds UDP on the TCP port, so a client-side closed socket is the
        // hermetic stand-in for wire-level blocking; it is equivalent at the
        // decision (an OR over the two counters), not identical, because a
        // blocked wire still lets the client send.
        HumlaClientHandle aliceHandle = connectHandle(
                "InteropAlice", null, InteropClient.Voice.UDP_SILENTLY_BLOCKED);
        HumlaClient alice = aliceHandle.getClient();
        Session session = InteropClient.awaitConnected(alice, "alice sync");

        final CountDownLatch gotText = new CountDownLatch(1);
        IHumlaObserver bobObserver = noopObserver();
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
                if (message != null && "tunnel-hello".equals(message.getMessage())) {
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
                server.host(), server.port(), "InteropBob", null, trustStore, false);
        HumlaClientHandle bobHandle = InteropClient.create(capturing, InteropClient.Voice.UDP);
        open.add(bobHandle);
        bobHandle.getClient().connect(bobTarget);
        InteropClient.awaitConnected(bobHandle.getClient(), "bob sync");

        // Baseline: the transport still presumes UDP (the seed) with no
        // traffic yet; the false reading below is the real assertion.
        assertTrue("transport must presume UDP before the giveup, got "
                        + session.getStats().getValue(),
                session.getStats().getValue().getUsingUdp());
        // With no good packets the transport must give up UDP (~20 s after
        // creation) and switch to tunnel mode; the session survives on the tunnel.
        InteropClient.awaitUsingUdp(session, false, "tunnel fallback", 60L);
        assertTrue(alice.getState().getValue() instanceof ClientState.Connected);
        // Data still flows post-fallback.
        session.sendText(
                java.util.Collections.singletonList(0),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                "tunnel-hello");
        assertTrue("timed out waiting for post-fallback text",
                gotText.await(30, TimeUnit.SECONDS));
    }

    @Test
    public void udpSocketFailureTunnelsImmediately() throws Exception {
        // voice.md: a socket that cannot be created or connected reports
        // UdpUnavailable and the transport tunnels at once, "as it would after
        // its 20 s rule". Timing is what separates the two triggers: the switch
        // must land far inside the 20 s giveup window, which only the event
        // path can do (the giveup needs a zero counter AND > 20 s).
        long startNanos = System.nanoTime();
        HumlaClientHandle aliceHandle = connectHandle(
                "InteropAlice", null, InteropClient.Voice.UDP_SOCKET_FAILS);
        HumlaClient alice = aliceHandle.getClient();
        Session session = InteropClient.awaitConnected(alice, "alice sync");
        InteropClient.awaitUsingUdp(session, false, "immediate tunnel", 10L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue("tunnel switch must not wait for the 20 s giveup, took "
                        + elapsedMillis + " ms",
                elapsedMillis < 15_000L);
        // The tunnel carries the session: still connected, keepalive latency
        // still measured after the switch.
        Thread.sleep(3000);
        assertTrue("session must survive the switch, got " + alice.getState().getValue(),
                alice.getState().getValue() instanceof ClientState.Connected);
        assertNotNull("TCP latency must keep being measured after the switch",
                session.getStats().getValue().getTcpLatencyMicros());
    }

    @Test
    public void tunnelVoicePlaysThroughNativeRender() throws Exception {
        // Step 5a.4 done-when: voice from a peer over the tunnel plays
        // non-silent PCM through the shared native voice path. Both clients
        // force TCP; the sender drives the native capture entry with
        // synthetic PCM, the send thread tunnels it, the native receive
        // parses and filters, and the test renders the shared engine.
        // No AudioRecord/AudioTrack exists on the host.
        HumlaClientHandle receiverHandle = connectHandle(
                "InteropAlice", null, InteropClient.Voice.FORCED_TCP);
        Session receiverSession = InteropClient.awaitConnected(
                receiverHandle.getClient(), "receiver sync");
        HumlaClientHandle senderHandle = connectHandle(
                "InteropBob", null, InteropClient.Voice.FORCED_TCP);
        Session senderSession = InteropClient.awaitConnected(
                senderHandle.getClient(), "sender sync");
        final int senderId = senderSession.getSessionId();
        InteropClient.awaitUser(receiverSession, "InteropBob", "receiver sees sender");

        // No PolicyCollector in the harness: open the native sender filter
        // for the peer directly (production pushes the mapped VoicePolicy),
        // plus an open gate for the sender so its capture transmits without
        // a collector.
        final se.lublin.humla.voice.VoiceEngine receiverVoice = receiverHandle.getVoice();
        receiverVoice.setFilterForTests(new int[0], new int[]{senderId});
        final se.lublin.humla.voice.VoiceEngine senderVoice = senderHandle.getVoice();
        senderVoice.setFilterForTests(new int[0], new int[0]);
        // 30 frames of 440 Hz sine at 48 kHz: continuous mode transmits
        // everything, so encoding needs no VAD warmup. Step 5a.4 drives the
        // native capture entry; the send thread tunnels via InteropClient's
        // sink and the receiver renders the shared engine.
        short[] sine = new short[480];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = (short) (10000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0));
        }
        for (int i = 0; i < 30; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        // The startup gate needs margin + 1 queued frames; render the shared
        // engine until clearly non-silent PCM comes out of the real output
        // engine and Opus decoder.
        short[] pcm = new short[960];
        int peak = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (peak <= 100 && System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > peak) peak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("native render must play non-silent PCM, peak=" + peak,
                peak > 100);
        // The sender's talk level arrives through the service-lifetime
        // publisher fed by the shared notifier.
        boolean sawSender = false;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sawSender && System.nanoTime() < deadline) {
            if (receiverVoice.getTalkActivity().getValue().getUsers().containsKey(senderId)) {
                sawSender = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue("notifier must report the sender talking", sawSender);
        // Both directions over the tunnel: reverse Alice -> Bob through the
        // same native path.
        final int receiverId = receiverSession.getSessionId();
        InteropClient.awaitUser(senderSession, "InteropAlice", "sender sees receiver");
        senderVoice.setFilterForTests(new int[0], new int[]{receiverId});
        receiverVoice.setFilterForTests(new int[0], new int[0]);
        for (int i = 0; i < 30; i++) {
            receiverVoice.captureFrame(sine, 0, sine.length);
        }
        short[] pcmRev = new short[960];
        int peakRev = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (peakRev <= 100 && System.nanoTime() < deadline) {
            int rendered = senderVoice.render(pcmRev, 0, pcmRev.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcmRev[s]);
                if (v > peakRev) peakRev = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("reverse tunnel voice must play non-silent PCM, peak=" + peakRev,
                peakRev > 100);
    }

    @Test
    public void udpVoicePlaysThroughNativeReceive() throws Exception {
        // Step 5a.4 done-when: voice from a peer over UDP plays non-silent
        // PCM through the shared native voice path. The sender drives the
        // native capture entry with synthetic PCM; the send thread encrypts
        // and sends on the shared socket; the native receive thread
        // decrypts, parses, and filters; the test renders the shared engine
        // directly. No AudioRecord/AudioTrack exists on the host.
        HumlaClientHandle receiverHandle = connectHandle(
                "InteropAlice", null, InteropClient.Voice.UDP);
        Session receiverSession = InteropClient.awaitConnected(
                receiverHandle.getClient(), "receiver sync");
        HumlaClientHandle senderHandle = connectHandle(
                "InteropBob", null, InteropClient.Voice.UDP);
        Session senderSession = InteropClient.awaitConnected(
                senderHandle.getClient(), "sender sync");
        final int senderId = senderSession.getSessionId();
        InteropClient.awaitUser(receiverSession, "InteropBob", "receiver sees sender");
        InteropClient.awaitUsingUdp(receiverSession, true, "receiver on UDP", 15L);
        InteropClient.awaitUsingUdp(senderSession, true, "sender on UDP", 15L);

        final se.lublin.humla.voice.VoiceEngine receiverVoice = receiverHandle.getVoice();
        receiverVoice.setFilterForTests(new int[0], new int[]{senderId});
        final se.lublin.humla.voice.VoiceEngine senderVoice = senderHandle.getVoice();
        senderVoice.setFilterForTests(new int[0], new int[0]);
        short[] sine = new short[480];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = (short) (10000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0));
        }
        for (int i = 0; i < 30; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        short[] pcm = new short[960];
        int peak = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (peak <= 100 && System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > peak) peak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("native render must play non-silent PCM, peak=" + peak,
                peak > 100);
        boolean sawSender = false;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sawSender && System.nanoTime() < deadline) {
            if (receiverVoice.getTalkActivity().getValue().getUsers().containsKey(senderId)) {
                sawSender = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue("notifier must report the sender talking", sawSender);
        // Both directions over UDP: reverse Alice -> Bob through the same
        // native path (sender gate already open via setFilterForTests).
        final int receiverId = receiverSession.getSessionId();
        senderVoice.setFilterForTests(new int[0], new int[]{receiverId});
        receiverVoice.setFilterForTests(new int[0], new int[0]);
        for (int i = 0; i < 30; i++) {
            receiverVoice.captureFrame(sine, 0, sine.length);
        }
        short[] pcmRev = new short[960];
        int peakRev = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (peakRev <= 100 && System.nanoTime() < deadline) {
            int rendered = senderVoice.render(pcmRev, 0, pcmRev.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcmRev[s]);
                if (v > peakRev) peakRev = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("reverse UDP voice must play non-silent PCM, peak=" + peakRev,
                peakRev > 100);
    }

    @Test
    public void voiceGatingMutesDeafensAndPtt() throws Exception {
        // Step 5a.4 done-when: mute, deafen, and push-to-talk gate
        // transmission in interop (tunnel). The native gate is the single
        // enforcement point; VoicePolicyTest pins the SessionState mapping
        // (desired mute wins, echo deafen closes, PTT pressed flows), so
        // here the harness drives the gate directly and asserts no audio
        // traverses, then reopens and asserts voice returns.
        HumlaClientHandle receiverHandle = connectHandle(
                "InteropAlice", null, InteropClient.Voice.FORCED_TCP);
        Session receiverSession = InteropClient.awaitConnected(
                receiverHandle.getClient(), "receiver sync");
        HumlaClientHandle senderHandle = connectHandle(
                "InteropBob", null, InteropClient.Voice.FORCED_TCP);
        Session senderSession = InteropClient.awaitConnected(
                senderHandle.getClient(), "sender sync");
        final int senderId = senderSession.getSessionId();
        InteropClient.awaitUser(receiverSession, "InteropBob", "receiver sees sender");
        final se.lublin.humla.voice.VoiceEngine receiverVoice = receiverHandle.getVoice();
        receiverVoice.setFilterForTests(new int[0], new int[]{senderId});
        final se.lublin.humla.voice.VoiceEngine senderVoice = senderHandle.getVoice();
        senderVoice.setFilterForTests(new int[0], new int[0]);
        short[] sine = new short[480];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = (short) (10000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0));
        }
        // Baseline open gate transmits.
        for (int i = 0; i < 30; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        short[] pcm = new short[960];
        int peak = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (peak <= 100 && System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > peak) peak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("open gate must transmit, peak=" + peak, peak > 100);
        // Drain the burst so the mute phase starts silent: render until the
        // inbound ring and jitter empty (no new captures during the drain).
        // Without this, leftover baseline voice would play into the mute
        // window and falsely trip the silence assert.
        long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < drainDeadline) {
            receiverVoice.render(pcm, 0, pcm.length);
            Thread.sleep(20);
        }
        // Mute closes the gate: terminator only, then silence. Push a closed
        // gate (transmitAllowed false) and capture; the receiver must stay
        // silent (peak <= 100) over 3 s of render.
        senderVoice.setGateForTests(false, false);
        for (int i = 0; i < 30; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        // Skip the first second: baseline tail + terminator transit + PLC
        // (loud for ~100 ms) must drain before silence is pinned.
        long muteSkip = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < muteSkip) {
            receiverVoice.render(pcm, 0, pcm.length);
            Thread.sleep(20);
        }
        int mutePeak = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > mutePeak) mutePeak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        boolean muteSelf = senderVoice.getTalkActivity().getValue().getSelfTalking();
        assertTrue("muted gate must stay silent, peak=" + mutePeak + " self=" + muteSelf, mutePeak <= 100);
        // Deafen closes the same gate (server also stops forwarding, but the
        // client-side gate is what this pins): same closed gate stays silent.
        senderVoice.setGateForTests(false, false);
        for (int i = 0; i < 10; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        long deafSkip = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deafSkip) {
            receiverVoice.render(pcm, 0, pcm.length);
            Thread.sleep(20);
        }
        int deafPeak = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > deafPeak) deafPeak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("deafened gate must stay silent, peak=" + deafPeak, deafPeak <= 100);
        // Push-to-talk: PTT mode with pttPressed false stays silent; pressed
        // transmits. Settings switch to PTT, gate carries pttPressed.
        senderVoice.setSettingsForTests(se.lublin.humla.voice.InputMode.PUSH_TO_TALK, false);
        senderVoice.setGateForTests(true, false);
        for (int i = 0; i < 10; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        long pttSkip = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < pttSkip) {
            receiverVoice.render(pcm, 0, pcm.length);
            Thread.sleep(20);
        }
        int pttIdlePeak = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > pttIdlePeak) pttIdlePeak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("PTT idle must stay silent, peak=" + pttIdlePeak, pttIdlePeak <= 100);
        senderVoice.setGateForTests(true, true);
        for (int i = 0; i < 30; i++) {
            senderVoice.captureFrame(sine, 0, sine.length);
        }
        int pttPeak = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (pttPeak <= 100 && System.nanoTime() < deadline) {
            int rendered = receiverVoice.render(pcm, 0, pcm.length);
            for (int s = 0; s < rendered; s++) {
                int v = Math.abs((int) pcm[s]);
                if (v > pttPeak) pttPeak = v;
            }
            if (rendered == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("PTT pressed must transmit, peak=" + pttPeak, pttPeak > 100);
    }

}
