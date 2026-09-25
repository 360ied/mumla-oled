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

#include <algorithm>
#include <cstring>

namespace mumla {
namespace audio {

AudioInputEngine::AudioInputEngine(std::unique_ptr<IVoiceEncoder> encoder,
                                   std::unique_ptr<IDenoiser> denoiser,
                                   int framesPerPacket,
                                   float amplitudeBoost,
                                   bool adaptiveLevelerEnabled,
                                   InputMode mode)
    : m_encoder(std::move(encoder)),
      m_denoiser(std::move(denoiser)),
      m_bitrate(m_encoder ? m_encoder->getBitrate() : 40000),
      m_framesPerPacket((framesPerPacket == 1 || framesPerPacket == 2 || framesPerPacket == 4 || framesPerPacket == 6) ? framesPerPacket : DEFAULT_FRAMES_PER_PACKET),
      m_amplitudeBoost(amplitudeBoost),
      m_inputMode(mode),
      m_pttTalking(false),
      m_pttHoldFramesRemaining(0),
      m_muted(false),
      m_talking(false),
      m_frameCounter(0),
      m_ringBuffer(8, SAMPLES_PER_10MS),
      m_leveler(adaptiveLevelerEnabled),
      m_vad(),
      m_processedFrame(SAMPLES_PER_10MS, 0),
      m_accumulatedPcm(12 * SAMPLES_PER_10MS, 0),
      m_accumulatedFrames(0),
      m_silenceDiscardBuffer(SAMPLES_PER_10MS, 0),
      m_opusBuffer(MAX_OPUS_BUFFER_BYTES, 0) {
    m_packetsToDispatch.reserve(16);
}

void AudioInputEngine::processFrame(const int16_t* pcm, size_t sampleCount) {
    if (pcm == nullptr || sampleCount == 0) {
        return;
    }

    bool notifyTalking = false;
    bool talkingState = false;
    float peakEnergy = 0.0f;

    AudioPacketCallback packetCb;
    TalkingStateCallback talkingCb;
    std::vector<DispatchedPacket> packetsToSend;

    std::lock_guard<std::mutex> cbLock(m_callbackMutex);
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_packetsToDispatch.clear();

        // 1. Copy to local frame buffer
        size_t count = std::min(sampleCount, SAMPLES_PER_10MS);
        std::memcpy(m_processedFrame.data(), pcm, count * sizeof(int16_t));
        if (count < SAMPLES_PER_10MS) {
            std::memset(m_processedFrame.data() + count, 0, (SAMPLES_PER_10MS - count) * sizeof(int16_t));
        }

        // 2. Infrasonic High-Pass Filtering (<90Hz)
        m_hpf.process(m_processedFrame.data(), SAMPLES_PER_10MS);

        // 3. Squelch-Gated & PTT-Gated Neural Denoising (RNNoise)
        float peakDb = HysteresisVad::calculateRmsDb(m_processedFrame.data(), SAMPLES_PER_10MS);
        float speechProb = -1.0f;

        bool isActivelyTransmitting = false;
        if (!m_muted) {
            if (m_inputMode == InputMode::CONTINUOUS) {
                isActivelyTransmitting = true;
            } else if (m_inputMode == InputMode::PUSH_TO_TALK) {
                isActivelyTransmitting = m_pttTalking || (m_pttHoldFramesRemaining > 0);
            } else { // InputMode::VOICE_ACTIVITY
                isActivelyTransmitting = m_vad.isSpeaking();
            }
        }

        if (m_denoiser) {
            // Bypass RNNoise during squelched silence, client mute, or when PTT is idle (unpressed):
            // In PTT mode when not transmitting or when client is muted, bypass unconditionally regardless of peakDb.
            // In VAD mode, bypass when below the squelch floor.
            bool shouldBypassRnnoise = m_muted || (!isActivelyTransmitting &&
                (m_inputMode == InputMode::PUSH_TO_TALK || peakDb < m_vad.getSquelchMinDb()));

            if (shouldBypassRnnoise) {
                // Feed static zeroes to RNNoise to advance overlap-add delay (delayed_X) and pitch buffers
                // while cleanly triggering its native silence bypass (!silence in denoise.c). This completely
                // avoids running recurrent GRU matrix multiplications without freezing internal filter state.
                static const int16_t kSilencePcm[SAMPLES_PER_10MS] = {0};
                m_denoiser->process(kSilencePcm, m_silenceDiscardBuffer.data(), SAMPLES_PER_10MS);
                speechProb = 0.0f;
            } else {
                speechProb = m_denoiser->process(m_processedFrame.data(), m_processedFrame.data(), SAMPLES_PER_10MS);
            }
        }

        // 4. Determine transmission state based on InputMode (Pre-Gain VAD evaluation)
        bool shouldTransmit = false;
        switch (m_inputMode) {
            case InputMode::CONTINUOUS:
                shouldTransmit = true;
                m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb, peakDb);
                break;
            case InputMode::PUSH_TO_TALK:
                if (m_pttTalking) {
                    m_pttHoldFramesRemaining = PTT_HOLD_FRAMES;
                    shouldTransmit = true;
                } else if (m_pttHoldFramesRemaining > 0) {
                    m_pttHoldFramesRemaining--;
                    shouldTransmit = true;
                } else {
                    shouldTransmit = false;
                }
                m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb, peakDb);
                break;
            case InputMode::VOICE_ACTIVITY:
            default:
                shouldTransmit = m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb, peakDb);
                break;
        }

        if (m_muted) {
            shouldTransmit = false;
            m_pttHoldFramesRemaining = 0;
        }

        // 5. Handle talking state transitions
        if (shouldTransmit != m_talking) {
            notifyTalking = true;
            talkingState = shouldTransmit;
            peakEnergy = m_vad.getPeakEnergy();

            if (!m_talking && shouldTransmit) {
                // Speech onset: In VAD mode, flush the 80ms lookahead ring buffer into encoder.
                // Lookahead frames were already denoised in sequential time order during Step 3
                // when buffered; do NOT run them through m_denoiser again to preserve recurrent
                // GRU state causality and prevent double-denoising spectral degradation.
                // PTT mode starts transmission immediately without prepending pre-PTT idle audio.
                if (m_inputMode == InputMode::VOICE_ACTIVITY) {
                    m_ringBuffer.flush([this](const int16_t* bufferedPcm, size_t len) {
                        int16_t tempPcm[SAMPLES_PER_10MS];
                        size_t count = std::min(len, static_cast<size_t>(SAMPLES_PER_10MS));
                        std::memcpy(tempPcm, bufferedPcm, count * sizeof(int16_t));
                        if (count < SAMPLES_PER_10MS) {
                            std::memset(tempPcm + count, 0, (SAMPLES_PER_10MS - count) * sizeof(int16_t));
                        }
                        if (m_leveler.isEnabled()) {
                            m_leveler.process(tempPcm, SAMPLES_PER_10MS, -1.0f, m_amplitudeBoost);
                        } else if (m_amplitudeBoost != 1.0f) {
                            SoftLimiter::processBuffer(tempPcm, SAMPLES_PER_10MS, m_amplitudeBoost);
                        }
                        std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                                    tempPcm, SAMPLES_PER_10MS * sizeof(int16_t));
                        m_accumulatedFrames++;
                        m_frameCounter++;
                        while (m_accumulatedFrames >= static_cast<size_t>(m_framesPerPacket)) {
                            flushAccumulatorLocked(false);
                        }
                    });
                }
            } else if (m_talking && !shouldTransmit) {
                // Speech terminated: Always dispatch a terminator packet
                flushAccumulatorLocked(true);
                m_ringBuffer.clear();
            }
        }

        // 6. Speech-Gated Adaptive RMS Voice Leveling & Amplitude Boost (Unified Single-Pass Saturation)
        if (shouldTransmit) {
            if (m_leveler.isEnabled()) {
                m_leveler.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb, m_amplitudeBoost);
            } else if (m_amplitudeBoost != 1.0f) {
                SoftLimiter::processBuffer(m_processedFrame.data(), SAMPLES_PER_10MS, m_amplitudeBoost);
            }
        }

        // 7. Process current frame
        if (shouldTransmit) {
            std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                        m_processedFrame.data(), SAMPLES_PER_10MS * sizeof(int16_t));
            m_accumulatedFrames++;
            m_frameCounter++;

            while (m_accumulatedFrames >= static_cast<size_t>(m_framesPerPacket)) {
                flushAccumulatorLocked(false);
            }
        } else if (!m_muted && m_inputMode == InputMode::VOICE_ACTIVITY) {
            // Silence in VAD mode: store into lookahead ring buffer to prevent syllable clipping
            m_ringBuffer.push(m_processedFrame.data(), SAMPLES_PER_10MS);
        }

        m_talking = shouldTransmit;
        packetCb = m_packetCallback;
        talkingCb = m_talkingCallback;
        packetsToSend = std::move(m_packetsToDispatch);
        m_packetsToDispatch.clear();
    } // Critical section exited, mutex released!

    // 8. Dispatch callbacks outside the lock to prevent deadlock
    if (notifyTalking && talkingCb) {
        talkingCb(talkingState, peakEnergy);
    }

    if (packetCb && !packetsToSend.empty()) {
        for (const auto& pkt : packetsToSend) {
            packetCb(pkt.data, pkt.size, pkt.frames, pkt.isTerminator, pkt.frameNumber);
        }
    }
}

