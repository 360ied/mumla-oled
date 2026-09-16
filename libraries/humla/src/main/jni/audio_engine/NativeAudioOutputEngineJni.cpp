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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "AudioOutputEngine.h"
#include "OpusVoiceDecoder.h"
#include <jni.h>
#include <android/log.h>
#include <memory>
#include <utility>
#include <vector>

#define LOG_TAG "NativeAudioOutputEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace mumla::audio;

struct OutputEngineContext {
    std::unique_ptr<AudioOutputEngine> engine;
    jobject listenerGlobalRef = nullptr;
    jmethodID onTalkMethod = nullptr;
};

static OutputEngineContext* getContext(jlong handle) {
    return reinterpret_cast<OutputEngineContext*>(handle);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeCreate(
        JNIEnv* env, jclass /*clazz*/, jobject listener) {
    auto* ctx = new OutputEngineContext();
    ctx->engine = std::make_unique<AudioOutputEngine>([] { return makeOpusOutputDecoder(); });
    if (listener != nullptr) {
        ctx->listenerGlobalRef = env->NewGlobalRef(listener);
        jclass listenerClass = env->GetObjectClass(listener);
        ctx->onTalkMethod = env->GetMethodID(listenerClass, "onTalkStateChanged",
                                             "(II)V");
        env->DeleteLocalRef(listenerClass);
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeDestroy(
        JNIEnv* env, jclass /*clazz*/, jlong handle) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return;
    }
    if (ctx->listenerGlobalRef != nullptr) {
        env->DeleteGlobalRef(ctx->listenerGlobalRef);
    }
    delete ctx;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeQueuePacket(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jint session,
        jbyteArray data, jint length, jint sequence, jint flags,
        jboolean isTerminator) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || data == nullptr || length <= 0) {
        return;
    }
    jsize arrayLen = env->GetArrayLength(data);
    if (length > arrayLen) {
        length = arrayLen;
    }
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return;
    }
    ctx->engine->queuePacket(session, reinterpret_cast<uint8_t*>(bytes),
                             static_cast<size_t>(length),
                             static_cast<uint32_t>(sequence), flags,
                             isTerminator == JNI_TRUE);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeRender(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jshortArray out,
        jint offset, jint length) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || out == nullptr || length <= 0 || offset < 0) {
        return 0;
    }

    // Collect talk events synchronously; the engine invokes the callback on
    // this thread while rendering, so forward them straight to Java.
    std::vector<std::pair<int32_t, int>> events;
    ctx->engine->setTalkCallback(
        [&events](int32_t session, int stateOrdinal) {
            events.emplace_back(session, stateOrdinal);
        });

    std::vector<int16_t> scratch(static_cast<size_t>(length));
    const size_t rendered = ctx->engine->renderMix(
        scratch.data(), static_cast<size_t>(length));

    ctx->engine->setTalkCallback(nullptr);

    if (rendered > 0) {
        env->SetShortArrayRegion(out, offset, static_cast<jsize>(rendered),
                                 scratch.data());
    }
    if (ctx->listenerGlobalRef != nullptr && ctx->onTalkMethod != nullptr) {
        for (const auto& event : events) {
            env->CallVoidMethod(ctx->listenerGlobalRef, ctx->onTalkMethod,
                                static_cast<jint>(event.first),
                                static_cast<jint>(event.second));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }
    }
    return static_cast<jint>(rendered);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeRemoveUser(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint session) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        ctx->engine->removeUser(session);
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeReset(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        ctx->engine->clear();
    }
}

} // extern "C"
