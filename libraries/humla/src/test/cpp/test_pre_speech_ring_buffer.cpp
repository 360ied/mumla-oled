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

#include "PreSpeechRingBuffer.h"
#include "TestHarness.h"

#include <iostream>
#include <vector>

namespace {

void testPushAndFlushInChronologicalOrder() {
    g_testCount++;
    mumla::audio::PreSpeechRingBuffer buffer(4, 10);

    for (int frameIdx = 0; frameIdx < 3; frameIdx++) {
        std::vector<int16_t> pcm(10);
        for (int i = 0; i < 10; i++) {
            pcm[i] = static_cast<int16_t>((frameIdx + 1) * 100 + i);
        }
        buffer.push(pcm.data(), 10);
    }

    TEST_ASSERT_EQ(buffer.getCount(), 3);

    std::vector<std::vector<int16_t>> flushed;
    buffer.flush([&flushed](const int16_t* pcm, size_t len) {
        flushed.emplace_back(pcm, pcm + len);
    });

    TEST_ASSERT_EQ(flushed.size(), 3);
    TEST_ASSERT_EQ(buffer.getCount(), 0);

    for (int frameIdx = 0; frameIdx < 3; frameIdx++) {
        std::vector<int16_t> expected(10);
        for (int i = 0; i < 10; i++) {
            expected[i] = static_cast<int16_t>((frameIdx + 1) * 100 + i);
        }
        TEST_ASSERT(flushed[frameIdx] == expected);
    }
    std::cout << "  [PASS] testPushAndFlushInChronologicalOrder" << std::endl;
}

void testOverflowOverwritesOldestFramesInFIFOOrder() {
    g_testCount++;
    mumla::audio::PreSpeechRingBuffer buffer(3, 4);

    // Push 5 frames into capacity-3 buffer (frames 1, 2, 3, 4, 5)
    for (int frameIdx = 1; frameIdx <= 5; frameIdx++) {
        std::vector<int16_t> pcm(4, static_cast<int16_t>(frameIdx));
        buffer.push(pcm.data(), 4);
    }

    TEST_ASSERT_EQ(buffer.getCount(), 3);

    std::vector<std::vector<int16_t>> flushed;
    buffer.flush([&flushed](const int16_t* pcm, size_t len) {
        flushed.emplace_back(pcm, pcm + len);
    });

    // Must contain frames 3, 4, 5 in exact FIFO order
    TEST_ASSERT_EQ(flushed.size(), 3);
    std::vector<int16_t> expected3(4, 3);
    std::vector<int16_t> expected4(4, 4);
    std::vector<int16_t> expected5(4, 5);
    TEST_ASSERT(flushed[0] == expected3);
    TEST_ASSERT(flushed[1] == expected4);
    TEST_ASSERT(flushed[2] == expected5);

    std::cout << "  [PASS] testOverflowOverwritesOldestFramesInFIFOOrder" << std::endl;
}

void testClearResetsCount() {
    g_testCount++;
    mumla::audio::PreSpeechRingBuffer buffer(4, 10);
    std::vector<int16_t> pcm = {1, 2, 3};
    buffer.push(pcm.data(), pcm.size());
    TEST_ASSERT_EQ(buffer.getCount(), 1);

    buffer.clear();
    TEST_ASSERT_EQ(buffer.getCount(), 0);

    size_t flushedCount = 0;
    buffer.flush([&flushedCount](const int16_t*, size_t) {
        flushedCount++;
    });
    TEST_ASSERT_EQ(flushedCount, 0);

    std::cout << "  [PASS] testClearResetsCount" << std::endl;
}

void testPartialFramePushZeroPadsRemainder() {
    g_testCount++;
    mumla::audio::PreSpeechRingBuffer buffer(2, 8);
    std::vector<int16_t> partial = {10, 20, 30};
    buffer.push(partial.data(), partial.size());

    TEST_ASSERT_EQ(buffer.getCount(), 1);
    std::vector<int16_t> flushedFrame;
    buffer.flush([&flushedFrame](const int16_t* pcm, size_t len) {
        flushedFrame.assign(pcm, pcm + len);
    });

    TEST_ASSERT_EQ(flushedFrame.size(), 8);
    TEST_ASSERT_EQ(flushedFrame[0], 10);
    TEST_ASSERT_EQ(flushedFrame[1], 20);
    TEST_ASSERT_EQ(flushedFrame[2], 30);
    for (size_t i = 3; i < 8; i++) {
        TEST_ASSERT_EQ(flushedFrame[i], 0);
    }
    std::cout << "  [PASS] testPartialFramePushZeroPadsRemainder" << std::endl;
}

} // namespace

void run_pre_speech_ring_buffer_tests() {
    std::cout << "--- PreSpeechRingBuffer Tests ---" << std::endl;
    testPushAndFlushInChronologicalOrder();
    testOverflowOverwritesOldestFramesInFIFOOrder();
    testClearResetsCount();
    testPartialFramePushZeroPadsRemainder();
}
