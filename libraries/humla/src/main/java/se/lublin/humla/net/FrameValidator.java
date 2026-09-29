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

package se.lublin.humla.net;

/**
 * Validates server-controlled TCP protobuf frame headers before any payload
 * allocation. Pure Java SE (no Android APIs) so it is unit-testable on the
 * JVM. Mirrors upstream {@code Connection::socketRead}, which disconnects on
 * frames over {@code 0x7fffff} bytes.
 */
public final class FrameValidator {
    /** Maximum accepted TCP protobuf frame payload, matching upstream Mumble. */
    public static final int MAX_FRAME_BYTES = 0x7fffff;

    public enum FrameError {
        NONE,
        BAD_TYPE,
        NEGATIVE_LENGTH,
        OVERLARGE_LENGTH
    }

    private FrameValidator() {
    }

    public static FrameError validateFrame(short messageType, int messageLength, int messageTypeCount) {
        if (messageType < 0 || messageType >= messageTypeCount) {
            return FrameError.BAD_TYPE;
        }
        if (messageLength < 0) {
            return FrameError.NEGATIVE_LENGTH;
        }
        if (messageLength > MAX_FRAME_BYTES) {
            return FrameError.OVERLARGE_LENGTH;
        }
        return FrameError.NONE;
    }

    public static FrameError validateFrame(short messageType, int messageLength) {
        return validateFrame(messageType, messageLength, HumlaTCPMessageType.values().length);
    }
}
