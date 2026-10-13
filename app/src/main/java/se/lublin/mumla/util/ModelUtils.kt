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

package se.lublin.mumla.util

import java.util.LinkedList
import se.lublin.humla.model.IChannel

/**
 * Tools for dealing with the recursive user-channel hierarchy.
 * Created by andrew on 18/10/14.
 */
object ModelUtils {
    /**
     * Flattens the channel hierarchy, returning an array of channels in hierarchical order.
     * @param channel The root channel to flatten from.
     * @return A list of channels.
     */
    @JvmStatic
    fun getChannelList(channel: IChannel): List<IChannel> {
        val channels = LinkedList<IChannel>()
        getChannelList(channel, channels)
        return channels
    }

    private fun getChannelList(channel: IChannel, channels: MutableList<IChannel>) {
        channels.add(channel)
        for (subc in channel.subchannels) {
            getChannelList(subc, channels)
        }
    }
}
