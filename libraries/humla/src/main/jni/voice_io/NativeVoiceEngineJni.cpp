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

#include "AudioInputEngine.h"
#include "OpusVoiceDecoder.h"
#include "OpusVoiceEncoder.h"
#include "RnnoiseProcessor.h"
#include "VoiceConfig.h"
#include "VoiceIo.h"
#include "VoiceNotifier.h"
#include "VoicePolicy.h"
#include "VoiceReceive.h"
#include "VoiceSend.h"
#include "VoiceStreamContext.h"
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
using mumla::voice_io::SettingsCell;
using mumla::voice_io::TalkCell;
using mumla::voice_io::TalkPublication;
using mumla::voice_io::VoiceIo;
using mumla::voice_io::VoiceNotifier;
using mumla::voice_io::VoiceReceive;
using mumla::voice_io::VoiceSend;
using mumla::voice_io::VoiceStreamContext;

// Unified voice I/O context (step 5a.3b facade, 5a.4 send half): the output
// engine and both inbound rings, the sender-filter policy cell, the capture
// policy (filter + gate) and settings cells, the UDP receive half, the capture/send half, and
// the talk notifier — one application-scoped native object owned by the
// VoiceEngine facade, kept across sessions. The AAudio render callback is
// the only caller of the output engine and the capture callback the only
// caller of the input engine; producers never reach either: UDP frames
// arrive via the receive thread, tunnelled frames via the session message
// coroutine, removals as control items on the tunnel ring, capture PCM via
// the capture entry. Talk travels as levels through the sequence lock plus
// the capture self-talking flag to the notifier thread, which calls Kotlin;
// render and capture never call into Kotlin (send calls only for tunnel).
struct VoiceEngineContext {
    VoiceIo io;
    TalkCell cell;
    PolicyCell policy;
    SettingsCell settings;
    VoiceReceive receive;
    VoiceSend send;
    // Step 5b: lifecycle pins, status counters, render carry, stream
    // generation and the disconnect atomic live here so the AAudio
    // callbacks can reach them without going through the JVM.
    VoiceStreamContext streams;
    VoiceNotifier notifier;
    JavaVM* vm = nullptr;
    jobject listenerGlobalRef = nullptr;
    jmethodID onPingMethod = nullptr;
    jmethodID onResyncMethod = nullptr;
    jmethodID onUnavailableMethod = nullptr;
    jmethodID onTalkSnapshotMethod = nullptr;
    jmethodID onTunnelPacketMethod = nullptr;
    jmethodID onStreamDisconnectMethod = nullptr;

    VoiceEngineContext(JavaVM* javaVm, jobject listener, jmethodID onPing,
                       jmethodID onResync, jmethodID onUnavailable, jmethodID onTalk,
                       jmethodID onTunnel, jmethodID onDisconnect)
        : io(mumla::audio::makeOpusOutputDecoder),
          receive(&io, &policy),
          send(&policy, &settings, &receive,
               [] { return mumla::audio::makeOpusVoiceEncoder(); },
               [] {
                   return mumla::audio::makeRnnoiseProcessor(true, nullptr, 0);
               }),
          streams(&io, &send),
          notifier(&cell, send.selfTalkingAtomic(),
                   [this](const TalkPublication& pub) { deliverTalk(pub); },
                   &streams.streamDisconnect,
                   [this](uint64_t generation) {
                       deliverStreamDisconnect(generation);
                   }),
          vm(javaVm),
          listenerGlobalRef(listener),
          onPingMethod(onPing),
          onResyncMethod(onResync),
          onUnavailableMethod(onUnavailable),
          onTalkSnapshotMethod(onTalk),
          onTunnelPacketMethod(onTunnel),
          onStreamDisconnectMethod(onDisconnect) {
        io.setTalkCell(&cell);
        io.setSettingsCell(&settings);
        io.setSelfTalking(send.selfTalkingAtomic());
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
        // and unavailability (all non-realtime). The send thread calls into
        // Kotlin only for tunnelled packets (non-realtime).
        send.setTunnelSink([this](const uint8_t* data, size_t length) {
            deliverTunnel(data, length);
        });
        send.setThreadHooks(
            [this] {
                JNIEnv* env = nullptr;
#ifdef __ANDROID__
                const jint attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
                const jint attached = vm->AttachCurrentThreadAsDaemon(
                    reinterpret_cast<void**>(&env), nullptr);
#endif
                if (attached != JNI_OK) {
                    LOGE("send attach failed; no tunnel callbacks this run");
                }
            },
            [this] { vm->DetachCurrentThread(); });
    }

