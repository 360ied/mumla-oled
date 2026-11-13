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

/**
 * Derived channel tree. See
 * docs/target-architecture/state.md.
 *
 * Channels whose parent is missing (possible transiently between messages)
 * are left out of the tree until the parent arrives.
 */
class ChannelTree private constructor(
    private val usersByChannel: Map<Int, List<UserInfo>>,
    private val children: Map<Int, List<ChannelInfo>>,
    private val links: Map<Int, Set<Int>>,
) {
    fun children(channelId: Int): List<ChannelInfo> =
        children[channelId] ?: emptyList()

    fun users(channelId: Int): List<UserInfo> =
        usersByChannel[channelId] ?: emptyList()

    fun userCountInSubtree(channelId: Int): Int {
        var count = 0
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(channelId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!visited.add(id)) continue
            count += (usersByChannel[id]?.size ?: 0)
            for (child in children[id] ?: emptyList()) {
                if (!visited.contains(child.id)) stack.add(child.id)
            }
        }
        return count
    }

    fun allLinks(channelId: Int): Set<Int> {
        val seen = HashSet<Int>()
        seen.add(channelId)
        val stack = ArrayDeque<Int>()
        stack.add(channelId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            for (linked in links[id] ?: emptySet()) {
                if (seen.add(linked)) stack.add(linked)
            }
        }
        return seen
    }

    companion object {
        fun channelComparator(): Comparator<ChannelInfo> =
            Comparator { a, b ->
                if (a.position != b.position) {
                    Integer.compare(a.position, b.position)
                } else {
                    val an = a.name
                    val bn = b.name
                    if (an == null && bn == null) {
                        Integer.compare(a.id, b.id)
                    } else if (an == null) {
                        -1
                    } else if (bn == null) {
                        1
                    } else {
                        val cmp = an.compareTo(bn)
                        if (cmp != 0) cmp else Integer.compare(a.id, b.id)
                    }
                }
            }

        fun userComparator(): Comparator<UserInfo> =
            Comparator { a, b ->
                val an = a.name
                val bn = b.name
                if (an == null && bn == null) {
                    Integer.compare(a.session, b.session)
                } else if (an == null) {
                    -1
                } else if (bn == null) {
                    1
                } else {
                    val cmp = an.compareTo(bn, ignoreCase = true)
                    if (cmp != 0) cmp else Integer.compare(a.session, b.session)
                }
            }

        fun build(
            channels: Map<Int, ChannelInfo>,
            users: Map<Int, UserInfo>,
        ): ChannelTree {
            val channelCmp = channelComparator()
            val userCmp = userComparator()
            val childrenMut = HashMap<Int, MutableList<ChannelInfo>>()
            for ((id, ch) in channels) {
                val parentId = ch.parentId
                if (parentId != null && parentId != id && channels.containsKey(parentId)) {
                    childrenMut.getOrPut(parentId) { ArrayList() }.add(ch)
                }
                // Channels whose parent is missing (or which are the parentless
                // root) stay out of the children map until the parent arrives;
                // root reachability comes from querying children(0)/users(0).
            }
            for (list in childrenMut.values) list.sortWith(channelCmp)
            val children: Map<Int, List<ChannelInfo>> = childrenMut.mapValues { it.value.toList() }

            val usersByChannelMut = HashMap<Int, MutableList<UserInfo>>()
            for (user in users.values) {
                val cid = user.channelId
                if (cid != null && channels.containsKey(cid)) {
                    usersByChannelMut.getOrPut(cid) { ArrayList() }.add(user)
                } else if (cid == null) {
                    // Between ChannelRemove and the server move the user is
                    // unplaced; keep it out of the tree like ModelHandler.
                } else {
                    // Unknown channel: fall back to root when present, else drop.
                    val root = if (channels.containsKey(0)) 0 else null
                    if (root != null) {
                        usersByChannelMut.getOrPut(root) { ArrayList() }.add(user)
                    }
                }
            }
            for (list in usersByChannelMut.values) list.sortWith(userCmp)
            val usersByChannel: Map<Int, List<UserInfo>> = usersByChannelMut.mapValues { it.value.toList() }

            val links: Map<Int, Set<Int>> = channels.mapValues { (id, ch) ->
                ch.links.filter { it != id && channels.containsKey(it) }.toSet()
            }
            return ChannelTree(usersByChannel, children, links)
        }
    }
}
