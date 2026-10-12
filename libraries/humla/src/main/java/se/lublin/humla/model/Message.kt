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
import java.util.Date

/**
 * A class encapsulating a text message from a Mumble server.
 * NOTE: Always prefer using getActorName(). You CANNOT rely on getActor() to provide this info,
 * as the actor may no longer be on the server.
 * Created by andrew on 03/12/13.
 */
class Message : IMessage {
    override var actor: Int = -1
        private set
    override var actorName: String? = null
        private set
    private var channelList: List<Channel> = ArrayList()
    private var treeList: List<Channel> = ArrayList()
    private var userList: List<User> = ArrayList()
    override var message: String? = null
        private set
    override val receivedTime: Long

    constructor(message: String?) {
        this.message = message
        actor = -1
        receivedTime = Date().time
        channelList = ArrayList()
        treeList = ArrayList()
        userList = ArrayList()
    }

    constructor(
        actor: Int,
        actorName: String?,
        channels: List<Channel>,
        trees: List<Channel>,
        users: List<User>,
        message: String?
    ) : this(message) {
        this.actor = actor
        this.actorName = actorName
        channelList = channels
        treeList = trees
        userList = users
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
    enum class Type {
        INFO,
        WARNING,
        TEXT_MESSAGE
    }
}
