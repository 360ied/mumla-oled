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

#ifndef MUMLA_SOFT_LIMITER_H_
#define MUMLA_SOFT_LIMITER_H_

#include <cstddef>
#include <cstdint>

namespace mumla {
namespace audio {

/**
 * Soft-Knee Saturation Limiter.
 *
 * Replaces hard digital square-wave clipping with a smooth C1-continuous
 * hyperbolic tangent saturation curve when boosting audio amplitude beyond 100%.
 */
class SoftLimiter {
public:
    static constexpr float MAX_AMPLITUDE = 32767.0f;
    static constexpr float KNEE_THRESHOLD = (2.0f / 3.0f) * MAX_AMPLITUDE; // 21844.67f
    static constexpr float HEADROOM = MAX_AMPLITUDE - KNEE_THRESHOLD;      // 10922.33f

    /**
     * Applies amplitude boost multiplier and smooth saturation to a single sample.
     */
    static int16_t processSample(int16_t sample, float boostMultiplier);

    /**
     * In-place batch processing of an array of 16-bit PCM samples.
     */
    static void processBuffer(int16_t* pcm, size_t sampleCount, float boostMultiplier);
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_SOFT_LIMITER_H_
