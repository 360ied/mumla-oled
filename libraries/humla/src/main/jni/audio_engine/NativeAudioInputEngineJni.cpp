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

#include "AudioInputEngine.h"

#include <jni.h>
#include <android/log.h>
#include <memory>

#define LOG_TAG "NativeAudioInputEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace mumla::audio;

struct EngineContext {
    std::unique_ptr<AudioInputEngine> engine;
    JavaVM* jvm;
    jobject listenerGlobalRef;
    jbyteArray cachedBufferGlobalRef;
    jmethodID onPacketMethod;
    jmethodID onTalkingMethod;
};

static EngineContext* getContext(jlong handle) {
    return reinterpret_cast<EngineContext*>(handle);
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

    auto mode = static_cast<InputMode>(inputMode);
    if (rnnoiseModelData != nullptr) {
        jsize modelLen = env->GetArrayLength(rnnoiseModelData);
        jbyte* modelBytes = env->GetByteArrayElements(rnnoiseModelData, nullptr);
        ctx->engine = std::make_unique<AudioInputEngine>(
                bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, adaptiveLevelerEnabled, mode,
                reinterpret_cast<const uint8_t*>(modelBytes), static_cast<size_t>(modelLen));
        env->ReleaseByteArrayElements(rnnoiseModelData, modelBytes, JNI_ABORT);
    } else {
        ctx->engine = std::make_unique<AudioInputEngine>(
                bitrate, framesPerPacket, amplitudeBoost, rnnoiseEnabled, adaptiveLevelerEnabled, mode);
    }

    ctx->engine->setPacketCallback([ctx](const uint8_t* data, size_t size, int frames, bool isTerminator, uint64_t frameNumber) {
        if (ctx->jvm == nullptr || ctx->listenerGlobalRef == nullptr ||
            ctx->onPacketMethod == nullptr || ctx->cachedBufferGlobalRef == nullptr) {
            return;
        }
        JNIEnv* env = nullptr;
        jint res = ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        bool attached = false;
        if (res == JNI_EDETACHED) {
            if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                return;
            }
            attached = true;
        }

        env->SetByteArrayRegion(ctx->cachedBufferGlobalRef, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(ctx->listenerGlobalRef, ctx->onPacketMethod,
                            ctx->cachedBufferGlobalRef, static_cast<jint>(size), static_cast<jint>(frames),
                            static_cast<jboolean>(isTerminator), static_cast<jlong>(frameNumber));

        if (attached) {
            ctx->jvm->DetachCurrentThread();
        }
    });

    ctx->engine->setTalkingCallback([ctx](bool isTalking, float peakEnergy) {
        if (ctx->jvm == nullptr || ctx->listenerGlobalRef == nullptr || ctx->onTalkingMethod == nullptr) {
            return;
        }
        JNIEnv* env = nullptr;
        jint res = ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        bool attached = false;
        if (res == JNI_EDETACHED) {
            if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                return;
            }
            attached = true;
        }

        env->CallVoidMethod(ctx->listenerGlobalRef, ctx->onTalkingMethod,
                            static_cast<jboolean>(isTalking), static_cast<jfloat>(peakEnergy));

        if (attached) {
            ctx->jvm->DetachCurrentThread();
        }
    });

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
    if (offset + length > arrayLen) return;

    jshort* pcmPtr = env->GetShortArrayElements(pcmArray, nullptr);
    if (pcmPtr != nullptr) {
        ctx->engine->processFrame(pcmPtr + offset, static_cast<size_t>(length));
        env->ReleaseShortArrayElements(pcmArray, pcmPtr, JNI_ABORT);
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
        ctx->engine->setInputMode(static_cast<InputMode>(inputMode));
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
        ctx->engine->setPttTalking(talking);
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
        ctx->engine->setMuted(muted);
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
        ctx->engine->setBitrate(bitrate);
    }
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeGetBitrate(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        return ctx->engine->getBitrate();
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
        ctx->engine->setFramesPerPacket(framesPerPacket);
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
        ctx->engine->setAmplitudeBoost(boost);
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
        ctx->engine->setRnnoiseEnabled(enabled);
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeIsRnnoiseEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        return ctx->engine->isRnnoiseEnabled();
    }
    return false;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetAdaptiveLevelerEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jboolean enabled) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        ctx->engine->setAdaptiveLevelerEnabled(enabled);
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeIsAdaptiveLevelerEnabled(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        return ctx->engine->isAdaptiveLevelerEnabled();
    }
    return false;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeSetRnnoiseModel(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jbyteArray rnnoiseModelData) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
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
        return ctx->engine->hasRnnoiseModel();
    }
    return false;
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
        ctx->engine->setVadThresholds(vadMax, vadMin);
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
        ctx->engine->setVadHoldFrames(static_cast<uint32_t>(holdFrames));
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeAudioInputEngine_nativeReset(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {
    auto ctx = getContext(handle);
    if (ctx != nullptr && ctx->engine != nullptr) {
        ctx->engine->reset();
    }
}

} // extern "C"
