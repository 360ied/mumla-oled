/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef MUMLA_AUDIO_INPUT_ENGINE_H_
#define MUMLA_AUDIO_INPUT_ENGINE_H_

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

struct EncodedPacket {
    std::vector<uint8_t> data;
    int frameCount;
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
                     InputMode mode = InputMode::VOICE_ACTIVITY);
    ~AudioInputEngine() = default;

    /**
     * Ingests a 10ms PCM audio frame from the capture device.
     */
    void processFrame(const int16_t* pcm, size_t sampleCount);

    void setPacketCallback(AudioPacketCallback callback);
    void setTalkingCallback(TalkingStateCallback callback);

    void setInputMode(InputMode mode);
    InputMode getInputMode() const { return m_inputMode; }

    void setPttTalking(bool talking);
    bool isPttTalking() const { return m_pttTalking; }

    void setMuted(bool muted);
    bool isMuted() const { return m_muted; }

    void setBitrate(int bitrate);
    int getBitrate() const;

    void setFramesPerPacket(int framesPerPacket);
    int getFramesPerPacket() const { return m_framesPerPacket; }

    void setAmplitudeBoost(float boost);
    float getAmplitudeBoost() const { return m_amplitudeBoost; }

    void setRnnoiseEnabled(bool enabled);
    bool isRnnoiseEnabled() const;

    void setVadThresholds(float vadMax, float vadMin);
    void setVadHoldFrames(uint32_t holdFrames);

    void reset();

private:
    void encodeAndDispatch(const int16_t* pcm, size_t sampleCount, bool isTerminator);
    void flushAccumulator(bool isTerminator);

    mutable std::mutex m_mutex;

    int m_framesPerPacket;
    float m_amplitudeBoost;
    InputMode m_inputMode;
    bool m_pttTalking;
    bool m_muted;
    bool m_talking;
    uint64_t m_frameCounter;

    // Submodules
    PreSpeechRingBuffer m_ringBuffer;
    RnnoiseProcessor m_rnnoise;
    HysteresisVad m_vad;
    OpusVoiceEncoder m_opus;

    // Buffers
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
