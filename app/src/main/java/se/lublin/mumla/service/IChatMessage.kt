/*
 * Copyright (C) 2015 Andrew Comminos
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

package se.lublin.mumla.service

/**
 * A general chat message, either a text message from a user or an
 * informational notice.
 * Created by andrew on 28/04/15.
 */
interface IChatMessage {
    /**
     * @return The body of the message.
     */
    val body: String?

    /**
     * @return the unix timestamp when the message was received.
     */
    val receivedTime: Long

    /**
     * Calls the provided visitor object with the proper message implementation.
     * @param visitor A visitor object responding to the underlying chat message type.
     */
    fun accept(visitor: Visitor)

    /**
     * A text message from a user, as values: the sender's and targets' names
     * are resolved when the record is logged, so no live handle or model
     * object outlives the event.
     */
    class TextMessage @JvmOverloads constructor(
        val actorName: String?,
        /** Names of the targeted channels or users, in today's display order. */
        val targetNames: List<String>,
        override val body: String,
        override val receivedTime: Long,
        val isSelfAuthored: Boolean = false,
    ) : IChatMessage {

        override fun accept(visitor: Visitor) {
            visitor.visit(this)
        }
    }

    /**
     * An informational message about the server or client state.
     */
    class InfoMessage(val type: Type, body: String?) : IChatMessage {
        /**
         * The message body.
         */
        override val body: String? = body
        /**
         * The time the message was received, as a unix timestamp.
         */
        override val receivedTime: Long = System.currentTimeMillis()

        override fun accept(visitor: Visitor) {
            visitor.visit(this)
        }

        enum class Type {
            INFO,
            WARNING,
            ERROR
        }
    }

    interface Visitor {
        fun visit(message: TextMessage)
        fun visit(message: InfoMessage)
    }
}
