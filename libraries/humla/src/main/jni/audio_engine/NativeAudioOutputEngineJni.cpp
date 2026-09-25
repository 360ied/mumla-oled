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

#include "AudioOutputEngine.h"
#include "OpusVoiceDecoder.h"
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <utility>
#include <vector>

#define LOG_TAG "NativeAudioOutputEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace mumla::audio;

// Cached in JNI_OnLoad; used to attach callback threads that the JVM did not
// attach itself (e.g. a native audio thread calling back into Java).
static JavaVM* g_vm = nullptr;

struct OutputEngineContext {
    std::unique_ptr<AudioOutputEngine> engine;
    jobject listenerGlobalRef = nullptr;
    jmethodID onTalkMethod = nullptr;
    // Talk events from the engine, which emits them after releasing its own
    // mutex on whichever thread produced them (the render thread for
    // mix-time transitions, network threads for removeUser/clear/eviction).
    // The persistent callback registered in nativeCreate appends here;
    // nativeRender drains after each quantum and forwards to Java. Lock
    // order is strictly one-way — engine mutex, then this one — because the
    // engine never invokes its callback while holding its mutex.
    std::mutex talkEventMutex;
    std::vector<std::pair<int32_t, int>> talkEvents;
};

static OutputEngineContext* getContext(jlong handle) {
    return reinterpret_cast<OutputEngineContext*>(handle);
}

