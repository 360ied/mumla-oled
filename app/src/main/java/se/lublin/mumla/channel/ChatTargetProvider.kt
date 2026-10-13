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

package se.lublin.mumla.channel

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.mumla.BuildConfig

interface ChatTargetProvider {

    /**
     * Abstraction for user and channel chat targets.
     */
    class ChatTarget {
        private val mChannel: IChannel?
        private val mUser: IUser?

        constructor(channel: IChannel) {
            mChannel = channel
            mUser = null
        }

        constructor(user: IUser) {
            mUser = user
            mChannel = null
        }

        fun getChannel(): IChannel? {
            return mChannel
        }

        fun getUser(): IUser? {
            return mUser
        }

        fun getName(): String? {
            if (mUser != null) {
                return mUser.name
            }
            if (mChannel != null) {
                return mChannel.name
            }
            if (BuildConfig.DEBUG) {
                throw RuntimeException()
            }
            return "Unknown"
        }
    }

    /**
     * Interface for classes which wish to receive chat target change calls.
     * Created by andrew on 06/08/13.
     */
    interface OnChatTargetSelectedListener {
        fun onChatTargetSelected(target: ChatTarget?)
    }

    fun getChatTarget(): ChatTarget?
    fun setChatTarget(target: ChatTarget?)
    fun registerChatTargetListener(listener: OnChatTargetSelectedListener)
    fun unregisterChatTargetListener(listener: OnChatTargetSelectedListener)
}
