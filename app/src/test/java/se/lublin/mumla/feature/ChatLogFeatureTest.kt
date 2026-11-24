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
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.AppLogLine

/** Chat-log history, LRU policy, event formatting and backpressure. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatLogFeatureTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture(lineSink: (suspend (ChatLine) -> Unit)? = null) {
        val clientState = MutableStateFlow<ClientState>(ClientState.Idle)
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 256)
        val voiceEvents = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
        val appLog = MutableSharedFlow<AppLogLine>(extraBufferCapacity = 16)
        val callbacks = HumlaCallbacks()
        val formatter = ChatEventFormatter(RuntimeEnvironment.getApplication())
        val feature = ChatLogFeature(
            RuntimeEnvironment.getApplication(), clientState, events, voiceEvents,
            appLog, callbacks, formatter, lineSink,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun start() {
            callbacks.registerObserver(feature.receivedTextObserver)
            feature.start(scope)
        }

        fun close() = scope.cancel()
    }

    private fun target(id: Long) = testTarget().copy(
        server = testTarget().server.copy(serverId = id),
    )

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
        repeat(550) { i -> f.feature.addSentText(Message("msg-$i")) }
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
        f.feature.addSentText(Message("s1-msg1"))
        f.feature.addSentText(Message("s1-msg2"))
        assertEquals(2, f.feature.currentMessages().size)

        f.clientState.value = ClientState.Connecting(target(2), 0)
        assertTrue(f.feature.currentMessages().isEmpty())
        f.feature.addSentText(Message("s2-msg1"))
        f.feature.addSentText(Message("s2-msg2"))
        f.feature.addSentText(Message("s2-msg3"))
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
        f.feature.addSentText(Message("old-msg-1"))
        f.feature.clear()
        assertTrue(f.feature.currentMessages().isEmpty())
        f.feature.addSentText(Message("new-msg-1"))
        assertEquals(1, f.feature.currentMessages().size)
        f.clientState.value = ClientState.Connecting(target(2), 0)
        f.feature.addSentText(Message("s2-msg"))
        f.clientState.value = ClientState.Connecting(target(1), 0)
        assertEquals("new-msg-1", f.feature.currentMessages().single().body)
        f.close()
    }

    @Test fun lruServerCacheEviction() {
        val f = Fixture()
        f.start()
        for (id in 1L..11L) {
            f.clientState.value = ClientState.Connecting(target(id), 0)
            f.feature.addSentText(Message("msg-from-server-$id"))
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
        f.feature.addSentText(Message("msg-1"))
        val snapshot = f.feature.currentMessages()
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableList<Any>).add(Message("illegal-add"))
            org.junit.Assert.fail("currentMessages() must be unmodifiable")
        } catch (expected: UnsupportedOperationException) {
        }
        f.feature.addSentText(Message("msg-2"))
        assertEquals(1, snapshot.size)
        assertEquals(2, f.feature.currentMessages().size)
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
        assertThrows(IllegalStateException::class.java) {
            f.feature.addSentText(Message("before-start"))
        }
        f.close()
    }

    @Test fun entryPointsWorkWhenCalledOffTheMainThread() {
        val f = Fixture()
        f.start()
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            f.feature.addSentText(Message("from-worker"))
            f.feature.clear()
        }
        assertTrue(f.feature.currentMessages().isEmpty())
        f.close()
    }

    @Test fun eventLineIsAppendedAndForwarded() {
        val f = Fixture()
        f.start()
        val forwarded = ArrayList<String>()
        f.callbacks.registerObserver(object : HumlaObserver() {
            override fun onLogInfo(message: String?) { forwarded.add(message ?: "") }
        })
        f.scope.launch {
            f.events.emit(SessionEventRecord(SessionEvent.UserJoinedServer(2, "Alice"), goldenState()))
        kotlinx.coroutines.yield()
        }
        assertEquals(
            "<font color=\"#33b5e5\">Alice</font> connected.",
            f.feature.currentMessages().last().body,
        )
        assertEquals(1, forwarded.size)
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
