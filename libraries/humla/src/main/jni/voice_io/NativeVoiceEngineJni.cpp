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
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

#include "VoicePolicy.h"
#include "VoiceReceive.h"

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

using mumla::voice_io::PolicyCell;
using mumla::voice_io::VoiceReceive;

// VoiceEngine network context (step 5a.3b): policy cell plus the UDP
// receive half. No VoiceIo here: render stays per-pipeline in AudioOutput's
// NativeVoiceIo until 5a.4, so voice frames cross to Kotlin through the
// listener (the non-realtime receive thread calling Kotlin is allowed; only
// realtime threads are forbidden). The render wake post stays in the
// voice_io push functions Kotlin calls after this callback.
struct VoiceEngineContext {
    PolicyCell policy;
    VoiceReceive receive;
    JavaVM* vm = nullptr;
    jobject listenerGlobalRef = nullptr;
    jmethodID onVoiceMethod = nullptr;
    jmethodID onPingMethod = nullptr;
    jmethodID onResyncMethod = nullptr;
    jmethodID onUnavailableMethod = nullptr;

    VoiceEngineContext(JavaVM* javaVm, jobject listener, jmethodID onVoice,
                       jmethodID onPing, jmethodID onResync, jmethodID onUnavailable)
        : receive(nullptr, &policy),
          vm(javaVm),
          listenerGlobalRef(listener),
          onVoiceMethod(onVoice),
          onPingMethod(onPing),
          onResyncMethod(onResync),
          onUnavailableMethod(onUnavailable) {
        receive.setPingSink([this](const VoiceReceive::PingReport& report) {
            deliverPing(report);
        });
        receive.setResyncSink([this] { deliverResync(); });
        receive.setUnavailableSink([this] { deliverUnavailable(); });
        receive.setVoiceSink([this](const mumla::audio::InboundPacket* frames, size_t count,
                                    bool tunnel) { deliverVoice(frames, count, tunnel); });
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
    }

    void deliverVoice(const mumla::audio::InboundPacket* frames, size_t count, bool tunnel);
    void deliverPing(const VoiceReceive::PingReport& report);
    void deliverResync();
    void deliverUnavailable();
};

void VoiceEngineContext::deliverVoice(const mumla::audio::InboundPacket* frames,
                                      size_t count, bool tunnel) {
    if (count == 0) return;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }
    const int n = static_cast<int>(count);
    jintArray jSessions = env->NewIntArray(n);
    jintArray jLengths = env->NewIntArray(n);
    jintArray jSequences = env->NewIntArray(n);
    jintArray jFlags = env->NewIntArray(n);
    jbooleanArray jTerms = env->NewBooleanArray(n);
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray jPayloads = env->NewObjectArray(n, byteArrayClass, nullptr);
    if (jSessions == nullptr || jLengths == nullptr || jSequences == nullptr ||
        jFlags == nullptr || jTerms == nullptr || jPayloads == nullptr) {
        env->ExceptionClear();
        return;
    }
    std::vector<int32_t> sessions(n);
    std::vector<int32_t> lengths(n);
    std::vector<int32_t> sequences(n);
    std::vector<int32_t> flags(n);
    std::vector<unsigned char> terms(n);
    for (int i = 0; i < n; ++i) {
        sessions[i] = frames[i].session;
        lengths[i] = frames[i].length;
        sequences[i] = static_cast<int32_t>(frames[i].sequence);
        flags[i] = frames[i].flags;
        terms[i] = frames[i].isTerminator ? 1 : 0;
        if (frames[i].length > 0) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(frames[i].length));
            if (arr == nullptr) {
                env->ExceptionClear();
                return;
            }
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(frames[i].length),
                                    reinterpret_cast<const jbyte*>(frames[i].data));
            env->SetObjectArrayElement(jPayloads, i, arr);
            env->DeleteLocalRef(arr);
        }
    }
    env->SetIntArrayRegion(jSessions, 0, n, sessions.data());
    env->SetIntArrayRegion(jLengths, 0, n, lengths.data());
    env->SetIntArrayRegion(jSequences, 0, n, sequences.data());
    env->SetIntArrayRegion(jFlags, 0, n, flags.data());
    env->SetBooleanArrayRegion(jTerms, 0, n, terms.data());
    env->CallVoidMethod(listenerGlobalRef, onVoiceMethod, jSessions, jPayloads, jLengths,
                        jSequences, jFlags, jTerms,
                        tunnel ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) {
        LOGE("onVoice threw; clearing");
        env->ExceptionClear();
    }
    env->DeleteLocalRef(jSessions);
    env->DeleteLocalRef(jPayloads);
    env->DeleteLocalRef(jLengths);
    env->DeleteLocalRef(jSequences);
    env->DeleteLocalRef(jFlags);
    env->DeleteLocalRef(jTerms);
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
    jmethodID onVoice = env->GetMethodID(cls, "onVoice", "([I[[B[I[I[I[ZZ)V");
    jmethodID onPing = env->GetMethodID(cls, "onUdpPing", "(JJI)V");
    jmethodID onResync = env->GetMethodID(cls, "onCryptResync", "()V");
    jmethodID onUnavailable = env->GetMethodID(cls, "onUdpUnavailable", "()V");
    env->DeleteLocalRef(cls);
    if (onVoice == nullptr || onPing == nullptr || onResync == nullptr ||
        onUnavailable == nullptr) {
        env->ExceptionClear();
        env->DeleteGlobalRef(ref);
        return 0;
    }
    VoiceEngineContext* ctx = nullptr;
    try {
        ctx = new (std::nothrow)
            VoiceEngineContext(vm, ref, onVoice, onPing, onResync, onUnavailable);
    } catch (...) {
        ctx = nullptr;
    }
    if (ctx == nullptr) {
        env->DeleteGlobalRef(ref);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeDestroy(JNIEnv* env, jclass /*clazz*/,
                                                           jlong handle) {
    VoiceEngineContext* ctx = getContext(handle);
    if (ctx == nullptr) {
        return;
    }
    ctx->receive.stop();
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
        ctx->receive.stop();
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
