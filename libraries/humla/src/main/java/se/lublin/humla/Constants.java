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

package se.lublin.humla;

public class Constants {
    public static final int PROTOCOL_MAJOR = 1;
    public static final int PROTOCOL_MINOR = 5;
    public static final int PROTOCOL_PATCH = 0;

    public static final int TRANSMIT_VOICE_ACTIVITY = 0;
    public static final int TRANSMIT_PUSH_TO_TALK = 1;
    public static final int TRANSMIT_CONTINUOUS = 2;

    public static final int PROTOCOL_VERSION = (PROTOCOL_MAJOR << 16) | (PROTOCOL_MINOR << 8) | PROTOCOL_PATCH;
    public static final long PROTOCOL_VERSION_V2 = toVersionV2(PROTOCOL_MAJOR, PROTOCOL_MINOR, PROTOCOL_PATCH);
    public static final String PROTOCOL_STRING = PROTOCOL_MAJOR + "." + PROTOCOL_MINOR + "." + PROTOCOL_PATCH;
    public static final int DEFAULT_PORT = 64738;

    public static final long PROTOBUF_INTRODUCTION_VERSION_V2 = toVersionV2(1, 5, 0);
    public static final int PROTOBUF_INTRODUCTION_VERSION_V1 = (1 << 16) | (5 << 8);

    public static long toVersionV2(int major, int minor, int patch) {
        return (((long) (major & 0xFFFF)) << 48) |
               (((long) (minor & 0xFFFF)) << 32) |
               (((long) (patch & 0xFFFF)) << 16);
    }

    public static long toVersionV2FromLegacy(int legacyVersion) {
        int major = (legacyVersion >> 16) & 0xFFFF;
        int minor = (legacyVersion >> 8) & 0xFF;
        int patch = legacyVersion & 0xFF;
        return toVersionV2(major, minor, patch);
    }

    public static int toLegacyVersion(long versionV2) {
        int major = (int) ((versionV2 >> 48) & 0xFFFF);
        int minor = (int) ((versionV2 >> 32) & 0xFFFF);
        int patch = (int) ((versionV2 >> 16) & 0xFFFF);
        return ((major & 0xFFFF) << 16) | ((minor & 0xFF) << 8) | (patch & 0xFF);
    }

    public static String formatVersion(long versionV2) {
        int major = (int) ((versionV2 >> 48) & 0xFFFF);
        int minor = (int) ((versionV2 >> 32) & 0xFFFF);
        int patch = (int) ((versionV2 >> 16) & 0xFFFF);
        return major + "." + minor + "." + patch;
    }

    public static String formatLegacyVersion(int legacyVersion) {
        int major = (legacyVersion >> 16) & 0xFFFF;
        int minor = (legacyVersion >> 8) & 0xFF;
        int patch = legacyVersion & 0xFF;
        return major + "." + minor + "." + patch;
    }

    public static boolean isProtobufUdpSupported(long versionV2, int versionV1) {
        if (versionV2 > 0) {
            return versionV2 >= PROTOBUF_INTRODUCTION_VERSION_V2;
        }
        if (versionV1 > 0) {
            return versionV1 >= PROTOBUF_INTRODUCTION_VERSION_V1;
        }
        return false;
    }
}
