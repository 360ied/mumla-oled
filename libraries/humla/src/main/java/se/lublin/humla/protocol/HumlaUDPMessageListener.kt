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

package se.lublin.humla.protocol

import se.lublin.humla.net.DecodedVoiceFrame
import se.lublin.humla.protobuf.MumbleUDP

/**
 * Created by andrew on 21/01/14.
 */
interface HumlaUDPMessageListener {
    fun messageUDPPing(data: ByteArray)
    fun messageDecodedVoice(frames: List<DecodedVoiceFrame>)
    fun messageProtobufPing(msg: MumbleUDP.Ping)

    open class Stub : HumlaUDPMessageListener {
        open override fun messageUDPPing(data: ByteArray) {}
        open override fun messageDecodedVoice(frames: List<DecodedVoiceFrame>) {}
        open override fun messageProtobufPing(msg: MumbleUDP.Ping) {}
    }
}
