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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.Session
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.VoiceTargetMode
import se.lublin.humla.state.WhisperTarget
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.Settings
import se.lublin.mumla.TalkKeyEvent
import se.lublin.mumla.feature.testSnapshot

/** The channel screen's UI state, talk-key hand-off and shared chat target. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture {
        val clientState = MutableStateFlow<ClientState>(
            ClientState.Connected(emptyConnectTarget(), mock(Session::class.java)),
        )
        val sessionState = MutableStateFlow(emptySessionState())
        val talk = MutableStateFlow(TalkActivity.initial())
        val settings = MutableStateFlow(testSnapshot())
        val talkKeys = ArrayList<TalkKeyEvent>()
        val voiceTargets = ArrayList<Int>()
        val unregistered = ArrayList<Int>()
        val viewModel = ChannelViewModel(
            clientState = clientState,
            sessionState = sessionState,
            talkActivity = talk,
            settings = settings,
            emitTalkKey = { talkKeys.add(it) },
            setVoiceTargetId = { voiceTargets.add(it) },
            unregisterVoiceTarget = { unregistered.add(it) },
        )
    }

    /** WhileSubscribed: something must collect before the state has a value. */
    private fun TestScope.subscribe(fixture: Fixture) {
        backgroundScope.launch { fixture.viewModel.state.collect {} }
        backgroundScope.launch { fixture.viewModel.chatTargetDisplay.collect {} }
        testScheduler.runCurrent()
    }

    private fun user(
        session: Int,
        name: String,
        channelId: Int = 0,
        muted: Boolean = false,
        suppressed: Boolean = false,
        selfMuted: Boolean = false,
    ): UserInfo = UserInfo(
        session = session,
        userId = session,
        name = name,
        channelId = channelId,
        certHash = null,
        comment = null,
        commentHash = null,
        texture = null,
        textureHash = null,
        muted = muted,
        deafened = false,
        suppressed = suppressed,
        selfMuted = selfMuted,
        selfDeafened = false,
        prioritySpeaker = false,
        recording = false,
        listeningChannels = persistentSetOf(),
        localMuted = false,
        localIgnored = false,
    )

    private fun channel(id: Int, name: String): ChannelInfo = ChannelInfo(
        id = id,
        parentId = if (id == 0) null else 0,
        name = name,
        position = 0,
        temporary = false,
        description = null,
        descriptionHash = null,
        links = persistentSetOf(),
        maxUsers = 0,
        enterRestricted = false,
        canEnter = true,
        permissions = 0,
    )

    private fun Fixture.synced(
        selfSession: Int = 1,
        selfMuted: Boolean = false,
        muted: Boolean = false,
        suppressed: Boolean = false,
        voiceTargetId: Int = 0,
        whisperTargets: Map<Int, WhisperTarget> = emptyMap(),
        users: List<UserInfo> = listOf(user(selfSession, "alice", muted = muted, suppressed = suppressed, selfMuted = selfMuted)),
        channels: List<ChannelInfo> = listOf(channel(0, "Root"), channel(5, "General")),
    ) {
        val server = emptySessionState().server.copy(
            selfSession = selfSession,
            users = persistentMapOf<Int, UserInfo>().putAll(users.associateBy { it.session }),
            channels = persistentMapOf<Int, ChannelInfo>().putAll(channels.associateBy { it.id }),
        )
        sessionState.value = emptySessionState().copy(
            server = server,
            local = emptySessionState().local.copy(
                voiceTargetId = voiceTargetId,
                whisperTargets = persistentMapOf<Int, WhisperTarget>().putAll(whisperTargets),
            ),
        )
    }

    @Test
    fun pttButtonFollowsTheModeTheSettingAndTheMuteGate() = runTest {
        val fixture = Fixture()
        fixture.synced()
        subscribe(fixture)
        assertTrue(fixture.viewModel.state.value.showPttButton)

        fixture.settings.value = testSnapshot(audioInputMethod = Settings.ARRAY_INPUT_METHOD_VOICE)
        testScheduler.runCurrent()
        assertFalse(fixture.viewModel.state.value.showPttButton)

        fixture.settings.value = testSnapshot()
        fixture.synced(selfMuted = true)
        testScheduler.runCurrent()
        assertTrue(fixture.viewModel.state.value.muted)
        assertFalse(fixture.viewModel.state.value.showPttButton)

        fixture.synced(muted = true)
        testScheduler.runCurrent()
        assertFalse(fixture.viewModel.state.value.showPttButton)

        fixture.synced(suppressed = true)
        testScheduler.runCurrent()
        assertFalse(fixture.viewModel.state.value.showPttButton)
    }

    @Test
    fun hiddenPttButtonSettingHidesIt() = runTest {
        val fixture = Fixture()
        fixture.synced()
        fixture.settings.value = testSnapshot().copy(hidePtt = true)
        subscribe(fixture)
        assertFalse(fixture.viewModel.state.value.showPttButton)
    }

    @Test
    fun buttonHeightIsClampedLikeTheSettingsFacade() = runTest {
        val fixture = Fixture()
        fixture.synced()
        fixture.settings.value = testSnapshot().copy(pttButtonHeight = 0)
        subscribe(fixture)
        assertEquals(Settings.DEFAULT_PTT_BUTTON_HEIGHT, fixture.viewModel.state.value.pttButtonHeight)
        fixture.settings.value = testSnapshot().copy(pttButtonHeight = 10)
        testScheduler.runCurrent()
        assertEquals(Settings.MIN_PTT_BUTTON_HEIGHT, fixture.viewModel.state.value.pttButtonHeight)
        fixture.settings.value = testSnapshot().copy(pttButtonHeight = 1000)
        testScheduler.runCurrent()
        assertEquals(Settings.MAX_PTT_BUTTON_HEIGHT, fixture.viewModel.state.value.pttButtonHeight)
        fixture.settings.value = testSnapshot().copy(pttButtonHeight = 80)
        testScheduler.runCurrent()
        assertEquals(80, fixture.viewModel.state.value.pttButtonHeight)
    }

    @Test
    fun selfTalkingOnlyWhileConnected() = runTest {
        val fixture = Fixture()
        fixture.synced()
        subscribe(fixture)
        fixture.talk.value = TalkActivity.initial().copy(selfTalking = true)
        testScheduler.runCurrent()
        assertTrue(fixture.viewModel.state.value.selfTalking)
        fixture.clientState.value = ClientState.Idle
        testScheduler.runCurrent()
        assertFalse(fixture.viewModel.state.value.selfTalking)
    }

    @Test
    fun whisperTargetOnlyShowsInWhisperMode() = runTest {
        val fixture = Fixture()
        fixture.synced(
            voiceTargetId = 2,
            whisperTargets = mapOf(
                2 to WhisperTarget(channelId = 5, includeLinked = false, includeSubchannels = false, group = null),
            ),
        )
        subscribe(fixture)
        assertEquals("General", fixture.viewModel.state.value.whisperTargetName)
        assertEquals(VoiceTargetMode.WHISPER, fixture.viewModel.state.value.voiceTargetMode)
        assertEquals(2, fixture.viewModel.state.value.voiceTargetId)

        fixture.synced(voiceTargetId = 0)
        testScheduler.runCurrent()
        assertNull(fixture.viewModel.state.value.whisperTargetName)
        assertEquals(VoiceTargetMode.NORMAL, fixture.viewModel.state.value.voiceTargetMode)
    }

    @Test
    fun clearVoiceTargetOnlyActsInWhisperMode() = runTest {
        val fixture = Fixture()
        fixture.synced(
            voiceTargetId = 2,
            whisperTargets = mapOf(
                2 to WhisperTarget(channelId = 5, includeLinked = false, includeSubchannels = false, group = null),
            ),
        )
        subscribe(fixture)
        fixture.viewModel.clearVoiceTarget()
        assertEquals(listOf(0), fixture.voiceTargets)
        assertEquals(listOf(2), fixture.unregistered)

        fixture.voiceTargets.clear()
        fixture.unregistered.clear()
        fixture.synced(voiceTargetId = 0)
        testScheduler.runCurrent()
        fixture.viewModel.clearVoiceTarget()
        assertTrue(fixture.voiceTargets.isEmpty())
        assertTrue(fixture.unregistered.isEmpty())
    }

    @Test
    fun talkKeysGoIntoTheHandOffFlow() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        fixture.viewModel.talkDown()
        fixture.viewModel.talkUp()
        fixture.viewModel.talkCancel()
        assertEquals(
            listOf(TalkKeyEvent.Down, TalkKeyEvent.Up, TalkKeyEvent.Cancel),
            fixture.talkKeys,
        )
    }

    @Test
    fun chatTargetDefaultsToTheSelfChannel() = runTest {
        val fixture = Fixture()
        fixture.synced(users = listOf(user(1, "alice", channelId = 5)))
        subscribe(fixture)
        val display = fixture.viewModel.chatTargetDisplay.value
        assertNull(display.target)
        assertEquals("General", display.name)
    }

    @Test
    fun chatTargetResolvesUserAndChannelNames() = runTest {
        val fixture = Fixture()
        fixture.synced(
            users = listOf(user(1, "alice", channelId = 5), user(2, "bob", channelId = 5)),
        )
        subscribe(fixture)

        fixture.viewModel.setChatTarget(ChatTarget.user(2))
        testScheduler.runCurrent()
        assertEquals("bob", fixture.viewModel.chatTargetDisplay.value.name)
        assertEquals(2, fixture.viewModel.chatTargetDisplay.value.target?.session)

        fixture.viewModel.setChatTarget(ChatTarget.channel(5))
        testScheduler.runCurrent()
        assertEquals("General", fixture.viewModel.chatTargetDisplay.value.name)
        assertEquals(5, fixture.viewModel.chatTargetDisplay.value.target?.channelId)

        fixture.viewModel.setChatTarget(ChatTarget.user(99))
        testScheduler.runCurrent()
        assertNull(fixture.viewModel.chatTargetDisplay.value.name)
    }

    @Test
    fun bluetoothMenuVisibilityFollowsTheConnection() = runTest {
        val fixture = Fixture()
        fixture.synced()
        fixture.settings.value = testSnapshot().copy(bluetoothHeadset = true)
        subscribe(fixture)
        assertTrue(fixture.viewModel.state.value.bluetoothMenuVisible)
        assertTrue(fixture.viewModel.state.value.bluetoothHeadset)
        fixture.clientState.value = ClientState.Idle
        testScheduler.runCurrent()
        assertFalse(fixture.viewModel.state.value.bluetoothMenuVisible)
    }
}
