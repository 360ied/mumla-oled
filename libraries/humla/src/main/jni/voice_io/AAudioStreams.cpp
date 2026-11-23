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

// Step 5b, Android-only half: everything that calls AAudio, including the
// open/start/stop/close JNI entry points. The host library and host tests
// never see AAudio; the callback bodies they exercise live in
// VoiceStreamCallbacks.cpp.

#include <jni.h>

#include <aaudio/AAudio.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>

#include "VoiceStreamContext.h"

namespace {

constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannelCount = 1;
constexpr int64_t kStopTimeoutNanos = 500LL * 1000LL * 1000LL;

const char* kLogTag = "MumlaAAudio";

void logError(const char* what, aaudio_result_t result) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s: %s (%d)", what,
                        AAudio_convertResultToText(result),
                        static_cast<int>(result));
}

// AAudio's documentation forbids stopping, closing, or reopening a stream
// from its error callback, and callbacks must not log either. Record only
// the generation of the stream pair that failed; the notifier delivers it
// on its next tick and Kotlin reopens on the voice control thread.
void streamErrorCallback(AAudioStream* /*stream*/, void* userData,
                         aaudio_result_t /*error*/) {
    auto* data = static_cast<mumla::voice_io::VoiceStreamCallbackData*>(userData);
    if (data == nullptr || data->context == nullptr) {
        return;
    }
    data->context->streamDisconnect.store(data->generation,
                                          std::memory_order_release);
}

