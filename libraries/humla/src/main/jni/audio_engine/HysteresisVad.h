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
    static constexpr float DEFAULT_VAD_MAX = 0.35f;
    static constexpr float DEFAULT_VAD_MIN = 0.25f;
    static constexpr uint32_t DEFAULT_HOLD_FRAMES = 25; // 250ms @ 10ms/frame
    static constexpr float DEFAULT_SQUELCH_MIN_DB = -65.0f; // Squelch noise floor in dBFS

    explicit HysteresisVad(float vadMax = DEFAULT_VAD_MAX,
                           float vadMin = DEFAULT_VAD_MIN,
                           uint32_t holdFrames = DEFAULT_HOLD_FRAMES,
                           float squelchMinDb = DEFAULT_SQUELCH_MIN_DB);
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
    void setSquelchMinDb(float squelchMinDb);
    void reset();

    bool isSpeaking() const { return m_speaking; }
    float getPeakEnergy() const { return m_peakEnergy; }
    float getLastSpeechProb() const { return m_lastSpeechProb; }
    float getVadMax() const { return m_vadMax; }
    float getVadMin() const { return m_vadMin; }
    float getSquelchMinDb() const { return m_squelchMinDb; }

private:
    float m_vadMax;
    float m_vadMin;
    uint32_t m_holdFrames;
    uint32_t m_currentHold;
    bool m_speaking;
    float m_peakEnergy;
    float m_lastSpeechProb;
    float m_squelchMinDb;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_HYSTERESIS_VAD_H_
