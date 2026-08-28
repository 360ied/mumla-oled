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

#include "RnnoiseProcessor.h"
#include "../rnnoise/rnnoise.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace mumla {
namespace audio {

RnnoiseProcessor::RnnoiseProcessor(bool enabled)
    : m_state(nullptr),
      m_enabled(enabled),
      m_floatIn(FRAME_SIZE, 0.0f),
      m_floatOut(FRAME_SIZE, 0.0f) {
    if (m_enabled) {
        m_state = rnnoise_create(nullptr);
    }
}

RnnoiseProcessor::~RnnoiseProcessor() {
    if (m_state != nullptr) {
        rnnoise_destroy(m_state);
        m_state = nullptr;
    }
}

void RnnoiseProcessor::setEnabled(bool enabled) {
    if (m_enabled == enabled) {
        return;
    }
    m_enabled = enabled;
    if (m_enabled && m_state == nullptr) {
        m_state = rnnoise_create(nullptr);
    } else if (!m_enabled && m_state != nullptr) {
        rnnoise_destroy(m_state);
        m_state = nullptr;
    }
}

void RnnoiseProcessor::reset() {
    if (m_state != nullptr) {
        rnnoise_destroy(m_state);
        m_state = rnnoise_create(nullptr);
    }
}

float RnnoiseProcessor::process(const int16_t* inPcm, int16_t* outPcm, size_t sampleCount) {
    if (inPcm == nullptr || outPcm == nullptr || sampleCount == 0) {
        return -1.0f;
    }

    if (!m_enabled || m_state == nullptr || sampleCount != FRAME_SIZE) {
        std::memcpy(outPcm, inPcm, sampleCount * sizeof(int16_t));
        return -1.0f;
    }

    // Convert short PCM -> float
    for (size_t i = 0; i < FRAME_SIZE; ++i) {
        m_floatIn[i] = static_cast<float>(inPcm[i]);
    }

    // Run RNNoise GRU
    float speechProb = rnnoise_process_frame(m_state, m_floatOut.data(), m_floatIn.data());

    // Convert float -> short PCM with clipping
    for (size_t i = 0; i < FRAME_SIZE; ++i) {
        float sample = m_floatOut[i];
        sample = std::max(-32768.0f, std::min(sample, 32767.0f));
        outPcm[i] = static_cast<int16_t>(sample);
    }

    return speechProb;
}

} // namespace audio
} // namespace mumla
