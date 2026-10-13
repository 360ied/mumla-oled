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

import java.util.ArrayList
import java.util.Collections

/**
 * A class encapsulating a text message from a Mumble server.
 * NOTE: Always prefer using getActorName(). You CANNOT rely on getActor() to provide this info,
 * as the actor may no longer be on the server.
 * Created by andrew on 03/12/13.
 */
// Final by intent (pre-image Java was non-final, but no subclass exists
// in-tree): documents sealing; re-open if extensibility is ever required.
class Message : IMessage {
    override var actor: Int = -1
        private set
    override var actorName: String? = null
        private set
    // Backed by immutable empties until a ctor assigns: the 6-arg form
    // always overwrites with defensive copies, so no throwaway allocation.
    private var channelList: List<Channel> = emptyList()
    private var treeList: List<Channel> = emptyList()
    private var userList: List<User> = emptyList()
    override var message: String? = null
        private set
    override val receivedTime: Long

    // Single primary-equivalent ctor: the 1-arg form delegates with empty
    // lists (fields already default to immutable empties above).
    constructor(message: String?) : this(-1, null, emptyList(), emptyList(), emptyList(), message)

    constructor(
        actor: Int,
        actorName: String?,
        channels: List<Channel>,
        trees: List<Channel>,
        users: List<User>,
        message: String?
    ) {
        this.message = message
        this.actor = actor
        this.actorName = actorName
        receivedTime = System.currentTimeMillis()
        // Defensive copies: the unmodifiable views below wrap these lists,
        // so aliasing the caller's mutable list would let post-construction
        // mutation show through the "unmodifiable" surface.
        channelList = ArrayList(channels)
        treeList = ArrayList(trees)
        userList = ArrayList(users)
    }

    override val targetChannels: List<Channel>
        get() = Collections.unmodifiableList(channelList)

    override val targetTrees: List<Channel>
        get() = Collections.unmodifiableList(treeList)

    override val targetUsers: List<User>
        get() = Collections.unmodifiableList(userList)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        other as Message

        if (actor != other.actor) return false
        if (receivedTime != other.receivedTime) return false
        if (actorName != other.actorName) return false
        if (channelList != other.channelList) return false
        if (message != other.message) return false
        if (treeList != other.treeList) return false
        if (userList != other.userList) return false

        return true
    }

    override fun hashCode(): Int {
        var result = actor
        result = 31 * result + (actorName?.hashCode() ?: 0)
        result = 31 * result + channelList.hashCode()
        result = 31 * result + treeList.hashCode()
        result = 31 * result + userList.hashCode()
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + (receivedTime xor (receivedTime ushr 32)).toInt()
        return result
    }

    /**
     * The type of message this object represents.
     * @deprecated
     */
    @Deprecated("Deprecated")
    enum class Type {
        INFO,
        WARNING,
        TEXT_MESSAGE
    }
}
