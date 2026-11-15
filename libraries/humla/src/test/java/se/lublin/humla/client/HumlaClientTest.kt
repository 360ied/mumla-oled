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

package se.lublin.humla.client

import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.net.TransportException
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionState
import se.lublin.humla.voice.TalkActivity

/**
 * Virtual-time client scenarios (docs/target-architecture/testing.md
 * § Virtual-time tests). Every scheduler step goes through [ClientHarness],
 * which asserts the five invariants after it.
 */
class HumlaClientTest {
    private val now = 1_000_000L

    private fun TestScope.harness(
        factory: FakeFactory = FakeFactory(),
        localFactsStore: LocalFactsStore = InMemoryLocalFactsStore(),
        talkActivity: MutableStateFlow<TalkActivity> = MutableStateFlow(TalkActivity.initial()),
        policySource: Flow<SessionState?>? = null,
    ): ClientHarness = ClientHarness(
        this,
        factory = factory,
        localFactsStore = localFactsStore,
        talkActivity = talkActivity,
        policySource = policySource,
    ).also { it.step() }

    private fun ClientHarness.reconnecting(): ClientState.Reconnecting {
        val s = state
        assertTrue("expected Reconnecting, got $s", s is ClientState.Reconnecting)
        return s as ClientState.Reconnecting
    }

    private fun ClientHarness.connecting(): ClientState.Connecting {
        val s = state
        assertTrue("expected Connecting, got $s", s is ClientState.Connecting)
        return s as ClientState.Connecting
    }

    private fun ClientHarness.failed(): ClientState.Failed {
        val s = state
        assertTrue("expected Failed, got $s", s is ClientState.Failed)
        return s as ClientState.Failed
    }

    @Test
    fun connectSyncDisconnect() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        // FakeTransport created; handshake sent (Version + Authenticate).
        val fake = h.transport
        assertTrue(fake.sent.any { it.first == HumlaTCPMessageType.Version })
        assertTrue(fake.sent.any { it.first == HumlaTCPMessageType.Authenticate })
        // TransportFactory forwarding: host/port/forceTcp arrive intact.
        assertEquals(1, h.factory.created.size)
        assertEquals("127.0.0.1", h.factory.created[0].host)
        assertEquals(64738, h.factory.created[0].port)
        assertEquals(true, h.factory.created[0].forceTcp)
        assertEquals(null, h.factory.created[0].certSize)
        assertEquals(null, h.factory.created[0].trustPath)
        h.deliver(*syncSequence().toTypedArray())
        assertTrue(h.state is ClientState.Connected)
        assertEquals(1, h.route.starts)
        assertEquals(1, h.voice.starts)
        // Voice starts in the transport's format; the route is not the
        // client's to push (the transport owns it, issue 16).
        assertEquals(listOf(fake.voiceFormat), h.voice.startFormats)

