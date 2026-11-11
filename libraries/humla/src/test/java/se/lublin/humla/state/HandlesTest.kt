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

package se.lublin.humla.state

import junit.framework.TestCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Test
import se.lublin.humla.model.TalkState
import se.lublin.humla.protobuf.Mumble

private fun channelMsg(id: Int, name: String, parent: Int? = null): Mumble.ChannelState {
    val b = Mumble.ChannelState.newBuilder().setChannelId(id).setName(name)
    if (parent != null) b.parent = parent
    return b.build()
}

private fun userMsg(session: Int, name: String): Mumble.UserState =
    Mumble.UserState.newBuilder().setSession(session).setName(name).build()

/** Live-handle contract: live reads, cached scalars after remove, equality, navigation. */
class HandlesTest : TestCase() {
    private fun syncedFlow(): MutableStateFlow<SessionState> {
        var s = emptySessionState()
        s = applyChannelState(s, channelMsg(0, "Root")).state
        s = applyChannelState(s, channelMsg(1, "A", 0)).state
        s = s.copy(server = s.server.copy(phase = SyncPhase.SYNCED, selfSession = 1))
        s = applyUserState(s, userMsg(1, "self")).state
        s = applyUserState(s, Mumble.UserState.newBuilder().setSession(1).setChannelId(1).build()).state
        s = applyUserState(s, userMsg(2, "other")).state
        s = applyUserState(s, Mumble.UserState.newBuilder().setSession(2).setChannelId(1).build()).state
        return MutableStateFlow(s)
    }

    @Test
    fun testLiveReadsFollowState() {
        val flow = syncedFlow()
        val handle = UserHandle(2, flow)
        assertEquals("other", handle.name)
        flow.update { cur ->
            applyUserState(cur, Mumble.UserState.newBuilder().setSession(2).setName("renamed").build()).state
        }
        assertEquals("renamed", handle.name)
    }

    @Test
    fun testScalarsCachedAfterRemove() {
        val flow = syncedFlow()
        val handle = ChannelHandle(1, flow)
        assertEquals("A", handle.name)
        flow.update { cur -> applyChannelRemove(cur, Mumble.ChannelRemove.newBuilder().setChannelId(1).build()).state }
        // Detached subtree reads empty navigation but keeps scalars.
        assertEquals("A", handle.name)
        assertNull(handle.parent)
        assertTrue(handle.users.isEmpty())
        assertTrue(handle.subchannels.isEmpty())
        // Removed user reads null channel, keeps name.
        val user = UserHandle(2, flow)
        user.name
        assertNull(user.channel)
    }

    @Test
    fun testEqualityById() {
        val flow = syncedFlow()
        assertEquals(UserHandle(2, flow), UserHandle(2, flow))
        assertEquals(ChannelHandle(1, flow), ChannelHandle(1, flow))
        assertFalse(UserHandle(1, flow).equals(UserHandle(2, flow)))
    }

    @Test
    fun testNavigationReturnsHandles() {
        val flow = syncedFlow()
        val user = UserHandle(2, flow)
        val channel = user.channel
        assertNotNull(channel)
        assertEquals(1, channel!!.id)
        assertEquals(listOf(1, 2), channel.users.map { it.session }.sorted())
        assertEquals(ChannelHandle(0, flow), channel.parent)
    }

    @Test
    fun testTalkStateStoredLocally() {
        val flow = syncedFlow()
        val handle = UserHandle(2, flow)
        assertEquals(TalkState.PASSIVE, handle.talkState)
        handle.talkState = TalkState.TALKING
        assertEquals(TalkState.TALKING, handle.talkState)
    }

    @Test
    fun testDerivedReads() {
        val flow = syncedFlow()
        // Self (session 1) listens? No: listening set empty.
        assertFalse(ChannelHandle(1, flow).isListening)
        assertEquals(1.0f, ChannelHandle(1, flow).listeningVolume)
        flow.update { cur ->
            applyListeningVolumeIntent(cur, 1, 0.5f).state
        }
        assertEquals(0.5f, ChannelHandle(1, flow).listeningVolume)
        assertEquals(2, ChannelHandle(1, flow).subchannelUserCount)
    }
}
