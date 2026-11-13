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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "AudioInputEngine.h"
#include "OpusVoiceEncoder.h"
#include "RnnoiseProcessor.h"

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <memory>
#include <mutex>

#define LOG_TAG "NativeAudioInputEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace mumla::audio;

// Interim JNI seam (deleted in step 5a.4 with the Kotlin audio path). The
// engine itself is single-threaded by contract and holds no lock; this bridge
// serializes the Kotlin capture thread against the control thread and keeps
// the latest InputConfig the capture thread hands to processFrame. Callbacks
// into Java run after the bridge mutex is released.
struct EngineContext {
    std::unique_ptr<AudioInputEngine> engine;
    // Bridge mutex. The tripwire's <mutex> ban covers the engine, not this
    // seam: the allowlist records the exception until 5a.4 deletes the file.
    std::mutex engineMutex;
    InputConfig config;
    JavaVM* jvm;
    jobject listenerGlobalRef;
    jbyteArray cachedBufferGlobalRef;
    jmethodID onPacketMethod;
    jmethodID onTalkingMethod;
};

static EngineContext* getContext(jlong handle) {
    return reinterpret_cast<EngineContext*>(handle);
}

// Attaches the calling native thread to the JVM when it is not already
// attached. Returns the usable env (or nullptr) and reports whether this scope
// attached, so the caller can detach again.
static JNIEnv* attachForCallback(EngineContext* ctx, bool* attached) {
    *attached = false;
    if (ctx->jvm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    const jint res =
        ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        *attached = true;
    }
    return res == JNI_OK || *attached ? env : nullptr;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeCreate(
        JNIEnv* env,
        jobject thiz,
        jint bitrate,
        jint framesPerPacket,
        jfloat amplitudeBoost,
        jboolean rnnoiseEnabled,
        jboolean adaptiveLevelerEnabled,
        jint inputMode,
        jbyteArray rnnoiseModelData,
        jobject listener) {

    auto ctx = new EngineContext();
    env->GetJavaVM(&ctx->jvm);

    if (listener != nullptr) {
        ctx->listenerGlobalRef = env->NewGlobalRef(listener);
        jclass listenerClass = env->GetObjectClass(listener);
        ctx->onPacketMethod = env->GetMethodID(listenerClass, "onAudioPacketEncoded", "([BIIZJ)V");
        ctx->onTalkingMethod = env->GetMethodID(listenerClass, "onTalkingStateChanged", "(ZF)V");
        env->DeleteLocalRef(listenerClass);

        jbyteArray localBuf = env->NewByteArray(AudioInputEngine::MAX_OPUS_BUFFER_BYTES);
        ctx->cachedBufferGlobalRef = reinterpret_cast<jbyteArray>(env->NewGlobalRef(localBuf));
        env->DeleteLocalRef(localBuf);
    } else {
        ctx->listenerGlobalRef = nullptr;
        ctx->cachedBufferGlobalRef = nullptr;
        ctx->onPacketMethod = nullptr;
        ctx->onTalkingMethod = nullptr;
    }

    ctx->config.mode = static_cast<InputMode>(inputMode);
    ctx->config.bitrate = bitrate;
    ctx->config.framesPerPacket = framesPerPacket;
    ctx->config.amplitudeBoost = amplitudeBoost;
    ctx->config.adaptiveLevelerEnabled = adaptiveLevelerEnabled != JNI_FALSE;
    ctx->config.transmitAllowed = true;
    ctx->config.pttPressed = false;

    auto encoder = makeOpusVoiceEncoder(bitrate);
    std::unique_ptr<IDenoiser> denoiser;
    if (rnnoiseModelData != nullptr) {
        jsize modelLen = env->GetArrayLength(rnnoiseModelData);
        jbyte* modelBytes = env->GetByteArrayElements(rnnoiseModelData, nullptr);
        denoiser = makeRnnoiseProcessor(rnnoiseEnabled != JNI_FALSE,
                                        reinterpret_cast<const uint8_t*>(modelBytes),
                                        static_cast<size_t>(modelLen));
        env->ReleaseByteArrayElements(rnnoiseModelData, modelBytes, JNI_ABORT);
    } else {
        denoiser = makeRnnoiseProcessor(rnnoiseEnabled != JNI_FALSE, nullptr, 0);
    }
    ctx->engine = std::make_unique<AudioInputEngine>(std::move(encoder), std::move(denoiser));

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeDestroy(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx == nullptr) return;

    if (ctx->cachedBufferGlobalRef != nullptr) {
        env->DeleteGlobalRef(ctx->cachedBufferGlobalRef);
        ctx->cachedBufferGlobalRef = nullptr;
    }
    if (ctx->listenerGlobalRef != nullptr) {
        env->DeleteGlobalRef(ctx->listenerGlobalRef);
        ctx->listenerGlobalRef = nullptr;
    }
    delete ctx;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeProcessFrame(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jshortArray pcmArray,
        jint offset,
        jint length) {
    auto ctx = getContext(handle);
    if (ctx == nullptr || ctx->engine == nullptr || pcmArray == nullptr || length <= 0 || offset < 0) return;

    jsize arrayLen = env->GetArrayLength(pcmArray);
    if (arrayLen <= 0) return;
    // Overflow-safe window check: promote to 64 bits before adding so a
    // hostile offset+length pair cannot wrap past the array end (mirrors
    // nativeRender in NativeAudioOutputEngineJni.cpp).
    const int64_t end =
        static_cast<int64_t>(offset) + static_cast<int64_t>(length);
    if (end > static_cast<int64_t>(arrayLen)) return;

    jshort* pcmPtr = env->GetShortArrayElements(pcmArray, nullptr);
    if (pcmPtr == nullptr) {
        return;
    }
    InputFrameResult result;
    {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->engine->processFrame(pcmPtr + offset, static_cast<size_t>(length),
                                  ctx->config, &result);
    }
    env->ReleaseShortArrayElements(pcmArray, pcmPtr, JNI_ABORT);

    if (ctx->listenerGlobalRef == nullptr || ctx->jvm == nullptr) {
        return;
    }
    const bool hasTalking = result.talkingChanged && ctx->onTalkingMethod != nullptr;
    const bool hasPackets = result.packetCount > 0 && ctx->onPacketMethod != nullptr &&
                            ctx->cachedBufferGlobalRef != nullptr;
    if (!hasTalking && !hasPackets) {
        return;
    }
    bool attached = false;
    JNIEnv* cbEnv = attachForCallback(ctx, &attached);
    if (cbEnv == nullptr) {
        return;
    }
    // Historical event order: the talking transition first, then the packets
    // the same frame produced.
    if (hasTalking) {
        cbEnv->CallVoidMethod(ctx->listenerGlobalRef, ctx->onTalkingMethod,
                              static_cast<jboolean>(result.talking),
                              static_cast<jfloat>(result.peakEnergy));
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    }
    for (size_t i = 0; hasPackets && i < result.packetCount; ++i) {
        const EncodedPacket& packet = result.packets[i];
        if (packet.size > 0) {
            cbEnv->SetByteArrayRegion(ctx->cachedBufferGlobalRef, 0,
                                      static_cast<jsize>(packet.size),
                                      reinterpret_cast<const jbyte*>(packet.data));
        }
        cbEnv->CallVoidMethod(ctx->listenerGlobalRef, ctx->onPacketMethod,
                              ctx->cachedBufferGlobalRef,
                              static_cast<jint>(packet.size),
                              static_cast<jint>(packet.frames),
                              static_cast<jboolean>(packet.isTerminator),
                              static_cast<jlong>(packet.frameNumber));
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    }
    if (attached) {
        ctx->jvm->DetachCurrentThread();
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetInputMode(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jint inputMode) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.mode = static_cast<InputMode>(inputMode);
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetPttTalking(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jboolean talking) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.pttPressed = talking != JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetMuted(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jboolean muted) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.transmitAllowed = muted == JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetBitrate(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jint bitrate) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.bitrate = bitrate;
    }
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeGetBitrate(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        return ctx->config.bitrate;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetFramesPerPacket(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jint framesPerPacket) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.framesPerPacket = framesPerPacket;
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetAmplitudeBoost(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jfloat boost) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.amplitudeBoost = boost;
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetRnnoiseEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jboolean enabled) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        // Control plane: enabling recreates the denoiser's recurrent state, so
        // this is deliberately not part of the per-frame config (see
        // AudioInputEngine::setRnnoiseEnabled).
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->engine->setRnnoiseEnabled(enabled != JNI_FALSE);
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeIsRnnoiseEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        return ctx->engine->isRnnoiseEnabled() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetAdaptiveLevelerEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jboolean enabled) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.adaptiveLevelerEnabled = enabled != JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeIsAdaptiveLevelerEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        return ctx->config.adaptiveLevelerEnabled ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetRnnoiseModel(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jbyteArray rnnoiseModelData) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        if (rnnoiseModelData != nullptr) {
            jsize modelLen = env->GetArrayLength(rnnoiseModelData);
            jbyte* modelBytes = env->GetByteArrayElements(rnnoiseModelData, nullptr);
            ctx->engine->setRnnoiseModel(reinterpret_cast<const uint8_t*>(modelBytes), static_cast<size_t>(modelLen));
            env->ReleaseByteArrayElements(rnnoiseModelData, modelBytes, JNI_ABORT);
        } else {
            ctx->engine->setRnnoiseModel(nullptr, 0);
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeHasRnnoiseModel(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        return ctx->engine->hasRnnoiseModel() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetVadThresholds(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jfloat vadMax,
        jfloat vadMin) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.vadMax = vadMax;
        ctx->config.vadMin = vadMin;
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetVadHoldFrames(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jint holdFrames) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.vadHoldFrames = static_cast<uint32_t>(holdFrames);
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetVadSquelchFloor(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jfloat minDb) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->config.vadSquelchFloorDb = minDb;
    }
}

JNIEXPORT jfloat JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeGetVadSquelchFloor(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        return ctx->config.vadSquelchFloorDb;
    }
    return -65.0f;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeReset(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        std::lock_guard<std::mutex> lock(ctx->engineMutex);
        ctx->engine->reset();
    }
}

} // extern "C"
