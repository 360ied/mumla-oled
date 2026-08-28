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

#include <algorithm>
#include <cstring>

namespace mumla {
namespace audio {

PreSpeechRingBuffer::PreSpeechRingBuffer(size_t frameCapacity, size_t frameSize)
    : m_capacity(frameCapacity > 0 ? frameCapacity : DEFAULT_CAPACITY_FRAMES),
      m_frameSize(frameSize > 0 ? frameSize : SAMPLES_PER_FRAME),
      m_storage(m_capacity * m_frameSize, 0),
      m_head(0),
      m_count(0) {}

void PreSpeechRingBuffer::push(const int16_t* pcm, size_t sampleCount) {
    if (pcm == nullptr || sampleCount == 0) {
        return;
    }
    size_t copyCount = std::min(sampleCount, m_frameSize);
    int16_t* dest = &m_storage[m_head * m_frameSize];
    std::memcpy(dest, pcm, copyCount * sizeof(int16_t));
    if (copyCount < m_frameSize) {
        std::memset(dest + copyCount, 0, (m_frameSize - copyCount) * sizeof(int16_t));
    }

    m_head = (m_head + 1) % m_capacity;
    if (m_count < m_capacity) {
        m_count++;
    }
}

void PreSpeechRingBuffer::flush(const std::function<void(const int16_t* pcm, size_t sampleCount)>& consumer) {
    if (m_count == 0 || !consumer) {
        return;
    }
    size_t startIndex = (m_head - m_count + m_capacity) % m_capacity;
    for (size_t i = 0; i < m_count; i++) {
        size_t index = (startIndex + i) % m_capacity;
        const int16_t* framePtr = &m_storage[index * m_frameSize];
        consumer(framePtr, m_frameSize);
    }
    m_count = 0;
}

void PreSpeechRingBuffer::clear() {
    m_count = 0;
    m_head = 0;
}

} // namespace audio
} // namespace mumla
