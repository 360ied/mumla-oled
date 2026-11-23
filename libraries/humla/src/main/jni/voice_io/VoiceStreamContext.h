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

#ifndef MUMLA_VOICE_STREAM_CONTEXT_H_
#define MUMLA_VOICE_STREAM_CONTEXT_H_

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "AudioOutputEngine.h"

namespace mumla {
namespace voice_io {

class VoiceIo;
class VoiceSend;
struct VoiceStreamContext;

// One 10 ms frame at 48 kHz. The output engine's startup gate counts
// samples / FRAME_SIZE, so a render call must cover whole frames.
constexpr size_t kStreamFrameSamples =
    static_cast<size_t>(mumla::audio::AudioOutputEngine::FRAME_SIZE);
// 5a's 20 ms render quantum: two whole frames per engine call. In-band FEC
// recovery needs the lost frame and its successor decoded in the same call,
// because an unrecovered FEC debt slot is concealed at the end of the call.
// One-frame calls would conceal every loss.
constexpr size_t kStreamChunkSamples = 2 * kStreamFrameSamples;

/**
 * Debug lifecycle pins (5a.3a render, 5a.4 capture, 5b callbacks).
 *
 * A data callback holds the matching flag for its whole call; destroy, stop,
 * and reset assert the flags are clear. The guards live here so the host
 * callback bodies and the JNI lifecycle see the same protocol. Checked by
 * scripts/check_architecture.py.
 */
class RenderGuard {
public:
    explicit RenderGuard(std::atomic<bool>* flag) : m_flag(flag) {
        m_flag->store(true, std::memory_order_release);
    }
    ~RenderGuard() { m_flag->store(false, std::memory_order_release); }
    RenderGuard(const RenderGuard&) = delete;
    RenderGuard& operator=(const RenderGuard&) = delete;

private:
    std::atomic<bool>* m_flag;
};

class CaptureGuard {
public:
    explicit CaptureGuard(std::atomic<bool>* flag) : m_flag(flag) {
        m_flag->store(true, std::memory_order_release);
    }
    ~CaptureGuard() { m_flag->store(false, std::memory_order_release); }
    CaptureGuard(const CaptureGuard&) = delete;
    CaptureGuard& operator=(const CaptureGuard&) = delete;

private:
    std::atomic<bool>* m_flag;
};

// Passed as AAudio's callback user data: the generation belongs to the pair
// of streams that was open when the callback was registered, so a late error
// from an old stream cannot report the newer generation.
struct VoiceStreamCallbackData {
    VoiceStreamContext* context = nullptr;
    uint64_t generation = 0;
};

/**
 * State shared between the AAudio callbacks, the JNI control plane, and the
 * host test entry points (step 5b item 1).
 *
 * One instance lives in the JNI-private VoiceEngineContext. The status
 * counters and lifecycle flags used to be JNI fields; moving them here lets
 * the callbacks publish status without going through the JVM. The render
 * carry and its offset are owned by the render callback thread alone, except
 * for resetRenderCarry(), which the control thread calls at open while no
 * callback can run.
 */
struct VoiceStreamContext {
    explicit VoiceStreamContext(VoiceIo* ioPtr, VoiceSend* sendPtr)
        : io(ioPtr), send(sendPtr) {}

    VoiceIo* io;
    VoiceSend* send;

    std::atomic<bool> renderActive{false};
    std::atomic<bool> captureActive{false};
    std::atomic<uint64_t> lastDroppedInbound{0};
    std::atomic<uint64_t> lastDroppedTunnel{0};
    std::atomic<uint64_t> lastDroppedJitter{0};
    std::atomic<uint64_t> lastDroppedOutbound{0};

    // Two-frame carry: one 20 ms chunk rendered at a time, copied out in
    // whatever burst sizes AAudio delivers.
    int16_t renderCarry[kStreamChunkSamples] = {};
    size_t renderCarryOffset = 0;
    size_t renderCarryRemaining = 0;
    // Whether the current carry chunk is silent; a burst that only consumes
    // leftover audio still reports what it actually wrote.
    bool renderCarrySilent = true;

    // Bumped at every open (starts at 0, so the first open returns 1) and
    // rolled back if either stream fails to open. Kotlin stores the value
    // and ignores disconnects from older generations.
    std::atomic<uint64_t> streamGeneration{0};
    // Written only by AAudio's error callback, read by the notifier thread.
    // 0 means no disconnect; nativeReset clears it at each session start.
    std::atomic<uint64_t> streamDisconnect{0};

    // Opaque AAudioStream* handles, touched by the control thread only. The
    // host build never sees AAudio, so these stay void* here.
    void* inputStream = nullptr;
    void* outputStream = nullptr;
    VoiceStreamCallbackData inputCallback;
    VoiceStreamCallbackData outputCallback;
};

// Defined in NativeVoiceEngineJni.cpp. int64_t keeps this host-compiled
// header free of <jni.h>; AAudioStreams.cpp passes its jlong handle directly.
VoiceStreamContext* streamContextFromHandle(int64_t handle);

// Callback bodies shared by the AAudio data callbacks and the JNI test entry
// points. Both are allocation-free and never attach to the JVM. The render
// body writes exactly `samples` PCM values and returns true when everything
// it wrote was silence.
bool renderStreamFrames(VoiceStreamContext* ctx, int16_t* out, size_t samples);
void captureStreamFrames(VoiceStreamContext* ctx, const int16_t* pcm,
                         size_t samples);
// Control thread only, at open while no callback can run.
void resetRenderCarry(VoiceStreamContext* ctx);

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_STREAM_CONTEXT_H_
