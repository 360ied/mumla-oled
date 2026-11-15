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

#include <jni.h>

#include <atomic>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

#include "AudioOutputEngine.h"
#include "OpusVoiceDecoder.h"
#include "VoiceIo.h"
#include "VoiceNotifier.h"
#include "VoiceTalk.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "NativeVoiceIo"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
// Host (interop-test) build: no android/log, log to stderr instead.
#define LOGE(...)                                 \
    do {                                          \
        std::fprintf(stderr, "[NativeVoiceIo] "); \
        std::fprintf(stderr, __VA_ARGS__);         \
        std::fputc('\n', stderr);                 \
    } while (0)
#endif

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::voice_io::TalkCell;
using mumla::voice_io::TalkPublication;
using mumla::voice_io::VoiceIo;
using mumla::voice_io::VoiceNotifier;

// No JNI_OnLoad in this library: the JavaVM is captured per context from
// the creating env instead.

// Voice I/O JNI seam (step 5a.3a, native render). The render thread drains the inbound and
// tunnel rings and mixes through the output engine it alone drives, while
// the notifier thread delivers talk snapshots to Kotlin. The render entry
// point never attaches to the JVM and never calls into Kotlin — it uses the
// caller's env only for the PCM array — so the render thread stays a pure
// native realtime path. Talk transitions reach Kotlin solely through the
// notifier's single onTalkSnapshot call per change.
struct VoiceIoContext {
    VoiceIo io;
    TalkCell cell;
    VoiceNotifier notifier;
    JavaVM* vm = nullptr;
    jobject listenerGlobalRef = nullptr;
    jmethodID onSnapshotMethod = nullptr;
    // Debug lifecycle pin: render sets this while inside VoiceIo::render;
    // destroy asserts it is clear, so tearing down under a live render
    // thread fails loudly in debug builds instead of freeing the engine
    // under it. Release builds rely on the Kotlin join ordering (control
    // thread destroys only after Thread.join, as today).
    std::atomic<bool> renderActive{false};
    // Last render's report, cached for the Kotlin idle wait and the
    // drop-count log. Written by the render thread, read on the same
    // thread after each render call, so no extra engine call is needed to
    // ask whether voices are active.
    std::atomic<bool> lastHasActiveVoices{false};
    std::atomic<uint64_t> lastDroppedInbound{0};
    std::atomic<uint64_t> lastDroppedTunnel{0};
    std::atomic<uint64_t> lastDroppedJitter{0};

    VoiceIoContext(JavaVM* javaVm, jobject listener, jmethodID method)
        : io(mumla::audio::makeOpusOutputDecoder),
          notifier(&cell,
                   [this](const TalkPublication& pub) { deliver(pub); }),
          vm(javaVm),
          listenerGlobalRef(listener),
          onSnapshotMethod(method) {
        io.setTalkCell(&cell);
        notifier.setThreadHooks(
            [this] {
                // C++ JNI takes JNIEnv** on Android, void** on the host JVM.
                JNIEnv* env = nullptr;
#ifdef __ANDROID__
                const jint attached =
                    vm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
                const jint attached = vm->AttachCurrentThreadAsDaemon(
                    reinterpret_cast<void**>(&env), nullptr);
#endif
                if (attached != JNI_OK) {
                    LOGE("notifier attach failed; no snapshots this run");
                }
            },
            [this] { vm->DetachCurrentThread(); });
    }

    void deliver(const TalkPublication& pub) {
        JNIEnv* env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
            env == nullptr) {
            return;
        }
        const size_t n = pub.talk.count;
        jintArray sessions = env->NewIntArray(static_cast<jsize>(n));
        jintArray states = env->NewIntArray(static_cast<jsize>(n));
        if (sessions == nullptr || states == nullptr) {
            // OOM: drop this snapshot; the next 20 ms tick carries a newer
            // table wholesale, so nothing sticks.
            env->ExceptionClear();
            return;
        }
        if (n > 0) {
            std::vector<jint> jsessions(n);
            std::vector<jint> jstates(n);
            for (size_t i = 0; i < n; ++i) {
                jsessions[i] = pub.talk.entries[i].session;
                jstates[i] = static_cast<jint>(pub.talk.entries[i].state);
            }
            env->SetIntArrayRegion(sessions, 0, static_cast<jsize>(n), jsessions.data());
            env->SetIntArrayRegion(states, 0, static_cast<jsize>(n), jstates.data());
        }
        env->CallVoidMethod(listenerGlobalRef, onSnapshotMethod, sessions, states,
                            static_cast<jlong>(pub.droppedInbound),
                            static_cast<jlong>(pub.droppedTunnel),
                            static_cast<jlong>(pub.droppedJitter));
        if (env->ExceptionCheck()) {
            LOGE("onTalkSnapshot threw; clearing to protect the notifier thread");
            env->ExceptionClear();
        }
        env->DeleteLocalRef(sessions);
        env->DeleteLocalRef(states);
    }
};

