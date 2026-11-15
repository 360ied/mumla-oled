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

#include "OpusVoiceDecoder.h"
#include "VoiceIo.h"
#include "VoiceNotifier.h"
#include "VoicePolicy.h"
#include "VoiceReceive.h"
#include "VoiceTalk.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "NativeVoiceEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGE(...)                                 \
    do {                                          \
        std::fprintf(stderr, "[NativeVoiceEngine] "); \
        std::fprintf(stderr, __VA_ARGS__);         \
        std::fputc('\n', stderr);                 \
    } while (0)
#endif

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::voice_io::PolicyCell;
using mumla::voice_io::TalkCell;
using mumla::voice_io::TalkPublication;
using mumla::voice_io::VoiceIo;
using mumla::voice_io::VoiceNotifier;
using mumla::voice_io::VoiceReceive;

// Unified voice I/O context (step 5a.3b, spec-literal): the output engine
// and both inbound rings, the sender-filter policy cell, the UDP receive
// half, and the talk notifier — one application-scoped native object owned
// by the VoiceEngine facade, kept across sessions. The render thread is the
// only thread that touches the engine; producers never reach it: UDP frames
// arrive via the receive thread, tunnelled frames via the session message
// coroutine, removals as control items on the tunnel ring. Talk travels as
// levels through the sequence lock to the notifier thread, which calls
// Kotlin; render never calls into Kotlin.
struct VoiceEngineContext {
    VoiceIo io;
    TalkCell cell;
    VoiceNotifier notifier;
    PolicyCell policy;
    VoiceReceive receive;
    JavaVM* vm = nullptr;
    jobject listenerGlobalRef = nullptr;
    jmethodID onPingMethod = nullptr;
    jmethodID onResyncMethod = nullptr;
    jmethodID onUnavailableMethod = nullptr;
    jmethodID onTalkSnapshotMethod = nullptr;
    // Debug lifecycle pin: render sets this while inside VoiceIo::render;
    // destroy asserts it is clear, so tearing down under a live render
    // thread fails loudly in debug builds instead of freeing the engine
    // under it. Release builds rely on the Kotlin join ordering (control
    // thread destroys only after the render thread is joined).
    std::atomic<bool> renderActive{false};
    std::atomic<bool> lastHasActiveVoices{false};
    std::atomic<uint64_t> lastDroppedInbound{0};
    std::atomic<uint64_t> lastDroppedTunnel{0};
    std::atomic<uint64_t> lastDroppedJitter{0};

    VoiceEngineContext(JavaVM* javaVm, jobject listener, jmethodID onPing,
                       jmethodID onResync, jmethodID onUnavailable, jmethodID onTalk)
        : io(mumla::audio::makeOpusOutputDecoder),
          notifier(&cell, [this](const TalkPublication& pub) { deliverTalk(pub); }),
          receive(&io, &policy),
          vm(javaVm),
          listenerGlobalRef(listener),
          onPingMethod(onPing),
          onResyncMethod(onResync),
          onUnavailableMethod(onUnavailable),
          onTalkSnapshotMethod(onTalk) {
        io.setTalkCell(&cell);
        notifier.setThreadHooks(
            [this] {
                JNIEnv* env = nullptr;
#ifdef __ANDROID__
                const jint attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
                const jint attached = vm->AttachCurrentThreadAsDaemon(
                    reinterpret_cast<void**>(&env), nullptr);
#endif
                if (attached != JNI_OK) {
                    LOGE("notifier attach failed; no snapshots this run");
                }
            },
            [this] { vm->DetachCurrentThread(); });
        receive.setPingSink([this](const VoiceReceive::PingReport& report) {
            deliverPing(report);
        });
        receive.setResyncSink([this] { deliverResync(); });
        receive.setUnavailableSink([this] { deliverUnavailable(); });
        receive.setThreadHooks(
            [this] {
                JNIEnv* env = nullptr;
#ifdef __ANDROID__
                const jint attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
                const jint attached = vm->AttachCurrentThreadAsDaemon(
                    reinterpret_cast<void**>(&env), nullptr);
#endif
                if (attached != JNI_OK) {
                    LOGE("receive attach failed; no callbacks this run");
                }
            },
            [this] { vm->DetachCurrentThread(); });
        // NOTE: no voice-frames sink. Voice stays native-direct (receive and
        // tunnel push straight into the owned VoiceIo rings); the receive
        // thread never calls into Kotlin for voice, only for pings, resync,
        // and unavailability (all non-realtime).
    }

