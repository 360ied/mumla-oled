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

#include "SoftLimiter.h"

#include <algorithm>
#include <cmath>

namespace mumla {
namespace audio {

int16_t SoftLimiter::processSample(int16_t sample, float boostMultiplier) {
    if (boostMultiplier == 1.0f) {
        return sample;
    }

    float val = static_cast<float>(sample) * boostMultiplier;
    float absVal = std::fabs(val);

    if (absVal <= KNEE_THRESHOLD) {
        return static_cast<int16_t>(val);
    }

    float sign = (val >= 0.0f) ? 1.0f : -1.0f;
    float over = absVal - KNEE_THRESHOLD;
    float saturated = KNEE_THRESHOLD + HEADROOM * std::tanh(over / HEADROOM);

    return static_cast<int16_t>(sign * std::min(saturated, MAX_AMPLITUDE));
}

void SoftLimiter::processBuffer(int16_t* pcm, size_t sampleCount, float boostMultiplier) {
    if (pcm == nullptr || sampleCount == 0 || boostMultiplier == 1.0f) {
        return;
    }
    for (size_t i = 0; i < sampleCount; ++i) {
        pcm[i] = processSample(pcm[i], boostMultiplier);
    }
}

} // namespace audio
} // namespace mumla
