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

import se.lublin.humla.model.IMessage

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
     * A text message from a user.
     */
    class TextMessage : IChatMessage {
        private val mMessage: IMessage?
        val isSelfAuthored: Boolean

        constructor(message: IMessage?) : this(message, false)

        constructor(message: IMessage?, selfAuthored: Boolean) {
            mMessage = message
            isSelfAuthored = selfAuthored
        }

        fun getMessage(): IMessage? {
            return mMessage
        }

        override val body: String?
            // NPE-exact: the original dereferenced mMessage unconditionally.
            get() = (mMessage ?: throw NullPointerException("message")).message

        override val receivedTime: Long
            // NPE-exact: the original dereferenced mMessage unconditionally.
            get() = (mMessage ?: throw NullPointerException("message")).receivedTime

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
