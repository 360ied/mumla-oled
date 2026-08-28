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

#include "AudioInputEngine.h"

#include <algorithm>
#include <cstring>

namespace mumla {
namespace audio {

AudioInputEngine::AudioInputEngine(int bitrate,
                                   int framesPerPacket,
                                   float amplitudeBoost,
                                   bool rnnoiseEnabled,
                                   InputMode mode)
    : m_framesPerPacket((framesPerPacket == 1 || framesPerPacket == 2 || framesPerPacket == 4 || framesPerPacket == 6) ? framesPerPacket : 2),
      m_amplitudeBoost(amplitudeBoost),
      m_inputMode(mode),
      m_pttTalking(false),
      m_muted(false),
      m_talking(false),
      m_frameCounter(0),
      m_ringBuffer(8, SAMPLES_PER_10MS),
      m_rnnoise(rnnoiseEnabled),
      m_vad(0.50f, 0.35f, 25),
      m_opus(bitrate),
      m_processedFrame(SAMPLES_PER_10MS, 0),
      m_accumulatedPcm(6 * SAMPLES_PER_10MS, 0),
      m_accumulatedFrames(0),
      m_opusBuffer(MAX_OPUS_BUFFER_BYTES, 0) {}

void AudioInputEngine::processFrame(const int16_t* pcm, size_t sampleCount) {
    if (pcm == nullptr || sampleCount == 0) {
        return;
    }

    bool notifyTalking = false;
    bool talkingState = false;
    float peakEnergy = 0.0f;
    std::vector<DispatchedPacket> packetsToDispatch;
    packetsToDispatch.reserve(4);

    AudioPacketCallback packetCb;
    TalkingStateCallback talkingCb;

    {
        std::unique_lock<std::mutex> lock(m_mutex);

        // 1. Copy to local frame buffer
        size_t count = std::min(sampleCount, SAMPLES_PER_10MS);
        std::memcpy(m_processedFrame.data(), pcm, count * sizeof(int16_t));
        if (count < SAMPLES_PER_10MS) {
            std::memset(m_processedFrame.data() + count, 0, (SAMPLES_PER_10MS - count) * sizeof(int16_t));
        }

        // 2. Amplitude boost with Soft-Knee Limiter
        if (m_amplitudeBoost != 1.0f) {
            SoftLimiter::processBuffer(m_processedFrame.data(), SAMPLES_PER_10MS, m_amplitudeBoost);
        }

        // 3. Neural Denoising (RNNoise)
        float speechProb = m_rnnoise.process(m_processedFrame.data(), m_processedFrame.data(), SAMPLES_PER_10MS);

        // 4. Determine transmission state based on InputMode
        bool shouldTransmit = false;
        switch (m_inputMode) {
            case InputMode::CONTINUOUS:
                shouldTransmit = true;
                m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
                break;
            case InputMode::PUSH_TO_TALK:
                shouldTransmit = m_pttTalking;
                m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
                break;
            case InputMode::VOICE_ACTIVITY:
            default:
                shouldTransmit = m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
                break;
        }

        if (m_muted) {
            shouldTransmit = false;
        }

        // 5. Handle talking state transitions
        if (shouldTransmit != m_talking) {
            notifyTalking = true;
            talkingState = shouldTransmit;
            peakEnergy = m_vad.getPeakEnergy();

            if (!m_talking && shouldTransmit) {
                // Speech onset: Flush the 80ms lookahead ring buffer through the encoder
                m_ringBuffer.flush([this, &packetsToDispatch](const int16_t* bufferedPcm, size_t len) {
                    std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                                bufferedPcm, len * sizeof(int16_t));
                    m_accumulatedFrames++;
                    m_frameCounter++;
                    if (m_accumulatedFrames >= static_cast<size_t>(m_framesPerPacket)) {
                        flushAccumulatorLocked(false, packetsToDispatch);
                    }
                });
            } else if (m_talking && !shouldTransmit) {
                // Speech terminated: Flush any remaining audio in accumulator with isTerminator = true
                if (m_accumulatedFrames > 0) {
                    flushAccumulatorLocked(true, packetsToDispatch);
                }
                m_ringBuffer.clear();
            }
        }

        // 6. Process current frame
        if (shouldTransmit) {
            std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                        m_processedFrame.data(), SAMPLES_PER_10MS * sizeof(int16_t));
            m_accumulatedFrames++;
            m_frameCounter++;

            if (m_accumulatedFrames >= static_cast<size_t>(m_framesPerPacket)) {
                flushAccumulatorLocked(false, packetsToDispatch);
            }
        } else {
            // Silence: store into lookahead ring buffer
            m_ringBuffer.push(m_processedFrame.data(), SAMPLES_PER_10MS);
        }

