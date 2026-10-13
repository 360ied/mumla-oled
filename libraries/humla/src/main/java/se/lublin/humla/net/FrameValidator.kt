/*
 * Copyright (C) 2026 Brian Zhu
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

package se.lublin.humla.net

/**
 * Validates server-controlled TCP protobuf frame headers before any payload
 * allocation. Pure Java SE (no Android APIs) so it is unit-testable on the
 * JVM. Mirrors upstream {@code Connection::socketRead}, which disconnects on
 * frames over {@code 0x7fffff} bytes.
 */
object FrameValidator {
    /** Maximum accepted TCP protobuf frame payload, matching upstream Mumble. */
    const val MAX_FRAME_BYTES = 0x7fffff

    /** Message-type universe size, hoisted out of the per-frame hot path. */
    @JvmField
    val MESSAGE_TYPE_COUNT: Int = HumlaTCPMessageType.entries.size

    /** Validation outcome; callers abort the connection on anything but NONE. */
    enum class FrameError {
        NONE,
        BAD_TYPE,
        NEGATIVE_LENGTH,
        OVERLARGE_LENGTH
    }

    /**
     * Validates a frame header before payload allocation. Type range is
     * checked first ({@code readShort} is signed), then length bounds, so a
     * bad type with a bad length still reports BAD_TYPE.
     */
    @JvmStatic
    fun validateFrame(messageType: Short, messageLength: Int, messageTypeCount: Int): FrameError {
        if (messageType < 0 || messageType >= messageTypeCount) {
            return FrameError.BAD_TYPE
        }
        if (messageLength < 0) {
            return FrameError.NEGATIVE_LENGTH
        }
        if (messageLength > MAX_FRAME_BYTES) {
            return FrameError.OVERLARGE_LENGTH
        }
        return FrameError.NONE
    }

    @JvmStatic
    fun validateFrame(messageType: Short, messageLength: Int): FrameError {
        return validateFrame(messageType, messageLength, MESSAGE_TYPE_COUNT)
    }
}
