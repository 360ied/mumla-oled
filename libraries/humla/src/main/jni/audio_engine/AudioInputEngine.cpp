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
#include <cassert>
#include <cstring>

namespace mumla {
namespace audio {

namespace {

bool isValidFramesPerPacket(int framesPerPacket) {
    // Opus supports 10ms, 20ms, 40ms, 60ms (1, 2, 4, 6 frames @ 10ms).
    return framesPerPacket == 1 || framesPerPacket == 2 ||
           framesPerPacket == 4 || framesPerPacket == 6;
}

const int16_t kSilencePcm[AudioInputEngine::SAMPLES_PER_10MS] = {0};

} // namespace

AudioInputEngine::AudioInputEngine(std::unique_ptr<IVoiceEncoder> encoder,
                                   std::unique_ptr<IDenoiser> denoiser)
    : m_encoder(std::move(encoder)),
      m_denoiser(std::move(denoiser)),
      m_pttHoldFramesRemaining(0),
      m_talking(false),
      m_frameCounter(0),
      m_ringBuffer(PreSpeechRingBuffer::DEFAULT_CAPACITY_FRAMES,
                   SAMPLES_PER_10MS),
      m_leveler(),
      m_vad(),
      m_accumulatedFrames(0) {
    std::memset(m_processedFrame, 0, sizeof(m_processedFrame));
    std::memset(m_accumulatedPcm, 0, sizeof(m_accumulatedPcm));
    std::memset(m_silenceDiscardBuffer, 0, sizeof(m_silenceDiscardBuffer));
    std::memset(m_opusBuffer, 0, sizeof(m_opusBuffer));
    // The initial configuration mirrors what the injected parts were
    // constructed with, so the first frame only applies real changes.
    m_config.bitrate = m_encoder ? m_encoder->getBitrate() : 40000;
    m_config.framesPerPacket = DEFAULT_FRAMES_PER_PACKET;
    m_config.amplitudeBoost = 1.0f;
    m_config.mode = InputMode::VOICE_ACTIVITY;
    m_config.transmitAllowed = true;
    m_config.pttPressed = false;
    m_config.adaptiveLevelerEnabled = m_leveler.isEnabled();
}

// Transition order matches the historical control-thread call order (mode,
// mute, push-to-talk, bitrate, packet size, boost, denoiser, leveler, VAD), so
// the fused per-frame form stays behavior-identical.
void AudioInputEngine::applyConfig(const InputConfig& config,
                                   InputFrameResult* out) {
    if (config.mode != m_config.mode) {
        m_ringBuffer.clear();
        if (config.mode != InputMode::PUSH_TO_TALK) {
            m_pttHoldFramesRemaining = 0;
        }
        m_config.mode = config.mode;
    }

    if (config.transmitAllowed != m_config.transmitAllowed) {
        m_config.transmitAllowed = config.transmitAllowed;
        if (!config.transmitAllowed) {
            m_ringBuffer.clear();
            m_pttHoldFramesRemaining = 0;
            if (m_talking) {
                flushAccumulator(true, out);
                m_talking = false;
                out->talkingChanged = true;
                out->talking = false;
                out->peakEnergy = 0.0f;
            } else {
                m_accumulatedFrames = 0;
            }
        } else {
            m_ringBuffer.clear();
            m_accumulatedFrames = 0;
        }
    }

    if (config.pttPressed != m_config.pttPressed) {
        m_config.pttPressed = config.pttPressed;
        if (config.pttPressed) {
            m_pttHoldFramesRemaining = PTT_HOLD_FRAMES;
        }
    }

    if (config.bitrate != m_config.bitrate) {
        m_config.bitrate = config.bitrate;
        if (m_encoder) {
            m_encoder->setBitrate(config.bitrate);
        }
    }

    if (config.framesPerPacket != m_config.framesPerPacket &&
        isValidFramesPerPacket(config.framesPerPacket)) {
        m_config.framesPerPacket = config.framesPerPacket;
    }

    if (config.amplitudeBoost != m_config.amplitudeBoost) {
        m_config.amplitudeBoost = config.amplitudeBoost;
    }

    if (config.adaptiveLevelerEnabled != m_config.adaptiveLevelerEnabled) {
        m_config.adaptiveLevelerEnabled = config.adaptiveLevelerEnabled;
        m_leveler.setEnabled(config.adaptiveLevelerEnabled);
    }

    if (config.vadMax != m_config.vadMax || config.vadMin != m_config.vadMin) {
        m_config.vadMax = config.vadMax;
        m_config.vadMin = config.vadMin;
        m_vad.setThresholds(config.vadMax, config.vadMin);
    }

    if (config.vadHoldFrames != m_config.vadHoldFrames) {
        m_config.vadHoldFrames = config.vadHoldFrames;
        m_vad.setHoldFrames(config.vadHoldFrames);
    }

    if (config.vadSquelchFloorDb != m_config.vadSquelchFloorDb) {
        m_config.vadSquelchFloorDb = config.vadSquelchFloorDb;
        m_vad.setSquelchMinDb(config.vadSquelchFloorDb);
    }
}

void AudioInputEngine::processFrame(const int16_t* pcm, size_t samples,
                                    const InputConfig& config,
                                    InputFrameResult* out) {
    if (out == nullptr) {
        return;
    }
    out->packetCount = 0;
    out->talkingChanged = false;
    out->talking = m_talking;
    out->peakEnergy = 0.0f;

    applyConfig(config, out);

    if (pcm == nullptr || samples == 0) {
        return;
    }

    const bool transmitAllowed = m_config.transmitAllowed;
    const InputMode mode = m_config.mode;
    const int framesPerPacket = m_config.framesPerPacket;
    const float amplitudeBoost = m_config.amplitudeBoost;

    // 1. Copy to local frame buffer
    const size_t count = std::min(samples, SAMPLES_PER_10MS);
    std::memcpy(m_processedFrame, pcm, count * sizeof(int16_t));
    if (count < SAMPLES_PER_10MS) {
        std::memset(m_processedFrame + count, 0,
                    (SAMPLES_PER_10MS - count) * sizeof(int16_t));
    }

    // 2. Infrasonic High-Pass Filtering (<90Hz)
    m_hpf.process(m_processedFrame, SAMPLES_PER_10MS);

    // 3. Squelch-Gated & PTT-Gated Neural Denoising (RNNoise)
    const float peakDb =
        HysteresisVad::calculateRmsDb(m_processedFrame, SAMPLES_PER_10MS);
    float speechProb = -1.0f;

    bool isActivelyTransmitting = false;
    if (transmitAllowed) {
        if (mode == InputMode::CONTINUOUS) {
            isActivelyTransmitting = true;
        } else if (mode == InputMode::PUSH_TO_TALK) {
            isActivelyTransmitting =
                m_config.pttPressed || (m_pttHoldFramesRemaining > 0);
        } else { // InputMode::VOICE_ACTIVITY
            isActivelyTransmitting = m_vad.isSpeaking();
        }
    }

    if (m_denoiser) {
        // Bypass RNNoise during squelched silence, client mute, or when PTT is
        // idle (unpressed): in PTT mode when not transmitting or when the
        // client is muted, bypass unconditionally regardless of peakDb. In VAD
        // mode, bypass when below the squelch floor.
        const bool shouldBypassRnnoise =
            !transmitAllowed ||
            (!isActivelyTransmitting &&
             (mode == InputMode::PUSH_TO_TALK ||
              peakDb < m_vad.getSquelchMinDb()));

        if (shouldBypassRnnoise) {
            // Feed static zeroes to RNNoise to advance overlap-add delay
            // (delayed_X) and pitch buffers while cleanly triggering its
            // native silence bypass (!silence in denoise.c). This completely
            // avoids running recurrent GRU matrix multiplications without
            // freezing internal filter state.
            m_denoiser->process(kSilencePcm, m_silenceDiscardBuffer,
                                SAMPLES_PER_10MS);
            speechProb = 0.0f;
        } else {
            speechProb = m_denoiser->process(m_processedFrame, m_processedFrame,
                                             SAMPLES_PER_10MS);
        }
    }

    // 4. Determine transmission state based on InputMode (pre-gain VAD)
    bool shouldTransmit = false;
    switch (mode) {
        case InputMode::CONTINUOUS:
            shouldTransmit = true;
            m_vad.process(m_processedFrame, SAMPLES_PER_10MS, speechProb, peakDb);
            break;
        case InputMode::PUSH_TO_TALK:
            if (m_config.pttPressed) {
                m_pttHoldFramesRemaining = PTT_HOLD_FRAMES;
                shouldTransmit = true;
            } else if (m_pttHoldFramesRemaining > 0) {
                m_pttHoldFramesRemaining--;
                shouldTransmit = true;
            } else {
                shouldTransmit = false;
            }
            m_vad.process(m_processedFrame, SAMPLES_PER_10MS, speechProb, peakDb);
            break;
        case InputMode::VOICE_ACTIVITY:
        default:
            shouldTransmit = m_vad.process(m_processedFrame, SAMPLES_PER_10MS,
                                           speechProb, peakDb);
            break;
    }

    if (!transmitAllowed) {
        shouldTransmit = false;
        m_pttHoldFramesRemaining = 0;
    }

    // 5. Handle talking state transitions
    if (shouldTransmit != m_talking) {
        out->talkingChanged = true;
        out->talking = shouldTransmit;
        out->peakEnergy = m_vad.getPeakEnergy();

        if (!m_talking && shouldTransmit) {
            // Speech onset: in VAD mode, flush the 80ms lookahead ring buffer
            // into the encoder. Lookahead frames were already denoised in
            // sequential time order during step 3 when buffered; do NOT run
            // them through the denoiser again, to preserve recurrent GRU state
            // causality and avoid double-denoising spectral degradation. PTT
            // mode starts transmission immediately without prepending pre-PTT
            // idle audio.
            if (mode == InputMode::VOICE_ACTIVITY) {
                m_ringBuffer.flush([this, out, framesPerPacket,
                                    amplitudeBoost](const int16_t* bufferedPcm,
                                                    size_t len) {
                    int16_t tempPcm[SAMPLES_PER_10MS];
                    const size_t buffered =
                        std::min(len, SAMPLES_PER_10MS);
                    std::memcpy(tempPcm, bufferedPcm,
                                buffered * sizeof(int16_t));
                    if (buffered < SAMPLES_PER_10MS) {
                        std::memset(tempPcm + buffered, 0,
                                    (SAMPLES_PER_10MS - buffered) *
                                        sizeof(int16_t));
                    }
                    if (m_leveler.isEnabled()) {
                        m_leveler.process(tempPcm, SAMPLES_PER_10MS, -1.0f,
                                          amplitudeBoost);
                    } else if (amplitudeBoost != 1.0f) {
                        SoftLimiter::processBuffer(tempPcm, SAMPLES_PER_10MS,
                                                   amplitudeBoost);
                    }
                    std::memcpy(&m_accumulatedPcm[m_accumulatedFrames *
                                                  SAMPLES_PER_10MS],
                                tempPcm, SAMPLES_PER_10MS * sizeof(int16_t));
                    m_accumulatedFrames++;
                    m_frameCounter++;
                    while (m_accumulatedFrames >=
                           static_cast<size_t>(framesPerPacket)) {
                        flushAccumulator(false, out);
                    }
                });
            }
        } else if (m_talking && !shouldTransmit) {
            // Speech terminated: always dispatch a terminator packet.
            flushAccumulator(true, out);
            m_ringBuffer.clear();
        }
    }

    // 6. Speech-gated adaptive RMS leveling and amplitude boost (unified
    // single-pass saturation)
    if (shouldTransmit) {
        if (m_leveler.isEnabled()) {
            m_leveler.process(m_processedFrame, SAMPLES_PER_10MS, speechProb,
                              amplitudeBoost);
        } else if (amplitudeBoost != 1.0f) {
            SoftLimiter::processBuffer(m_processedFrame, SAMPLES_PER_10MS,
                                       amplitudeBoost);
        }
    }

    // 7. Process current frame
    if (shouldTransmit) {
        std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                    m_processedFrame, SAMPLES_PER_10MS * sizeof(int16_t));
        m_accumulatedFrames++;
        m_frameCounter++;

        while (m_accumulatedFrames >= static_cast<size_t>(framesPerPacket)) {
            flushAccumulator(false, out);
        }
    } else if (transmitAllowed && mode == InputMode::VOICE_ACTIVITY) {
        // Silence in VAD mode: store into the lookahead ring buffer to prevent
        // syllable clipping.
        m_ringBuffer.push(m_processedFrame, SAMPLES_PER_10MS);
    }

