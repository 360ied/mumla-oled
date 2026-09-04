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
#include "TestHarness.h"

#include <cmath>
#include <iostream>
#include <vector>

namespace {

void testSilenceDoesNotTriggerSpeech() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.50f, 0.35f, 5);
    std::vector<int16_t> silence(480, 0);

    bool speaking = vad.process(silence.data(), silence.size(), 0.0f);
    TEST_ASSERT_FALSE(speaking);
    TEST_ASSERT_FALSE(vad.isSpeaking());
    std::cout << "  [PASS] testSilenceDoesNotTriggerSpeech" << std::endl;
}

void testSpeechActivationAtVadMax() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.50f, 0.35f, 5);

    // Loud signal (10000 amplitude -> well above squelch floor)
    std::vector<int16_t> loud(480, 10000);

    // Neural speech prob (0.80) exceeds vadMax (0.50)
    bool speaking = vad.process(loud.data(), loud.size(), 0.80f);
    TEST_ASSERT_TRUE(speaking);
    TEST_ASSERT_TRUE(vad.isSpeaking());
    std::cout << "  [PASS] testSpeechActivationAtVadMax" << std::endl;
}

void testHysteresisDeactivationBetweenMinAndMaxWithHangover() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.60f, 0.30f, 3);

    // 1. Activate with loud frame and high speech probability
    std::vector<int16_t> loud(480, 15000);
    TEST_ASSERT_TRUE(vad.process(loud.data(), loud.size(), 0.80f));
    TEST_ASSERT_TRUE(vad.isSpeaking());

    // 2. Score drops below vadMin (0.20 < 0.30) -> hangover frames countdown
    TEST_ASSERT_TRUE(vad.process(loud.data(), loud.size(), 0.20f)); // hangover 1
    TEST_ASSERT_TRUE(vad.isSpeaking());

    TEST_ASSERT_TRUE(vad.process(loud.data(), loud.size(), 0.20f)); // hangover 2
    TEST_ASSERT_TRUE(vad.isSpeaking());

    TEST_ASSERT_TRUE(vad.process(loud.data(), loud.size(), 0.20f)); // hangover 3
    TEST_ASSERT_TRUE(vad.isSpeaking());

    // 3. Hangover expired -> must deactivate
    TEST_ASSERT_FALSE(vad.process(loud.data(), loud.size(), 0.20f));
    TEST_ASSERT_FALSE(vad.isSpeaking());
    std::cout << "  [PASS] testHysteresisDeactivationBetweenMinAndMaxWithHangover" << std::endl;
}

void testSquelchGateSuppressesAcousticNoiseBelowFloor() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.35f, 0.25f, 5, -65.0f);

    // Complete silence (amplitude 0 -> -96 dBFS, well below -65 dBFS)
    std::vector<int16_t> deadSilence(480, 0);

    // Even with high neural probability (0.95), squelch gate blocks transmission
    bool speaking = vad.process(deadSilence.data(), deadSilence.size(), 0.95f);
    TEST_ASSERT_FALSE(speaking);
    TEST_ASSERT_FALSE(vad.isSpeaking());
    std::cout << "  [PASS] testSquelchGateSuppressesAcousticNoiseBelowFloor" << std::endl;
}

void testResetClearsSpeakingState() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.35f, 0.25f, 5);
    std::vector<int16_t> loud(480, 10000);

    TEST_ASSERT_TRUE(vad.process(loud.data(), loud.size(), 0.80f));
    TEST_ASSERT_TRUE(vad.isSpeaking());

    vad.reset();
    TEST_ASSERT_FALSE(vad.isSpeaking());
    std::cout << "  [PASS] testResetClearsSpeakingState" << std::endl;
}

void testNeuralProbabilityOverridesEnergy() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.50f, 0.35f, 5);
    std::vector<int16_t> quiet(480, 200); // Quiet consonant (~ -44 dBFS, above -65 dBFS squelch)

    // High neural speech probability (0.95) should trigger speech even on quiet consonants
    bool speaking = vad.process(quiet.data(), quiet.size(), 0.95f);
    TEST_ASSERT_TRUE(speaking);
    std::cout << "  [PASS] testNeuralProbabilityOverridesEnergy" << std::endl;
}

