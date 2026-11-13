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

#ifndef MUMLA_AUDIO_INPUT_ENGINE_H_
#define MUMLA_AUDIO_INPUT_ENGINE_H_

#include "AdaptiveLeveler.h"
#include "BiquadFilter.h"
#include "HysteresisVad.h"
#include "PreSpeechRingBuffer.h"
#include "SoftLimiter.h"

#include <cstddef>
#include <cstdint>
#include <memory>

namespace mumla {
namespace audio {

enum class InputMode {
    VOICE_ACTIVITY = 0,
    PUSH_TO_TALK = 1,
    CONTINUOUS = 2
};

constexpr size_t kMaxOpusBufferBytes = 1024;

/**
 * Engine configuration for one 10 ms frame, assembled by the capture thread
 * from `VoicePolicy` and `VoiceSettings` (the engine never sees either type).
 * The engine compares it with the previous frame's and applies the
 * transitions itself, so no control-thread setters exist.
 */
struct InputConfig {
    InputMode mode = InputMode::VOICE_ACTIVITY;
    bool transmitAllowed = true; // from VoicePolicy; false = muted/deafened
    bool pttPressed = false;     // from VoicePolicy
    int bitrate = 40000;
    int framesPerPacket = 2;
    float amplitudeBoost = 1.0f;
    bool adaptiveLevelerEnabled = true;
    float vadMax = HysteresisVad::DEFAULT_VAD_MAX;
    float vadMin = HysteresisVad::DEFAULT_VAD_MIN;
    uint32_t vadHoldFrames = HysteresisVad::DEFAULT_HOLD_FRAMES;
    float vadSquelchFloorDb = HysteresisVad::DEFAULT_SQUELCH_MIN_DB;
};

struct EncodedPacket {
    uint8_t data[kMaxOpusBufferBytes];
    size_t size = 0;
    int frames = 0;
    bool isTerminator = false;
    uint64_t frameNumber = 0;
};

// A talk start releases the whole pre-speech ring buffer at one frame per
// packet, plus the current frame.
constexpr size_t kMaxPacketsPerFrame =
    PreSpeechRingBuffer::DEFAULT_CAPACITY_FRAMES + 1;

/**
 * Per-frame outputs. Everything the engine produces for one frame lands
 * here: no callbacks, so the caller decides what runs where.
 */
struct InputFrameResult {
    size_t packetCount = 0;
    EncodedPacket packets[kMaxPacketsPerFrame];
    bool talkingChanged = false;
    bool talking = false;
    // Valid when talkingChanged; zero for an externally forced stop (mute).
    float peakEnergy = 0.0f;
};

/**
 * Abstract voice encoder interface.
 * Implemented by OpusVoiceEncoder in production; tests inject fake encoders.
 */
class IVoiceEncoder {
public:
    virtual ~IVoiceEncoder() = default;
    virtual int encode(const int16_t* pcm, size_t sampleCount, uint8_t* outBuffer, size_t maxBytes) = 0;
    virtual void setBitrate(int bitrate) = 0;
    virtual int getBitrate() const = 0;
    virtual void reset() = 0;
};

/**
 * Abstract neural denoiser processor interface.
 * Implemented by RnnoiseProcessor in production; tests inject fakes or null.
 */
class IDenoiser {
public:
    virtual ~IDenoiser() = default;
    virtual float process(const int16_t* inPcm, int16_t* outPcm, size_t sampleCount) = 0;
    virtual void setEnabled(bool enabled) = 0;
    virtual bool isEnabled() const = 0;
    virtual void setModel(const uint8_t* modelData, size_t modelSize) = 0;
    virtual bool hasModel() const = 0;
    virtual void reset() = 0;
};

/**
 * Native audio input engine: pre-speech lookahead buffering, neural RNNoise
 * DSP, dual-threshold hysteresis VAD, soft-knee saturation and CBR Opus
 * encoding.
 *
 * Single-threaded by contract: one capture thread drives the instance. There
 * are no locks, no atomics and no callbacks, and nothing allocates after
 * construction. Configuration arrives with every frame (see InputConfig).
 */
class AudioInputEngine {
public:
    static constexpr size_t SAMPLES_PER_10MS = 480; // 10ms @ 48kHz
    static constexpr size_t MAX_OPUS_BUFFER_BYTES = kMaxOpusBufferBytes;
    static constexpr uint32_t PTT_HOLD_FRAMES = 15; // 15 frames = 150ms fixed hangover
    static constexpr int DEFAULT_FRAMES_PER_PACKET = 2; // 2 frames @ 10ms = 20ms audio per packet
    // Frames an accumulator may hold before flushing: the largest packet (6
    // frames) plus one frame of slack, as today.
    static constexpr size_t MAX_ACCUMULATED_FRAMES = 12;

    explicit AudioInputEngine(std::unique_ptr<IVoiceEncoder> encoder = nullptr,
                              std::unique_ptr<IDenoiser> denoiser = nullptr);

    // Non-copyable
    AudioInputEngine(const AudioInputEngine&) = delete;
    AudioInputEngine& operator=(const AudioInputEngine&) = delete;

    /**
     * Ingests one 10 ms PCM frame from the capture device.
     *
     * Realtime: no locks, no allocation, no calls out. Applies the
     * configuration transitions first (see the .cpp for the order), then runs
     * the frame. `out` may be the same struct on every call; it is reset on
     * entry.
     */
    void processFrame(const int16_t* pcm, size_t samples,
                      const InputConfig& config, InputFrameResult* out);

    // Control plane (may allocate, so never called from the capture thread):
    // the neural denoiser owns a large recurrent state, and RNNoise has no
    // allocation-free way to reset it, so enabling it again recreates that
    // state. The capture thread only reads the resulting flag.
    void setRnnoiseEnabled(bool enabled);
    bool isRnnoiseEnabled() const;

    // Control thread only, while stopped.
    void setRnnoiseModel(const uint8_t* modelData, size_t modelSize);
    bool hasRnnoiseModel() const;
    void reset();

private:
    void applyConfig(const InputConfig& config, InputFrameResult* out);
    void flushAccumulator(bool isTerminator, InputFrameResult* out);

    std::unique_ptr<IVoiceEncoder> m_encoder;
    std::unique_ptr<IDenoiser> m_denoiser;
    // Everything the last frame applied; the source of truth for transitions.
    InputConfig m_config;
    uint32_t m_pttHoldFramesRemaining;
    bool m_talking;
    uint64_t m_frameCounter;

    // Submodules
    BiquadFilter m_hpf;
    PreSpeechRingBuffer m_ringBuffer;
    AdaptiveLeveler m_leveler;
    HysteresisVad m_vad;

    // Pre-allocated buffers: nothing allocates after construction.
    int16_t m_processedFrame[SAMPLES_PER_10MS];
    int16_t m_accumulatedPcm[MAX_ACCUMULATED_FRAMES * SAMPLES_PER_10MS];
    int16_t m_silenceDiscardBuffer[SAMPLES_PER_10MS];
    uint8_t m_opusBuffer[MAX_OPUS_BUFFER_BYTES];
    size_t m_accumulatedFrames;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_AUDIO_INPUT_ENGINE_H_
