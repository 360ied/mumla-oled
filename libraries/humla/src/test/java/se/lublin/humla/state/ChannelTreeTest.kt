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

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test
import junit.framework.TestCase

private fun channel(
    id: Int,
    parentId: Int? = null,
    name: String? = null,
    position: Int = 0,
    links: Set<Int> = emptySet(),
): ChannelInfo = ChannelInfo(
    id = id,
    parentId = parentId,
    name = name,
    position = position,
    temporary = false,
    description = null,
    descriptionHash = null,
    links = links.fold(persistentSetOf<Int>()) { acc, v -> acc.adding(v) },
    maxUsers = 0,
    enterRestricted = false,
    canEnter = true,
    permissions = 0,
)

private fun user(session: Int, name: String?, channelId: Int?): UserInfo = UserInfo(
    session = session,
    userId = null,
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

/**
 * Pins ChannelTree derived-view behavior: sorting matches Channel/User
 * compareTo, missing parents stay out, users fall back to root, link
 * traversal is transitive and cycle-safe.
 */
class ChannelTreeTest : TestCase() {
    @Test
    fun testChannelsSortByPositionThenNameThenId() {
        val channels = persistentMapOf(
            1 to channel(1, parentId = 0, name = "b", position = 0),
            2 to channel(2, parentId = 0, name = "a", position = 0),
            3 to channel(3, parentId = 0, name = "a", position = -1),
            0 to channel(0, parentId = null, name = "Root"),
        )
        val tree = ChannelTree.build(channels, persistentMapOf())
        val ids = tree.children(0).map { it.id }
        // position -1 first, then name a before b.
        assertEquals(listOf(3, 2, 1), ids)
    }

    @Test
    fun testNullChannelNameSortsFirst() {
        val channels = persistentMapOf(
            0 to channel(0, parentId = null, name = "Root"),
            1 to channel(1, parentId = 0, name = null),
            2 to channel(2, parentId = 0, name = "a"),
        )
        val tree = ChannelTree.build(channels, persistentMapOf())
        assertEquals(listOf(1, 2), tree.children(0).map { it.id })
    }

    @Test
    fun testUsersSortCaseInsensitiveThenSession() {
        val channels = persistentMapOf(0 to channel(0, parentId = null, name = "Root"))
        val users = persistentMapOf(
            1 to user(1, "bob", 0),
            2 to user(2, "Alice", 0),
            3 to user(3, null, 0),
            4 to user(4, "alice", 0),
        )
        val tree = ChannelTree.build(channels, users)
        // null first, then Alice/alice (tie -> session), then bob.
        assertEquals(listOf(3, 2, 4, 1), tree.users(0).map { it.session })
    }

    @Test
    fun testMissingParentLeftOutUntilItArrives() {
        val channels = persistentMapOf(
            0 to channel(0, parentId = null, name = "Root"),
            2 to channel(2, parentId = 1, name = "orphan"),
        )
        val tree = ChannelTree.build(channels, persistentMapOf())
        assertTrue(tree.children(0).isEmpty())
        assertTrue(tree.children(1).isEmpty())
    }

    @Test
    fun testUserInUnknownChannelFallsBackToRoot() {
        val channels = persistentMapOf(0 to channel(0, parentId = null, name = "Root"))
        val users = persistentMapOf(7 to user(7, "u", 99))
        val tree = ChannelTree.build(channels, users)
        assertEquals(listOf(7), tree.users(0).map { it.session })
    }

    @Test
    fun testUnplacedUserStaysOutOfTree() {
        val channels = persistentMapOf(0 to channel(0, parentId = null, name = "Root"))
        val users = persistentMapOf(7 to user(7, "u", null))
        val tree = ChannelTree.build(channels, users)
        assertTrue(tree.users(0).isEmpty())
    }

    @Test
    fun testAllLinksTransitiveAndCycleSafe() {
        val channels = persistentMapOf(
            1 to channel(1, name = "a", links = setOf(2)),
            2 to channel(2, name = "b", links = setOf(1, 3)),
            3 to channel(3, name = "c", links = setOf(2)),
        )
        val tree = ChannelTree.build(channels, persistentMapOf())
        assertEquals(setOf(1, 2, 3), tree.allLinks(1))
        assertEquals(setOf(1, 2, 3), tree.allLinks(3))
    }

    @Test
    fun testUserCountInSubtree() {
        val channels = persistentMapOf(
            0 to channel(0, parentId = null, name = "Root"),
            1 to channel(1, parentId = 0, name = "sub"),
            2 to channel(2, parentId = 1, name = "leaf"),
        )
        val users = persistentMapOf(
            1 to user(1, "a", 0),
            2 to user(2, "b", 2),
            3 to user(3, "c", 2),
        )
        val tree = ChannelTree.build(channels, users)
        assertEquals(3, tree.userCountInSubtree(0))
        assertEquals(2, tree.userCountInSubtree(1))
        assertEquals(2, tree.userCountInSubtree(2))
    }
}
