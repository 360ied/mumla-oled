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

import se.lublin.humla.protobuf.Mumble

/**
 * Result of a pure state transition: the new value plus any semantic events.
 *
 * [selfRemoved] is set only by the self [Mumble.UserRemove] path; the
 * session fails with USER_REMOVE instead of emitting a leave event.
 */
data class TransitionResult(
    val state: SessionState,
    val events: List<SessionEvent> = emptyList(),
    val selfRemoved: Boolean = false,
)

/** Placeholder for forward-referenced ids. Shared with new-user creation in UserTransitions. */
internal fun stubChannel(id: Int): ChannelInfo = ChannelInfo(
    id = id,
    parentId = null,
    name = null,
    position = 0,
    temporary = false,
    description = null,
    descriptionHash = null,
    links = kotlinx.collections.immutable.persistentSetOf(),
    maxUsers = 0,
    enterRestricted = false,
    canEnter = true,
    permissions = 0,
)

/** ChannelState / ChannelRemove / links. Ported from ModelHandler. */
fun applyChannelState(state: SessionState, msg: Mumble.ChannelState): TransitionResult {
    if (!msg.hasChannelId()) return TransitionResult(state)
    val id = msg.channelId

    // Self-parent is trivially a cycle: refuse before creating any stub.
    if (msg.hasParent() && msg.parent == id) return TransitionResult(state)

    var channels = state.server.channels
    if (msg.hasParent()) {
        val parentId = msg.parent
        // Root never has a parent; ignore a hostile parent on id 0 to keep
        // the parentId-null-only-for-root invariant (server never sends it).
        if (id != 0 && !channels.containsKey(parentId)) {
            channels = channels.putting(parentId, stubChannel(parentId))
        }
    }

    val existing = channels[id]
    // temporary is create-only (no hasTemporary branch below), matching
    // the old model where it was set once at construction.
    var updated: ChannelInfo = existing ?: stubChannel(id).copy(temporary = msg.temporary)

    // Cycle guard before any mutation: refuse moves into self or a descendant.
    if (msg.hasParent() && id != 0) {
        var pp: Int? = msg.parent
        val visited = HashSet<Int>()
        while (pp != null) {
            if (pp == id) return TransitionResult(state.copy(server = state.server.copy(channels = channels)))
            if (!visited.add(pp)) break
            pp = channels[pp]?.parentId
        }
    }

    if (msg.hasName() && updated.name != msg.name) updated = updated.copy(name = msg.name)
    if (msg.hasPosition() && updated.position != msg.position) updated = updated.copy(position = msg.position)

    if (msg.hasParent() && id != 0) {
        val newParent: Int? = msg.parent
        if (updated.parentId != newParent) updated = updated.copy(parentId = newParent)
    }

    if (msg.hasDescriptionHash()) {
        updated = updated.copy(
            descriptionHash = msg.descriptionHash,
            description = null,
        )
    }
    if (msg.hasDescription()) {
        updated = updated.copy(
            description = msg.description,
            descriptionHash = null,
        )
    }

    // Links are applied in today's order: replace, then remove, then add.
    if (msg.linksCount > 0) {
        val newLinks = msg.linksList.toSet()
        for (linkId in newLinks) {
            if (!channels.containsKey(linkId) && linkId != id) {
                channels = channels.putting(linkId, stubChannel(linkId))
            }
        }
        // Detach old bidirectional links no longer present.
        val oldLinks = updated.links
        for (oldId in oldLinks) {
            if (!newLinks.contains(oldId)) {
                val linked = channels[oldId]
                if (linked != null) {
                    channels = channels.putting(oldId, linked.copy(links = linked.links.removing(id)))
                }
            }
        }
        // Attach new bidirectional links.
        for (linkId in newLinks) {
            if (linkId == id) continue
            val linked = channels[linkId]
            if (linked != null && !linked.links.contains(id)) {
                channels = channels.putting(linkId, linked.copy(links = linked.links.adding(id)))
            }
        }
        var filtered = kotlinx.collections.immutable.persistentSetOf<Int>()
        for (linkId in newLinks) {
            if (linkId != id) filtered = filtered.adding(linkId)
        }
        updated = updated.copy(links = filtered)
    }
    if (msg.linksRemoveCount > 0) {
        var links = updated.links
        for (linkId in msg.linksRemoveList) {
            if (links.contains(linkId)) {
                links = links.removing(linkId)
                val linked = channels[linkId]
                if (linked != null && linked.links.contains(id)) {
                    channels = channels.putting(linkId, linked.copy(links = linked.links.removing(id)))
                }
            } else {
                // Still detach the reverse side if it points back.
                val linked = channels[linkId]
                if (linked != null && linked.links.contains(id)) {
                    channels = channels.putting(linkId, linked.copy(links = linked.links.removing(id)))
                }
            }
        }
        updated = updated.copy(links = links)
    }
    if (msg.linksAddCount > 0) {
        var links = updated.links
        for (linkId in msg.linksAddList) {
            if (linkId == id) continue
            if (!channels.containsKey(linkId)) {
                channels = channels.putting(linkId, stubChannel(linkId))
            }
            if (!links.contains(linkId)) {
                links = links.adding(linkId)
                val linked = channels[linkId]
                if (linked != null && !linked.links.contains(id)) {
                    channels = channels.putting(linkId, linked.copy(links = linked.links.adding(id)))
                }
            } else {
                val linked = channels[linkId]
                if (linked != null && !linked.links.contains(id)) {
                    channels = channels.putting(linkId, linked.copy(links = linked.links.adding(id)))
                }
            }
        }
        updated = updated.copy(links = links)
    }

    if (msg.hasMaxUsers()) updated = updated.copy(maxUsers = msg.maxUsers)
    if (msg.hasIsEnterRestricted()) updated = updated.copy(enterRestricted = msg.isEnterRestricted)
    if (msg.hasCanEnter()) updated = updated.copy(canEnter = msg.canEnter)

    channels = channels.putting(id, updated)
    return TransitionResult(state.copy(server = state.server.copy(channels = channels)))
}