    void deliverTalk(const TalkPublication& pub);
    void deliverStreamDisconnect(uint64_t generation);
    void deliverPing(const VoiceReceive::PingReport& report);
    void deliverResync();
    void deliverUnavailable();
    void deliverTunnel(const uint8_t* data, size_t length);
};

void VoiceEngineContext::deliverStreamDisconnect(uint64_t generation) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }
    env->CallVoidMethod(listenerGlobalRef, onStreamDisconnectMethod,
                        static_cast<jlong>(generation));
    if (env->ExceptionCheck()) {
        LOGE("onStreamDisconnect threw; clearing to protect the notifier thread");
        env->ExceptionClear();
    }
}

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
                        static_cast<jlong>(pub.droppedJitter),
                        pub.selfTalking ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) {
        LOGE("onTalkSnapshot threw; clearing to protect the notifier thread");
        env->ExceptionClear();
    }
    env->DeleteLocalRef(sessions);
    env->DeleteLocalRef(states);
}

void VoiceEngineContext::deliverTunnel(const uint8_t* data, size_t length) {
    if (data == nullptr || length == 0) {
        return;
    }
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        LOGE("deliverTunnel: not attached; dropping packet");
        return;
    }
    jbyteArray packet = env->NewByteArray(static_cast<jsize>(length));
    if (packet == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->SetByteArrayRegion(packet, 0, static_cast<jsize>(length),
                            reinterpret_cast<const jbyte*>(data));
    if (env->ExceptionCheck()) {
        LOGE("SetByteArrayRegion(tunnel) failed; dropping packet");
        env->ExceptionClear();
        env->DeleteLocalRef(packet);
        return;
    }
    env->CallVoidMethod(listenerGlobalRef, onTunnelPacketMethod, packet);
    if (env->ExceptionCheck()) {
        LOGE("onTunnelPacket threw; clearing to protect the send thread");
        env->ExceptionClear();
    }
    env->DeleteLocalRef(packet);
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
} // namespace

namespace mumla {
namespace voice_io {

// AAudioStreams.cpp reaches the callbacks' shared state through the opaque
// JNI handle. Host tests call the callback bodies with a context directly.
VoiceStreamContext* streamContextFromHandle(int64_t handle) {
    VoiceEngineContext* ctx = getContext(static_cast<jlong>(handle));
    return ctx == nullptr ? nullptr : &ctx->streams;
}

} // namespace voice_io
} // namespace mumla

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
    jmethodID onTalk = env->GetMethodID(cls, "onTalkSnapshot", "([I[IJJJZ)V");
    jmethodID onTunnel = env->GetMethodID(cls, "onTunnelPacket", "([B)V");
    jmethodID onDisconnect =
        env->GetMethodID(cls, "onStreamDisconnect", "(J)V");
    env->DeleteLocalRef(cls);
    if (onPing == nullptr || onResync == nullptr || onUnavailable == nullptr ||
        onTalk == nullptr || onTunnel == nullptr || onDisconnect == nullptr) {
        env->ExceptionClear();
        env->DeleteGlobalRef(ref);
        return 0;
    }
    VoiceEngineContext* ctx = nullptr;
    try {
        ctx = new (std::nothrow) VoiceEngineContext(vm, ref, onPing, onResync,
                                                    onUnavailable, onTalk, onTunnel,
                                                    onDisconnect);
    } catch (...) {
        ctx = nullptr;
    }
    if (ctx == nullptr) {
        env->DeleteGlobalRef(ref);
        return 0;
    }
    // The notifier is session-lifetime: nativeReset starts it (covering UDP
    // and tunnel sessions alike) and nativeStop stops it, so no notifier
    // thread outlives the session. The Kotlin talkActivity flow itself stays
    // service-lifetime; only its native producer is per-session.
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
    // Defensive Android cleanup: normal Kotlin teardown already closed the
    // streams, but destroying with live streams would leave AAudio callback
    // user data pointing at freed context. close joins any in-flight
    // callback before the asserts below and the delete.
#ifdef __ANDROID__
    mumla::voice_io::stopAndCloseVoiceStreams(&ctx->streams);
