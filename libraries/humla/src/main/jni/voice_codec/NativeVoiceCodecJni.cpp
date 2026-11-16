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

// Interim JNI seam (deleted in step 5a.4 with the Kotlin audio path). The
// codec itself is pure no-I/O with no locks; this bridge only marshals
// Kotlin byte arrays into caller buffers and parsed structs back into Java
// objects. It is voice_codec's single <jni.h> allowlist entry.

#include "VoiceCodec.h"

#include <jni.h>

#include <cstdint>
#include <cstring>

using mumla::voice_codec::UdpFormat;

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_frameLegacyVoice(
        JNIEnv* env,
        jclass,
        jint voiceTarget,
        jlong frameNumber,
        jbyteArray opusArray,
        jint opusLength,
        jboolean isTerminator,
        jint legacyVersion) {
    if (opusArray == nullptr || opusLength < 0) return nullptr;
    jsize arrayLen = env->GetArrayLength(opusArray);
    if (opusLength > arrayLen) return nullptr;
    // Opus payloads are at most 1024 bytes; legacy datagrams at most
    // 1 + 10 + 10 + 1024. A fixed stack buffer keeps this allocation-free.
    uint8_t opusBuf[mumla::audio::kMaxOpusBufferBytes];
    if (static_cast<size_t>(opusLength) > sizeof(opusBuf)) return nullptr;
    if (opusLength > 0) {
        env->GetByteArrayRegion(opusArray, 0, opusLength,
                                reinterpret_cast<jbyte*>(opusBuf));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
    }
    mumla::audio::EncodedPacket packet;
    packet.size = static_cast<size_t>(opusLength);
    if (packet.size > 0) {
        std::memcpy(packet.data, opusBuf, packet.size);
    }
    packet.frames = 0;
    packet.isTerminator = isTerminator != JNI_FALSE;
    packet.frameNumber = static_cast<uint64_t>(frameNumber);
    (void)legacyVersion;  // Opus framing is unaffected by the 1.2.2 swap.

    // Exact parity with AudioHandler's 1024-byte legacy buffer: larger
    // payloads drop (return null) instead of emitting longer datagrams.
    uint8_t out[1024];
    size_t written = mumla::voice_codec::frameVoice(
        UdpFormat::LEGACY, packet, static_cast<uint32_t>(voiceTarget),
        out, sizeof(out));
    if (written == 0) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(out));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_frameProtobufVoice(
        JNIEnv* env,
        jclass,
        jint voiceTarget,
        jlong frameNumber,
        jbyteArray opusArray,
        jint opusLength,
        jboolean isTerminator) {
    if (opusArray == nullptr || opusLength < 0) return nullptr;
    jsize arrayLen = env->GetArrayLength(opusArray);
    if (opusLength > arrayLen) return nullptr;
    uint8_t opusBuf[mumla::audio::kMaxOpusBufferBytes];
    if (static_cast<size_t>(opusLength) > sizeof(opusBuf)) return nullptr;
    if (opusLength > 0) {
        env->GetByteArrayRegion(opusArray, 0, opusLength,
                                reinterpret_cast<jbyte*>(opusBuf));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
    }
    mumla::audio::EncodedPacket packet;
    packet.size = static_cast<size_t>(opusLength);
    if (packet.size > 0) {
        std::memcpy(packet.data, opusBuf, packet.size);
    }
    packet.frames = 0;
    packet.isTerminator = isTerminator != JNI_FALSE;
    packet.frameNumber = static_cast<uint64_t>(frameNumber);

    // Exact parity with AudioHandler's 2048-byte protobuf buffer.
    uint8_t out[2048];
    size_t written = mumla::voice_codec::frameVoice(
        UdpFormat::PROTOBUF, packet, static_cast<uint32_t>(voiceTarget),
        out, sizeof(out));
    if (written == 0) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(out));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_frameLegacyPing(
        JNIEnv* env, jclass, jlong timestamp) {
    uint8_t out[1 + 9];
    size_t written = mumla::voice_codec::framePing(
        UdpFormat::LEGACY, static_cast<uint64_t>(timestamp), out, sizeof(out));
    if (written == 0) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(out));
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_frameProtobufPing(
        JNIEnv* env, jclass, jlong timestamp) {
    uint8_t out[1 + 16];
    size_t written = mumla::voice_codec::framePing(
        UdpFormat::PROTOBUF, static_cast<uint64_t>(timestamp), out, sizeof(out));
    if (written == 0) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
                            reinterpret_cast<const jbyte*>(out));
    return result;
}

}  // extern "C"
