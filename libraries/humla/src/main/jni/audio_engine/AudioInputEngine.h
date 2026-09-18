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
#include <functional>
#include <memory>
#include <mutex>
#include <vector>

namespace mumla {
namespace audio {

enum class InputMode {
    VOICE_ACTIVITY = 0,
    PUSH_TO_TALK = 1,
    CONTINUOUS = 2
};

struct DispatchedPacket {
    uint8_t data[1024];
    size_t size;
    int frames;
    bool isTerminator;
    uint64_t frameNumber;
};

using AudioPacketCallback = std::function<void(const uint8_t* data, size_t size, int frames, bool isTerminator, uint64_t frameNumber)>;
using TalkingStateCallback = std::function<void(bool isTalking, float peakEnergy)>;

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
 * Modern High-Performance Native Audio Input Engine.
 *
 * Coordinates Pre-Speech Lookahead Buffering, Neural RNNoise DSP,
 * Dual-Threshold Hysteresis VAD, Soft-Knee Saturation, and Mandatory Hard CBR Opus Encoding.
 */
class AudioInputEngine {
public:
    static constexpr size_t SAMPLES_PER_10MS = 480; // 10ms @ 48kHz
    static constexpr size_t MAX_OPUS_BUFFER_BYTES = 1024;
    static constexpr uint32_t PTT_HOLD_FRAMES = 15; // 15 frames = 150ms fixed hangover

    explicit AudioInputEngine(std::unique_ptr<IVoiceEncoder> encoder = nullptr,
                              std::unique_ptr<IDenoiser> denoiser = nullptr,
                              int framesPerPacket = 2,
                              float amplitudeBoost = 1.0f,
                              bool adaptiveLevelerEnabled = true,
                              InputMode mode = InputMode::VOICE_ACTIVITY);
    ~AudioInputEngine() = default;

    // Non-copyable
    AudioInputEngine(const AudioInputEngine&) = delete;
    AudioInputEngine& operator=(const AudioInputEngine&) = delete;

    /**
     * Ingests a 10ms PCM audio frame from the capture device.
     */
    void processFrame(const int16_t* pcm, size_t sampleCount);

    void setPacketCallback(AudioPacketCallback callback);
    void setTalkingCallback(TalkingStateCallback callback);

    void setInputMode(InputMode mode);
    InputMode getInputMode() const;

    void setPttTalking(bool talking);
    bool isPttTalking() const;

    void setMuted(bool muted);
    bool isMuted() const;

    void setBitrate(int bitrate);
    int getBitrate() const;

    void setFramesPerPacket(int framesPerPacket);
    int getFramesPerPacket() const;

    void setAmplitudeBoost(float boost);
    float getAmplitudeBoost() const;

    void setRnnoiseEnabled(bool enabled);
    bool isRnnoiseEnabled() const;

    void setAdaptiveLevelerEnabled(bool enabled);
    bool isAdaptiveLevelerEnabled() const;

    void setRnnoiseModel(const uint8_t* modelData, size_t modelSize);
    bool hasRnnoiseModel() const;

    void setVadThresholds(float vadMax, float vadMin);
    void setVadHoldFrames(uint32_t holdFrames);
    void setVadSquelchFloor(float minDb);
    float getVadSquelchFloor() const;

    void reset();

private:
    void flushAccumulatorLocked(bool isTerminator);

    mutable std::mutex m_mutex;

    std::unique_ptr<IVoiceEncoder> m_encoder;
    std::unique_ptr<IDenoiser> m_denoiser;
    int m_bitrate;
    int m_framesPerPacket;
    float m_amplitudeBoost;
    InputMode m_inputMode;
    bool m_pttTalking;
    uint32_t m_pttHoldFramesRemaining;
    bool m_muted;
    bool m_talking;
    uint64_t m_frameCounter;

    // Submodules
    BiquadFilter m_hpf;
    PreSpeechRingBuffer m_ringBuffer;
    AdaptiveLeveler m_leveler;
    HysteresisVad m_vad;

    // Pre-allocated Buffers
    std::vector<int16_t> m_processedFrame;
    std::vector<int16_t> m_accumulatedPcm;
    size_t m_accumulatedFrames;
    std::vector<uint8_t> m_opusBuffer;
    std::vector<DispatchedPacket> m_packetsToDispatch;

    AudioPacketCallback m_packetCallback;
    TalkingStateCallback m_talkingCallback;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_AUDIO_INPUT_ENGINE_H_