// Renders into this scratch so steady-state ticks allocate nothing. It is
// thread-local because renderMix may run on a dedicated audio thread while
// queuePacket runs on network threads; sharing one buffer would race. The
// vector only grows (up to the largest render quantum seen) and is reused
// across ticks after warmup.
static thread_local std::vector<int16_t> t_renderScratch;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeCreate(
        JNIEnv* env, jclass /*clazz*/, jobject listener) {
    auto* ctx = new (std::nothrow) OutputEngineContext();
    if (ctx == nullptr) {
        // OOM: an exception may already be pending; just propagate it.
        return 0;
    }
    ctx->engine = std::make_unique<AudioOutputEngine>([] { return makeOpusOutputDecoder(); });
    // One persistent talk callback for the engine's lifetime. The previous
    // code re-registered a lambda capturing a nativeRender stack vector per
    // render; removeUser on a network thread could copy that callback and
    // invoke it concurrently with — or after — the render call, a data race
    // and use-after-free on the captured vector. It also dropped removeUser
    // events entirely whenever no render was in flight. Events now land in
    // the context queue and are drained by nativeRender, idle or not.
    ctx->engine->setTalkCallback(
        [ctx](int32_t session, int stateOrdinal) {
            std::lock_guard<std::mutex> lock(ctx->talkEventMutex);
            ctx->talkEvents.emplace_back(session, stateOrdinal);
        });
    if (listener != nullptr) {
        jobject ref = env->NewGlobalRef(listener);
        if (ref == nullptr) {
            // OOM exception stays pending for the caller to observe.
            delete ctx;
            return 0;
        }
        jclass listenerClass = env->GetObjectClass(listener);
        if (listenerClass == nullptr) {
            env->ExceptionClear();
            env->DeleteGlobalRef(ref);
            delete ctx;
            return 0;
        }
        jmethodID method = env->GetMethodID(listenerClass, "onTalkStateChanged",
                                            "(II)V");
        if (method == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(listenerClass);
            env->DeleteGlobalRef(ref);
            delete ctx;
            return 0;
        }
        env->DeleteLocalRef(listenerClass);
        ctx->listenerGlobalRef = ref;
        ctx->onTalkMethod = method;
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
    if (env->ExceptionCheck()) {
        return;
    }
    OutputEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return;
    }
    if (isTerminator == JNI_TRUE && length == 0) {
        // Empty end-of-speech marker (terminator with no Opus payload): no
        // bytes to pin, just flag the voice so it drains instead of hitting
        // the miss-expiry. data may be an empty array or null here.
        ctx->engine->queuePacket(session, nullptr, 0,
                                 static_cast<uint32_t>(sequence), flags, true);
        return;
    }
    if (data == nullptr || length <= 0) {
        return;
    }
    const jsize arrayLen = env->GetArrayLength(data);
    if (arrayLen <= 0) {
        return;
    }
    // Overflow-safe overrun check in jsize arithmetic: both operands are
    // non-negative here, so the comparison cannot wrap. Reject instead of
    // clamping so a corrupt length never silently decodes a truncated frame.
    if (length > arrayLen) {
        return;
    }
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        // OOM: the pending exception propagates to the caller.
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
    if (env->ExceptionCheck()) {
        return 0;
    }
    OutputEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || out == nullptr || length <= 0 || offset < 0) {
        return 0;
    }
    const jsize arrayLen = env->GetArrayLength(out);
    if (arrayLen <= 0) {
        return 0;
    }
    // Overflow-safe window check: promote to 64 bits before adding so a
    // hostile offset+length pair cannot wrap past the array end.
    const int64_t end =
        static_cast<int64_t>(offset) + static_cast<int64_t>(length);
    if (end > static_cast<int64_t>(arrayLen)) {
        return 0;
    }

    if (t_renderScratch.size() < static_cast<size_t>(length)) {
        t_renderScratch.resize(static_cast<size_t>(length));
    }
    const size_t rendered = ctx->engine->renderMix(
        t_renderScratch.data(), static_cast<size_t>(length));

    if (rendered > 0) {
        env->SetShortArrayRegion(out, offset, static_cast<jsize>(rendered),
                                 t_renderScratch.data());
        if (env->ExceptionCheck()) {
            LOGE("nativeRender: SetShortArrayRegion failed, dropping %zu samples",
                 rendered);
            env->ExceptionClear();
            return 0;
        }
    }
    // Drain events queued by the persistent callback: this quantum's talk
    // transitions plus anything emitted by removeUser/clear/eviction on
    // network threads since the last render. Draining happens on every
    // call, including silent quanta, so idle-time removals still reach
    // Java within one 20 ms wake.
    std::vector<std::pair<int32_t, int>> events;
    {
        std::lock_guard<std::mutex> lock(ctx->talkEventMutex);
        events.swap(ctx->talkEvents);
    }
    if (ctx->listenerGlobalRef != nullptr && ctx->onTalkMethod != nullptr &&
        !events.empty()) {
        // Render is normally invoked from a Java-attached thread, but a
        // native audio thread may call in without attachment. Attach as a
        // daemon (no Java-thread semantics needed) and detach only if this
        // scope attached; a pre-attached thread is left alone.
        JNIEnv* cbEnv = env;
        bool didAttach = false;
        if (g_vm != nullptr) {
            JNIEnv* probe = nullptr;
            if (g_vm->GetEnv(reinterpret_cast<void**>(&probe),
                             JNI_VERSION_1_6) == JNI_EDETACHED) {
                if (g_vm->AttachCurrentThreadAsDaemon(&cbEnv, nullptr) == JNI_OK) {
                    didAttach = true;
                } else {
                    LOGE("nativeRender: failed to attach callback thread, "
                         "dropping %zu talk events", events.size());
                    cbEnv = nullptr;
                }
            }
        }
        if (cbEnv != nullptr) {
            for (const auto& event : events) {
                cbEnv->CallVoidMethod(ctx->listenerGlobalRef, ctx->onTalkMethod,
                                      static_cast<jint>(event.first),
                                      static_cast<jint>(event.second));
                if (cbEnv->ExceptionCheck()) {
                    LOGE("nativeRender: onTalkStateChanged threw for session %d, "
                         "continuing with remaining events",
                         static_cast<int>(event.first));
                    cbEnv->ExceptionClear();
                    continue;
                }
            }
        }
        if (didAttach) {
            g_vm->DetachCurrentThread();
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
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeSetJitterMarginFrames(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint marginFrames) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        ctx->engine->setJitterMarginFrames(marginFrames);
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

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_audio_NativeAudioOutputEngine_nativeHasActiveVoices(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    OutputEngineContext* ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        return ctx->engine->activeUserCount() > 0 ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

} // extern "C"