    void deliverTalk(const TalkPublication& pub);
    void deliverPing(const VoiceReceive::PingReport& report);
    void deliverResync();
    void deliverUnavailable();
};

void VoiceEngineContext::deliverTalk(const TalkPublication& pub) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }
    const size_t n = pub.talk.count;
    jintArray sessions = env->NewIntArray(static_cast<jsize>(n));
    jintArray states = env->NewIntArray(static_cast<jsize>(n));
    if (sessions == nullptr || states == nullptr) {
        env->ExceptionClear();
        if (sessions != nullptr) env->DeleteLocalRef(sessions);
        if (states != nullptr) env->DeleteLocalRef(states);
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
        if (env->ExceptionCheck()) {
            LOGE("SetIntArrayRegion(sessions) failed; dropping snapshot");
            env->ExceptionClear();
            env->DeleteLocalRef(sessions);
            env->DeleteLocalRef(states);
            return;
        }
        env->SetIntArrayRegion(states, 0, static_cast<jsize>(n), jstates.data());
        if (env->ExceptionCheck()) {
            LOGE("SetIntArrayRegion(states) failed; dropping snapshot");
            env->ExceptionClear();
            env->DeleteLocalRef(sessions);
            env->DeleteLocalRef(states);
            return;
        }
    }
    env->CallVoidMethod(listenerGlobalRef, onTalkSnapshotMethod, sessions, states,
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

void VoiceEngineContext::deliverPing(const VoiceReceive::PingReport& report) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }
    env->CallVoidMethod(listenerGlobalRef, onPingMethod,
                        static_cast<jlong>(report.timestampMicros),
                        static_cast<jlong>(report.serverVersionV2),
                        static_cast<jint>(report.maxBandwidth));
    if (env->ExceptionCheck()) {
        LOGE("onUdpPing threw; clearing");
        env->ExceptionClear();
    }
}

void VoiceEngineContext::deliverResync() {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }
    env->CallVoidMethod(listenerGlobalRef, onResyncMethod);
    if (env->ExceptionCheck()) {
        LOGE("onCryptResync threw; clearing");
        env->ExceptionClear();
    }
}

void VoiceEngineContext::deliverUnavailable() {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }
    env->CallVoidMethod(listenerGlobalRef, onUnavailableMethod);
    if (env->ExceptionCheck()) {
        LOGE("onUdpUnavailable threw; clearing");
        env->ExceptionClear();
    }
}

static VoiceEngineContext* getContext(jlong handle) {
    return reinterpret_cast<VoiceEngineContext*>(handle);
}

// Renders into this scratch so steady-state ticks allocate nothing. It is
// thread-local because the render entry runs on a Java thread; the vector
// only grows (up to the largest render quantum seen) and is reused after
// warmup.
static thread_local std::vector<int16_t> t_renderScratch;

