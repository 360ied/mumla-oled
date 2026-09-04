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

#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

namespace {

void testDisabledLevelerPassesThroughUnchanged() {
    mumla::audio::AdaptiveLeveler leveler;
    leveler.setEnabled(false);
    assert(!leveler.isEnabled());

    std::vector<int16_t> buffer = {100, 500, 1000, -2000, 3000};
    std::vector<int16_t> original = buffer;

    leveler.process(buffer.data(), buffer.size(), 0.9f);

    assert(buffer == original);
    std::cout << "  [PASS] testDisabledLevelerPassesThroughUnchanged" << std::endl;
}

void testSilenceFreezesGainAndDoesNotPump() {
    mumla::audio::AdaptiveLeveler leveler;
    // Feed silence / ambient noise with speechProb = 0.0f
    std::vector<int16_t> silenceBuffer(mumla::audio::AdaptiveLeveler::SAMPLES_PER_10MS);
    for (size_t i = 0; i < silenceBuffer.size(); i++) {
        silenceBuffer[i] = static_cast<int16_t>(i % 50); // Low amplitude noise (~29 RMS)
    }

    float initialGain = leveler.getCurrentGain();
    float initialSmoothedRms = leveler.getSmoothedRms();

    // Process 50 consecutive frames (500ms) of ambient noise
    for (int frame = 0; frame < 50; frame++) {
        std::vector<int16_t> frameCopy = silenceBuffer;
        leveler.process(frameCopy.data(), frameCopy.size(), 0.0f);
    }

    // Gain must remain frozen at initial gain (1.0) without upward pumping
    assert(leveler.getCurrentGain() == initialGain);
    assert(leveler.getSmoothedRms() == initialSmoothedRms);
    std::cout << "  [PASS] testSilenceFreezesGainAndDoesNotPump" << std::endl;
}

void testQuietSpeechIncreasesGainUpToMaxGain() {
    mumla::audio::AdaptiveLeveler leveler;
    std::vector<int16_t> quietSpeech(mumla::audio::AdaptiveLeveler::SAMPLES_PER_10MS);
    for (size_t i = 0; i < quietSpeech.size(); i++) {
        // 1000 amplitude sine wave (~707 RMS, well below target 4125)
        quietSpeech[i] = static_cast<int16_t>(1000.0 * std::sin(2.0 * M_PI * 440.0 * i / 48000.0));
    }

    float prevGain = leveler.getCurrentGain();

    // Process 300 frames (3 seconds) of active quiet speech
    for (int frame = 0; frame < 300; frame++) {
        std::vector<int16_t> frameCopy = quietSpeech;
        leveler.process(frameCopy.data(), frameCopy.size(), 0.9f);

        float currentGain = leveler.getCurrentGain();
        // Slew rate must never exceed MAX_GAIN_SLEW_PER_FRAME per 10ms frame
        assert(currentGain - prevGain <= mumla::audio::AdaptiveLeveler::MAX_GAIN_SLEW_PER_FRAME + 1e-4f);
        prevGain = currentGain;
    }

    // Gain must have increased towards MAX_GAIN (4.0) and be bounded
    assert(leveler.getCurrentGain() > 1.0f);
    assert(leveler.getCurrentGain() <= mumla::audio::AdaptiveLeveler::MAX_GAIN);
    assert(leveler.getSmoothedRms() < mumla::audio::AdaptiveLeveler::DEFAULT_TARGET_RMS);
    std::cout << "  [PASS] testQuietSpeechIncreasesGainUpToMaxGain (gain: " << leveler.getCurrentGain() << ")" << std::endl;
}

void testLoudSpeechDecreasesGainDownToMinGain() {
    mumla::audio::AdaptiveLeveler leveler;
    std::vector<int16_t> loudSpeech(mumla::audio::AdaptiveLeveler::SAMPLES_PER_10MS);
    for (size_t i = 0; i < loudSpeech.size(); i++) {
        // 15000 amplitude sine wave (~10600 RMS, well above target 4125)
        loudSpeech[i] = static_cast<int16_t>(15000.0 * std::sin(2.0 * M_PI * 440.0 * i / 48000.0));
    }

    float prevGain = leveler.getCurrentGain();

    // Process 300 frames (3 seconds) of active loud speech
    for (int frame = 0; frame < 300; frame++) {
        std::vector<int16_t> frameCopy = loudSpeech;
        leveler.process(frameCopy.data(), frameCopy.size(), 0.9f);

        float currentGain = leveler.getCurrentGain();
        // Slew rate must never exceed MAX_GAIN_SLEW_PER_FRAME per 10ms frame
        assert(prevGain - currentGain <= mumla::audio::AdaptiveLeveler::MAX_GAIN_SLEW_PER_FRAME + 1e-4f);
        prevGain = currentGain;
    }

    // Gain must have attenuated towards MIN_GAIN (0.25) and be bounded
    assert(leveler.getCurrentGain() < 1.0f);
    assert(leveler.getCurrentGain() >= mumla::audio::AdaptiveLeveler::MIN_GAIN);
    assert(leveler.getSmoothedRms() > mumla::audio::AdaptiveLeveler::DEFAULT_TARGET_RMS);
    std::cout << "  [PASS] testLoudSpeechDecreasesGainDownToMinGain (gain: " << leveler.getCurrentGain() << ")" << std::endl;
}

void testSoftLimiterSaturationProtection() {
    mumla::audio::AdaptiveLeveler leveler(true, 10000.0f);
    std::vector<int16_t> frame(mumla::audio::AdaptiveLeveler::SAMPLES_PER_10MS);
    for (size_t i = 0; i < frame.size(); i++) {
        frame[i] = static_cast<int16_t>(1000.0 * std::sin(2.0 * M_PI * 440.0 * i / 48000.0));
    }

    // Slew gain upwards
    for (int i = 0; i < 200; i++) {
        std::vector<int16_t> c = frame;
        leveler.process(c.data(), c.size(), 0.9f);
    }

    // Send sudden massive peak: +32000
    std::vector<int16_t> peakFrame(mumla::audio::AdaptiveLeveler::SAMPLES_PER_10MS, 32000);
    leveler.process(peakFrame.data(), peakFrame.size(), 0.9f);

    // Verify saturation limiting: smooth saturation above knee
    for (int16_t s : peakFrame) {
        assert(s > 21844);
    }
    std::cout << "  [PASS] testSoftLimiterSaturationProtection" << std::endl;
}

void testResetRestoresInitialGain() {
    mumla::audio::AdaptiveLeveler leveler;
    std::vector<int16_t> loudSpeech(mumla::audio::AdaptiveLeveler::SAMPLES_PER_10MS, 15000);
    for (int i = 0; i < 50; i++) {
        std::vector<int16_t> c = loudSpeech;
        leveler.process(c.data(), c.size(), 0.9f);
    }
    assert(leveler.getCurrentGain() < 1.0f);

    leveler.reset();

    assert(leveler.getCurrentGain() == 1.0f);
    assert(std::abs(leveler.getSmoothedRms() - mumla::audio::AdaptiveLeveler::DEFAULT_TARGET_RMS) < 0.0001f);
    std::cout << "  [PASS] testResetRestoresInitialGain" << std::endl;
}

} // namespace

void run_adaptive_leveler_tests() {
    std::cout << "--- AdaptiveLeveler Tests ---" << std::endl;
    testDisabledLevelerPassesThroughUnchanged();
    testSilenceFreezesGainAndDoesNotPump();
    testQuietSpeechIncreasesGainUpToMaxGain();
    testLoudSpeechDecreasesGainDownToMinGain();
    testSoftLimiterSaturationProtection();
    testResetRestoresInitialGain();
}