        m_talking = shouldTransmit;
        packetCb = m_packetCallback;
        talkingCb = m_talkingCallback;
    } // Critical section exited, mutex released!

    // 7. Dispatch callbacks outside the lock to prevent deadlock
    if (notifyTalking && talkingCb) {
        talkingCb(talkingState, peakEnergy);
    }

    if (packetCb) {
        for (const auto& pkt : packetsToDispatch) {
            packetCb(pkt.data, pkt.size, pkt.frames, pkt.isTerminator, pkt.frameNumber);
        }
    }
}

void AudioInputEngine::flushAccumulatorLocked(bool isTerminator, std::vector<DispatchedPacket>& packetsOut) {
    if (m_accumulatedFrames == 0) {
        return;
    }

    // Zero-pad if underfilled
    if (m_accumulatedFrames < static_cast<size_t>(m_framesPerPacket)) {
        size_t missingFrames = static_cast<size_t>(m_framesPerPacket) - m_accumulatedFrames;
        std::memset(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS], 0,
                    missingFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_frameCounter += missingFrames;
        m_accumulatedFrames = m_framesPerPacket;
    }

    size_t totalSamples = m_accumulatedFrames * SAMPLES_PER_10MS;
    int encodedBytes = m_opus.encode(m_accumulatedPcm.data(), totalSamples,
                                     m_opusBuffer.data(), m_opusBuffer.size());

    if (encodedBytes > 0) {
        uint64_t startFrameNumber = m_frameCounter - m_accumulatedFrames;
        DispatchedPacket pkt;
        size_t copyLen = std::min(static_cast<size_t>(encodedBytes), sizeof(pkt.data));
        std::memcpy(pkt.data, m_opusBuffer.data(), copyLen);
        pkt.size = copyLen;
        pkt.frames = static_cast<int>(m_accumulatedFrames);
        pkt.isTerminator = isTerminator;
        pkt.frameNumber = startFrameNumber;
        packetsOut.push_back(pkt);
    }

    m_accumulatedFrames = 0;
}

void AudioInputEngine::setPacketCallback(AudioPacketCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_packetCallback = std::move(callback);
}

void AudioInputEngine::setTalkingCallback(TalkingStateCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_talkingCallback = std::move(callback);
}

void AudioInputEngine::setInputMode(InputMode mode) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_inputMode = mode;
}

InputMode AudioInputEngine::getInputMode() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_inputMode;
}

void AudioInputEngine::setPttTalking(bool talking) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_pttTalking = talking;
}

bool AudioInputEngine::isPttTalking() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_pttTalking;
}

void AudioInputEngine::setMuted(bool muted) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_muted = muted;
}

bool AudioInputEngine::isMuted() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_muted;
}

void AudioInputEngine::setBitrate(int bitrate) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_opus.setBitrate(bitrate);
}

int AudioInputEngine::getBitrate() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_opus.getBitrate();
}

void AudioInputEngine::setFramesPerPacket(int framesPerPacket) {
    std::lock_guard<std::mutex> lock(m_mutex);
    // Opus supports 10ms, 20ms, 40ms, 60ms (1, 2, 4, 6 frames @ 10ms)
    if (framesPerPacket == 1 || framesPerPacket == 2 || framesPerPacket == 4 || framesPerPacket == 6) {
        m_framesPerPacket = framesPerPacket;
    }
}

int AudioInputEngine::getFramesPerPacket() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_framesPerPacket;
}

void AudioInputEngine::setAmplitudeBoost(float boost) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_amplitudeBoost = boost;
}

float AudioInputEngine::getAmplitudeBoost() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_amplitudeBoost;
}

void AudioInputEngine::setRnnoiseEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_rnnoise.setEnabled(enabled);
}

bool AudioInputEngine::isRnnoiseEnabled() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_rnnoise.isEnabled();
}

void AudioInputEngine::setVadThresholds(float vadMax, float vadMin) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_vad.setThresholds(vadMax, vadMin);
}

void AudioInputEngine::setVadHoldFrames(uint32_t holdFrames) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_vad.setHoldFrames(holdFrames);
}

void AudioInputEngine::reset() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_ringBuffer.clear();
    m_rnnoise.reset();
    m_vad.reset();
    m_opus.reset();
    m_accumulatedFrames = 0;
    m_talking = false;
    m_frameCounter = 0;
}

} // namespace audio
} // namespace mumla
