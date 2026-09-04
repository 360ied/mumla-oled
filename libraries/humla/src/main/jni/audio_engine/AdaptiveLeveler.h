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

#ifndef MUMLA_ADAPTIVE_LEVELER_H_
#define MUMLA_ADAPTIVE_LEVELER_H_

#include "SoftLimiter.h"

#include <cstddef>
#include <cstdint>

namespace mumla {
namespace audio {

/**
 * Speech-Gated Adaptive RMS Voice Leveler.
 *
 * Automatically balances microphone loudness to a nominal conversational target (-18 dBFS)
 * using an Exponential Moving Average (EMA) of speech energy. Adapts only during active
 * speech (P_speech >= 0.50 or energy fallback) to eliminate background noise pumping during
 * pauses. Employs a slew-rate limiter and sample-interpolated gain application for click-free
 * transitions, coupled with SoftLimiter saturation protection.
 */
class AdaptiveLeveler {
public:
    static constexpr size_t SAMPLES_PER_10MS = 480; // 10ms @ 48kHz

    // Nominal target RMS level for 16-bit PCM: -18 dBFS relative to 32767 full-scale peak.
    static constexpr float DEFAULT_TARGET_RMS = 4125.0f;

    // Operational gain boundaries: -12 dB (0.25x) to +12 dB (4.0x).
    static constexpr float MIN_GAIN = 0.25f;
    static constexpr float MAX_GAIN = 4.0f;

    // Neural speech probability gate threshold (from RNNoise).
    static constexpr float SPEECH_PROB_THRESHOLD = 0.30f;

    // Minimum frame RMS energy threshold to prevent adapting on faint ambient room noise.
    static constexpr float MIN_SPEECH_RMS = 150.0f;

    // Fallback RMS energy threshold for speech gating when RNNoise is disabled (speechProb < 0).
    static constexpr float ENERGY_GATE_FALLBACK = 400.0f;

    // Smoothing coefficient alpha for ~2.5s time constant over 100 frames/sec (10ms frames).
    // alpha = 1.0f / 250.0f = 0.004f
    static constexpr float EMA_ALPHA = 0.004f;

    // Maximum gain adjustment per 10ms frame (~0.05 dB/frame, ~5 dB/s) for click-free transitions.
    static constexpr float MAX_GAIN_SLEW_PER_FRAME = 0.006f;

    explicit AdaptiveLeveler(bool enabled = true, float targetRms = DEFAULT_TARGET_RMS);
    ~AdaptiveLeveler() = default;

    /**
     * Ingests and processes a 10ms frame in-place.
     *
     * @param pcm Array of 16-bit PCM samples.
     * @param sampleCount Number of samples in the frame.
     * @param speechProb Neural speech probability from RNNoise (0.0 - 1.0, or < 0 if unavailable).
     * @param amplitudeBoost Static user amplitude boost multiplier (default 1.0f).
     */
    void process(int16_t* pcm, size_t sampleCount, float speechProb, float amplitudeBoost = 1.0f);

    void setEnabled(bool enabled);
    bool isEnabled() const;

    void setTargetRms(float targetRms);
    float getTargetRms() const;

    float getCurrentGain() const;
    float getSmoothedRms() const;

    void reset();

private:
    bool m_enabled;
    float m_targetRms;
    float m_smoothedRms;
    float m_currentGain;
    float m_targetGain;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_ADAPTIVE_LEVELER_H_
