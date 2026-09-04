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

#include <algorithm>
#include <cstring>

namespace mumla {
namespace audio {

AudioInputEngine::AudioInputEngine(int bitrate,
                                   int framesPerPacket,
                                   float amplitudeBoost,
                                   bool rnnoiseEnabled,
                                   bool adaptiveLevelerEnabled,
                                   InputMode mode,
                                   const uint8_t* rnnoiseModelData,
                                   size_t rnnoiseModelSize)
    : m_framesPerPacket((framesPerPacket == 1 || framesPerPacket == 2 || framesPerPacket == 4 || framesPerPacket == 6) ? framesPerPacket : 2),
      m_amplitudeBoost(amplitudeBoost),
      m_inputMode(mode),
      m_pttTalking(false),
      m_muted(false),
      m_talking(false),
      m_frameCounter(0),
      m_ringBuffer(8, SAMPLES_PER_10MS),
      m_rnnoiseModelData(rnnoiseModelData != nullptr && rnnoiseModelSize > 0 ? std::vector<uint8_t>(rnnoiseModelData, rnnoiseModelData + rnnoiseModelSize) : std::vector<uint8_t>()),
      m_rnnoise(rnnoiseEnabled, m_rnnoiseModelData.data(), m_rnnoiseModelData.size()),
      m_leveler(adaptiveLevelerEnabled),
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

        // 2. Infrasonic High-Pass Filtering (<90Hz)
        m_hpf.process(m_processedFrame.data(), SAMPLES_PER_10MS);

        // 3. Neural Denoising (RNNoise)
        float speechProb = m_rnnoise.process(m_processedFrame.data(), m_processedFrame.data(), SAMPLES_PER_10MS);

        // 4. Determine transmission state based on InputMode (Pre-Gain VAD evaluation)
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

        // 5. Speech-Gated Adaptive RMS Voice Leveling & Amplitude Boost (Unified Single-Pass Saturation)
        if (m_leveler.isEnabled()) {
            m_leveler.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb, m_amplitudeBoost);
        } else if (m_amplitudeBoost != 1.0f) {
            SoftLimiter::processBuffer(m_processedFrame.data(), SAMPLES_PER_10MS, m_amplitudeBoost);
        }

        // 6. Handle talking state transitions
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
        } else if (!m_muted) {
            // Silence: store into lookahead ring buffer (only when not muted)
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
    if (muted) {
        m_ringBuffer.clear();
        m_accumulatedFrames = 0;
        m_talking = false;
    }
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

void AudioInputEngine::setAdaptiveLevelerEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_leveler.setEnabled(enabled);
}

bool AudioInputEngine::isAdaptiveLevelerEnabled() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_leveler.isEnabled();
}

void AudioInputEngine::setRnnoiseModel(const uint8_t* modelData, size_t modelSize) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (modelData != nullptr && modelSize > 0) {
        m_rnnoiseModelData.assign(modelData, modelData + modelSize);
    } else {
        m_rnnoiseModelData.clear();
    }
    m_rnnoise.setModel(m_rnnoiseModelData.data(), m_rnnoiseModelData.size());
}

bool AudioInputEngine::hasRnnoiseModel() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_rnnoise.hasModel();
}

void AudioInputEngine::setVadThresholds(float vadMax, float vadMin) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_vad.setThresholds(vadMax, vadMin);
}

void AudioInputEngine::setVadHoldFrames(uint32_t holdFrames) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_vad.setHoldFrames(holdFrames);
}

void AudioInputEngine::setVadSquelchFloor(float minDb) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_vad.setSquelchMinDb(minDb);
}

float AudioInputEngine::getVadSquelchFloor() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_vad.getSquelchMinDb();
}

void AudioInputEngine::reset() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_hpf.reset();
    m_ringBuffer.clear();
    m_rnnoise.reset();
    m_leveler.reset();
    m_vad.reset();
    m_opus.reset();
    m_accumulatedFrames = 0;
    m_talking = false;
    m_frameCounter = 0;
}

} // namespace audio
} // namespace mumla