void AudioInputEngine::flushAccumulatorLocked(bool isTerminator) {
    if (m_accumulatedFrames == 0 && !isTerminator) {
        return;
    }

    size_t targetFrames = static_cast<size_t>(m_framesPerPacket);

    // If terminator and empty accumulator, encode full packet of silence
    if (m_accumulatedFrames == 0 && isTerminator) {
        std::memset(m_accumulatedPcm.data(), 0, targetFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_accumulatedFrames = targetFrames;
        m_frameCounter += targetFrames;
    } else if (m_accumulatedFrames < targetFrames) {
        // Zero-pad if underfilled
        size_t missingFrames = targetFrames - m_accumulatedFrames;
        std::memset(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS], 0,
                    missingFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_frameCounter += missingFrames;
        m_accumulatedFrames = targetFrames;
    }

    if (!m_encoder) {
        m_accumulatedFrames = 0;
        return;
    }

    size_t totalSamples = targetFrames * SAMPLES_PER_10MS;
    int encodedBytes = m_encoder->encode(m_accumulatedPcm.data(), totalSamples,
                                         m_opusBuffer.data(), m_opusBuffer.size());

    if (encodedBytes > 0) {
        uint64_t startFrameNumber = m_frameCounter - m_accumulatedFrames;
        m_packetsToDispatch.emplace_back();
        auto& pkt = m_packetsToDispatch.back();
        size_t copyLen = std::min(static_cast<size_t>(encodedBytes), sizeof(pkt.data));
        std::memcpy(pkt.data, m_opusBuffer.data(), copyLen);
        pkt.size = copyLen;
        pkt.frames = static_cast<int>(targetFrames);
        pkt.isTerminator = isTerminator;
        pkt.frameNumber = startFrameNumber;
    }

    if (m_accumulatedFrames > targetFrames) {
        size_t remainingFrames = m_accumulatedFrames - targetFrames;
        std::memmove(m_accumulatedPcm.data(),
                     &m_accumulatedPcm[targetFrames * SAMPLES_PER_10MS],
                     remainingFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_accumulatedFrames = remainingFrames;
    } else {
        m_accumulatedFrames = 0;
    }
}

void AudioInputEngine::setPacketCallback(AudioPacketCallback callback) {
    std::lock_guard<std::mutex> cbLock(m_callbackMutex);
    std::lock_guard<std::mutex> lock(m_mutex);
    m_packetCallback = std::move(callback);
}

void AudioInputEngine::setTalkingCallback(TalkingStateCallback callback) {
    std::lock_guard<std::mutex> cbLock(m_callbackMutex);
    std::lock_guard<std::mutex> lock(m_mutex);
    m_talkingCallback = std::move(callback);
}

void AudioInputEngine::setInputMode(InputMode mode) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_inputMode != mode) {
        m_ringBuffer.clear();
        if (mode != InputMode::PUSH_TO_TALK) {
            m_pttHoldFramesRemaining = 0;
        }
    }
    m_inputMode = mode;
}

