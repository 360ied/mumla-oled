/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
