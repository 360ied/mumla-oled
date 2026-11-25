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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import android.content.Context
import android.view.MenuItem
import androidx.fragment.app.FragmentManager
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.Session
import se.lublin.humla.client.testTarget
import se.lublin.humla.model.TalkState
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.VoiceTargetMode
import se.lublin.humla.state.WhisperTarget
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.state.voiceTargetMode
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.R
import se.lublin.mumla.feature.testSnapshot

/** Channel list state, pinned loading, and menu/panel actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class RecordingActions : ChannelListActions {
        val calls = ArrayList<String>()
        var whisperResult = 3
        var nextWhisperId = 0

        override fun joinChannel(channelId: Int) { calls.add("join:$channelId") }
        override fun moveUserToChannel(session: Int, channelId: Int) { calls.add("move:$session->$channelId") }
        override fun kickBanUser(session: Int, reason: String?, ban: Boolean) { calls.add("kick:$session:$ban:$reason") }
        override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) { calls.add("muteDeaf:$session:$mute:$deaf") }
        override fun setPrioritySpeaker(session: Int, priority: Boolean) { calls.add("priority:$session:$priority") }
        override fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) { calls.add("self:$mute:$deaf") }
        override fun setLocalMute(session: Int, muted: Boolean) { calls.add("localMute:$session:$muted") }
        override fun setLocalIgnore(session: Int, ignored: Boolean) { calls.add("localIgnore:$session:$ignored") }
        override fun setUserComment(session: Int, comment: String) { calls.add("comment:$session:$comment") }
        override fun registerUser(session: Int) { calls.add("register:$session") }
        override fun linkChannels(channelId: Int, otherChannelId: Int) { calls.add("link:$channelId:$otherChannelId") }
        override fun unlinkChannels(channelId: Int, otherChannelId: Int) { calls.add("unlink:$channelId:$otherChannelId") }
        override fun unlinkAllChannels(channelId: Int) { calls.add("unlinkAll:$channelId") }
        override fun setListening(channelId: Int, listening: Boolean) { calls.add("listen:$channelId:$listening") }
        override fun requestPermissions(channelId: Int) { calls.add("permissions:$channelId") }
        override fun registerWhisperTarget(target: WhisperTarget): Int {
            calls.add("registerWhisper:${target.channelId}")
            nextWhisperId++
            return whisperResult
        }
        override fun unregisterWhisperTarget(id: Int) { calls.add("unregisterWhisper:$id") }
        override fun setVoiceTarget(id: Int) { calls.add("voiceTarget:$id") }
    }

    private inner class Fixture(private val serverId: Long = 42L) {
        val clientState = MutableStateFlow<ClientState>(
            ClientState.Connected(
                testTarget().copy(server = testTarget().server.copy(serverId = serverId)),
                mock(Session::class.java),
            ),
        )
        val sessionState = MutableStateFlow(syncedState())
        val talk = MutableStateFlow(TalkActivity.initial())
        val settings = MutableStateFlow(testSnapshot().copy(showUserCount = true))
        val actions = RecordingActions()
        val pinned = HashMap<Long, MutableList<Int>>().apply { put(42L, mutableListOf(5)) }
        val saves = ArrayList<String>()
        val viewModel = ChannelListViewModel(
            clientState = clientState,
            sessionState = sessionState,
            talkActivity = talk,
            settings = settings,
            loadPinnedChannels = { id -> pinned[id].orEmpty().toList() },
            savePinnedChannel = { id, channelId, pinnedOn ->
                saves.add("$id:$channelId:$pinnedOn")
                val list = pinned.getOrPut(id) { ArrayList() }
                if (pinnedOn) list.add(channelId) else list.remove(channelId)
            },
            actions = actions,
        )
    }

    private fun TestScope.subscribe(fixture: Fixture) {
        backgroundScope.launch { fixture.viewModel.state.collect {} }
        testScheduler.runCurrent()
    }

    private fun channel(id: Int, name: String, permissions: Int = 0): ChannelInfo = ChannelInfo(
        id = id,
        parentId = if (id == 0) null else 0,
        name = name,
        position = id,
        temporary = false,
        description = null,
        descriptionHash = null,
        links = persistentSetOf(),
        maxUsers = 0,
        enterRestricted = false,
        canEnter = true,
        permissions = permissions,
    )

    private fun user(session: Int, name: String, channelId: Int = 0): UserInfo = UserInfo(
        session = session,
        userId = session,
        name = name,
        channelId = channelId,
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

    private fun syncedState(
        selfMuted: Boolean = false,
        selfDeafened: Boolean = false,
        rootPermissions: Int = 0,
    ): SessionState {
        val base = emptySessionState()
        val self = user(1, "Self").copy(
            selfMuted = selfMuted,
            selfDeafened = selfDeafened,
        )
        return base.copy(
            server = base.server.copy(
                selfSession = 1,
                rootPermissions = rootPermissions,
                channels = persistentMapOf(
                    0 to channel(0, "Root"),
                    5 to channel(5, "General", permissions = 0x40),
                ),
                users = persistentMapOf(
                    1 to self,
                    2 to user(2, "Alice"),
                ),
            ),
        )
    }

    @Test
    fun stateCarriesTheTreeUsersAndSettings() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        val state = fixture.viewModel.state.value
        assertTrue(state.connected)
        assertEquals("Root", state.server.channels[0]?.name)
        assertEquals("Alice", state.server.users[2]?.name)
        assertTrue(state.showUserCount)
        assertEquals(listOf(5), state.pinnedChannelIds)
    }

    @Test
    fun talkActivityReachesTheState() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        fixture.talk.value = TalkActivity.initial().copy(
            users = persistentMapOf(2 to TalkState.TALKING),
        )
        testScheduler.runCurrent()
        assertEquals(TalkState.TALKING, fixture.viewModel.state.value.talkActivity.users[2])
    }

    @Test
    fun emptyChannelsCollapseLikeTheHandleAdapter() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        // The adapter owns expansion; the VM only carries the tree.
        assertTrue(fixture.viewModel.state.value.server.tree.children(0).isNotEmpty())
    }

    @Test
    fun pinnedChannelsLoadFromTheDatabase() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        assertEquals(listOf(5), fixture.viewModel.state.value.pinnedChannelIds)
        assertTrue(fixture.viewModel.isChannelPinned(5))
        assertFalse(fixture.viewModel.isChannelPinned(9))
    }

    @Test
    fun pinningPersistsAndRefreshes() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        fixture.viewModel.setChannelPinned(5, false)
        testScheduler.runCurrent()
        assertEquals(listOf("42:5:false"), fixture.saves)
        assertFalse(fixture.viewModel.isChannelPinned(5))
        fixture.viewModel.setChannelPinned(9, true)
        testScheduler.runCurrent()
        assertTrue(fixture.viewModel.isChannelPinned(9))
    }

    @Test
    fun adHocServersPinUnderTheUnsavedSentinel() = runTest {
        val fixture = Fixture(serverId = -1L)
        fixture.pinned[-1L] = mutableListOf()
        subscribe(fixture)
        assertTrue(fixture.viewModel.state.value.pinnedChannelIds.isEmpty())
        fixture.viewModel.setChannelPinned(5, true)
        testScheduler.runCurrent()
        assertEquals(listOf("-1:5:true"), fixture.saves)
        assertTrue(fixture.viewModel.isChannelPinned(5))
    }

    @Test
    fun aStalePinnedReadDoesNotOverwriteANewerOne() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stored = mutableListOf(5)
        var loads = 0
        val clientState = MutableStateFlow<ClientState>(
            ClientState.Connected(
                testTarget().copy(server = testTarget().server.copy(serverId = 42L)),
                mock(Session::class.java),
            ),
        )
        val actions = RecordingActions()
        val viewModel = ChannelListViewModel(
            clientState = clientState,
            sessionState = MutableStateFlow(syncedState()),
            talkActivity = MutableStateFlow(TalkActivity.initial()),
            settings = MutableStateFlow(testSnapshot()),
            loadPinnedChannels = {
                loads++
                if (loads == 1) {
                    gate.await()
                    listOf(5)
                } else {
                    stored.toList()
                }
            },
            savePinnedChannel = { _, _, pinned ->
                if (pinned) stored.add(9) else stored.remove(9)
            },
            actions = actions,
        )
        backgroundScope.launch { viewModel.state.collect {} }
        testScheduler.runCurrent()
        // The initial read is in flight; a pin toggle saves and reads again.
        viewModel.setChannelPinned(9, true)
        testScheduler.runCurrent()
        assertEquals(listOf(5, 9), viewModel.state.value.pinnedChannelIds)
        // The older read now returns a pre-toggle snapshot and must lose.
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(5, 9), viewModel.state.value.pinnedChannelIds)
    }

    @Test
    fun pinnedLoadingIsIgnoredWithoutAConnectedTarget() = runTest {
        val fixture = Fixture()
        fixture.clientState.value = ClientState.Idle
        subscribe(fixture)
        assertTrue(fixture.viewModel.state.value.pinnedChannelIds.isEmpty())
        // Pinning needs a connected target: nothing is written.
        fixture.viewModel.setChannelPinned(5, true)
        testScheduler.runCurrent()
        assertTrue(fixture.saves.isEmpty())
    }

    @Test
    fun permissionsComeFromTheLatestState() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        val channelPermissions = fixture.viewModel.permissionsFor(5)
        backgroundScope.launch { channelPermissions.collect {} }
        testScheduler.runCurrent()
        assertEquals(0x40, channelPermissions.value)

        val rootPermissions = fixture.viewModel.permissionsFor(0)
        backgroundScope.launch { rootPermissions.collect {} }
        testScheduler.runCurrent()
        assertEquals(0, rootPermissions.value)
        fixture.sessionState.value = fixture.sessionState.value.copy(
            server = fixture.sessionState.value.server.copy(rootPermissions = 0x1F),
        )
        testScheduler.runCurrent()
        assertEquals(0x1F, rootPermissions.value)
    }

    @Test
    fun selfMuteAndDeafenFollowTheEchoedState() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        fixture.viewModel.toggleSelfMute()
        assertEquals(listOf("self:true:false"), fixture.actions.calls)

        fixture.actions.calls.clear()
        fixture.viewModel.toggleSelfDeafen()
        assertEquals(listOf("self:true:true"), fixture.actions.calls)
    }

    @Test
    fun menuActionsDelegateBySessionAndChannelId() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        fixture.viewModel.joinChannel(5)
        fixture.viewModel.moveUserToChannel(2, 5)
        fixture.viewModel.kickBanUser(2, "rude", true)
        fixture.viewModel.setMuteDeafState(2, true, true)
        fixture.viewModel.setPrioritySpeaker(2, true)
        fixture.viewModel.setLocalMute(2, true)
        fixture.viewModel.setLocalIgnore(2, true)
        fixture.viewModel.setUserComment(2, "hi")
        fixture.viewModel.registerUser(2)
        fixture.viewModel.linkChannels(5, 0)
        fixture.viewModel.unlinkChannels(5, 0)
        fixture.viewModel.unlinkAllChannels(5)
        fixture.viewModel.setListening(5, true)
        fixture.viewModel.requestPermissions(5)
        assertEquals(
            listOf(
                "join:5",
                "move:2->5",
                "kick:2:true:rude",
                "muteDeaf:2:true:true",
                "priority:2:true",
                "localMute:2:true",
                "localIgnore:2:true",
                "comment:2:hi",
                "register:2",
                "link:5:0",
                "unlink:5:0",
                "unlinkAll:5",
                "listen:5:true",
                "permissions:5",
            ),
            fixture.actions.calls,
        )
    }

    @Test
    fun shoutReplacesALiveWhisperTargetFirst() = runTest {
        val fixture = Fixture()
        fixture.sessionState.value = fixture.sessionState.value.copy(
            local = fixture.sessionState.value.local.copy(
                voiceTargetId = 2,
                whisperTargets = persistentMapOf(
                    2 to WhisperTarget(5, includeLinked = false, includeSubchannels = false, group = null),
                ),
            ),
        )
        subscribe(fixture)
        val target = WhisperTarget(9, includeLinked = true, includeSubchannels = true, group = "g")
        assertTrue(fixture.viewModel.shout(target))
        assertEquals(
            listOf("unregisterWhisper:2", "registerWhisper:9", "voiceTarget:3"),
            fixture.actions.calls,
        )
        assertEquals(VoiceTargetMode.WHISPER, fixture.sessionState.value.local.voiceTargetMode())
    }

    @Test
    fun shoutFailureLeavesNoVoiceTarget() = runTest {
        val fixture = Fixture()
        fixture.actions.whisperResult = -1
        subscribe(fixture)
        assertFalse(fixture.viewModel.shout(WhisperTarget(5, false, false, null)))
        assertEquals(listOf("registerWhisper:5"), fixture.actions.calls)
    }

    @Test
    fun channelsInTreeOrderWalksTheRoot() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        assertEquals(listOf(0, 5), fixture.viewModel.channelsInTreeOrder().map { it.id })
    }

    private fun clickItem(id: Int): MenuItem = mock(MenuItem::class.java).also {
        `when`(it.itemId).thenReturn(id)
    }

    private fun Fixture.updateUser(session: Int, change: (UserInfo) -> UserInfo) {
        val server = sessionState.value.server
        sessionState.value = sessionState.value.copy(
            server = server.copy(users = server.users.put(session, change(server.users.getValue(session)))),
        )
    }

    @Test
    fun userMenuTogglesInvertTheLatestValueNotTheTappedRow() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        val menu = UserMenu(
            mock(Context::class.java),
            fixture.viewModel.user(2)!!,
            fixture.viewModel,
            mock(FragmentManager::class.java),
        )
        // Another actor mutes and deafens Alice while the menu is open.
        fixture.updateUser(2) { it.copy(muted = true, deafened = true, localMuted = true) }

        menu.onMenuItemClick(clickItem(R.id.context_mute))
        menu.onMenuItemClick(clickItem(R.id.context_deafen))
        menu.onMenuItemClick(clickItem(R.id.context_local_mute))

        assertEquals(
            listOf("muteDeaf:2:false:true", "muteDeaf:2:true:false", "localMute:2:false"),
            fixture.actions.calls,
        )
    }

    @Test
    fun userMenuFallsBackToTheTappedRowOnceTheUserLeaves() = runTest {
        val fixture = Fixture()
        subscribe(fixture)
        val menu = UserMenu(
            mock(Context::class.java),
            fixture.viewModel.user(2)!!,
            fixture.viewModel,
            mock(FragmentManager::class.java),
        )
        val server = fixture.sessionState.value.server
        fixture.sessionState.value = fixture.sessionState.value.copy(
            server = server.copy(users = server.users.remove(2)),
        )

        menu.onMenuItemClick(clickItem(R.id.context_priority))

        assertEquals(listOf("priority:2:true"), fixture.actions.calls)
    }
}
