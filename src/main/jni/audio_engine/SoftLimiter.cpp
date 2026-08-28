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