aaudio_data_callback_result_t outputDataCallback(AAudioStream* /*stream*/,
                                                 void* userData,
                                                 void* audioData,
                                                 int32_t numFrames) {
    auto* data = static_cast<mumla::voice_io::VoiceStreamCallbackData*>(userData);
    if (audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    if (data == nullptr || data->context == nullptr) {
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    // The silence report is for the JNI test entry; the callback always
    // delivers the full buffer so the output stream stays active.
    (void)mumla::voice_io::renderStreamFrames(
        data->context, static_cast<int16_t*>(audioData),
        static_cast<size_t>(numFrames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t inputDataCallback(AAudioStream* /*stream*/,
                                                void* userData,
                                                void* audioData,
                                                int32_t numFrames) {
    auto* data = static_cast<mumla::voice_io::VoiceStreamCallbackData*>(userData);
    if (data != nullptr && data->context != nullptr && audioData != nullptr &&
        numFrames > 0) {
        mumla::voice_io::captureStreamFrames(
            data->context, static_cast<const int16_t*>(audioData),
            static_cast<size_t>(numFrames));
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void configureCommon(AAudioStreamBuilder* builder, aaudio_direction_t direction,
                     AAudioStream_dataCallback callback,
                     mumla::voice_io::VoiceStreamCallbackData* userData) {
    AAudioStreamBuilder_setDirection(builder, direction);
    AAudioStreamBuilder_setSampleRate(builder, kSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, kChannelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder,
                                           AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, callback, userData);
    AAudioStreamBuilder_setErrorCallback(builder, streamErrorCallback, userData);
    // Device ID stays unspecified: setCommunicationDevice and the
    // speakerphone setting decide it (route.md stream parameters).
    // Buffer capacity and callback size stay at AAudio's defaults.
}

bool openStream(AAudioStream** out, aaudio_direction_t direction,
                int32_t routeParameter, AAudioStream_dataCallback callback,
                mumla::voice_io::VoiceStreamCallbackData* userData) {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        return false;
    }
    configureCommon(builder, direction, callback, userData);
    if (direction == AAUDIO_DIRECTION_INPUT) {
        AAudioStreamBuilder_setInputPreset(
            builder, static_cast<aaudio_input_preset_t>(routeParameter));
        // Allocating a session ID lets Kotlin attach the capture effects.
        // AAudio warns this can put the input on a higher-latency path.
        AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
    } else {
        AAudioStreamBuilder_setUsage(
            builder, static_cast<aaudio_usage_t>(routeParameter));
    }
    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        logError("open stream", result);
        return false;
    }
    // A stream that AAudio opened at another rate, channel count, or format
    // fails like any other open; the engines assume whole 10 ms mono frames.
    if (AAudioStream_getSampleRate(stream) != kSampleRate ||
        AAudioStream_getChannelCount(stream) != kChannelCount ||
        AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "open stream: rate=%d channels=%d format=%d",
                            AAudioStream_getSampleRate(stream),
                            AAudioStream_getChannelCount(stream),
                            AAudioStream_getFormat(stream));
        AAudioStream_close(stream);
        return false;
    }
    *out = stream;
    return true;
}

// States that no longer need a stop request or a wait: a stream that never
// started (its pair failed to open) is already final, and a disconnected
// stream may never report STOPPED.
bool stopIsFinal(aaudio_stream_state_t state) {
    switch (state) {
        case AAUDIO_STREAM_STATE_UNINITIALIZED:
        case AAUDIO_STREAM_STATE_UNKNOWN:
        case AAUDIO_STREAM_STATE_OPEN:
        case AAUDIO_STREAM_STATE_STOPPED:
        case AAUDIO_STREAM_STATE_CLOSING:
        case AAUDIO_STREAM_STATE_CLOSED:
        case AAUDIO_STREAM_STATE_DISCONNECTED:
            return true;
        default:
            return false;
    }
}

void stopStream(AAudioStream* stream, const char* which) {
    aaudio_stream_state_t state = AAudioStream_getState(stream);
    if (!stopIsFinal(state)) {
        const aaudio_result_t requested = AAudioStream_requestStop(stream);
        if (requested != AAUDIO_OK) {
            logError(which, requested);
        }
        state = AAudioStream_getState(stream);
    }
    while (!stopIsFinal(state)) {
        aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNKNOWN;
        const aaudio_result_t result = AAudioStream_waitForStateChange(
            stream, state, &next, kStopTimeoutNanos);
        if (result != AAUDIO_OK || next == state) {
            break;
        }
        state = next;
    }
    // XRuns and stream errors are logged on the control thread, never from
    // a callback, and gate nothing.
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "%s stopped: state=%d xruns=%d", which,
                        static_cast<int>(state),
                        AAudioStream_getXRunCount(stream));
}

void closeStream(AAudioStream* stream, const char* which) {
    if (stream == nullptr) {
        return;
    }
    const aaudio_result_t result = AAudioStream_close(stream);
    if (result != AAUDIO_OK) {
        logError(which, result);
    }
}

void closeBoth(mumla::voice_io::VoiceStreamContext* ctx) {
    closeStream(static_cast<AAudioStream*>(ctx->inputStream), "close input");
    closeStream(static_cast<AAudioStream*>(ctx->outputStream), "close output");
    ctx->inputStream = nullptr;
    ctx->outputStream = nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jlongArray JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeOpenStreams(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint inputPreset, jint usage) {
    auto* ctx = mumla::voice_io::streamContextFromHandle(handle);
    if (ctx == nullptr || env == nullptr) {
        return nullptr;
    }
    // Kotlin closes before reopening; clean up defensively so a failure in
    // the previous teardown cannot leak a stream.
    if (ctx->inputStream != nullptr || ctx->outputStream != nullptr) {
        closeBoth(ctx);
    }
    const uint64_t generation =
        ctx->streamGeneration.fetch_add(1, std::memory_order_acq_rel) + 1;
    mumla::voice_io::resetRenderCarry(ctx);
    ctx->inputCallback.context = ctx;
    ctx->inputCallback.generation = generation;
    ctx->outputCallback.context = ctx;
    ctx->outputCallback.generation = generation;

    AAudioStream* input = nullptr;
    AAudioStream* output = nullptr;
    if (!openStream(&input, AAUDIO_DIRECTION_INPUT, inputPreset,
                    inputDataCallback, &ctx->inputCallback) ||
        !openStream(&output, AAUDIO_DIRECTION_OUTPUT, usage, outputDataCallback,
                    &ctx->outputCallback)) {
        closeStream(input, "close input after failed open");
        closeStream(output, "close output after failed open");
        ctx->inputStream = nullptr;
        ctx->outputStream = nullptr;
        ctx->streamGeneration.store(generation - 1, std::memory_order_release);
        return nullptr;
    }
    ctx->inputStream = input;
    ctx->outputStream = output;
    const aaudio_session_id_t sessionId = AAudioStream_getSessionId(input);
    jlongArray result = env->NewLongArray(2);
    if (result == nullptr) {
        return nullptr;
    }
    const jlong values[2] = {static_cast<jlong>(sessionId),
                             static_cast<jlong>(generation)};
    env->SetLongArrayRegion(result, 0, 2, values);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeStartStreams(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = mumla::voice_io::streamContextFromHandle(handle);
    if (ctx == nullptr || ctx->inputStream == nullptr ||
        ctx->outputStream == nullptr) {
        return JNI_FALSE;
    }
    const aaudio_result_t input = AAudioStream_requestStart(
        static_cast<AAudioStream*>(ctx->inputStream));
    const aaudio_result_t output = AAudioStream_requestStart(
        static_cast<AAudioStream*>(ctx->outputStream));
    if (input != AAUDIO_OK) {
        logError("start input", input);
    }
    if (output != AAUDIO_OK) {
        logError("start output", output);
    }
    return input == AAUDIO_OK && output == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeStopStreams(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = mumla::voice_io::streamContextFromHandle(handle);
    if (ctx == nullptr) {
        return;
    }
    if (ctx->inputStream != nullptr) {
        stopStream(static_cast<AAudioStream*>(ctx->inputStream), "input");
    }
    if (ctx->outputStream != nullptr) {
        stopStream(static_cast<AAudioStream*>(ctx->outputStream), "output");
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_voice_NativeVoiceEngine_nativeCloseStreams(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = mumla::voice_io::streamContextFromHandle(handle);
    if (ctx != nullptr) {
        closeBoth(ctx);
    }
}

} // extern "C"
