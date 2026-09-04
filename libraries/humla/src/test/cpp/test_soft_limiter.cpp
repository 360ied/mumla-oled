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
#include "TestHarness.h"

#include <iostream>
#include <vector>

namespace {

void testUnityBoostPassesThrough() {
    g_testCount++;
    int16_t sample = 15000;
    TEST_ASSERT_EQ(mumla::audio::SoftLimiter::processSample(sample, 1.0f), 15000);
    std::cout << "  [PASS] testUnityBoostPassesThrough" << std::endl;
}

void testLinearRegionBelowKnee() {
    g_testCount++;
    // Knee is at 2/3 of 32767 = 21844
    int16_t sample = 10000;
    // 10000 * 1.5 = 15000 (< 21844) -> passes linearly
    TEST_ASSERT_EQ(mumla::audio::SoftLimiter::processSample(sample, 1.5f), 15000);
    std::cout << "  [PASS] testLinearRegionBelowKnee" << std::endl;
}

void testSmoothSaturationAboveKneeWithoutSquareClipping() {
    g_testCount++;
    // High boost factor of 3.0x on 20000 (scaled = 60000, > 32767)
    int16_t sample = 20000;
    int16_t processed = mumla::audio::SoftLimiter::processSample(sample, 3.0f);

    // Must be smoothly saturated, strictly within [-32768, 32767]
    TEST_ASSERT(processed > 21844 && processed < 32767);

    // Negative sample test
    int16_t negSample = -20000;
    int16_t negProcessed = mumla::audio::SoftLimiter::processSample(negSample, 3.0f);
    TEST_ASSERT(negProcessed < -21844 && negProcessed > -32768);

    std::cout << "  [PASS] testSmoothSaturationAboveKneeWithoutSquareClipping (pos: "
              << processed << ", neg: " << negProcessed << ")" << std::endl;
}

void testProcessBufferInPlace() {
    g_testCount++;
    std::vector<int16_t> buffer = {0, 1000, 15000, 30000};
    mumla::audio::SoftLimiter::processBuffer(buffer.data(), buffer.size(), 2.0f);

    TEST_ASSERT_EQ(buffer[0], 0);
    TEST_ASSERT_EQ(buffer[1], 2000);
    TEST_ASSERT(buffer[2] > 21844);
    TEST_ASSERT(buffer[3] > 21844);

    std::cout << "  [PASS] testProcessBufferInPlace" << std::endl;
}

} // namespace

void run_soft_limiter_tests() {
    std::cout << "--- SoftLimiter Tests ---" << std::endl;
    testUnityBoostPassesThrough();
    testLinearRegionBelowKnee();
    testSmoothSaturationAboveKneeWithoutSquareClipping();
    testProcessBufferInPlace();
}
