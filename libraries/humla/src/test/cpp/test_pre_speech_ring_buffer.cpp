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

#include <cassert>
#include <iostream>
#include <vector>

namespace {

void testPushAndFlushInChronologicalOrder() {
    mumla::audio::PreSpeechRingBuffer buffer(4, 10);

    for (int frameIdx = 0; frameIdx < 3; frameIdx++) {
        std::vector<int16_t> pcm(10);
        for (int i = 0; i < 10; i++) {
            pcm[i] = static_cast<int16_t>((frameIdx + 1) * 100 + i);
        }
        buffer.push(pcm.data(), 10);
    }

    assert(buffer.getCount() == 3);

    std::vector<std::vector<int16_t>> flushed;
    buffer.flush([&flushed](const int16_t* pcm, size_t len) {
        flushed.emplace_back(pcm, pcm + len);
    });

    assert(flushed.size() == 3);
    assert(buffer.getCount() == 0);

    for (int frameIdx = 0; frameIdx < 3; frameIdx++) {
        std::vector<int16_t> expected(10);
        for (int i = 0; i < 10; i++) {
            expected[i] = static_cast<int16_t>((frameIdx + 1) * 100 + i);
        }
        assert(flushed[frameIdx] == expected);
    }
    std::cout << "  [PASS] testPushAndFlushInChronologicalOrder" << std::endl;
}

void testOverflowOverwritesOldestFramesInFIFOOrder() {
    mumla::audio::PreSpeechRingBuffer buffer(3, 4);

    // Push 5 frames into capacity-3 buffer (frames 1, 2, 3, 4, 5)
    for (int frameIdx = 1; frameIdx <= 5; frameIdx++) {
        std::vector<int16_t> pcm(4, static_cast<int16_t>(frameIdx));
        buffer.push(pcm.data(), 4);
    }

    assert(buffer.getCount() == 3);

    std::vector<std::vector<int16_t>> flushed;
    buffer.flush([&flushed](const int16_t* pcm, size_t len) {
        flushed.emplace_back(pcm, pcm + len);
    });

    // Must contain frames 3, 4, 5 in exact FIFO order
    assert(flushed.size() == 3);
    std::vector<int16_t> expected3(4, 3);
    std::vector<int16_t> expected4(4, 4);
    std::vector<int16_t> expected5(4, 5);
    assert(flushed[0] == expected3);
    assert(flushed[1] == expected4);
    assert(flushed[2] == expected5);

    std::cout << "  [PASS] testOverflowOverwritesOldestFramesInFIFOOrder" << std::endl;
}

void testClearResetsCount() {
    mumla::audio::PreSpeechRingBuffer buffer(4, 10);
    std::vector<int16_t> pcm = {1, 2, 3};
    buffer.push(pcm.data(), pcm.size());
    assert(buffer.getCount() == 1);

    buffer.clear();
    assert(buffer.getCount() == 0);

    size_t flushedCount = 0;
    buffer.flush([&flushedCount](const int16_t*, size_t) {
        flushedCount++;
    });
    assert(flushedCount == 0);

    std::cout << "  [PASS] testClearResetsCount" << std::endl;
}

} // namespace

void run_pre_speech_ring_buffer_tests() {
    std::cout << "--- PreSpeechRingBuffer Tests ---" << std::endl;
    testPushAndFlushInChronologicalOrder();
    testOverflowOverwritesOldestFramesInFIFOOrder();
    testClearResetsCount();
}
