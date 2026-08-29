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

#include "AdaptiveLeveler.h"

#include <algorithm>
#include <cmath>

namespace mumla {
namespace audio {

AdaptiveLeveler::AdaptiveLeveler(bool enabled, float targetRms)
    : m_enabled(enabled),
      m_targetRms(targetRms > 0.0f ? targetRms : DEFAULT_TARGET_RMS),
      m_smoothedRms(m_targetRms),
      m_currentGain(1.0f),
      m_targetGain(1.0f) {}

void AdaptiveLeveler::process(int16_t* pcm, size_t sampleCount, float speechProb) {
    if (!m_enabled || pcm == nullptr || sampleCount == 0) {
        return;
    }

    // 1. Calculate frame RMS energy
    double sumSquares = 0.0;
    for (size_t i = 0; i < sampleCount; ++i) {
        double s = static_cast<double>(pcm[i]);
        sumSquares += s * s;
    }
    float frameRms = static_cast<float>(std::sqrt(sumSquares / static_cast<double>(sampleCount)));

    // 2. Speech-Gating: only adapt when speech is active and above the ambient noise floor
    bool isSpeech = (speechProb >= 0.0f) ? (speechProb >= SPEECH_PROB_THRESHOLD) : (frameRms >= ENERGY_GATE_FALLBACK);

    if (isSpeech && frameRms >= MIN_SPEECH_RMS) {
        // Update long-term smoothed speech RMS via Exponential Moving Average (EMA)
        m_smoothedRms = (1.0f - EMA_ALPHA) * m_smoothedRms + EMA_ALPHA * frameRms;
        if (m_smoothedRms > 1.0f) {
            float rawTarget = m_targetRms / m_smoothedRms;
            m_targetGain = std::max(MIN_GAIN, std::min(rawTarget, MAX_GAIN));
        }
    }

    // 3. Slew rate limiter: transition smoothly towards target gain to prevent clicks
    float prevGain = m_currentGain;
    if (m_targetGain > m_currentGain) {
        m_currentGain = std::min(m_currentGain + MAX_GAIN_SLEW_PER_FRAME, m_targetGain);
    } else if (m_targetGain < m_currentGain) {
        m_currentGain = std::max(m_currentGain - MAX_GAIN_SLEW_PER_FRAME, m_targetGain);
    }

    // 4. In-frame linear gain interpolation with SoftLimiter saturation protection
    if (prevGain == 1.0f && m_currentGain == 1.0f) {
        return;
    }

    float gainStep = (sampleCount > 1) ? (m_currentGain - prevGain) / static_cast<float>(sampleCount - 1) : 0.0f;
    float gain = prevGain;

    for (size_t i = 0; i < sampleCount; ++i) {
        pcm[i] = SoftLimiter::processSample(pcm[i], gain);
        gain += gainStep;
    }
}

void AdaptiveLeveler::setEnabled(bool enabled) {
    m_enabled = enabled;
}

bool AdaptiveLeveler::isEnabled() const {
    return m_enabled;
}

void AdaptiveLeveler::setTargetRms(float targetRms) {
    if (targetRms > 0.0f) {
        m_targetRms = targetRms;
        m_smoothedRms = targetRms;
        m_targetGain = 1.0f;
        m_currentGain = 1.0f;
    }
}

float AdaptiveLeveler::getTargetRms() const {
    return m_targetRms;
}

float AdaptiveLeveler::getCurrentGain() const {
    return m_currentGain;
}

float AdaptiveLeveler::getSmoothedRms() const {
    return m_smoothedRms;
}

void AdaptiveLeveler::reset() {
    m_smoothedRms = m_targetRms;
    m_currentGain = 1.0f;
    m_targetGain = 1.0f;
}

} // namespace audio
} // namespace mumla
