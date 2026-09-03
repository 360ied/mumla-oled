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

#ifndef MUMLA_AUDIO_INPUT_ENGINE_H_
#define MUMLA_AUDIO_INPUT_ENGINE_H_

#include "AdaptiveLeveler.h"
#include "BiquadFilter.h"
#include "HysteresisVad.h"
#include "OpusVoiceEncoder.h"
#include "PreSpeechRingBuffer.h"
#include "RnnoiseProcessor.h"
#include "SoftLimiter.h"

#include <cstddef>
#include <cstdint>
#include <functional>
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
 * Modern High-Performance Native Audio Input Engine.
 *
 * Coordinates Pre-Speech Lookahead Buffering, Neural RNNoise DSP,
 * Dual-Threshold Hysteresis VAD, Soft-Knee Saturation, and Mandatory Hard CBR Opus Encoding.
 */
class AudioInputEngine {
public:
    static constexpr size_t SAMPLES_PER_10MS = 480; // 10ms @ 48kHz
    static constexpr size_t MAX_OPUS_BUFFER_BYTES = 1024;

    AudioInputEngine(int bitrate = 40000,
                     int framesPerPacket = 2,
                     float amplitudeBoost = 1.0f,
                     bool rnnoiseEnabled = true,
                     bool adaptiveLevelerEnabled = true,
                     InputMode mode = InputMode::VOICE_ACTIVITY,
                     const uint8_t* rnnoiseModelData = nullptr,
                     size_t rnnoiseModelSize = 0);
    ~AudioInputEngine() = default;

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

    void reset();

private:
    void flushAccumulatorLocked(bool isTerminator, std::vector<DispatchedPacket>& packetsOut);

    mutable std::mutex m_mutex;

    int m_framesPerPacket;
    float m_amplitudeBoost;
    InputMode m_inputMode;
    bool m_pttTalking;
    bool m_muted;
    bool m_talking;
    uint64_t m_frameCounter;

    // Submodules
    BiquadFilter m_hpf;
    PreSpeechRingBuffer m_ringBuffer;
    std::vector<uint8_t> m_rnnoiseModelData;
    RnnoiseProcessor m_rnnoise;
    AdaptiveLeveler m_leveler;
    HysteresisVad m_vad;
    OpusVoiceEncoder m_opus;

    // Pre-allocated Buffers
    std::vector<int16_t> m_processedFrame;
    std::vector<int16_t> m_accumulatedPcm;
    size_t m_accumulatedFrames;
    std::vector<uint8_t> m_opusBuffer;

    AudioPacketCallback m_packetCallback;
    TalkingStateCallback m_talkingCallback;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_AUDIO_INPUT_ENGINE_H_
