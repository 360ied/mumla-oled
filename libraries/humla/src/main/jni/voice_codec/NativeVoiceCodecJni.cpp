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
#include <vector>

using mumla::voice_codec::ParsedDatagram;
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

JNIEXPORT jobjectArray JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_parseLegacyVoice(
        JNIEnv* env, jclass, jbyteArray dataArray, jint legacyVersion) {
    if (dataArray == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArray);
    if (len <= 0) return nullptr;
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(dataArray, 0, len, reinterpret_cast<jbyte*>(data.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    ParsedDatagram parsed;
    if (!mumla::voice_codec::parseDatagram(UdpFormat::LEGACY, data.data(),
                                            data.size(),
                                            static_cast<uint32_t>(legacyVersion),
                                            &parsed)) {
        return nullptr;
    }
    if (parsed.kind != ParsedDatagram::VOICE) {
        // Unknown lead byte: no frames, not malformed. Return an empty array
        // so the caller drops without logging a codec failure.
        jclass frameClass = env->FindClass("se/lublin/humla/net/DecodedVoiceFrame");
        if (frameClass == nullptr) return nullptr;
        return env->NewObjectArray(0, frameClass, nullptr);
    }
    jclass frameClass = env->FindClass("se/lublin/humla/net/DecodedVoiceFrame");
    if (frameClass == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(frameClass, "<init>", "(III[BZ)V");
    if (ctor == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(parsed.frameCount),
                                             frameClass, nullptr);
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < parsed.frameCount; ++i) {
        const auto& f = parsed.frames[i];
        jbyteArray opus = env->NewByteArray(f.length);
        if (opus == nullptr) return nullptr;
        if (f.length > 0) {
            env->SetByteArrayRegion(opus, 0, f.length,
                                    reinterpret_cast<const jbyte*>(f.data));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return nullptr;
            }
        }
        jobject frame = env->NewObject(frameClass, ctor, f.session,
                                       static_cast<jint>(f.sequence), f.flags, opus,
                                       f.isTerminator ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(opus);
        if (frame == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), frame);
        env->DeleteLocalRef(frame);
    }
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_parseProtobufVoice(
        JNIEnv* env, jclass, jbyteArray dataArray) {
    if (dataArray == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArray);
    if (len <= 0) return nullptr;
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(dataArray, 0, len, reinterpret_cast<jbyte*>(data.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    ParsedDatagram parsed;
    if (!mumla::voice_codec::parseDatagram(UdpFormat::PROTOBUF, data.data(),
                                            data.size(), 0, &parsed)) {
        return nullptr;
    }
    if (parsed.kind != ParsedDatagram::VOICE) {
        jclass frameClass = env->FindClass("se/lublin/humla/net/DecodedVoiceFrame");
        if (frameClass == nullptr) return nullptr;
        return env->NewObjectArray(0, frameClass, nullptr);
    }
    jclass frameClass = env->FindClass("se/lublin/humla/net/DecodedVoiceFrame");
    if (frameClass == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(frameClass, "<init>", "(III[BZ)V");
    if (ctor == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(parsed.frameCount),
                                             frameClass, nullptr);
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < parsed.frameCount; ++i) {
        const auto& f = parsed.frames[i];
        jbyteArray opus = env->NewByteArray(f.length);
        if (opus == nullptr) return nullptr;
        if (f.length > 0) {
            env->SetByteArrayRegion(opus, 0, f.length,
                                    reinterpret_cast<const jbyte*>(f.data));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return nullptr;
            }
        }
        jobject frame = env->NewObject(frameClass, ctor, f.session,
                                       static_cast<jint>(f.sequence), f.flags, opus,
                                       f.isTerminator ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(opus);
        if (frame == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), frame);
        env->DeleteLocalRef(frame);
    }
    return result;
}

JNIEXPORT jobject JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_parseLegacyPing(
        JNIEnv* env, jclass, jbyteArray dataArray) {
    if (dataArray == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArray);
    if (len <= 0) return nullptr;
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(dataArray, 0, len, reinterpret_cast<jbyte*>(data.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    ParsedDatagram parsed;
    if (!mumla::voice_codec::parseDatagram(UdpFormat::LEGACY, data.data(),
                                            data.size(), 0, &parsed)) {
        return nullptr;
    }
    if (parsed.kind != ParsedDatagram::PING) return nullptr;
    jclass pingClass = env->FindClass("se/lublin/humla/net/DecodedPing");
    if (pingClass == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(pingClass, "<init>", "(JJI)V");
    if (ctor == nullptr) return nullptr;
    return env->NewObject(pingClass, ctor,
                          static_cast<jlong>(parsed.pingTimestamp),
                          static_cast<jlong>(parsed.serverVersionV2),
                          static_cast<jint>(parsed.maxBandwidthPerUser));
}

JNIEXPORT jobject JNICALL
Java_se_lublin_humla_net_NativeVoiceCodec_parseProtobufPing(
        JNIEnv* env, jclass, jbyteArray dataArray) {
    if (dataArray == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArray);
    if (len <= 0) return nullptr;
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(dataArray, 0, len, reinterpret_cast<jbyte*>(data.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    ParsedDatagram parsed;
    if (!mumla::voice_codec::parseDatagram(UdpFormat::PROTOBUF, data.data(),
                                            data.size(), 0, &parsed)) {
        return nullptr;
    }
    if (parsed.kind != ParsedDatagram::PING) return nullptr;
    jclass pingClass = env->FindClass("se/lublin/humla/net/DecodedPing");
    if (pingClass == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(pingClass, "<init>", "(JJI)V");
    if (ctor == nullptr) return nullptr;
    return env->NewObject(pingClass, ctor,
                          static_cast<jlong>(parsed.pingTimestamp),
                          static_cast<jlong>(parsed.serverVersionV2),
                          static_cast<jint>(parsed.maxBandwidthPerUser));
}

}  // extern "C"
