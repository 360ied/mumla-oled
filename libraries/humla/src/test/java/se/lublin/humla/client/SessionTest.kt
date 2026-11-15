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

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.voice.TalkActivity

/**
 * Session scenarios against a fake transport: handshake, ServerSync side
 * effects, and failure semantics. Every step runs the shared invariant
 * checker (docs/target-architecture/testing.md § Virtual-time tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private class Fixture(
        val scope: TestScope,
        localFacts: LocalFacts = LocalFacts.empty(),
        target: ConnectTarget = testTarget(),
    ) {
        val transport = FakeTransport()
        val voice = FakeVoice()
        val invariants = InvariantChecker(scope, voice.order)
        private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as TestDispatcher
        val session = Session(
            id = 1,
            target = target,
            parentScope = scope.backgroundScope,
            transport = transport,
            localFacts = localFacts,
            talkActivity = MutableStateFlow(TalkActivity.initial()),
            voiceLink = voice,
            observer = NoopObserver,
            localFactsStore = InMemoryLocalFactsStore(),
            mainPoster = { r -> r.run() },
            denyFormatter = { "denied" },
            workDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )

        init {
            invariants.watchSession(session)
            step()
        }

        fun step() {
            scope.runCurrent()
            invariants.check()
        }

        fun deliver(vararg items: Inbound) {
            for (item in items) check(transport.inboundChannel.trySend(item).isSuccess)
            step()
        }

        fun deliver(type: HumlaTCPMessageType, message: com.google.protobuf.MessageLite) =
            deliver(Inbound.Message(type, message))

        fun sync() = deliver(*syncSequence().toTypedArray())

        fun close() {
            session.close()
            step()
        }
    }

    @Test
    fun handshakeIsVersionThenAuthenticate() = runTest {
        val target = testTarget().copy(
            server = testTarget().server.copy(password = "pw"),
            accessTokens = persistentListOf("t1", "t2"),
        )
        val f = Fixture(this, target = target)
        val sent = f.transport.sent.map { it.first }
        assertEquals(listOf(HumlaTCPMessageType.Version, HumlaTCPMessageType.Authenticate), sent)
        val auth = f.transport.sentOf<Mumble.Authenticate>(HumlaTCPMessageType.Authenticate).single()
        assertEquals("alice", auth.username)
        assertEquals("pw", auth.password)
        assertEquals(listOf("t1", "t2"), auth.tokensList)
        assertTrue(auth.opus)
        f.close()
    }

    @Test
    fun serverSyncStartsKeepaliveOnce() = runTest {
        val f = Fixture(this)
        val sequence = syncSequence()
        val syncIndex = sequence.indexOfFirst { it.type == HumlaTCPMessageType.ServerSync }
        f.deliver(*sequence.take(syncIndex).toTypedArray())
        assertEquals(0, f.transport.keepaliveStarts)
        assertEquals(SyncPhase.HANDSHAKING, f.session.state.value.server.phase)
        f.deliver(*sequence.drop(syncIndex).toTypedArray())
        assertEquals(1, f.transport.keepaliveStarts)
        assertEquals(SyncPhase.SYNCED, f.session.state.value.server.phase)
        // A repeated ServerSync updates state but never restarts keepalive.
        f.deliver(HumlaTCPMessageType.ServerSync, serverSync(1))
        assertEquals(1, f.transport.keepaliveStarts)
        f.close()
    }

    @Test
    fun restoredSelfMuteIsSentAtServerSync() = runTest {
        val facts = LocalFacts.empty().copy(selfMuted = true, selfDeafened = false)
        val f = Fixture(this, localFacts = facts)
        val sequence = syncSequence()
        val syncIndex = sequence.indexOfFirst { it.type == HumlaTCPMessageType.ServerSync }
        f.deliver(*sequence.take(syncIndex).toTypedArray())
        assertTrue(f.transport.sentOf<Mumble.UserState>(HumlaTCPMessageType.UserState).isEmpty())
        f.deliver(*sequence.drop(syncIndex).toTypedArray())
        val states = f.transport.sentOf<Mumble.UserState>(HumlaTCPMessageType.UserState)
        assertEquals(1, states.size)
        assertTrue(states[0].selfMute)
        assertFalse(states[0].selfDeaf)
        f.close()
    }

    @Test
    fun noSelfMuteSentWhenNoneRestored() = runTest {
        val f = Fixture(this)
        f.sync()
        assertTrue(f.transport.sentOf<Mumble.UserState>(HumlaTCPMessageType.UserState).isEmpty())
        f.close()
    }

    @Test
    fun rejectFailsOnceAndStopsProcessing() = runTest {
        val f = Fixture(this)
        f.sync()
        f.deliver(
            Inbound.Message(HumlaTCPMessageType.Reject, reject("full")),
            Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(7, "Late", parent = 0)),
        )
        assertEquals(ClientError.Rejected(Mumble.Reject.RejectType.WrongServerPW.number, "full"), f.session.failure.getCompleted())
        // Nothing after the fatal message is applied.
        assertFalse(f.session.state.value.server.channels.containsKey(7))
        // A later transport failure does not replace the first error.
        f.transport.fail(ClientError.Connection("late", null))
        f.step()
        assertTrue(f.session.failure.getCompleted() is ClientError.Rejected)
        f.close()
    }

    @Test
    fun selfRemoveFailsWithRemoved() = runTest {
        val f = Fixture(this)
        f.sync()
        f.deliver(HumlaTCPMessageType.UserRemove, userRemove(1, actor = 2, reason = "kick"))
        assertEquals(ClientError.Removed(actor = 2, reason = "kick", ban = false), f.session.failure.getCompleted())
        f.close()
    }

    @Test
    fun otherUserRemoveIsNotAFailure() = runTest {
        val f = Fixture(this)
        f.sync()
        f.deliver(HumlaTCPMessageType.UserRemove, userRemove(2, actor = 1, reason = "kick"))
        assertFalse(f.session.failure.isCompleted)
        assertFalse(f.session.state.value.server.users.containsKey(2))
        f.close()
    }

    @Test
    fun transportFailuresPropagateUnchanged() = runTest {
        for (error in listOf(
            ClientError.Connection("TCP read failed: reset", java.io.IOException("reset")),
            ClientError.Connection("Invalid protocol frame (TYPE_OUT_OF_RANGE)", null),
            ClientError.Connection("Transport outbound full; server stopped reading", null),
        )) {
            val f = Fixture(this)
            f.sync()
            f.transport.fail(error)
            f.step()
            assertSame(error, f.session.failure.getCompleted())
            f.close()
        }
    }

    @Test
    fun tunnelledPacketsGoToVoiceInOrder() = runTest {
        val f = Fixture(this)
        f.sync()
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        f.deliver(
            Inbound.Tunnelled(a),
            Inbound.Message(HumlaTCPMessageType.ChannelState, channelState(8, "Mid", parent = 0)),
            Inbound.Tunnelled(b),
        )
        assertEquals(2, f.voice.tunnelled.size)
        assertArrayEquals(a, f.voice.tunnelled[0])
        assertArrayEquals(b, f.voice.tunnelled[1])
        assertTrue(f.session.state.value.server.channels.containsKey(8))
        f.close()
    }

    @Test
    fun usersStayInExistingChannelsAcrossRemoveAndUnknownMoves() = runTest {
        val f = Fixture(this)
        f.sync()
        assertEquals(1, f.session.state.value.server.users[2]!!.channelId)
        // A move to a channel we never saw lands in root.
        f.deliver(HumlaTCPMessageType.UserState, userState(2, "bob", 99))
        assertEquals(0, f.session.state.value.server.users[2]!!.channelId)
        f.deliver(HumlaTCPMessageType.UserState, userState(2, "bob", 1))
        // Removing an occupied channel leaves bob there until the server
        // moves him: the one transient exception to the invariant.
        f.deliver(HumlaTCPMessageType.ChannelRemove, Mumble.ChannelRemove.newBuilder().setChannelId(1).build())
        assertFalse(f.session.state.value.server.channels.containsKey(1))
        f.deliver(HumlaTCPMessageType.UserState, userState(2, "bob", 0))
        assertEquals(0, f.session.state.value.server.users[2]!!.channelId)
        f.close()
    }

    @Test
    fun closeAtEverySyncBoundaryLeavesNothingRunning() = runTest {
        val sequence = syncSequence()
        for (k in 0..sequence.size) {
            val f = Fixture(this)
            if (k > 0) f.deliver(*sequence.take(k).toTypedArray())
            f.close()
            assertTrue("boundary $k: transport left open", f.transport.closed)
            assertFalse("boundary $k: scope alive", f.session.scopeJob.isActive)
            // Closing is not a failure; the client already knows it ended.
            assertFalse("boundary $k", f.session.failure.isCompleted)
            // Intents on the dead session are safe no-ops.
            f.session.setSelfMuteDeaf(true, true)
            f.session.setPtt(true)
            f.step()
        }
    }
}