#endif
    assert(!ctx->streams.renderActive.load(std::memory_order_acquire));
    assert(!ctx->streams.captureActive.load(std::memory_order_acquire));
    ctx->receive.stop();
    ctx->send.stop();
    ctx->notifier.stop();
    if (ctx->listenerGlobalRef != nullptr) {
        env->DeleteGlobalRef(ctx->listenerGlobalRef);
    }
    delete ctx;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetFormat(JNIEnv* /*env*/,
                                                             jclass /*clazz*/, jlong handle,
                                                             jint udpFormat) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return;
    auto format = udpFormat == 1 ? mumla::voice_codec::UdpFormat::PROTOBUF
                                 : mumla::voice_codec::UdpFormat::LEGACY;
    ctx->receive.setFormat(format);
    ctx->send.setFormat(format);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetSettings(JNIEnv* /*env*/,
                                                              jclass /*clazz*/, jlong handle,
                                                              jint inputMode, jfloat vadMax,
                                                              jfloat vadMin,
                                                              jfloat amplitudeBoost,
                                                              jboolean adaptiveLeveler,
                                                              jboolean halfDuplex) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return;
    ctx->settings.publish(inputMode, vadMax, vadMin, amplitudeBoost,
                          adaptiveLeveler == JNI_TRUE, halfDuplex == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetVoiceRoute(JNIEnv* /*env*/,
                                                                 jclass /*clazz*/,
                                                                 jlong handle,
                                                                 jboolean udp) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return;
    ctx->send.setUseUdp(udp == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetRnnoiseModel(JNIEnv* env,
                                                                  jclass /*clazz*/,
                                                                  jlong handle,
                                                                  jbyteArray model) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return JNI_FALSE;
    // Control-plane only while capture is stopped (Start/Stop ordering):
    // the input engine is single-threaded, so a model swap racing a frame
    // would tear inside AudioInputEngine. Kotlin applies this at Start and
    // on model-load restarts, never from the capture callback.
    assert(!ctx->streams.captureActive.load(std::memory_order_acquire));
    if (model == nullptr) {
        ctx->send.setDenoiserModel(nullptr, 0);
        return JNI_TRUE;
    }
    const jsize len = env->GetArrayLength(model);
    if (len <= 0) {
        ctx->send.setDenoiserModel(nullptr, 0);
        return JNI_TRUE;
    }
    jbyte* bytes = env->GetByteArrayElements(model, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    ctx->send.setDenoiserModel(reinterpret_cast<uint8_t*>(bytes),
                               static_cast<size_t>(len));
    env->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeEnqueuePing(JNIEnv* /*env*/,
                                                              jclass /*clazz*/,
                                                              jlong handle,
                                                              jlong timestampMicros) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) return JNI_FALSE;
    return ctx->send.enqueuePing(static_cast<uint64_t>(timestampMicros)) ? JNI_TRUE
                                                                          : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedOutbound(JNIEnv* /*env*/,
                                                                      jclass /*clazz*/,
                                                                      jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr ? 0
                          : static_cast<jlong>(ctx->send.droppedOutbound());
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeSetPolicy(JNIEnv* env, jclass /*clazz*/,
                                                             jlong handle,
                                                             jintArray muted,
                                                             jintArray known,
                                                             jboolean transmitAllowed,
                                                             jboolean pttPressed,
                                                             jint voiceTarget,
                                                             jint bitrate,
                                                             jint framesPerPacket) {
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
            if (m > 256) {
                LOGE("nativeSetPolicy: muted list %d exceeds 256, keeping first 256", m);
            }
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
    ctx->policy.publish(mutedBuf, mutedCount, knownBuf, knownCount, overflow,
                         transmitAllowed == JNI_TRUE, pttPressed == JNI_TRUE,
                         voiceTarget, bitrate, framesPerPacket);
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
        // Session stop, after Kotlin closed the AAudio streams (see
        // VoiceEngine.apply Stop, which stops and closes both streams on the
        // voice control thread before calling here): both debug guards below
        // must hold, so a teardown-ordering regression fails loudly in debug
        // builds. Release builds rely on that same Kotlin close ordering; the
        // guards are check-then-act by design, since a lock on the realtime
        // path would be worse. Stop threads, then clear both halves while no
        // callback is live (send selfTalking false, io empty publish),
        // deliver the final empty snapshot synchronously, then stop the
        // notifier. The notifier loop is joined before the final notifyNow
        // so the version gate has a single writer; the join itself blocks up
        // to one 20 ms interval, accepted as Stop-time latency. Route resets
        // to tunnel; the socket stays closed until the next Start pushes UDP
        // with an endpoint.
        assert(!ctx->streams.renderActive.load(std::memory_order_acquire));
        assert(!ctx->streams.captureActive.load(std::memory_order_acquire));
        ctx->send.stop();
        ctx->receive.stop();
        ctx->notifier.stop();
        ctx->send.setUseUdp(false);
        ctx->send.clear();
        ctx->io.clear();
        ctx->notifier.notifyNow();
        ctx->receive.clearKeys();
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeReset(JNIEnv* /*env*/, jclass /*clazz*/,
                                                         jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx != nullptr) {
        // Session start, when no render or capture is live: join the send
        // thread, drain stale rings, reset both engines, publish the emptied
        // table (see VoiceIo::clear), and (re)start the session-lifetime
        // notifier and send thread. The stop-before-clear joins a send
        // thread left running by a previous Start without an intervening
        // Stop, so clear() never races the loop's drain (stop() is
        // idempotent and a no-op when already stopped, mirroring
        // nativeStop's order). Both debug asserts below must hold; a
        // violation fails loudly in debug builds.
        assert(!ctx->streams.renderActive.load(std::memory_order_acquire));
        assert(!ctx->streams.captureActive.load(std::memory_order_acquire));
        // A new session starts with no disconnect or stream error: the
        // notifier delivers generation 0 on its next tick, which Kotlin
        // skips because it is not the live pipeline's generation.
        ctx->streams.streamDisconnect.store(0, std::memory_order_release);
        ctx->streams.streamError.store(0, std::memory_order_release);
        ctx->send.stop();
        ctx->io.clear();
        ctx->send.clear();
        ctx->send.start();
        ctx->notifier.start();
    }
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

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeCaptureFrame(JNIEnv* env,
                                                              jclass /*clazz*/,
                                                              jlong handle,
                                                              jshortArray pcm,
                                                              jint offset, jint length) {
    if (env->ExceptionCheck()) {
        return;
    }
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr || pcm == nullptr || length <= 0 || offset < 0) {
        return;
    }
    const jsize arrayLen = env->GetArrayLength(pcm);
    if (arrayLen <= 0) {
        return;
    }
    const int64_t end = static_cast<int64_t>(offset) + static_cast<int64_t>(length);
    if (end > static_cast<int64_t>(arrayLen)) {
        return;
    }
    jshort* ptr = env->GetShortArrayElements(pcm, nullptr);
    if (ptr == nullptr) {
        return;
    }
    // Step 5b: the JNI test entry drives exactly the body AAudio's capture
    // callback runs. It owns the debug guard and the status counter, and
    // never attaches to the JVM or calls into Kotlin.
    mumla::voice_io::captureStreamFrames(
        &ctx->streams, reinterpret_cast<int16_t*>(ptr + offset),
        static_cast<size_t>(length));
    // Capture owns the PCM copy (engine copies per frame, remainder carried
    // inside VoiceSend), so the Java array is never written back.
    env->ReleaseShortArrayElements(pcm, ptr, JNI_ABORT);
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
    // Step 5b: the JNI test entry drives exactly the body AAudio's render
    // callback runs, including 20 ms chunks, the carry buffer, the debug
    // guard, and the status counters. It never calls into Kotlin.
    const bool silent = mumla::voice_io::renderStreamFrames(
        &ctx->streams, t_renderScratch.data(), static_cast<size_t>(length));
    if (silent) {
        return 0;
    }
    env->SetShortArrayRegion(out, offset, static_cast<jsize>(length),
                             t_renderScratch.data());
    if (env->ExceptionCheck()) {
        LOGE("nativeRender: SetShortArrayRegion failed, dropping %d samples",
             length);
        env->ExceptionClear();
        return 0;
    }
    return static_cast<jint>(length);
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedInbound(JNIEnv* /*env*/,
                                                                      jclass /*clazz*/,
                                                                      jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->streams.lastDroppedInbound.load(
                         std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedTunnel(JNIEnv* /*env*/,
                                                                     jclass /*clazz*/,
                                                                     jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->streams.lastDroppedTunnel.load(
                         std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeLastDroppedJitter(JNIEnv* /*env*/,
                                                                     jclass /*clazz*/,
                                                                     jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    return ctx == nullptr
               ? 0
               : static_cast<jlong>(
                     ctx->streams.lastDroppedJitter.load(
                         std::memory_order_relaxed));
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
