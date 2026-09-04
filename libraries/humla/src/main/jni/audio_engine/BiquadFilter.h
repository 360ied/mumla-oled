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

#ifndef MUMLA_BIQUAD_FILTER_H_
#define MUMLA_BIQUAD_FILTER_H_

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace mumla {
namespace audio {

/**
 * 2nd-order Direct Form II Transposed Biquad Filter.
 *
 * Configured as a Butterworth High-Pass Filter (Q = 1/sqrt(2) = 0.70710678)
 * to attenuate infrasonic wind buffeting, mechanical vibration, and vehicle
 * chassis rumble (<90 Hz) prior to neural denoising and VAD energy measurement.
 */
class BiquadFilter {
public:
    static constexpr float DEFAULT_SAMPLE_RATE = 48000.0f;
    static constexpr float DEFAULT_CUTOFF_FREQ = 90.0f;
    static constexpr float DEFAULT_Q = 0.70710678f;

    explicit BiquadFilter(float sampleRate = DEFAULT_SAMPLE_RATE,
                          float cutoffFreq = DEFAULT_CUTOFF_FREQ,
                          float q = DEFAULT_Q) {
        configureHighPass(sampleRate, cutoffFreq, q);
    }

    void configureHighPass(float sampleRate, float cutoffFreq, float q = DEFAULT_Q) {
        if (sampleRate <= 0.0f || cutoffFreq <= 0.0f || q <= 0.0f) {
            setPassThrough();
            return;
        }

        // Clamp cutoff frequency safely below Nyquist
        float nyquist = sampleRate * 0.499f;
        float fc = std::min(cutoffFreq, nyquist);

        double w0 = 2.0 * M_PI * (static_cast<double>(fc) / static_cast<double>(sampleRate));
        double cosw0 = std::cos(w0);
        double sinw0 = std::sin(w0);
        double alpha = sinw0 / (2.0 * static_cast<double>(q));

        double b0 = (1.0 + cosw0) / 2.0;
        double b1 = -(1.0 + cosw0);
        double b2 = (1.0 + cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;

        m_b0 = static_cast<float>(b0 / a0);
        m_b1 = static_cast<float>(b1 / a0);
        m_b2 = static_cast<float>(b2 / a0);
        m_a1 = static_cast<float>(a1 / a0);
        m_a2 = static_cast<float>(a2 / a0);

        reset();
    }

    void setPassThrough() {
        m_b0 = 1.0f;
        m_b1 = 0.0f;
        m_b2 = 0.0f;
        m_a1 = 0.0f;
        m_a2 = 0.0f;
        reset();
    }

    inline void process(int16_t* pcm, size_t sampleCount) {
        if (pcm == nullptr || sampleCount == 0) {
            return;
        }

        float z1 = m_z1;
        float z2 = m_z2;
        const float b0 = m_b0;
        const float b1 = m_b1;
        const float b2 = m_b2;
        const float a1 = m_a1;
        const float a2 = m_a2;

        for (size_t i = 0; i < sampleCount; ++i) {
            float x = static_cast<float>(pcm[i]);
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;

            y = std::max(-32768.0f, std::min(y, 32767.0f));
            pcm[i] = static_cast<int16_t>(y);
        }

        m_z1 = z1;
        m_z2 = z2;
    }

    void reset() {
        m_z1 = 0.0f;
        m_z2 = 0.0f;
    }

    float getB0() const { return m_b0; }
    float getB1() const { return m_b1; }
    float getB2() const { return m_b2; }
    float getA1() const { return m_a1; }
    float getA2() const { return m_a2; }

private:
    float m_b0 = 1.0f;
    float m_b1 = 0.0f;
    float m_b2 = 0.0f;
    float m_a1 = 0.0f;
    float m_a2 = 0.0f;
    float m_z1 = 0.0f;
    float m_z2 = 0.0f;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_BIQUAD_FILTER_H_