/** ChannelRemove with whole-subtree delete. Drops every removed id from links and listeners. */
fun applyChannelRemove(state: SessionState, msg: Mumble.ChannelRemove): TransitionResult {
    val id = msg.channelId
    val channels = state.server.channels
    val target = channels[id] ?: return TransitionResult(state)
    if (id == 0) return TransitionResult(state)

    // Iterative subtree collect, cycle-safe. Quadratic in pathological
    // trees; accepted at Mumble scale (channels number in the dozens).
    val removed = ArrayList<Int>()
    val visited = HashSet<Int>()
    val stack = ArrayDeque<Int>()
    stack.add(id)
    while (stack.isNotEmpty()) {
        val nodeId = stack.removeLast()
        if (!visited.add(nodeId)) continue
        if (!channels.containsKey(nodeId)) continue
        removed.add(nodeId)
        for ((childId, child) in channels) {
            if (child.parentId == nodeId && childId != nodeId) {
                if (!visited.contains(childId)) stack.add(childId)
            }
        }
    }
    val removedIds = removed.toSet()

    var newChannels = channels
    for (rid in removedIds) newChannels = newChannels.removing(rid)
    // Drop removed ids from remaining channels' links.
    for ((cid, ch) in newChannels) {
        var links = ch.links
        var changed = false
        for (rid in removedIds) {
            if (links.contains(rid)) {
                links = links.removing(rid)
                changed = true
            }
        }
        if (changed) newChannels = newChannels.putting(cid, ch.copy(links = links))
    }

    // Users inside lose their channel until the server moves them; every
    // removed id leaves every listener set (fix of the top-only leak).
    var users = state.server.users
    for ((session, u) in users) {
        var next = u
        if (u.channelId != null && removedIds.contains(u.channelId)) {
            next = next.copy(channelId = null)
        }
        var listening = next.listeningChannels
        var listenChanged = false
        for (rid in removedIds) {
            if (listening.contains(rid)) {
                listening = listening.removing(rid)
                listenChanged = true
            }
        }
        if (listenChanged) next = next.copy(listeningChannels = listening)
        if (next != u) users = users.putting(session, next)
    }

    return TransitionResult(
        state.copy(server = state.server.copy(channels = newChannels, users = users)),
    )
}