        h.client.disconnect()
        h.step()
        assertTrue(h.state is ClientState.Idle)
        assertEquals(1, h.voice.stops)
        assertEquals(1, h.route.ends)
        assertTrue(fake.closed)
        h.finish()
    }

    @Test
    fun disconnectAtEverySyncBoundaryEndsClean() = runTest {
        val sequence = syncSequence()
        val syncIndex = sequence.indexOfFirst { it.type == HumlaTCPMessageType.ServerSync }
        for (k in 0..sequence.size) {
            val h = harness()
            h.client.connect(testTarget())
            h.step()
            val fake = h.transport
            val session = h.session
            if (k > 0) h.deliver(*sequence.take(k).toTypedArray())
            val synced = k > syncIndex
            assertEquals("boundary $k", synced, h.state is ClientState.Connected)
            h.client.disconnect()
            h.step()
            assertTrue("boundary $k: ${h.state}", h.state is ClientState.Idle)
            assertTrue("boundary $k: transport left open", fake.closed)
            assertFalse("boundary $k: session scope alive", session.scopeJob.isActive)
            assertEquals("boundary $k", if (synced) 1 else 0, h.voice.starts)
            assertEquals("boundary $k", 1, h.voice.stops)
            assertEquals("boundary $k", 1, h.route.ends)
            // Nothing comes back after the disconnect.
            h.advance(60_000)
            assertTrue(h.state is ClientState.Idle)
            assertEquals(1, h.factory.created.size)
            h.finish()
        }
    }

    @Test
    fun localFactsLoadFailureStartsEmptyAndLogs() = runTest {
        val records = Collections.synchronizedList(ArrayList<LogRecord>())
        val logger = java.util.logging.Logger.getLogger("HumlaClient")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = logger.level
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            // LocalFacts load throws: the session must still start with empty
            // lists, and the failure must be logged (spec, client.md start
            // sequence step 1).
            val store = object : LocalFactsStore {
                override suspend fun load(serverId: Long): LocalFacts =
                    throw IllegalStateException("db unavailable")

                override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) = Unit
                override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) = Unit
                override suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean) = Unit
            }
            val h = harness(localFactsStore = store)
            h.client.connect(testTarget())
            h.step()
            // A DB problem never blocks connecting: the transport was created.
            assertEquals(1, h.factory.created.size)
            assertTrue(h.state is ClientState.Connecting)
            assertTrue(
                "expected a LocalFacts load warning, got $records",
                records.any {
                    it.level == Level.WARNING &&
                        it.message.contains("LocalFacts load failed")
                },
            )
            h.client.disconnect()
            h.step()
            h.finish()
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
        }
    }

    @Test
    fun reconnectBackoffSequence() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        val first = h.reconnecting()
        assertEquals(0, first.attempt)
        assertEquals(now + 1000, first.retryAtMillis)
        // Nothing fires before the delay elapses.
        h.advance(999)
        h.reconnecting()
        h.advance(1)
        assertEquals(1, h.connecting().attempt)
        h.failTransport()
        assertEquals(1, h.reconnecting().attempt)
        // 3s -> attempt 2, 6s -> attempt 3, 12s -> attempt 4, 25s stays.
        for ((delay, expected) in listOf(3000L to 2, 6000L to 3, 12000L to 4, 25000L to 5, 25000L to 6)) {
            assertEquals(now + delay, h.reconnecting().retryAtMillis)
            h.advance(delay)
            assertEquals(expected, h.connecting().attempt)
            h.failTransport()
        }
        h.finish()
    }

    @Test
    fun attemptCounterResetsOnConnected() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        h.advance(1000)
        h.failTransport()
        assertEquals(1, h.reconnecting().attempt)
        h.advance(3000)
        assertEquals(2, h.connecting().attempt)
        h.deliver(*syncSequence().toTypedArray())
        assertTrue(h.state is ClientState.Connected)
        val stopsBefore = h.voice.stops
        val endsBefore = h.route.ends
        // A connection error after Connected starts the delays fresh and
        // carries the last server state.
        h.failTransport()
        val r = h.reconnecting()
        assertEquals(0, r.attempt)
        assertEquals(now + 1000, r.retryAtMillis)
        assertNotNull(r.lastState)
        assertEquals(stopsBefore + 1, h.voice.stops)
        assertEquals(endsBefore + 1, h.route.ends)
        h.finish()
    }

    @Test
    fun attemptCounterResetsOnDisconnect() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        h.advance(1000)
        h.failTransport()
        assertEquals(1, h.reconnecting().attempt)
        h.client.disconnect()
        h.step()
        h.client.connect(testTarget())
        h.step()
        assertEquals(0, h.connecting().attempt)
        h.failTransport()
        assertEquals(now + 1000, h.reconnecting().retryAtMillis)
        h.finish()
    }

    @Test
    fun cancelReconnectResetsAttemptCounter() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        h.advance(1000)
        h.failTransport()
        assertEquals(1, h.reconnecting().attempt)
        h.client.cancelReconnect()
        h.step()
        assertTrue(h.state is ClientState.Idle)
        // The app's "cancel and connect now" starts the delays fresh.
        h.client.connect(testTarget())
        h.step()
        assertEquals(0, h.connecting().attempt)
        h.failTransport()
        val r = h.reconnecting()
        assertEquals(0, r.attempt)
        assertEquals(now + 1000, r.retryAtMillis)
        h.finish()
    }

    @Test
    fun manualConnectKeepsAttemptCounter() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        h.advance(1000)
        h.failTransport()
        assertEquals(1, h.reconnecting().attempt)
        // A bare manual connect keeps the grown backoff.
        h.client.connect(testTarget())
        h.step()
        assertEquals(1, h.connecting().attempt)
        h.failTransport()
        val r = h.reconnecting()
        assertEquals(1, r.attempt)
        assertEquals(now + 3000, r.retryAtMillis)
        h.finish()
    }

    @Test
    fun rejectGoesFailedWithoutReconnect() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.deliver(HumlaTCPMessageType.Reject, reject())
        assertTrue(h.failed().error is ClientError.Rejected)
        // Rejected sessions never start voice or route.
        assertEquals(0, h.voice.starts)
        assertEquals(0, h.route.starts)
        h.advance(60_000)
        h.failed()
        assertEquals(1, h.factory.created.size)
        h.finish()
    }

    @Test
    fun removalOfSelfWhileConnectedGoesFailedWithoutReconnect() = runTest {
        val h = harness()
        h.connectAndSync()
        val fake = h.transport
        h.deliver(HumlaTCPMessageType.UserRemove, userRemove(1, actor = 2, reason = "bye", ban = true))
        val error = h.failed().error
        assertEquals(ClientError.Removed(actor = 2, reason = "bye", ban = true), error)
        assertTrue(fake.closed)
        assertEquals(1, h.voice.stops)
        assertEquals(1, h.route.ends)
        h.advance(60_000)
        h.failed()
        assertEquals(1, h.factory.created.size)
        h.finish()
    }

    @Test
    fun removalOfSelfInSyncBatchGoesFailedWithoutReconnect() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        val sequence = syncSequence()
        val syncIndex = sequence.indexOfFirst { it.type == HumlaTCPMessageType.ServerSync }
        h.deliver(*sequence.take(syncIndex).toTypedArray())
        // ServerSync and the kick in one batch: the self session is known
        // from ServerSync on, and whether or not Connected is published
        // first, the client ends in Failed.
        h.deliver(sequence[syncIndex], Inbound.Message(HumlaTCPMessageType.UserRemove, userRemove(1)))
        assertTrue(h.failed().error is ClientError.Removed)
        h.advance(60_000)
        h.failed()
        assertEquals(1, h.factory.created.size)
        h.finish()
    }

    @Test
    fun trustFailureGoesFailedWithoutReconnect() = runTest {
        val factory = FakeFactory(throwOnCreate = TransportException(trustError()))
        val h = harness(factory = factory)
        h.client.connect(testTarget())
        h.step()
        assertTrue(h.failed().error is ClientError.Trust)
        assertEquals(0, h.voice.starts)
        assertEquals(0, h.route.starts)
        h.network.emitAvailable()
        h.advance(60_000)
        h.failed()
        assertEquals(1, h.factory.created.size)
        h.finish()
    }

    @Test
    fun networkAvailableRetriesAtOnce() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport(ClientError.Connection("down", null))
        h.reconnecting()
        // Network available cancels the 1s timer and retries immediately as attempt 1.
        h.network.emitAvailable()
        h.step()
        assertEquals(1, h.connecting().attempt)
        assertEquals(2, h.factory.created.size)
        // Repeated events while Connecting are ignored, and the cancelled
        // timer does not start a second attempt.
        h.network.emitAvailable()
        h.advance(5_000)
        assertEquals(1, h.connecting().attempt)
        assertEquals(2, h.factory.created.size)
        h.finish()
    }

    @Test
    fun networkAvailableIgnoredOutsideReconnecting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(factory = FakeFactory(createGate = gate))
        suspend fun assertIgnored(expected: ClientState) {
            val created = h.factory.created.size
            h.network.emitAvailable()
            h.step()
            assertSame(expected, h.state)
            assertEquals(created, h.factory.created.size)
        }
        assertIgnored(ClientState.Idle)
        // Connecting, before the transport exists.
        h.client.connect(testTarget())
        h.step()
        assertIgnored(h.connecting())
        // Connecting, with a handshaking session.
        h.factory.createGate = null
        gate.complete(Unit)
        h.step()
        val handshaking = h.session
        assertIgnored(h.connecting())
        assertSame(handshaking, h.session)
        // Connected.
        h.deliver(*syncSequence().toTypedArray())
        assertIgnored(h.state as ClientState.Connected)
        assertSame(handshaking, h.session)
        // Failed.
        h.deliver(HumlaTCPMessageType.Reject, reject())
        assertIgnored(h.failed())
        h.finish()
    }

    @Test
    fun connectWhileConnectedKeepsOneSession() = runTest {
        val h = harness()
        h.connectAndSync()
        val first = h.session
        val firstFake = h.transport
        // Connect to another target while connected: old session torn down.
        h.client.connect(testTarget(username = "bob"))
        h.step()
        assertTrue(first !== h.session)
        assertTrue(firstFake.closed)
        assertEquals("bob", h.connecting().target.server.username)
        assertEquals(1, h.voice.stops)
        assertEquals(1, h.route.ends)
        h.finish()
    }

    @Test
    fun connectToOtherTargetWhileConnecting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(factory = FakeFactory(createGate = gate))
        // Attempt A parks in DNS/TLS; B takes over and A is cancelled.
        h.client.connect(testTarget(username = "alice"))
        h.step()
        h.client.connect(testTarget(username = "bob"))
        h.step()
        assertEquals("bob", h.connecting().target.server.username)
        assertTrue(
            "connect while connecting is a Connecting -> Connecting edge",
            h.invariants.transitions.zipWithNext().any { (a, b) ->
                a is ClientState.Connecting && b is ClientState.Connecting &&
                    a.target.server.username == "alice" && b.target.server.username == "bob"
            },
        )
        h.factory.createGate = null
        gate.complete(Unit)
        h.step()
        // Only B's transport was created past the gate: one session.
        assertEquals(1, h.invariants.sessions.size)
        assertEquals("bob", h.session.target.server.username)
        // B is handshaking; connect C replaces the live session.
        val bFake = h.transport
        h.client.connect(testTarget(username = "carol"))
        h.step()
        assertTrue(bFake.closed)
        assertEquals("carol", h.session.target.server.username)
        h.deliver(*syncSequence("carol").toTypedArray())
        val connected = h.state as ClientState.Connected
        assertEquals("carol", connected.target.server.username)
        h.finish()
    }

    @Test
    fun connectToOtherTargetWhileReconnecting() = runTest {
        val h = harness()
        h.client.connect(testTarget(username = "alice"))
        h.step()
        h.failTransport()
        h.reconnecting()
        h.client.connect(testTarget(username = "bob"))
        h.step()
        assertEquals("bob", h.connecting().target.server.username)
        assertEquals(2, h.factory.created.size)
        // A's backoff timer was disarmed: no third attempt, and B stays.
        h.advance(30_000)
        assertEquals(2, h.factory.created.size)
        assertEquals("bob", h.connecting().target.server.username)
        h.deliver(*syncSequence("bob").toTypedArray())
        assertTrue(h.state is ClientState.Connected)
        h.finish()
    }

    @Test
    fun disconnectBeatsPendingReconnect() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        h.advance(500)
        h.client.disconnect()
        h.step()
        assertTrue(h.state is ClientState.Idle)
        h.advance(60_000)
        assertTrue(h.state is ClientState.Idle)
        assertEquals(1, h.factory.created.size)
        h.finish()
    }

    @Test
    fun disconnectRacingReconnectTimerEndsIdle() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        // The timer is due now but has not run; the disconnect lands in the
        // same scheduler step. Whichever order they run in, the result is
        // Idle with nothing left open.
        h.scope.testScheduler.advanceTimeBy(1000)
        h.client.disconnect()
        h.step()
        assertTrue(h.state is ClientState.Idle)
        h.advance(60_000)
        assertTrue(h.state is ClientState.Idle)
        h.finish()
    }

    @Test
    fun cancelReconnectBeatsPendingReconnect() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        h.advance(500)
        h.client.cancelReconnect()
        h.step()
        assertTrue(h.state is ClientState.Idle)
        h.advance(60_000)
        assertTrue(h.state is ClientState.Idle)
        assertEquals(1, h.factory.created.size)
        h.finish()
    }

    @Test
    fun cancelReconnectRacingReconnectTimerLetsAttemptRun() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        h.failTransport()
        // The timer fires first in the step (it was queued earlier), so the
        // client is Connecting by the time cancelReconnect runs, and only a
        // pending Reconnecting is disarmed (legacy parity): the attempt runs.
        h.scope.testScheduler.advanceTimeBy(1000)
        h.client.cancelReconnect()
        h.step()
        assertEquals(1, h.connecting().attempt)
        assertEquals(2, h.factory.created.size)
        h.client.disconnect()
        h.step()
        assertTrue(h.state is ClientState.Idle)
        h.finish()
    }

    @Test
    fun serverSyncPublishesDumpAndPreSyncIntents() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        val live = h.session
        // Pre-sync dump accumulates in the session's private builder; the
        // intents below land only on the published state.
        h.deliver(
            Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(0, "Root")),
            Inbound.Message(HumlaTCPMessageType.UserState, userState(1, "alice", 0)),
        )
        live.setPtt(true)
        live.setVoiceTarget(3)
        h.step()
        h.deliver(HumlaTCPMessageType.ServerSync, serverSync(1))
        assertTrue(h.state is ClientState.Connected)
        // The publish carries the accumulated dump and merges the pre-sync
        // intents instead of overwriting them with the builder's initial
        // values.
        val synced = live.state.value
        assertTrue(synced.server.channels.containsKey(0))
        assertTrue(synced.server.users.containsKey(1))
        assertTrue(synced.local.pttPressed)
        assertEquals(3, synced.local.voiceTargetId)
        h.finish()
    }

    @Test
    fun muteDeafenPttSpamDuringConnectAndDisconnectIsSafe() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        val first = h.session
        fun spam(session: Session, n: Int) {
            repeat(n) { i ->
                session.setSelfMuteDeaf(i % 2 == 0, i % 3 == 0)
                session.setPtt(i % 2 == 1)
                session.setVoiceTarget(i % 4)
            }
        }
        // Pre-sync intents are no-ops for the server but must not throw.
        spam(first, 50)
        h.step()
        assertTrue(h.transport.sentOf<com.google.protobuf.MessageLite>(HumlaTCPMessageType.UserState).isEmpty())
        // Disconnect mid-connect with intents racing it in the same step.
        spam(first, 10)
        h.client.disconnect()
        spam(first, 10)
        h.step()
        assertTrue(h.state is ClientState.Idle)
        spam(first, 10)
        h.step()
        // Connected: spam, then disconnect while more intents are queued.
        h.connectAndSync()
        val second = h.session
        spam(second, 50)
        h.step()
        // Server-side mute, deafen, and suppress interleaved with the spam:
        // the policy invariant checks every published state.
        for (flags in listOf("mute", "suppress", "deaf", "none")) {
            val us = Mumble.UserState.newBuilder().setSession(1)
                .setMute(flags == "mute").setSuppress(flags == "suppress").setDeaf(flags == "deaf")
                .build()
            spam(second, 5)
            h.deliver(HumlaTCPMessageType.UserState, us)
            spam(second, 5)
            h.step()
        }
        assertTrue(h.transport.sentOf<com.google.protobuf.MessageLite>(HumlaTCPMessageType.UserState).isNotEmpty())
        spam(second, 10)
        h.client.disconnect()
        spam(second, 10)
        h.step()
        assertTrue(h.state is ClientState.Idle)
        spam(second, 10)
        h.step()
        h.finish()
    }

    @Test
    fun staleConnectFailureDoesNotClobberNewerAttempt() = runTest {
        val h = harness()
        // Park attempt A in a non-cooperative factory that reports failure
        // even after cancellation.
        val gate = CompletableDeferred<Unit>()
        h.factory.createGate = gate
        h.factory.swallowGateCancel = true
        h.factory.throwOnCreate = TransportException(ClientError.Connection("stale", null))
        val targetA = testTarget()
        h.client.connect(targetA)
        h.step()
        // Take over with B while A is parked; B parks behind the same gate.
        val targetB = targetA.copy(server = targetA.server.copy(username = "InteropBob"))
        h.client.connect(targetB)
        h.step()
        assertEquals("InteropBob", h.connecting().target.server.username)
        // Finish B normally; the stale A failure must not have torn it down.
        h.factory.throwOnCreate = null
        gate.complete(Unit)
        h.step()
        val liveB = h.session
        assertFalse(h.transport.closed)
        h.deliver(*syncSequence("InteropBob").toTypedArray())
        assertTrue(h.state is ClientState.Connected)
        assertSame(liveB, h.session)
        h.finish()
    }

    @Test
    fun staleEqualTargetFailureDoesNotClobberNewerAttempt() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.factory.createGate = gate
        h.factory.swallowGateCancel = true
        h.factory.throwOnCreate = TransportException(ClientError.Connection("stale", null))
        val targetA = testTarget()
        h.client.connect(targetA)
        h.step()
        // Same-server manual retry: equal value, distinct instance. Value
        // equality on the target cannot tell A apart from B; the epoch can.
        val targetB = targetA.copy()
        assertEquals(targetA, targetB)
        assertTrue(targetA !== targetB)
        h.client.connect(targetB)
        h.step()
        assertEquals(targetB, h.connecting().target)
        h.factory.throwOnCreate = null
        gate.complete(Unit)
        h.step()
        val liveB = h.session
        assertFalse(h.transport.closed)
        h.deliver(*syncSequence().toTypedArray())
        assertTrue(h.state is ClientState.Connected)
        assertSame(liveB, h.session)
        h.finish()
    }

    @Test
    fun staleBugFailureDoesNotClobberNewerAttempt() = runTest {
        val h = harness()
        // Park attempt A in a non-cooperative factory whose stale error is
        // a deterministic bug, not a transport failure.
        val gate = CompletableDeferred<Unit>()
        h.factory.createGate = gate
        h.factory.swallowGateCancel = true
        h.factory.throwOnCreate = IllegalStateException("stale-bug")
        val targetA = testTarget()
        h.client.connect(targetA)
        h.step()
        h.client.connect(targetA.copy())
        h.step()
        // The stale bug must neither tear down B nor publish Failed.
        h.connecting()
        h.factory.throwOnCreate = null
        gate.complete(Unit)
        h.step()
        val liveB = h.session
        assertFalse(h.transport.closed)
        h.deliver(*syncSequence().toTypedArray())
        assertTrue(h.state is ClientState.Connected)
        assertSame(liveB, h.session)
        h.finish()
    }

    @Test
    fun ioErrorReconnectsButBugDoesNot() = runTest {
        // IOException (DNS/TLS I/O class) reconnects.
        run {
            val h = harness(factory = FakeFactory(throwOnCreate = java.io.IOException("dns")))
            h.client.connect(testTarget())
            h.step()
            h.reconnecting()
            h.finish()
        }
        // Programming bugs fail terminally instead of looping reconnects
        // forever (and without crashing the process).
        run {
            val h = harness(factory = FakeFactory(throwOnCreate = IllegalStateException("bug")))
            h.client.connect(testTarget())
            h.step()
            val error = h.failed().error
            assertTrue(error is ClientError.Connection)
            assertTrue((error as ClientError.Connection).cause is IllegalStateException)
            h.advance(60_000)
            h.failed()
            h.finish()
        }
    }

    @Test
    fun channelRosterReadsLiveTalkLevels() = runTest {
        val talk = MutableStateFlow(TalkActivity.initial())
        val h = harness(talkActivity = talk)
        h.connectAndSync()
        val live = h.session
        // Channel-roster handles read the same live levels as user handles:
        // without the forwarded flow they would stay passive here.
        talk.value = TalkActivity(selfTalking = false, users = persistentMapOf(1 to TalkState.TALKING))
        assertEquals(
            TalkState.TALKING,
            live.getChannel(0)!!.users.single { it.session == 1 }.talkState,
        )
        talk.value = TalkActivity.initial()
        assertEquals(
            TalkState.PASSIVE,
            live.getChannel(0)!!.users.single { it.session == 1 }.talkState,
        )
        h.client.disconnect()
        h.step()
        h.finish()
    }

    // The checker itself: each invariant has a detector that fires.

    @Test
    fun invariantCheckerRejectsEdgeOutsideDiagram() = runTest {
        val checker = InvariantChecker(this, emptyList())
        val target = testTarget()
        checker.transitions.add(ClientState.Failed(target, trustError()))
        checker.transitions.add(
            ClientState.Reconnecting(target, 0, 0L, ClientError.Connection("x", null), null),
        )
        assertThrows(AssertionError::class.java) { checker.check() }
    }

    @Test
    fun invariantCheckerRejectsRouteEndBeforeVoiceStop() = runTest {
        val checker = InvariantChecker(this, listOf("route-end", "voice-stop"))
        assertThrows(AssertionError::class.java) { checker.check() }
    }

    @Test
    fun invariantCheckerRejectsTwoLiveSessions() = runTest {
        val h = harness()
        h.client.connect(testTarget())
        h.step()
        // A session the client does not own, left running.
        val stray = Session(
            id = 99,
            target = testTarget(),
            parentScope = backgroundScope,
            transport = FakeTransport(),
            localFacts = LocalFacts.empty(),
            talkActivity = MutableStateFlow(TalkActivity.initial()),
            voiceLink = h.voice,
            observer = NoopObserver,
            localFactsStore = InMemoryLocalFactsStore(),
            mainPoster = { r -> r.run() },
            denyFormatter = { "denied" },
            workDispatcher = h.dispatcher,
            ioDispatcher = h.dispatcher,
        )
        h.invariants.watchSession(stray)
        assertThrows(AssertionError::class.java) { h.invariants.check() }
        stray.close()
        h.finish()
    }

    // Published-policy detector (issue 34): a real PolicyCollector fed by a
    // freezable mirror of the session source. Freezing proves the detector
    // catches the stale policy a broken collector would keep publishing.

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.stuckPolicyHarness(): Pair<ClientHarness, MutableStateFlow<SessionState?>> {
        val stuck = MutableStateFlow<SessionState?>(null)
        val h = harness(policySource = stuck)
        val mirror = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.client.currentSession.flatMapLatest { s ->
                if (s == null) flowOf(null) else s.state
            }.collect { stuck.value = it }
        }
        h.connectAndSync()
        h.step()
        mirror.cancel()
        return h to stuck
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun invariantCheckerRejectsPublishedTransmittingWhileMuted() = runTest {
        val (h, _) = stuckPolicyHarness()
        // The live session mutes while the frozen source keeps the
        // transmitting policy published.
        h.session.setSelfMuteDeaf(true, false)
        assertThrows(AssertionError::class.java) { h.step() }
        h.client.close().join()
        h.collector.close()
        runCurrent()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun publishedPolicyWithoutResetOnDisconnectIsCaught() = runTest {
        val (h, _) = stuckPolicyHarness()
        // Disconnect without resetting the source: the transmitting policy
        // outlives the session instead of falling back to muted.
        h.client.disconnect()
        assertThrows(AssertionError::class.java) { h.step() }
        h.client.close().join()
        h.collector.close()
        runCurrent()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun publishedPolicyStuckAcrossSessionSwitchIsCaught() = runTest {
        val (h, _) = stuckPolicyHarness()
        // A new target tears the session down; the frozen source keeps the
        // old session's transmitting policy published past the switch.
        h.client.connect(testTarget(username = "bob"))
        assertThrows(AssertionError::class.java) { h.step() }
        h.client.close().join()
        h.collector.close()
        runCurrent()
    }
}
