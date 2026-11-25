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

package se.lublin.mumla.channel

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.Session
import se.lublin.humla.client.testTarget
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.state.ServerSettingsInfo
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.emptySessionState
import se.lublin.mumla.AppLogLine
import se.lublin.mumla.feature.ChatLogFeature
import se.lublin.mumla.service.IChatMessage

/** Chat routing, size checks, clearing, and one log entry per sent message. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private data class SentCall(val channels: List<Int>, val users: List<Int>, val message: String)

    private class Fixture(settings: ServerSettingsInfo? = null) {
        val clientState = MutableStateFlow<ClientState>(
            ClientState.Connected(testTarget(), mock(Session::class.java)),
        )
        val sessionState = MutableStateFlow(syncedState(settings))
        val target = MutableStateFlow(ChatTargetDisplay())
        val messages = MutableStateFlow<List<IChatMessage>>(emptyList())
        val sent = ArrayList<SentCall>()
        var cleared = 0
        val viewModel = ChatViewModel(
            messages = messages,
            target = target,
            clientState = clientState,
            sessionState = sessionState,
            sendText = { channels, _, users, message ->
                sent.add(SentCall(channels, users, message))
            },
            clearLog = { cleared++ },
        )
    }

    @Test
    fun defaultTargetIsTheSelfChannel() = runTest {
        val fixture = Fixture()
        assertEquals(ChatSendResult.SENT, fixture.viewModel.send("hello"))
        assertEquals(listOf(SentCall(listOf(0), emptyList(), "hello")), fixture.sent)
    }

    @Test
    fun channelAndUserTargetsRouteById() = runTest {
        val fixture = Fixture()
        fixture.target.value = ChatTargetDisplay(ChatTarget.channel(5))
        assertEquals(ChatSendResult.SENT, fixture.viewModel.send("to channel"))
        fixture.target.value = ChatTargetDisplay(ChatTarget.user(2))
        assertEquals(ChatSendResult.SENT, fixture.viewModel.send("to user"))
        assertEquals(
            listOf(
                SentCall(listOf(5), emptyList(), "to channel"),
                SentCall(emptyList(), listOf(2), "to user"),
            ),
            fixture.sent,
        )
    }

    @Test
    fun noConnectionRefusesTheSend() = runTest {
        val fixture = Fixture()
        fixture.clientState.value = ClientState.Idle
        assertEquals(ChatSendResult.NOT_CONNECTED, fixture.viewModel.send("hello"))
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun everyStateButConnectedRefusesTheSend() = runTest {
        val error = ClientError.Connection("boom", null)
        for (state in listOf(
            ClientState.Connecting(testTarget(), 0),
            ClientState.Reconnecting(testTarget(), 1, 0L, error, null),
            ClientState.Failed(testTarget(), error),
        )) {
            val fixture = Fixture()
            fixture.clientState.value = state
            assertEquals("$state", ChatSendResult.NOT_CONNECTED, fixture.viewModel.send("hello"))
            assertFalse("$state", fixture.viewModel.isConnected())
            assertTrue("$state", fixture.sent.isEmpty())
        }
    }

    @Test
    fun theImagePickerGateFollowsTheConnection() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.viewModel.isConnected())
        fixture.clientState.value = ClientState.Idle
        assertFalse(fixture.viewModel.isConnected())
    }

    @Test
    fun noSelfChannelRefusesTheSend() = runTest {
        val fixture = Fixture()
        fixture.sessionState.value = emptySessionState()
        assertEquals(ChatSendResult.NO_TARGET, fixture.viewModel.send("hello"))
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun textOverTheServerLimitIsRefused() = runTest {
        val fixture = Fixture(settings = limits(messageLength = 5, imageMessageLength = 100))
        assertEquals(ChatSendResult.TOO_LARGE, fixture.viewModel.send("123456"))
        assertTrue(fixture.sent.isEmpty())
        assertEquals(ChatSendResult.SENT, fixture.viewModel.send("12345"))
        assertEquals(1, fixture.sent.size)
    }

    @Test
    fun imageSendsUseTheImageLimit() = runTest {
        val fixture = Fixture(settings = limits(messageLength = 5, imageMessageLength = 10))
        assertEquals(ChatSendResult.SENT, fixture.viewModel.send("<img/>", image = true))
        assertEquals(1, fixture.sent.size)
        assertEquals(ChatSendResult.TOO_LARGE, fixture.viewModel.send("12345678901", image = true))
        assertEquals(1, fixture.sent.size)
    }

    @Test
    fun anImageNeedsKnownServerLimits() = runTest {
        // Today's image dialog aborted when the server settings were absent.
        val fixture = Fixture(settings = null)
        assertEquals(
            ChatSendResult.LIMITS_UNKNOWN,
            fixture.viewModel.send("<img/>", image = true),
        )
        assertTrue(fixture.sent.isEmpty())
        // Text sends are not gated on the settings (there was no client-side
        // text length check before this step).
        assertEquals(ChatSendResult.SENT, fixture.viewModel.send("hi"))
    }

    @Test
    fun limitsAreReadFromState() = runTest {
        val fixture = Fixture(settings = limits(messageLength = 5, imageMessageLength = 700))
        assertEquals(ChatLimits(messageLength = 5, imageMessageLength = 700), fixture.viewModel.limits())
        fixture.sessionState.value = syncedState(limits(messageLength = 8, imageMessageLength = 900))
        assertEquals(ChatLimits(messageLength = 8, imageMessageLength = 900), fixture.viewModel.limits())
    }

    @Test
    fun clearGoesToTheFeature() = runTest {
        val fixture = Fixture()
        fixture.viewModel.clear()
        assertEquals(1, fixture.cleared)
    }

    /**
     * The sent message reaches the log through the session's
     * TextMessageSent record, exactly once, with no direct append.
     */
    @Test
    fun sentMessageAppearsInTheLogOnce() = runTest {
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 16)
        val clientState = MutableStateFlow<ClientState>(
            ClientState.Connected(testTarget(), mock(Session::class.java)),
        )
        val state = syncedState(null)
        val feature = ChatLogFeature(
            context = RuntimeEnvironment.getApplication(),
            clientState = clientState,
            events = events,
            voiceEvents = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 1),
            appLog = MutableSharedFlow<AppLogLine>(extraBufferCapacity = 1),
        )
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        feature.start(scope)
        try {
            val viewModel = ChatViewModel(
                messages = feature.messages,
                target = MutableStateFlow(ChatTargetDisplay()),
                clientState = clientState,
                sessionState = MutableStateFlow(state),
                sendText = { channels, trees, users, message ->
                    events.tryEmit(
                        SessionEventRecord(
                            SessionEvent.TextMessageSent(channels, trees, users, message, 0L),
                            state,
                        ),
                    )
                },
                clearLog = { feature.clear() },
            )
            assertEquals(ChatSendResult.SENT, viewModel.send("hello"))
            val entries = feature.messages.value
            assertEquals(1, entries.size)
            val text = entries.single() as IChatMessage.TextMessage
            assertEquals("hello", text.body)
            assertTrue(text.isSelfAuthored)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        fun limits(messageLength: Int, imageMessageLength: Int) = ServerSettingsInfo(
            allowHtml = true,
            messageLength = messageLength,
            imageMessageLength = imageMessageLength,
            maxUsers = 100,
        )

        fun syncedState(settings: ServerSettingsInfo?): se.lublin.humla.state.SessionState {
            val self = UserInfo(
                session = 1,
                userId = 1,
                name = "Self",
                channelId = 0,
                certHash = null,
                comment = null,
                commentHash = null,
                texture = null,
                textureHash = null,
                muted = false,
                deafened = false,
                suppressed = false,
                selfMuted = false,
                selfDeafened = false,
                prioritySpeaker = false,
                recording = false,
                listeningChannels = persistentSetOf(),
                localMuted = false,
                localIgnored = false,
            )
            val base = emptySessionState()
            return base.copy(
                server = base.server.copy(
                    selfSession = 1,
                    users = persistentMapOf(1 to self),
                    info = base.server.info.copy(settings = settings),
                ),
            )
        }
    }
}
