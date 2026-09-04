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

#include "BiquadFilter.h"
#include "TestHarness.h"

#include <cmath>
#include <iostream>
#include <vector>

namespace {

void testDcBlockAttenuatesConstantOffset() {
    g_testCount++;
    mumla::audio::BiquadFilter filter;
    std::vector<int16_t> dcSignal(4800, 10000); // 100ms of DC offset
    filter.process(dcSignal.data(), dcSignal.size());

    // After 100ms, the constant DC offset must be decayed close to 0 (< 100)
    int tailSample = std::abs(dcSignal.back());
    TEST_ASSERT(tailSample < 100);
    std::cout << "  [PASS] testDcBlockAttenuatesConstantOffset (decayed to " << tailSample << ")" << std::endl;
}

void testInfrasonicRumbleAttenuatedAt30Hz() {
    g_testCount++;
    mumla::audio::BiquadFilter filter;
    const int sampleRate = 48000;
    const int freq = 30;
    const int count = sampleRate; // 1 second
    std::vector<int16_t> rumble(count);
    for (int i = 0; i < count; ++i) {
        rumble[i] = static_cast<int16_t>(10000.0 * std::sin(2.0 * M_PI * freq * i / sampleRate));
    }

    filter.process(rumble.data(), count);

    // Compute RMS of the steady-state tail (last 500ms)
    double sumIn = 0.0;
    double sumOut = 0.0;
    for (int i = count / 2; i < count; ++i) {
        double orig = 10000.0 * std::sin(2.0 * M_PI * freq * i / sampleRate);
        sumIn += orig * orig;
        sumOut += static_cast<double>(rumble[i]) * static_cast<double>(rumble[i]);
    }
    double rmsIn = std::sqrt(sumIn / (count / 2.0));
    double rmsOut = std::sqrt(sumOut / (count / 2.0));
    double attenuationDb = 20.0 * std::log10(rmsOut / rmsIn);

    // 2nd-order Butterworth at 30 Hz should have ~ -18 dB attenuation (< -15 dB)
    TEST_ASSERT(attenuationDb < -15.0);
    std::cout << "  [PASS] testInfrasonicRumbleAttenuatedAt30Hz (attenuation: " << attenuationDb << " dB)" << std::endl;
}

void testVoiceBandPassedWithUnityGainAt1kHz() {
    g_testCount++;
    mumla::audio::BiquadFilter filter;
    const int sampleRate = 48000;
    const int freq = 1000;
    const int count = sampleRate; // 1 second
    std::vector<int16_t> voice(count);
    for (int i = 0; i < count; ++i) {
        voice[i] = static_cast<int16_t>(10000.0 * std::sin(2.0 * M_PI * freq * i / sampleRate));
    }

    filter.process(voice.data(), count);

    double sumIn = 0.0;
    double sumOut = 0.0;
    for (int i = count / 2; i < count; ++i) {
        double orig = 10000.0 * std::sin(2.0 * M_PI * freq * i / sampleRate);
        sumIn += orig * orig;
        sumOut += static_cast<double>(voice[i]) * static_cast<double>(voice[i]);
    }
    double rmsIn = std::sqrt(sumIn / (count / 2.0));
    double rmsOut = std::sqrt(sumOut / (count / 2.0));
    double gainDb = 20.0 * std::log10(rmsOut / rmsIn);

    // At 1 kHz, unity gain within +/- 0.1 dB
    TEST_ASSERT(std::abs(gainDb) < 0.1);
    std::cout << "  [PASS] testVoiceBandPassedWithUnityGainAt1kHz (gain: " << gainDb << " dB)" << std::endl;
}

void testResetClearsInternalState() {
    g_testCount++;
    mumla::audio::BiquadFilter filter;
    std::vector<int16_t> signal(480, 15000);
    filter.process(signal.data(), signal.size());

    filter.reset();

    std::vector<int16_t> zeroSignal(480, 0);
    filter.process(zeroSignal.data(), zeroSignal.size());

    for (int16_t s : zeroSignal) {
        TEST_ASSERT_EQ(s, 0);
    }
    std::cout << "  [PASS] testResetClearsInternalState" << std::endl;
}

} // namespace

void run_biquad_filter_tests() {
    std::cout << "--- BiquadFilter Tests ---" << std::endl;
    testDcBlockAttenuatesConstantOffset();
    testInfrasonicRumbleAttenuatedAt30Hz();
    testVoiceBandPassedWithUnityGainAt1kHz();
    testResetClearsInternalState();
}