static VoiceIoContext* getContext(jlong handle) {
    return reinterpret_cast<VoiceIoContext*>(handle);
}

// Renders into this scratch so steady-state ticks allocate nothing. It is
// thread-local because the render entry runs on a Java thread; the vector
// only grows (up to the largest render quantum seen) and is reused after
// warmup.
static thread_local std::vector<int16_t> t_renderScratch;

namespace {
// RAII debug pin for the render-active lifecycle assertion.
struct RenderGuard {
    explicit RenderGuard(std::atomic<bool>* flag) : m_flag(flag) {
        m_flag->store(true, std::memory_order_release);
    }
    ~RenderGuard() { m_flag->store(false, std::memory_order_release); }

    std::atomic<bool>* m_flag;
};
} // namespace

static bool fillPacket(JNIEnv* env, InboundPacket* packet, jint session,
                        jbyteArray data, jint length, jint sequence, jint flags,
                        jboolean isTerminator) {
    packet->session = session;
    packet->sequence = static_cast<uint32_t>(sequence);
    packet->flags = flags;
    packet->isTerminator = isTerminator == JNI_TRUE;
    if (!packet->isTerminator && (data == nullptr || length <= 0)) {
        return false;
    }
    if (length > 0) {
        if (data == nullptr ||
            length > static_cast<jint>(AudioOutputEngine::MAX_PACKET_BYTES)) {
            return false;
        }
        const jsize arrayLen = env->GetArrayLength(data);
        if (arrayLen <= 0 || length > arrayLen) {
            return false;
        }
        jbyte* bytes = env->GetByteArrayElements(data, nullptr);
        if (bytes == nullptr) {
            return false;
        }
        std::memcpy(packet->data, bytes, static_cast<size_t>(length));
        packet->length = static_cast<uint16_t>(length);
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    } else {
        packet->length = 0;
    }
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeCreate(JNIEnv* env, jclass /*clazz*/,
                                                      jobject listener) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
        return 0;
    }
    jobject ref = nullptr;
    jmethodID method = nullptr;
    if (listener != nullptr) {
        ref = env->NewGlobalRef(listener);
        if (ref == nullptr) {
            return 0;
        }
        jclass listenerClass = env->GetObjectClass(listener);
        if (listenerClass == nullptr) {
            env->ExceptionClear();
            env->DeleteGlobalRef(ref);
            return 0;
        }
        method = env->GetMethodID(listenerClass, "onTalkSnapshot", "([I[IJJJ)V");
        env->DeleteLocalRef(listenerClass);
        if (method == nullptr) {
            env->ExceptionClear();
            env->DeleteGlobalRef(ref);
            return 0;
        }
    }
    auto* ctx = new (std::nothrow) VoiceIoContext(vm, ref, method);
    if (ctx == nullptr) {
        if (ref != nullptr) {
            env->DeleteGlobalRef(ref);
        }
        return 0;
    }
    if (ref != nullptr) {
        ctx->notifier.start();
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeDestroy(JNIEnv* env, jclass /*clazz*/,
                                                       jlong handle) {
    VoiceIoContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return;
    }
    // The control thread destroys only after the render thread joined: a
    // live render here is a lifecycle bug, loud in debug builds.
    assert(!ctx->renderActive.load(std::memory_order_acquire));
    ctx->notifier.stop();
    if (ctx->listenerGlobalRef != nullptr) {
        env->DeleteGlobalRef(ctx->listenerGlobalRef);
    }
    delete ctx;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativePushInbound(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jint session, jbyteArray data,
        jint length, jint sequence, jint flags, jboolean isTerminator) {
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }
    VoiceIoContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    InboundPacket packet;
    if (!fillPacket(env, &packet, session, data, length, sequence, flags,
                    isTerminator)) {
        return JNI_FALSE;
    }
    return ctx->io.pushInbound(packet) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativePushTunnel(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jint session, jbyteArray data,
        jint length, jint sequence, jint flags, jboolean isTerminator) {
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }
    VoiceIoContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    InboundPacket packet;
    if (!fillPacket(env, &packet, session, data, length, sequence, flags,
                    isTerminator)) {
        return JNI_FALSE;
    }
    return ctx->io.pushTunnel(packet) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativePushRemoveUser(JNIEnv* /*env*/,
                                                              jclass /*clazz*/,
                                                              jlong handle,
                                                              jint session) {
    VoiceIoContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    return ctx->io.pushRemoveUser(session) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeRender(JNIEnv* env, jclass /*clazz*/,
                                                      jlong handle, jshortArray out,
                                                      jint offset, jint length) {
    if (env->ExceptionCheck()) {
        return 0;
    }
    VoiceIoContext* ctx = getContext(handle);
    if (ctx == nullptr || out == nullptr || length <= 0 || offset < 0) {
        return 0;
    }
    const jsize arrayLen = env->GetArrayLength(out);
    if (arrayLen <= 0) {
        return 0;
    }
    const int64_t end = static_cast<int64_t>(offset) + static_cast<int64_t>(length);
    if (end > static_cast<int64_t>(arrayLen)) {
        return 0;
    }
    // The engine covers quanta up to MAX_QUANTUM_SAMPLES (60 ms); larger
    // requests are a caller bug, refused rather than silently truncated.
    if (static_cast<size_t>(length) > AudioOutputEngine::MAX_QUANTUM_SAMPLES * 4) {
        LOGE("nativeRender: refusing %d samples (max %zu)", length,
             AudioOutputEngine::MAX_QUANTUM_SAMPLES * 4);
        return 0;
    }
    if (t_renderScratch.size() < static_cast<size_t>(length)) {
        t_renderScratch.resize(static_cast<size_t>(length));
    }
    RenderGuard guard(&ctx->renderActive);
    VoiceIo::RenderOut result;
    // Never attaches to the JVM and never calls into Kotlin: the caller's
    // env touches only the PCM array below, and the talk snapshot waits in
    // the sequence lock for the notifier thread.
    ctx->io.render(t_renderScratch.data(), static_cast<size_t>(length), &result);
    ctx->lastHasActiveVoices.store(result.hasActiveVoices, std::memory_order_relaxed);
    ctx->lastDroppedInbound.store(result.droppedInbound, std::memory_order_relaxed);
    ctx->lastDroppedTunnel.store(result.droppedTunnel, std::memory_order_relaxed);
    ctx->lastDroppedJitter.store(result.droppedJitter, std::memory_order_relaxed);
    if (result.samples > 0) {
        env->SetShortArrayRegion(out, offset, static_cast<jsize>(result.samples),
                                 t_renderScratch.data());
        if (env->ExceptionCheck()) {
            LOGE("nativeRender: SetShortArrayRegion failed, dropping %zu samples",
                 result.samples);
            env->ExceptionClear();
            return 0;
        }
    }
    return static_cast<jint>(result.samples);
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeLastHasActiveVoices(JNIEnv* /*env*/,
                                                                   jclass /*clazz*/,
                                                                   jlong handle) {
    VoiceIoContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    // The last render already folded this; Kotlin's idle wait reads the
    // cached flag instead of a separate engine call.
    return ctx->lastHasActiveVoices.load(std::memory_order_relaxed) ? JNI_TRUE
                                                                    : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeLastDroppedInbound(JNIEnv* /*env*/,
                                                                  jclass /*clazz*/,
                                                                  jlong handle) {
    VoiceIoContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->lastDroppedInbound.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeLastDroppedTunnel(JNIEnv* /*env*/,
                                                                 jclass /*clazz*/,
                                                                 jlong handle) {
    VoiceIoContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->lastDroppedTunnel.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeLastDroppedJitter(JNIEnv* /*env*/,
                                                                 jclass /*clazz*/,
                                                                 jlong handle) {
    VoiceIoContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->lastDroppedJitter.load(std::memory_order_relaxed));
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceIo_nativeClear(JNIEnv* /*env*/, jclass /*clazz*/,
                                                     jlong handle) {
    VoiceIoContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        assert(!ctx->renderActive.load(std::memory_order_acquire));
        ctx->io.clear();
    }
}

} // extern "C"
