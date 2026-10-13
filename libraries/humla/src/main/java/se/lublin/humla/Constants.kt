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

package se.lublin.humla

import se.lublin.humla.protobuf.Mumble

object Constants {
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 5
    const val PROTOCOL_PATCH = 0

    const val TRANSMIT_VOICE_ACTIVITY = 0
    const val TRANSMIT_PUSH_TO_TALK = 1
    const val TRANSMIT_CONTINUOUS = 2

    // Derived values are @JvmField (not const): const initializers cannot call
    // functions such as shl/or/toVersionV2. Plain literals above stay const so
    // Java switch case labels (HumlaService transmit mode) keep compiling.
    @JvmField
    val PROTOCOL_VERSION: Int = (PROTOCOL_MAJOR shl 16) or (PROTOCOL_MINOR shl 8) or PROTOCOL_PATCH
    @JvmField
    val PROTOCOL_VERSION_V2: Long = toVersionV2(PROTOCOL_MAJOR, PROTOCOL_MINOR, PROTOCOL_PATCH)
    @JvmField
    val PROTOCOL_STRING: String = "$PROTOCOL_MAJOR.$PROTOCOL_MINOR.$PROTOCOL_PATCH"
    const val DEFAULT_PORT = 64738

    /** Duration in milliseconds of an individual Opus audio frame in Mumble. */
    const val FRAME_DURATION_MS = 10

    /** Default audio frames per packet in Mumble (2 frames @ 10ms = 20ms audio per packet). */
    const val DEFAULT_FRAMES_PER_PACKET = 2

    /**
     * Sanitizes audio frames per packet to valid Opus configurations (1, 2, 4, 6).
     *
     * @param fpp Requested frames per packet count.
     * @return A valid Opus frames per packet count, or [DEFAULT_FRAMES_PER_PACKET].
     */
    @JvmStatic
    fun sanitizeFramesPerPacket(fpp: Int): Int {
        if (fpp == 1 || fpp == 2 || fpp == 4 || fpp == 6) {
            return fpp
        }
        return DEFAULT_FRAMES_PER_PACKET
    }

    @JvmField
    val PROTOBUF_INTRODUCTION_VERSION_V2: Long = toVersionV2(1, 5, 0)
    @JvmField
    val PROTOBUF_INTRODUCTION_VERSION_V1: Int = (1 shl 16) or (5 shl 8)

    @JvmStatic
    fun toVersionV2(major: Int, minor: Int, patch: Int): Long {
        return (((major and 0xFFFF).toLong()) shl 48) or
            (((minor and 0xFFFF).toLong()) shl 32) or
            (((patch and 0xFFFF).toLong()) shl 16)
    }

    @JvmStatic
    fun toVersionV2FromLegacy(legacyVersion: Int): Long {
        val major = (legacyVersion shr 16) and 0xFFFF
        val minor = (legacyVersion shr 8) and 0xFF
        val patch = legacyVersion and 0xFF
        return toVersionV2(major, minor, patch)
    }

    @JvmStatic
    fun toLegacyVersion(versionV2: Long): Int {
        val major = ((versionV2 shr 48) and 0xFFFF).toInt()
        val minor = ((versionV2 shr 32) and 0xFFFF).toInt()
        val patch = ((versionV2 shr 16) and 0xFFFF).toInt()
        return ((major and 0xFFFF) shl 16) or ((minor and 0xFF) shl 8) or (patch and 0xFF)
    }

    @JvmStatic
    fun formatVersion(versionV2: Long): String {
        val major = ((versionV2 shr 48) and 0xFFFF).toInt()
        val minor = ((versionV2 shr 32) and 0xFFFF).toInt()
        val patch = ((versionV2 shr 16) and 0xFFFF).toInt()
        return "$major.$minor.$patch"
    }

    @JvmStatic
    fun formatLegacyVersion(legacyVersion: Int): String {
        val major = (legacyVersion shr 16) and 0xFFFF
        val minor = (legacyVersion shr 8) and 0xFF
        val patch = legacyVersion and 0xFF
        return "$major.$minor.$patch"
    }

    @JvmStatic
    fun isProtobufUdpSupported(versionV2: Long, versionV1: Int): Boolean {
        if (versionV2 > 0) {
            return versionV2 >= PROTOBUF_INTRODUCTION_VERSION_V2
        }
        if (versionV1 > 0) {
            return versionV1 >= PROTOBUF_INTRODUCTION_VERSION_V1
        }
        return false
    }

    /**
     * Creates a Mumble Version message builder with protocol versions and release name.
     * OS name and OS version fields are intentionally omitted to protect user privacy
     * and prevent device fingerprinting.
     *
     * @param release Client release name (e.g. "Mumla 0.17.0")
     * @return Version message builder ready to be sent or augmented
     */
    @JvmStatic
    fun createVersionMessage(release: String?): Mumble.Version.Builder {
        val version = Mumble.Version.newBuilder()
        if (release != null) {
            version.release = release
        }
        version.versionV1 = PROTOCOL_VERSION
        version.versionV2 = PROTOCOL_VERSION_V2
        return version
    }
}
