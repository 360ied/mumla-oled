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

package se.lublin.mumla.feature

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.testTarget
import se.lublin.humla.client.trustError
import se.lublin.humla.model.Server
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.mumla.AppLogLine
import se.lublin.mumla.service.IChatMessage

/** Chat-log history, LRU policy, text entries, event formatting and backpressure. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatLogFeatureTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture(lineSink: (suspend (ChatLogEntry) -> Unit)? = null) {
        val clientState = MutableStateFlow<ClientState>(ClientState.Idle)
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 256)
        val voiceEvents = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
        val appLog = MutableSharedFlow<AppLogLine>(extraBufferCapacity = 16)
        val formatter = ChatEventFormatter(RuntimeEnvironment.getApplication())
        val feature = ChatLogFeature(
            RuntimeEnvironment.getApplication(), clientState, events, voiceEvents,
            appLog, formatter, lineSink,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun start() {
            feature.start(scope)
        }

        fun close() = scope.cancel()
    }

    private fun target(id: Long) = testTarget().copy(
        server = testTarget().server.copy(serverId = id),
    )

    /** Logs a sent message the way the session does: one TextMessageSent record. */
    private fun Fixture.sent(
        body: String,
        channels: List<Int> = emptyList(),
        users: List<Int> = emptyList(),
    ) {
        kotlinx.coroutines.runBlocking {
            events.emit(
                SessionEventRecord(
                    SessionEvent.TextMessageSent(channels, emptyList(), users, body, 0L),
                    goldenState(),
                ),
            )
        }
    }

    @Test fun serverKeyOfNullIsEmpty() {
        assertEquals("", ChatLogFeature.serverKey(null))
    }

    @Test fun serverKeyOfSavedServer() {
        assertEquals(
            "id:42",
            ChatLogFeature.serverKey(Server(42, "My Server", "mumble.example.com", 64738, "alice", "secret")),
        )
    }

    @Test fun serverKeyOfUnsavedServer() {
        assertEquals(
            "endpoint:voice.example.org:64739:bob",
            ChatLogFeature.serverKey(Server(-1, "AdHoc", "voice.example.org", 64739, "bob", "")),
        )
    }

    @Test fun serverKeyCanonicalizesHostAndPort() {
        val upper = Server(-1, "Upper", "MUMBLE.EXAMPLE.COM", 64738, "alice", "")
        val lower = Server(-1, "Lower", "mumble.example.com", 64738, "alice", "")
        assertEquals(ChatLogFeature.serverKey(lower), ChatLogFeature.serverKey(upper))
        assertEquals(
            "endpoint:mumble.example.com:" + Constants.DEFAULT_PORT + ":charlie",
            ChatLogFeature.serverKey(Server(-1, "Zero", "mumble.example.com", 0, "charlie", "")),
        )
    }

    @Test fun constants() {
        assertEquals(500, ChatLogFeature.MAX_CHAT_LOG_SIZE)
        assertEquals(10, ChatLogFeature.MAX_CACHED_SERVERS)
    }

    @Test fun messageLogCapacityEviction() {
        val f = Fixture()
        f.start()
        repeat(550) { i -> f.sent("msg-$i") }
        val log = f.feature.currentMessages()
        assertEquals(500, log.size)
        assertEquals("msg-50", log[0].body)
        assertEquals("msg-549", log.last().body)
        f.close()
    }

    @Test fun serverSwitchingAndPersistence() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.sent("s1-msg1")
        f.sent("s1-msg2")
        assertEquals(2, f.feature.currentMessages().size)

        f.clientState.value = ClientState.Connecting(target(2), 0)
        assertTrue(f.feature.currentMessages().isEmpty())
        f.sent("s2-msg1")
        f.sent("s2-msg2")
        f.sent("s2-msg3")
        assertEquals(3, f.feature.currentMessages().size)

        f.clientState.value = ClientState.Connecting(target(1), 0)
        assertEquals("s1-msg1", f.feature.currentMessages()[0].body)
        f.clientState.value = ClientState.Connecting(target(2), 0)
        assertEquals(3, f.feature.currentMessages().size)
        f.close()
    }

    @Test fun clearIsConsistentAcrossServerSwitches() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.sent("old-msg-1")
        f.feature.clear()
        assertTrue(f.feature.currentMessages().isEmpty())
        f.sent("new-msg-1")
        assertEquals(1, f.feature.currentMessages().size)
        f.clientState.value = ClientState.Connecting(target(2), 0)
        f.sent("s2-msg")
        f.clientState.value = ClientState.Connecting(target(1), 0)
        assertEquals("new-msg-1", f.feature.currentMessages().single().body)
        f.close()
    }

    @Test fun lruServerCacheEviction() {
        val f = Fixture()
        f.start()
        for (id in 1L..11L) {
            f.clientState.value = ClientState.Connecting(target(id), 0)
            f.sent("msg-from-server-$id")
        }
        f.clientState.value = ClientState.Connecting(target(1), 0)
        assertTrue(f.feature.currentMessages().isEmpty())
        f.clientState.value = ClientState.Connecting(target(2), 0)
        assertEquals("msg-from-server-2", f.feature.currentMessages().single().body)
        f.close()
    }

    @Test fun readIsADefensiveSnapshot() {
        val f = Fixture()
        f.start()
        f.sent("msg-1")
        val snapshot = f.feature.currentMessages()
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableList<Any>).add("illegal-add")
            org.junit.Assert.fail("currentMessages() must be unmodifiable")
        } catch (expected: UnsupportedOperationException) {
        }
        f.sent("msg-2")
        assertEquals(1, snapshot.size)
        assertEquals(2, f.feature.currentMessages().size)
        f.close()
    }

    @Test fun receivedTextBecomesAValueEntryWithResolvedNames() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        kotlinx.coroutines.runBlocking {
            f.events.emit(
                SessionEventRecord(
                    SessionEvent.TextMessageReceived(
                        actor = 2,
                        actorName = "Alice",
                        channels = listOf(0),
                        trees = emptyList(),
                        users = listOf(3),
                        message = "hi all",
                        receivedAtMillis = 1234L,
                    ),
                    goldenState(),
                ),
            )
        }
        val entry = f.feature.currentMessages().single() as IChatMessage.TextMessage
        assertEquals("Alice", entry.actorName)
        assertEquals(listOf("Root", "Bob"), entry.targetNames)
        assertEquals("hi all", entry.body)
        assertEquals(1234L, entry.receivedTime)
        assertFalse(entry.isSelfAuthored)
        f.close()
    }

    @Test fun receivedTextFromTheSelfSessionIsSelfAuthored() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        kotlinx.coroutines.runBlocking {
            f.events.emit(
                SessionEventRecord(
                    SessionEvent.TextMessageReceived(
                        actor = 1,
                        actorName = "Self",
                        channels = listOf(0),
                        trees = emptyList(),
                        users = emptyList(),
                        message = "echo",
                        receivedAtMillis = 1L,
                    ),
                    goldenState(),
                ),
            )
        }
        val entry = f.feature.currentMessages().single() as IChatMessage.TextMessage
        assertTrue("an echo of the self user keeps today's right alignment", entry.isSelfAuthored)
        f.close()
    }

    /** One received line from [actor] named [actorName] against the golden state. */
    private fun Fixture.received(
        actor: Int?,
        actorName: String,
        channels: List<Int> = listOf(0),
        trees: List<Int> = emptyList(),
        users: List<Int> = emptyList(),
    ): IChatMessage.TextMessage {
        kotlinx.coroutines.runBlocking {
            events.emit(
                SessionEventRecord(
                    SessionEvent.TextMessageReceived(actor, actorName, channels, trees, users, "x", 1L),
                    goldenState(),
                ),
            )
        }
        return feature.currentMessages().last() as IChatMessage.TextMessage
    }

    @Test fun selfAuthorshipUsesTheSessionThenFallsBackToTheName() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        // No actor session: the name check decides.
        assertTrue(f.received(actor = null, actorName = "Self").isSelfAuthored)
        assertFalse(f.received(actor = null, actorName = "Alice").isSelfAuthored)
        // A session wins over a matching name: another user named Self.
        assertFalse(f.received(actor = 2, actorName = "Self").isSelfAuthored)
        f.close()
    }

    @Test fun unknownTargetIdsAreSkippedInOrder() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        val entry = f.received(
            actor = 2,
            actorName = "Alice",
            channels = listOf(99, 0),
            trees = listOf(98),
            users = listOf(77, 3),
        )
        assertEquals(listOf("Root", "Bob"), entry.targetNames)
        f.close()
    }

    @Test fun sentTextIsLoggedOnceWithTheSelfName() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.sent("hello", channels = listOf(0))
        val entries = f.feature.currentMessages()
        assertEquals(1, entries.size)
        val entry = entries.single() as IChatMessage.TextMessage
        assertEquals("Self", entry.actorName)
        assertEquals(listOf("Root"), entry.targetNames)
        assertEquals("hello", entry.body)
        assertTrue(entry.isSelfAuthored)
        f.close()
    }

    @Test fun reconnectingThenConnectedLogsDisconnectAndReconnect() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.clientState.value = ClientState.Reconnecting(
            target(1), 2, 0L, ClientError.Connection("boom", null), null,
        )
        assertEquals(1, f.feature.currentMessages().size)
        assertTrue(f.feature.currentMessages().last().body!!.contains("boom"))
        f.clientState.value = connectedState().copy(target = target(1))
        assertEquals(2, f.feature.currentMessages().size)
        assertEquals("Reconnected", f.feature.currentMessages().last().body)
        f.close()
    }

    @Test fun failedTrustAddsNoLineAndClearsTheReconnectLatch() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.clientState.value = ClientState.Reconnecting(
            target(1), 1, 0L, ClientError.Connection("boom", null), null,
        )
        val afterReconnecting = f.feature.currentMessages().size
        // Trust is a dialog, not a chat line: displayMessage() returns null.
        f.clientState.value = ClientState.Failed(target(1), trustError())
        assertEquals(afterReconnecting, f.feature.currentMessages().size)
        f.clientState.value = connectedState().copy(target = target(1))
        assertEquals(afterReconnecting, f.feature.currentMessages().size)
        f.close()
    }

    @Test fun idleResetsTheReconnectLatch() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.clientState.value = ClientState.Reconnecting(
            target(1), 1, 0L, ClientError.Connection("boom", null), null,
        )
        val count = f.feature.currentMessages().size
        f.clientState.value = ClientState.Idle
        f.clientState.value = connectedState().copy(target = target(1))
        assertEquals(count, f.feature.currentMessages().size)
        f.close()
    }

    @Test fun voiceEventsReachTheChatLog() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        kotlinx.coroutines.runBlocking {
            f.voiceEvents.emit(VoiceEvent.EncoderLimited(72000, 40000, 2))
        }
        assertEquals(1, f.feature.currentMessages().size)
        assertTrue(f.feature.currentMessages().last().body!!.contains("72"))
        f.close()
    }

    @Test fun entryPointsRequireAStartedMainScope() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { f.feature.clear() }
        f.close()
    }

    @Test fun clearWorksWhenCalledOffTheMainThread() {
        val f = Fixture()
        f.start()
        f.clientState.value = ClientState.Connecting(target(1), 0)
        f.sent("from-worker")
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            f.feature.clear()
        }
        assertTrue(f.feature.currentMessages().isEmpty())
        f.close()
    }

    @Test fun eventLineIsAppended() {
        val f = Fixture()
        f.start()
        f.scope.launch {
            f.events.emit(SessionEventRecord(SessionEvent.UserJoinedServer(2, "Alice"), goldenState()))
            kotlinx.coroutines.yield()
        }
        assertEquals(
            "<font color=\"#33b5e5\">Alice</font> connected.",
            f.feature.currentMessages().last().body,
        )
        f.close()
    }

    @Test fun appLogLinesReachTheChatLog() {
        val f = Fixture()
        f.start()
        f.scope.launch { f.appLog.emit(AppLogLine("Could not start TTS", warning = true))
        kotlinx.coroutines.yield() }
        assertEquals("Could not start TTS", f.feature.currentMessages().last().body)
        f.close()
    }

    @Test fun slowLineSinkNeverStallsTheEventEmitter() {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(lineSink = { gate.await() })
        f.start()
        val record = SessionEventRecord(SessionEvent.WelcomeText("hello"), goldenState())
        kotlinx.coroutines.runBlocking {
            withTimeout(2_000) {
                repeat(300) { f.events.emit(record) }
            }
        }
        gate.complete(Unit)
        f.close()
    }
}
