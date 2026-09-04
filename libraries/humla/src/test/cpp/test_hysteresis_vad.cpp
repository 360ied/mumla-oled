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

#include "HysteresisVad.h"

#include <cassert>
#include <iostream>
#include <vector>

namespace {

void testSilenceDoesNotTriggerSpeech() {
    mumla::audio::HysteresisVad vad(0.50f, 0.35f, 5);
    std::vector<int16_t> silence(480, 0);

    bool speaking = vad.process(silence.data(), silence.size(), 0.0f);
    assert(!speaking);
    assert(!vad.isSpeaking());
    std::cout << "  [PASS] testSilenceDoesNotTriggerSpeech" << std::endl;
}

void testSpeechActivationAtVadMax() {
    mumla::audio::HysteresisVad vad(0.50f, 0.35f, 5);

    // Loud signal (10000 amplitude -> well above squelch floor)
    std::vector<int16_t> loud(480, 10000);

    // Neural speech prob (0.80) exceeds vadMax (0.50)
    bool speaking = vad.process(loud.data(), loud.size(), 0.80f);
    assert(speaking);
    assert(vad.isSpeaking());
    std::cout << "  [PASS] testSpeechActivationAtVadMax" << std::endl;
}

void testHysteresisDeactivationBetweenMinAndMaxWithHangover() {
    mumla::audio::HysteresisVad vad(0.60f, 0.30f, 3);

    // 1. Activate with loud frame and high speech probability
    std::vector<int16_t> loud(480, 15000);
    assert(vad.process(loud.data(), loud.size(), 0.80f));
    assert(vad.isSpeaking());

    // 2. Score drops below vadMin (0.20 < 0.30) -> hangover frames countdown
    assert(vad.process(loud.data(), loud.size(), 0.20f)); // hangover 1
    assert(vad.isSpeaking());

    assert(vad.process(loud.data(), loud.size(), 0.20f)); // hangover 2
    assert(vad.isSpeaking());

    assert(vad.process(loud.data(), loud.size(), 0.20f)); // hangover 3
    assert(vad.isSpeaking());

    // 3. Hangover expired -> must deactivate
    assert(!vad.process(loud.data(), loud.size(), 0.20f));
    assert(!vad.isSpeaking());
    std::cout << "  [PASS] testHysteresisDeactivationBetweenMinAndMaxWithHangover" << std::endl;
}

void testSquelchGateSuppressesAcousticNoiseBelowFloor() {
    mumla::audio::HysteresisVad vad(0.35f, 0.25f, 5, -65.0f);

    // Complete silence (amplitude 0 -> -96 dBFS, well below -65 dBFS)
    std::vector<int16_t> deadSilence(480, 0);

    // Even with high neural probability (0.95), squelch gate blocks transmission
    bool speaking = vad.process(deadSilence.data(), deadSilence.size(), 0.95f);
    assert(!speaking);
    assert(!vad.isSpeaking());
    std::cout << "  [PASS] testSquelchGateSuppressesAcousticNoiseBelowFloor" << std::endl;
}

void testResetClearsSpeakingState() {
    mumla::audio::HysteresisVad vad(0.35f, 0.25f, 5);
    std::vector<int16_t> loud(480, 10000);

    assert(vad.process(loud.data(), loud.size(), 0.80f));
    assert(vad.isSpeaking());

    vad.reset();
    assert(!vad.isSpeaking());
    std::cout << "  [PASS] testResetClearsSpeakingState" << std::endl;
}

} // namespace

void run_hysteresis_vad_tests() {
    std::cout << "--- HysteresisVad Tests ---" << std::endl;
    testSilenceDoesNotTriggerSpeech();
    testSpeechActivationAtVadMax();
    testHysteresisDeactivationBetweenMinAndMaxWithHangover();
    testSquelchGateSuppressesAcousticNoiseBelowFloor();
    testResetClearsSpeakingState();
}