InputMode AudioInputEngine::getInputMode() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_inputMode;
}

void AudioInputEngine::setPttTalking(bool talking) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_pttTalking = talking;
    if (talking) {
        m_pttHoldFramesRemaining = PTT_HOLD_FRAMES;
    }
}

bool AudioInputEngine::isPttTalking() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_pttTalking;
}

void AudioInputEngine::setMuted(bool muted) {
    bool notifyTalking = false;
    AudioPacketCallback packetCb = nullptr;
    TalkingStateCallback talkingCb = nullptr;
    std::vector<DispatchedPacket> packetsToSend;

    std::lock_guard<std::mutex> cbLock(m_callbackMutex);
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_muted == muted) {
            return;
        }
        m_muted = muted;
        if (muted) {
            m_ringBuffer.clear();
            m_pttHoldFramesRemaining = 0;
            if (m_talking) {
                m_packetsToDispatch.clear();
                flushAccumulatorLocked(true);
                m_talking = false;
                notifyTalking = true;
                packetsToSend = m_packetsToDispatch;
                m_packetsToDispatch.clear();
            } else {
                m_accumulatedFrames = 0;
            }
            packetCb = m_packetCallback;
            talkingCb = m_talkingCallback;
        } else {
            m_ringBuffer.clear();
            m_accumulatedFrames = 0;
        }
    }

    if (notifyTalking && talkingCb) {
        talkingCb(false, 0.0f);
    }

    if (packetCb && !packetsToSend.empty()) {
        for (const auto& pkt : packetsToSend) {
            packetCb(pkt.data, pkt.size, pkt.frames, pkt.isTerminator, pkt.frameNumber);
        }
    }
}

bool AudioInputEngine::isMuted() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_muted;
}

void AudioInputEngine::setBitrate(int bitrate) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_bitrate = bitrate;
    if (m_encoder) {
        m_encoder->setBitrate(bitrate);
    }
}

int AudioInputEngine::getBitrate() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_encoder ? m_encoder->getBitrate() : m_bitrate;
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
    if (m_denoiser) {
        m_denoiser->setEnabled(enabled);
    }
}

bool AudioInputEngine::isRnnoiseEnabled() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_denoiser ? m_denoiser->isEnabled() : false;
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
    if (m_denoiser) {
        m_denoiser->setModel(modelData, modelSize);
    }
}

bool AudioInputEngine::hasRnnoiseModel() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_denoiser ? m_denoiser->hasModel() : false;
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
    if (m_denoiser) {
        m_denoiser->reset();
    }
    m_leveler.reset();
    m_vad.reset();
    if (m_encoder) {
        m_encoder->reset();
    }
    m_accumulatedFrames = 0;
    m_talking = false;
    m_pttTalking = false;
    m_frameCounter = 0;
    m_pttHoldFramesRemaining = 0;
}

} // namespace audio
} // namespace mumla
