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

#include "HysteresisVad.h"

#include <algorithm>
#include <cmath>

namespace mumla {
namespace audio {

template <typename T>
static inline T clampVal(T val, T minVal, T maxVal) {
    return std::max(minVal, std::min(val, maxVal));
}

HysteresisVad::HysteresisVad(float vadMax, float vadMin, uint32_t holdFrames)
    : m_vadMax(vadMax),
      m_vadMin(vadMin),
      m_holdFrames(holdFrames),
      m_currentHold(0),
      m_speaking(false),
      m_peakEnergy(0.0f),
      m_lastSpeechProb(0.0f) {}

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

    // 2. Determine combined activation level
    float score;
    if (neuralSpeechProb >= 0.0f) {
        // If neural probability is available, combine with energy level
        score = (0.7f * neuralSpeechProb) + (0.3f * m_peakEnergy);
    } else {
        score = m_peakEnergy;
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

void HysteresisVad::reset() {
    m_speaking = false;
    m_currentHold = 0;
    m_peakEnergy = 0.0f;
    m_lastSpeechProb = 0.0f;
}

} // namespace audio
} // namespace mumla
