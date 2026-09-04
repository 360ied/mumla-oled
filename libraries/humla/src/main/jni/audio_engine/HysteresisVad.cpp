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

#include "HysteresisVad.h"

#include <algorithm>
#include <cmath>

namespace mumla {
namespace audio {

template <typename T>
static inline T clampVal(T val, T minVal, T maxVal) {
    return std::max(minVal, std::min(val, maxVal));
}

HysteresisVad::HysteresisVad(float vadMax, float vadMin, uint32_t holdFrames, float squelchMinDb)
    : m_vadMax(vadMax),
      m_vadMin(vadMin),
      m_holdFrames(holdFrames),
      m_currentHold(0),
      m_speaking(false),
      m_peakEnergy(0.0f),
      m_lastSpeechProb(0.0f),
      m_squelchMinDb(squelchMinDb) {}

bool HysteresisVad::process(const int16_t* pcm, size_t sampleCount, float neuralSpeechProb) {
    if (pcm == nullptr || sampleCount == 0) {
        return false;
    }

    // 1. Calculate RMS energy
    double sum = 1.0;
    for (size_t i = 0; i < sampleCount; ++i) {
        double s = static_cast<double>(pcm[i]);
        sum += s * s;
    }
    double micLevel = std::sqrt(sum / static_cast<double>(sampleCount));
    // Logarithmic scale: 0.0 (-96dB) to 1.0 (0dB)
    float peakDb = static_cast<float>(20.0 * std::log10(micLevel / 32768.0));
    peakDb = std::max(peakDb, -96.0f);
    m_peakEnergy = 1.0f + (peakDb / 96.0f); // 0.0 to 1.0
    m_peakEnergy = clampVal(m_peakEnergy, 0.0f, 1.0f);

    m_lastSpeechProb = neuralSpeechProb;

    // 2. Determine activation score (Pure Neural Probability with Hard Squelch Gate)
    float score = 0.0f;
    if (peakDb >= m_squelchMinDb) {
        // Signal exceeds hard squelch floor: evaluate neural probability directly
        if (neuralSpeechProb >= 0.0f) {
            score = neuralSpeechProb;
        } else {
            // Fallback when neural probability is unavailable/negative
            score = m_peakEnergy;
        }
    } else {
        // Squelched: signal is below the absolute silence/noise floor (-65 dBFS)
        score = 0.0f;
    }

    // 3. Hysteresis decision
    bool detected = false;
    if (m_speaking) {
        if (score >= m_vadMin) {
            detected = true;
            m_currentHold = m_holdFrames;
        } else if (m_currentHold > 0) {
            detected = true;
            m_currentHold--;
        } else {
            detected = false;
        }
    } else {
        if (score >= m_vadMax) {
            detected = true;
            m_currentHold = m_holdFrames;
        } else {
            detected = false;
        }
    }

    m_speaking = detected;
    return m_speaking;
}

void HysteresisVad::setThresholds(float vadMax, float vadMin) {
    m_vadMax = clampVal(vadMax, 0.0f, 1.0f);
    m_vadMin = clampVal(vadMin, 0.0f, m_vadMax);
}

void HysteresisVad::setHoldFrames(uint32_t holdFrames) {
    m_holdFrames = holdFrames;
}

void HysteresisVad::setSquelchMinDb(float squelchMinDb) {
    m_squelchMinDb = squelchMinDb;
}

void HysteresisVad::reset() {
    m_speaking = false;
    m_currentHold = 0;
    m_peakEnergy = 0.0f;
    m_lastSpeechProb = 0.0f;
}

} // namespace audio
} // namespace mumla
