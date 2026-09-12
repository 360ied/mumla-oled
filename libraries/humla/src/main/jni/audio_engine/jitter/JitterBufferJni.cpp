/*
 * Copyright (C) 2026 Mumla Developers
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

#include <jni.h>
#include <cstdlib>
#include <cstring>
#include "speex_jitter.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeInit(JNIEnv* /*env*/, jclass /*clazz*/, jint frameSize) {
    JitterBuffer* jb = jitter_buffer_init(frameSize);
    return reinterpret_cast<jlong>(jb);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        jitter_buffer_destroy(jb);
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeReset(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        jitter_buffer_reset(jb);
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeSetMargin(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint margin) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        int m = margin;
        jitter_buffer_ctl(jb, JITTER_BUFFER_SET_MARGIN, &m);
    }
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeGetPointerTimestamp(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        return jitter_buffer_get_pointer_timestamp(jb);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeGetAvailableCount(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        int avail = 0;
        jitter_buffer_ctl(jb, JITTER_BUFFER_GET_AVAILABLE_COUNT, &avail);
        return avail;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativePut(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                                  jbyteArray data, jint length, jint timestamp,
                                                  jint span, jint sequence, jint userData) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb == nullptr || data == nullptr || length <= 0) {
        return;
    }

    jsize dataLen = env->GetArrayLength(data);
    if (length > dataLen) {
        length = dataLen;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return;
    }

    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(bytes);
    packet.len = static_cast<spx_uint32_t>(length);
    packet.timestamp = static_cast<spx_uint32_t>(timestamp);
    packet.span = static_cast<spx_uint32_t>(span);
    packet.sequence = static_cast<spx_uint16_t>(sequence);
    packet.user_data = static_cast<spx_uint32_t>(userData);

    jitter_buffer_put(jb, &packet);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeGet(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                                  jobject directBuffer, jint desiredSpan, jintArray outInfo) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb == nullptr || directBuffer == nullptr || outInfo == nullptr) {
        return JITTER_BUFFER_BAD_ARGUMENT;
    }

    if (env->GetArrayLength(outInfo) < 3) {
        return JITTER_BUFFER_BAD_ARGUMENT;
    }

    void* buf = env->GetDirectBufferAddress(directBuffer);
    jlong cap = env->GetDirectBufferCapacity(directBuffer);
    if (buf == nullptr || cap <= 0) {
        return JITTER_BUFFER_BAD_ARGUMENT;
    }

    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(buf);
    packet.len = static_cast<spx_uint32_t>(cap);

    spx_int32_t startOffset = 0;
    int res = jitter_buffer_get(jb, &packet, desiredSpan, &startOffset);

    if (res == JITTER_BUFFER_OK) {
        jint info[3] = {
            static_cast<jint>(packet.len),
            static_cast<jint>(packet.user_data),
            static_cast<jint>(startOffset)
        };
        env->SetIntArrayRegion(outInfo, 0, 3, info);
    }

    return res;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeUpdateDelay(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        jitter_buffer_update_delay(jb, nullptr, nullptr);
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_JitterBuffer_nativeTick(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* jb = reinterpret_cast<JitterBuffer*>(handle);
    if (jb != nullptr) {
        jitter_buffer_tick(jb);
    }
}

} // extern "C"
