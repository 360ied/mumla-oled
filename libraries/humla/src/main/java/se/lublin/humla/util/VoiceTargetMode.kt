/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.util

/**
 * Created by andrew on 29/04/16.
 */
enum class VoiceTargetMode {
    NORMAL,
    WHISPER,
    SERVER_LOOPBACK;

    companion object {
        @JvmStatic
        fun fromId(targetId: Byte): VoiceTargetMode {
            // Unsigned mask: matches the and 0xFF masking at the encode site
            // (HumlaService.setVoiceTargetId), so -1 and 255 agree.
            val id = targetId.toInt() and 0xFF
            if (id == 0) {
                return NORMAL
            } else if (id in 1..30) {
                return WHISPER
            } else if (id == 31) {
                return SERVER_LOOPBACK
            } else {
                throw IllegalArgumentException("Unknown voice target mode id: $id")
            }
        }
    }
}