namespace {
constexpr size_t kMaxRenderSamples = AudioOutputEngine::MAX_QUANTUM_SAMPLES * 4;
struct RenderGuard {
    explicit RenderGuard(std::atomic<bool>* flag) : m_flag(flag) {
        m_flag->store(true, std::memory_order_release);
    }
    ~RenderGuard() { m_flag->store(false, std::memory_order_release); }
    std::atomic<bool>* m_flag;
};
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeCreate(JNIEnv* env, jclass /*clazz*/,
                                                          jobject listener) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr || listener == nullptr) {
        return 0;
    }
    jobject ref = env->NewGlobalRef(listener);
    if (ref == nullptr) {
        return 0;
    }
    jclass cls = env->GetObjectClass(listener);
    if (cls == nullptr) {
        env->ExceptionClear();
        env->DeleteGlobalRef(ref);
        return 0;
    }
    jmethodID onPing = env->GetMethodID(cls, "onUdpPing", "(JJI)V");
    jmethodID onResync = env->GetMethodID(cls, "onCryptResync", "()V");
    jmethodID onUnavailable = env->GetMethodID(cls, "onUdpUnavailable", "()V");
    jmethodID onTalk = env->GetMethodID(cls, "onTalkSnapshot", "([I[IJJJ)V");
    env->DeleteLocalRef(cls);
    if (onPing == nullptr || onResync == nullptr || onUnavailable == nullptr ||
        onTalk == nullptr) {
        env->ExceptionClear();
        env->DeleteGlobalRef(ref);
        return 0;
    }
    VoiceEngineContext* ctx = nullptr;
    try {
        ctx = new (std::nothrow)
            VoiceEngineContext(vm, ref, onPing, onResync, onUnavailable, onTalk);
    } catch (...) {
        ctx = nullptr;
    }
    if (ctx == nullptr) {
        env->DeleteGlobalRef(ref);
        return 0;
    }
    // The notifier runs for the context lifetime (service-scoped publisher);
    // render wakeups and talk delivery need no per-session start.
    ctx->notifier.start();
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeDestroy(JNIEnv* env, jclass /*clazz*/,
                                                           jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return;
    }
    // The control thread destroys only after the render thread is joined: a
    // live render here is a lifecycle bug, loud in debug builds.
    assert(!ctx->renderActive.load(std::memory_order_acquire));
    ctx->receive.stop();
    ctx->notifier.stop();
    if (ctx->listenerGlobalRef != nullptr) {
        env->DeleteGlobalRef(ctx->listenerGlobalRef);
    }
    delete ctx;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetFormat(JNIEnv* /*env*/,
                                                             jclass /*clazz*/, jlong handle,
                                                             jint udpFormat,
                                                             jint legacyVersion) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return;
    auto format = udpFormat == 1 ? mumla::voice_codec::UdpFormat::PROTOBUF
                                 : mumla::voice_codec::UdpFormat::LEGACY;
    ctx->receive.setFormat(format, static_cast<uint32_t>(legacyVersion));
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetPolicy(JNIEnv* env, jclass /*clazz*/,
                                                             jlong handle,
                                                             jintArray muted,
                                                             jintArray known) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return;
    int32_t mutedBuf[256];
    int32_t knownBuf[256];
    size_t mutedCount = 0;
    size_t knownCount = 0;
    bool overflow = false;
    if (muted != nullptr) {
        const jsize m = env->GetArrayLength(muted);
        if (m > 0) {
            jsize take = m > 256 ? 256 : m;
            env->GetIntArrayRegion(muted, 0, take, mutedBuf);
            mutedCount = static_cast<size_t>(take);
        }
    }
    if (known != nullptr) {
        const jsize k = env->GetArrayLength(known);
        if (k > 0) {
            if (k > 256) overflow = true;
            jsize take = k > 256 ? 256 : k;
            env->GetIntArrayRegion(known, 0, take, knownBuf);
            knownCount = static_cast<size_t>(take);
        }
    }
    ctx->policy.publish(mutedBuf, mutedCount, knownBuf, knownCount, overflow);
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetKeys(JNIEnv* env, jclass /*clazz*/,
                                                           jlong handle, jbyteArray key,
                                                           jbyteArray eiv, jbyteArray div) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || key == nullptr || eiv == nullptr || div == nullptr) {
        return JNI_FALSE;
    }
    if (env->GetArrayLength(key) != 16 || env->GetArrayLength(eiv) != 16 ||
        env->GetArrayLength(div) != 16) {
        return JNI_FALSE;
    }
    uint8_t k[16], e[16], d[16];
    env->GetByteArrayRegion(key, 0, 16, reinterpret_cast<jbyte*>(k));
    env->GetByteArrayRegion(eiv, 0, 16, reinterpret_cast<jbyte*>(e));
    env->GetByteArrayRegion(div, 0, 16, reinterpret_cast<jbyte*>(d));
    return ctx->receive.setKeys(k, e, d) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetDecryptIv(JNIEnv* env, jclass /*clazz*/,
                                                                jlong handle,
                                                                jbyteArray div) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || div == nullptr || env->GetArrayLength(div) != 16) {
        return JNI_FALSE;
    }
    uint8_t d[16];
    env->GetByteArrayRegion(div, 0, 16, reinterpret_cast<jbyte*>(d));
    return ctx->receive.setDecryptIv(d) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeGetEncryptIv(JNIEnv* env, jclass /*clazz*/,
                                                                jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return nullptr;
    uint8_t out[16];
    ctx->receive.getEncryptIv(out);
    jbyteArray arr = env->NewByteArray(16);
    if (arr == nullptr) return nullptr;
    env->SetByteArrayRegion(arr, 0, 16, reinterpret_cast<jbyte*>(out));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeStart(JNIEnv* env, jclass /*clazz*/,
                                                         jlong handle, jstring host,
                                                         jint port) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || host == nullptr || port <= 0 || port > 65535) {
        return JNI_FALSE;
    }
    const char* chars = env->GetStringUTFChars(host, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const bool ok = ctx->receive.start(chars, static_cast<uint16_t>(port));
    env->ReleaseStringUTFChars(host, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeStop(JNIEnv* /*env*/, jclass /*clazz*/,
                                                        jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        // Stop closes the socket and joins the receive thread. It never
        // touches the engine: the render thread is owned by the pipeline
        // and joined there, so clearing here would race it. Reset happens
        // in nativeReset at the next Start, when no render is live.
        ctx->receive.stop();
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeReset(JNIEnv* /*env*/, jclass /*clazz*/,
                                                         jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        // Session start, when no render is live: drain stale rings, reset
        // both engines, publish the emptied table (see VoiceIo::clear).
        ctx->io.clear();
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSendDatagram(JNIEnv* env, jclass /*clazz*/,
                                                                jlong handle,
                                                                jbyteArray plain,
                                                                jint length) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || plain == nullptr || length <= 0) {
        return JNI_FALSE;
    }
    const jsize arrayLen = env->GetArrayLength(plain);
    if (arrayLen < length) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(plain, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    const bool ok = ctx->receive.sendDatagram(reinterpret_cast<uint8_t*>(bytes),
                                              static_cast<size_t>(length));
    env->ReleaseByteArrayElements(plain, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeQueueTunnelled(JNIEnv* env, jclass /*clazz*/,
                                                                  jlong handle,
                                                                  jbyteArray packet) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || packet == nullptr) {
        return JNI_FALSE;
    }
    const jsize len = env->GetArrayLength(packet);
    if (len <= 0) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(packet, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    const bool ok = ctx->receive.handleTunnelled(reinterpret_cast<uint8_t*>(bytes),
                                                 static_cast<size_t>(len));
    env->ReleaseByteArrayElements(packet, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativePushRemoveUser(JNIEnv* /*env*/,
                                                                  jclass /*clazz*/,
                                                                  jlong handle,
                                                                  jint session) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    return ctx->io.pushRemoveUser(session) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeRender(JNIEnv* env, jclass /*clazz*/,
                                                          jlong handle, jshortArray out,
                                                          jint offset, jint length) {
    if (env->ExceptionCheck()) {
        return 0;
    }
    VoiceEngineContext* ctx = getContext(handle);
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
    if (static_cast<size_t>(length) > kMaxRenderSamples) {
        LOGE("nativeRender: refusing %d samples (max %zu)", length, kMaxRenderSamples);
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
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeAwaitWork(JNIEnv* /*env*/,
                                                             jclass /*clazz*/,
                                                             jlong handle,
                                                             jint timeoutMs) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    return ctx->io.awaitWork(timeoutMs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeWakeForStop(JNIEnv* /*env*/,
                                                               jclass /*clazz*/,
                                                               jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        ctx->io.wakeForStop();
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastHasActiveVoices(JNIEnv* /*env*/,
                                                                       jclass /*clazz*/,
                                                                       jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    return ctx->lastHasActiveVoices.load(std::memory_order_relaxed) ? JNI_TRUE
                                                                    : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedInbound(JNIEnv* /*env*/,
                                                                      jclass /*clazz*/,
                                                                      jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->lastDroppedInbound.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedTunnel(JNIEnv* /*env*/,
                                                                     jclass /*clazz*/,
                                                                     jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->lastDroppedTunnel.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedJitter(JNIEnv* /*env*/,
                                                                     jclass /*clazz*/,
                                                                     jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->lastDroppedJitter.load(std::memory_order_relaxed));
}

JNIEXPORT jintArray JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeGetStats(JNIEnv* env, jclass /*clazz*/,
                                                            jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return nullptr;
    int32_t good = 0, late = 0, lost = 0, resync = 0;
    ctx->receive.getStats(&good, &late, &lost, &resync);
    jintArray arr = env->NewIntArray(4);
    if (arr == nullptr) return nullptr;
    jint vals[4] = {good, late, lost, resync};
    env->SetIntArrayRegion(arr, 0, 4, vals);
    return arr;
}

} // extern "C"
