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

    {
        std::unique_lock<std::mutex> lock(m_mutex);
        m_packetsToDispatch.clear();

        // 1. Copy to local frame buffer
        size_t count = std::min(sampleCount, SAMPLES_PER_10MS);
        std::memcpy(m_processedFrame.data(), pcm, count * sizeof(int16_t));
        if (count < SAMPLES_PER_10MS) {
            std::memset(m_processedFrame.data() + count, 0, (SAMPLES_PER_10MS - count) * sizeof(int16_t));
        }

        // 2. Infrasonic High-Pass Filtering (<90Hz)
        m_hpf.process(m_processedFrame.data(), SAMPLES_PER_10MS);

        // 3. Squelch-Gated Neural Denoising (RNNoise)
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
            if (!isActivelyTransmitting && peakDb < m_vad.getSquelchMinDb()) {
                // Idle silence (< -65 dBFS) while not actively transmitting: feed static zeroes
                // to RNNoise to advance overlap-add delay (delayed_X) and pitch buffers while cleanly
                // triggering its native silence bypass (!silence in denoise.c). This completely avoids
                // running recurrent GRU matrix multiplications without freezing internal filter state.
                // Raw acoustic PCM in m_processedFrame is preserved so pre-speech lookahead buffering
                // and VAD evaluate authentic audio.
                static const int16_t kSilencePcm[SAMPLES_PER_10MS] = {0};
                static int16_t s_dummyOut[SAMPLES_PER_10MS];
                m_denoiser->process(kSilencePcm, s_dummyOut, SAMPLES_PER_10MS);
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
                m_ringBuffer.flush([this](const int16_t* bufferedPcm, size_t len) {
                    std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                                bufferedPcm, len * sizeof(int16_t));
                    m_accumulatedFrames++;
                    m_frameCounter++;
                    while (m_accumulatedFrames >= static_cast<size_t>(m_framesPerPacket)) {
                        flushAccumulatorLocked(false);
                    }
                });
            } else if (m_talking && !shouldTransmit) {
                // Speech terminated: Always dispatch a terminator packet
                flushAccumulatorLocked(true);
                m_ringBuffer.clear();
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
        } else if (!m_muted) {
            // Silence: store into lookahead ring buffer (only when not muted)
            m_ringBuffer.push(m_processedFrame.data(), SAMPLES_PER_10MS);
        }

        m_talking = shouldTransmit;
        packetCb = m_packetCallback;
        talkingCb = m_talkingCallback;
    } // Critical section exited, mutex released!

    // 8. Dispatch callbacks outside the lock to prevent deadlock
    if (notifyTalking && talkingCb) {
        talkingCb(talkingState, peakEnergy);
    }

    if (packetCb) {
        for (const auto& pkt : m_packetsToDispatch) {
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
    std::lock_guard<std::mutex> lock(m_mutex);
    m_packetCallback = std::move(callback);
}

void AudioInputEngine::setTalkingCallback(TalkingStateCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_talkingCallback = std::move(callback);
}

void AudioInputEngine::setInputMode(InputMode mode) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_inputMode != mode && mode != InputMode::PUSH_TO_TALK) {
        m_pttHoldFramesRemaining = 0;
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
    std::lock_guard<std::mutex> lock(m_mutex);
    m_muted = muted;
    if (muted) {
        m_ringBuffer.clear();
        m_accumulatedFrames = 0;
        m_pttHoldFramesRemaining = 0;
        // Do not clear m_talking here. If speech was active, the audio thread
        // in processFrame() will detect that transmission is gated, encode
        // a silence terminator packet, set m_talking = false, and fire
        // the talking state change callback.
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
