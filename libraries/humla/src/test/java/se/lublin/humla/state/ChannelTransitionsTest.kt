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
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

private fun userIn(session: Int, name: String, channelId: Int?, listening: Set<Int> = emptySet()): UserInfo =
    UserInfo(
        session = session, userId = null, name = name, channelId = channelId,
        certHash = null, comment = null, commentHash = null, texture = null,
        textureHash = null, muted = false, deafened = false, suppressed = false,
        selfMuted = false, selfDeafened = false, prioritySpeaker = false,
        recording = false,
        listeningChannels = listening.fold(persistentSetOf<Int>()) { a, v -> a.adding(v) },
        localMuted = false, localIgnored = false,
    )

/** Ported from ModelHandlerTopologyTest and ChannelLinksTest. */
class ChannelTransitionsTest : TestCase() {
    @Test
    fun testForwardParentCreatesStubAndAdoptsChild() {
        var s = emptySessionState()
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(2).setName("Child").setParent(1).build(),
        ).state
        assertNotNull(s.server.channels[1])
        assertEquals(1, s.server.channels[2]?.parentId)
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(1).setName("Root").build(),
        ).state
        assertEquals("Root", s.server.channels[1]?.name)
        assertEquals(1, s.server.channels[2]?.parentId)
        val tree = ChannelTree.build(s.server.channels, s.server.users)
        assertEquals(listOf(2), tree.children(1).map { it.id })
    }

    @Test
    fun testForwardLinksCreateStubsBidirectionally() {
        var s = emptySessionState()
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(1).setName("Ch1").addLinks(2).build(),
        ).state
        assertNotNull(s.server.channels[2])
        assertTrue(s.server.channels[1]?.links?.contains(2) == true)
        assertTrue(s.server.channels[2]?.links?.contains(1) == true)
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(2).setName("Ch2").addLinks(1).build(),
        ).state
        assertEquals("Ch2", s.server.channels[2]?.name)
        assertEquals(1, s.server.channels[1]?.links?.size)
        assertEquals(1, s.server.channels[2]?.links?.size)
    }

    @Test
    fun testSelfParentIgnored() {
        var s = emptySessionState()
        s = applyChannelState(
            s, Mumble.ChannelState.newBuilder().setChannelId(5).setName("A").build(),
        ).state
        val before = s
        s = applyChannelState(
            s, Mumble.ChannelState.newBuilder().setChannelId(5).setParent(5).build(),
        ).state
        assertEquals(before, s)
    }

    @Test
    fun testCycleMoveIgnoredWithoutHalfApply() {
        var s = emptySessionState()
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(1).setName("P").setParent(0).build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(2).setName("C").setParent(1).build()).state
        val before = s
        // Move P under its own descendant C: refused entirely (name must not apply).
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(1).setName("Renamed").setParent(2).build(),
        ).state
        assertEquals(before, s)
    }

    @Test
    fun testLinksReplaceRemoveAddOrder() {
        var s = emptySessionState()
        for (id in 1..4) {
            s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(id).setName("c$id").build()).state
        }
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinks(2).addLinks(3).build(),
        ).state
        assertEquals(setOf(2, 3), s.server.channels[1]?.links)
        // Replace with links=4, plus remove 4 and add 2 in the same message:
        // replace wins first, then remove, then add.
        s = applyChannelState(
            s,
            Mumble.ChannelState.newBuilder().setChannelId(1)
                .addLinks(4).addLinksRemove(4).addLinksAdd(2).build(),
        ).state
        assertEquals(setOf(2), s.server.channels[1]?.links)
        assertTrue(s.server.channels[2]?.links?.contains(1) == true)
        assertFalse(s.server.channels[4]?.links?.contains(1) == true)
    }

    @Test
    fun testChannelRemoveDeletesSubtreeAndListeners() {
        var s = emptySessionState()
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(1).setName("P").setParent(0).build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(2).setName("C").setParent(1).build()).state
        var users = persistentMapOf(
            10 to userIn(10, "a", 2, listening = setOf(1, 2)),
            11 to userIn(11, "b", 0),
        )
        s = s.copy(server = s.server.copy(users = users))
        // Link remaining channel 3 to removed 1; link must detach.
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(3).setName("Q").setParent(0).addLinks(1).build()).state
        assertTrue(s.server.channels[3]?.links?.contains(1) == true)

        s = applyChannelRemove(s, Mumble.ChannelRemove.newBuilder().setChannelId(1).build()).state
        assertNull(s.server.channels[1])
        assertNull(s.server.channels[2])
        assertNotNull(s.server.channels[0])
        assertNotNull(s.server.channels[3])
        assertFalse(s.server.channels[3]?.links?.contains(1) == true)
        // User inside loses its channel; every removed id leaves listeners.
        assertNull(s.server.users[10]?.channelId)
        assertEquals(emptySet<Int>(), s.server.users[10]?.listeningChannels)
        assertEquals(0, s.server.users[11]?.channelId)
    }

    @Test
    fun testRemoveRootIgnored() {
        var s = emptySessionState()
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build()).state
        val before = s
        s = applyChannelRemove(s, Mumble.ChannelRemove.newBuilder().setChannelId(0).build()).state
        assertEquals(before, s)
    }

    @Test
    fun testMessageWithoutChannelIdIsNoOp() {
        val s = emptySessionState()
        val out = applyChannelState(s, Mumble.ChannelState.newBuilder().setName("x").build())
        assertEquals(s, out.state)
    }
}