void testDefaultThresholdsAndGetters() {
    g_testCount++;
    mumla::audio::HysteresisVad vad;
    TEST_ASSERT_NEAR(vad.getVadMax(), 0.35f, 1e-4f);
    TEST_ASSERT_NEAR(vad.getVadMin(), 0.25f, 1e-4f);
    TEST_ASSERT_NEAR(vad.getSquelchMinDb(), -65.0f, 1e-4f);
    std::cout << "  [PASS] testDefaultThresholdsAndGetters" << std::endl;
}

void testSoftSpeechActivationWithCalibratedThreshold() {
    g_testCount++;
    mumla::audio::HysteresisVad vad; // 0.35f / 0.25f
    std::vector<int16_t> softSpeech(480, 400); // ~ -38 dBFS, above -65 dBFS squelch

    // Neural prob = 0.40 >= vadMax (0.35) activates directly without acoustic energy penalty
    bool speaking = vad.process(softSpeech.data(), softSpeech.size(), 0.40f);
    TEST_ASSERT_TRUE(speaking);
    TEST_ASSERT_TRUE(vad.isSpeaking());
    std::cout << "  [PASS] testSoftSpeechActivationWithCalibratedThreshold" << std::endl;
}

void testHighAmbientNoiseImmunity() {
    g_testCount++;
    mumla::audio::HysteresisVad vad; // vadMax = 0.35
    // Very loud ambient acoustic noise (~ -7 dBFS), but non-speech (neuralSpeechProb = 0.10)
    std::vector<int16_t> loudNoise(480, 15000);

    // Acoustic energy does not add to score; neural score (0.10) < vadMax (0.35)
    bool speaking = vad.process(loudNoise.data(), loudNoise.size(), 0.10f);
    TEST_ASSERT_FALSE(speaking);
    TEST_ASSERT_FALSE(vad.isSpeaking());
    std::cout << "  [PASS] testHighAmbientNoiseImmunity" << std::endl;
}

void testQuietWhisperSensitivityAboveSquelch() {
    g_testCount++;
    mumla::audio::HysteresisVad vad; // vadMax = 0.35, squelch = -65 dBFS
    // Quiet whisper (~ -44 dBFS, amplitude = 200, well above -65 dBFS)
    std::vector<int16_t> whisper(480, 200);

    // Neural probability (0.38 >= 0.35) cleanly activates without energy attenuation
    bool speaking = vad.process(whisper.data(), whisper.size(), 0.38f);
    TEST_ASSERT_TRUE(speaking);
    TEST_ASSERT_TRUE(vad.isSpeaking());
    std::cout << "  [PASS] testQuietWhisperSensitivityAboveSquelch" << std::endl;
}

void testSquelchConfigurationAndGetters() {
    g_testCount++;
    mumla::audio::HysteresisVad vad(0.35f, 0.25f, 25, -50.0f);
    TEST_ASSERT_NEAR(vad.getSquelchMinDb(), -50.0f, 1e-4f);

    // Amplitude 200 (~ -44 dBFS) is above -50 dBFS squelch
    std::vector<int16_t> signal44(480, 200);
    TEST_ASSERT_TRUE(vad.process(signal44.data(), signal44.size(), 0.50f));

    vad.reset();

    // Amplitude 50 (~ -56 dBFS) is below -50 dBFS squelch
    std::vector<int16_t> signal56(480, 50);
    TEST_ASSERT_FALSE(vad.process(signal56.data(), signal56.size(), 0.50f));
    std::cout << "  [PASS] testSquelchConfigurationAndGetters" << std::endl;
}

} // namespace

void run_hysteresis_vad_tests() {
    std::cout << "--- HysteresisVad Tests ---" << std::endl;
    testSilenceDoesNotTriggerSpeech();
    testSpeechActivationAtVadMax();
    testHysteresisDeactivationBetweenMinAndMaxWithHangover();
    testSquelchGateSuppressesAcousticNoiseBelowFloor();
    testResetClearsSpeakingState();
    testNeuralProbabilityOverridesEnergy();
    testDefaultThresholdsAndGetters();
    testSoftSpeechActivationWithCalibratedThreshold();
    testHighAmbientNoiseImmunity();
    testQuietWhisperSensitivityAboveSquelch();
    testSquelchConfigurationAndGetters();
}
