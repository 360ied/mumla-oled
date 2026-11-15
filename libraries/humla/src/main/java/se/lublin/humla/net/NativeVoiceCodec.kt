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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla.net

import se.lublin.humla.HumlaNative

/**
 * One decoded voice frame from the native packet codec (step 5a.2).
 *
 * Android-free: lives in net for android-free framing use without android
 * imports. [opus] is empty for an end-of-transmission marker.
 */
data class DecodedVoiceFrame(
    @JvmField val session: Int,
    @JvmField val sequence: Int,
    @JvmField val flags: Int,
    @JvmField val opus: ByteArray,
    @JvmField val isTerminator: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedVoiceFrame) return false
        return session == other.session &&
            sequence == other.sequence &&
            flags == other.flags &&
            isTerminator == other.isTerminator &&
            opus.contentEquals(other.opus)
    }

    override fun hashCode(): Int {
        var result = session
        result = 31 * result + sequence
        result = 31 * result + flags
        result = 31 * result + opus.contentHashCode()
        result = 31 * result + isTerminator.hashCode()
        return result
    }
}

/**
 * Interim JNI over the native packet codec (step 5a.2, trimmed in 5a.3b).
 *
 * Thin bridge only: AudioHandler encode and VoiceEngine ping framing call
 * through here. Both inbound parses are native now (voice_io receive and
 * tunnel), so the parse and ping-parse entries are deleted with the Kotlin
 * framing they served. The codec core stays JNI-free; this file holds
 * voice_codec's single <jni.h> allowlist entry and leaves with the Kotlin
 * encode framing in 5a.4. See docs/target-architecture/voice.md#packet-codec.
 *
 * Android-free: no android.* imports, so Transport stays in the
 * android-free check.
 */
object NativeVoiceCodec {
    init {
        HumlaNative.ensureLoaded()
    }

    @JvmStatic
    external fun frameLegacyVoice(
        voiceTarget: Int,
        frameNumber: Long,
        opus: ByteArray,
        opusLength: Int,
        isTerminator: Boolean,
        legacyVersion: Int,
    ): ByteArray?

    @JvmStatic
    external fun frameProtobufVoice(
        voiceTarget: Int,
        frameNumber: Long,
        opus: ByteArray,
        opusLength: Int,
        isTerminator: Boolean,
    ): ByteArray?

    @JvmStatic
    external fun frameLegacyPing(timestamp: Long): ByteArray?

    @JvmStatic
    external fun frameProtobufPing(timestamp: Long): ByteArray?
}

