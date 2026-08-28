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

#ifndef MUMLA_HYSTERESIS_VAD_H_
#define MUMLA_HYSTERESIS_VAD_H_

#include <cstddef>
#include <cstdint>

namespace mumla {
namespace audio {

/**
 * Dual-Threshold Hysteresis Voice Activity Detector.
 *
 * Combines acoustic RMS energy and neural RNNoise speech probability
 * with a dual-threshold state machine (vadMax activation, vadMin deactivation)
 * and configurable hold hangover timer.
 */
class HysteresisVad {
public:
    static constexpr float DEFAULT_VAD_MAX = 0.50f;
    static constexpr float DEFAULT_VAD_MIN = 0.35f;
    static constexpr uint32_t DEFAULT_HOLD_FRAMES = 25; // 250ms @ 10ms/frame

    explicit HysteresisVad(float vadMax = DEFAULT_VAD_MAX,
                           float vadMin = DEFAULT_VAD_MIN,
                           uint32_t holdFrames = DEFAULT_HOLD_FRAMES);
    ~HysteresisVad() = default;

    /**
     * Computes voice activity for a 10ms frame.
     *
     * @param pcm 16-bit PCM samples.
     * @param sampleCount Number of samples.
     * @param neuralSpeechProb Speech probability from RNNoise (0.0 to 1.0, or <0 if unused).
     * @return true if transmitting speech.
     */
    bool process(const int16_t* pcm, size_t sampleCount, float neuralSpeechProb);

    void setThresholds(float vadMax, float vadMin);
    void setHoldFrames(uint32_t holdFrames);
    void reset();

    bool isSpeaking() const { return m_speaking; }
    float getPeakEnergy() const { return m_peakEnergy; }
    float getLastSpeechProb() const { return m_lastSpeechProb; }

private:
    float m_vadMax;
    float m_vadMin;
    uint32_t m_holdFrames;
    uint32_t m_currentHold;
    bool m_speaking;
    float m_peakEnergy;
    float m_lastSpeechProb;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_HYSTERESIS_VAD_H_
