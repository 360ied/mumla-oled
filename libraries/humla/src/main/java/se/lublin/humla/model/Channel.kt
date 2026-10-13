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
    override var id: Int = 0
    override var position: Int = 0
    private var level: Int = 0
    override var isTemporary: Boolean = false
    override var parent: Channel? = null
    override var name: String? = null
    override var description: String? = null
    override var descriptionHash: ByteArray? = null
    private val subchannelList = ArrayList<Channel>()
    private val userList = ArrayList<User>()
    private val linkList = ArrayList<Channel>()
    override var permissions: Int = 0
    override var maxUsers: Int = 0
    override var isEnterRestricted: Boolean = false
    private var canEnterField: Boolean = true
    override var isListening: Boolean = false
    override var listeningVolume: Float = 1.0f

    constructor()

    constructor(id: Int, temporary: Boolean) {
        this.id = id
        isTemporary = temporary
    }

    /**
     * @see User.channel
     */
    internal fun addUser(user: User) {
        for (i in userList.indices) {
            val u = userList[i]
            if (user.compareTo(u) <= 0) {
                userList.add(i, user)
                return
            }
        }
        userList.add(user)
    }

    /**
     * @see User.channel
     */
    internal fun removeUser(user: User) {
        userList.remove(user)
    }

    override val users: List<User>
        get() = Collections.unmodifiableList(userList)

    override val subchannels: List<Channel>
        get() = Collections.unmodifiableList(subchannelList)

    fun addSubchannel(channel: Channel?) {
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

    fun removeSubchannel(channel: Channel?) {
        subchannelList.remove(channel)
    }

    override val links: List<Channel>
        get() = Collections.unmodifiableList(linkList)

    /**
     * Returns the set of all channels transitively linked to this channel,
     * including this channel itself.
     * Matches upstream Mumble's Channel::allLinks() traversal.
     * @return Set of all transitively linked channels.
     */
    fun getAllLinks(): Set<Channel> {
        val seen = HashSet<Channel>()
        seen.add(this)
        if (linkList.isEmpty()) {
            return Collections.unmodifiableSet(seen)
        }
        val stack = ArrayDeque<Channel>()
        stack.push(this)
        while (!stack.isEmpty()) {
            val ch = stack.pop()
            for (linked in ArrayList(ch.linkList)) {
                // Null guard mirrors the original (addLink rejects null, so
                // unreachable in practice).
                if (linked != null && seen.add(linked)) {
                    stack.push(linked)
                }
            }
        }
        return Collections.unmodifiableSet(seen)
    }

    fun addLink(channel: Channel?) {
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

    fun removeLink(channel: Channel?) {
        linkList.remove(channel)
    }

    fun clearLinks() {
        linkList.clear()
    }

    /**
     * Recursively fetches the subchannel user count.
     * FIXME: is it necessary to cache this?
     * @return The sum of users in this channel and its subchannel.
     */
    override val subchannelUserCount: Int
        get() {
            var userCount = userList.size
            for (subc in subchannelList) {
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
