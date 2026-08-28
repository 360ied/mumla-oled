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