    m_talking = shouldTransmit;
}

void AudioInputEngine::flushAccumulator(bool isTerminator,
                                        InputFrameResult* out) {
    if (m_accumulatedFrames == 0 && !isTerminator) {
        return;
    }

    const size_t targetFrames = static_cast<size_t>(m_config.framesPerPacket);

    // A terminator with an empty accumulator encodes a full packet of silence.
    if (m_accumulatedFrames == 0 && isTerminator) {
        std::memset(m_accumulatedPcm, 0,
                    targetFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_accumulatedFrames = targetFrames;
        m_frameCounter += targetFrames;
    } else if (m_accumulatedFrames < targetFrames) {
        // Zero-pad if underfilled.
        const size_t missingFrames = targetFrames - m_accumulatedFrames;
        std::memset(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                    0, missingFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_frameCounter += missingFrames;
        m_accumulatedFrames = targetFrames;
    }

    if (!m_encoder) {
        m_accumulatedFrames = 0;
        return;
    }

    const size_t totalSamples = targetFrames * SAMPLES_PER_10MS;
    const int encodedBytes =
        m_encoder->encode(m_accumulatedPcm, totalSamples, m_opusBuffer,
                          sizeof(m_opusBuffer));

    if (encodedBytes > 0) {
        const uint64_t startFrameNumber =
            m_frameCounter - m_accumulatedFrames;
        // The bound is guaranteed by kMaxPacketsPerFrame; a debug assert keeps
        // a future ring-buffer or packet-size change from corrupting memory.
        assert(out->packetCount < kMaxPacketsPerFrame);
        if (out->packetCount < kMaxPacketsPerFrame) {
            EncodedPacket& packet = out->packets[out->packetCount++];
            packet.size = std::min(static_cast<size_t>(encodedBytes),
                                   sizeof(packet.data));
            std::memcpy(packet.data, m_opusBuffer, packet.size);
            packet.frames = static_cast<int>(targetFrames);
            packet.isTerminator = isTerminator;
            packet.frameNumber = startFrameNumber;
        }
    }

    if (m_accumulatedFrames > targetFrames) {
        const size_t remainingFrames = m_accumulatedFrames - targetFrames;
        std::memmove(m_accumulatedPcm,
                     &m_accumulatedPcm[targetFrames * SAMPLES_PER_10MS],
                     remainingFrames * SAMPLES_PER_10MS * sizeof(int16_t));
        m_accumulatedFrames = remainingFrames;
    } else {
        m_accumulatedFrames = 0;
    }
}

void AudioInputEngine::setRnnoiseEnabled(bool enabled) {
    if (m_denoiser) {
        m_denoiser->setEnabled(enabled);
    }
}

bool AudioInputEngine::isRnnoiseEnabled() const {
    return m_denoiser ? m_denoiser->isEnabled() : false;
}

void AudioInputEngine::setRnnoiseModel(const uint8_t* modelData,
                                      size_t modelSize) {
    if (m_denoiser) {
        m_denoiser->setModel(modelData, modelSize);
    }
}

bool AudioInputEngine::hasRnnoiseModel() const {
    return m_denoiser ? m_denoiser->hasModel() : false;
}

void AudioInputEngine::reset() {
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
    m_frameCounter = 0;
    m_pttHoldFramesRemaining = 0;
    // Force the next frame to re-arm push-to-talk: the engine no longer owns
    // the pressed state, only its hangover.
    m_config.pttPressed = false;
}

} // namespace audio
} // namespace mumla
