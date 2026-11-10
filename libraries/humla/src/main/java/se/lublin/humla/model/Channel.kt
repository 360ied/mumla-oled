/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.model

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet

class Channel : IChannel, Comparable<Channel> {
    /** Model-thread confined: written by ModelHandler on the TCP/model thread, read on UI/binder. */
    override var id: Int = 0
    /** Model-thread confined: use [resortInParent] after in-place updates (see ModelHandler). */
    override var position: Int = 0
    override var isTemporary: Boolean = false
    override var parent: Channel? = null
    /** Model-thread confined: use [resortInParent] after in-place updates (see ModelHandler). */
    override var name: String? = null
    override var description: String? = null
    // Copy-on-access like User's ByteString getters: holders of the getter
    // ref must not be able to mutate channel state through the array.
    // Note: overrides an immutable `IChannel` val as a mutable concrete-only
    // var (see `IChannel` KDoc); mutation is only via this concrete type.
    override var descriptionHash: ByteArray? = null
        get() = field?.clone()
        set(value) {
            field = value?.clone()
        }
    private val subchannelList = ArrayList<Channel>()
    private val userList = ArrayList<User>()
    private val linkList = ArrayList<Channel>()
    override var permissions: Int = 0
    override var maxUsers: Int = 0
    override var isEnterRestricted: Boolean = false
    // Written on the model thread, read on UI/binder: volatile for
    // cross-thread visibility (same hazard as listening state below).
    @Volatile
    private var canEnterField: Boolean = true
    // Visible across threads: ModelHandler writers run on the TCP/model thread
    // while isListeningToChannel reads off any binder thread. ConcurrentHashMap
    // gives map safety, not field visibility.
    @Volatile
    override var isListening: Boolean = false
    @Volatile
    override var listeningVolume: Float = 1.0f

    constructor()

    constructor(id: Int, temporary: Boolean) {
        this.id = id
        isTemporary = temporary
    }

    /**
     * See `User.channel`.
     * Widened to public: Kotlin has no package visibility.
     */
    fun addUser(user: User) {
        synchronized(userList) {
            for (i in userList.indices) {
                val u = userList[i]
                if (user.compareTo(u) <= 0) {
                    userList.add(i, user)
                    return
                }
            }
            userList.add(user)
        }
    }

    /**
     * See `User.channel`.
     * Widened to public: Kotlin has no package visibility.
     */
    fun removeUser(user: User) {
        synchronized(userList) {
            userList.remove(user)
        }
    }

    override val users: List<User>
        get() = synchronized(userList) { ArrayList(userList) }

    override val subchannels: List<Channel>
        get() = synchronized(subchannelList) { ArrayList(subchannelList) }

    fun addSubchannel(channel: Channel?) {
        synchronized(subchannelList) {
            if (channel == null || channel == this || subchannelList.contains(channel)) return
            for (i in subchannelList.indices) {
                val sc = subchannelList[i]
                if (channel.compareTo(sc) <= 0) {
                    subchannelList.add(i, channel)
                    return
                }
            }
            subchannelList.add(channel)
        }
    }

    fun removeSubchannel(channel: Channel?) {
        synchronized(subchannelList) {
            subchannelList.remove(channel)
        }
    }

    /** Re-sorts an in-place mutated child (position/name) to preserve sorted-insertion order. */
    fun resortInParent(child: Channel) {
        synchronized(subchannelList) {
            if (subchannelList.remove(child)) {
                addSubchannel(child)
            }
        }
    }

    override val links: List<Channel>
        get() = synchronized(linkList) { ArrayList(linkList) }

    /**
     * Returns the set of all channels transitively linked to this channel,
     * including this channel itself.
     * Matches upstream Mumble's Channel::allLinks() traversal.
     * @return Set of all transitively linked channels.
     */
    fun getAllLinks(): Set<Channel> {
        val seen = HashSet<Channel>()
        seen.add(this)
        val stack = ArrayDeque<Channel>()
        stack.push(this)
        while (!stack.isEmpty()) {
            val ch = stack.pop()
            // Snapshot under the channel leaf lock (see addLink): the copy
            // used to iterate the live list, so an off-monitor mutator
            // racing the traversal could CME. Writers take the ModelHandler
            // monitor *then* this lock; readers take only this lock, one
            // channel at a time — ordering is fixed, so no deadlock.
            val snapshot: List<Channel> = synchronized(ch.linkList) {
                ArrayList(ch.linkList)
            }
            for (linked in snapshot) {
                // linkList's element type is non-null (addLink rejects null),
                // so no null check: the pre-image Java guard is unreachable.
                if (seen.add(linked)) {
                    stack.push(linked)
                }
            }
        }
        return Collections.unmodifiableSet(seen)
    }

    fun addLink(channel: Channel?) {
        synchronized(linkList) {
            if (channel == null || channel == this || linkList.contains(channel)) {
                return
            }
            for (i in linkList.indices) {
                val sc = linkList[i]
                if (channel.compareTo(sc) <= 0) {
                    linkList.add(i, channel)
                    return
                }
            }
            linkList.add(channel)
        }
    }

    fun removeLink(channel: Channel?) {
        synchronized(linkList) {
            linkList.remove(channel)
        }
    }

    fun clearLinks() {
        synchronized(linkList) {
            linkList.clear()
        }
    }

    /**
     * Recursively fetches the subchannel user count.
     * FIXME: is it necessary to cache this?
     * @return The sum of users in this channel and its subchannel.
     */
    override val subchannelUserCount: Int
        get() {
            val usersSnapshot: List<User> = synchronized(userList) { ArrayList(userList) }
            val subsSnapshot: List<Channel> = synchronized(subchannelList) { ArrayList(subchannelList) }
            var userCount = usersSnapshot.size
            for (subc in subsSnapshot) {
                userCount += subc.subchannelUserCount
            }
            return userCount
        }

    override fun canEnter(): Boolean = canEnterField

    fun setCanEnter(canEnter: Boolean) {
        canEnterField = canEnter
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        other as Channel

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id
    }

    override fun compareTo(other: Channel): Int {
        if (position != other.position) {
            return Integer.compare(position, other.position)
        }
        val thisName = name
        val otherName = other.name
        if (thisName == null && otherName == null) {
            return Integer.compare(id, other.id)
        }
        if (thisName == null) {
            return -1
        }
        if (otherName == null) {
            return 1
        }
        val nameCmp = thisName.compareTo(otherName)
        if (nameCmp != 0) {
            return nameCmp
        }
        return Integer.compare(id, other.id)
    }
}
