/*
 * Copyright (C) 2026 Brian Zhu
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

#ifndef MUMLA_PRE_SPEECH_RING_BUFFER_H_
#define MUMLA_PRE_SPEECH_RING_BUFFER_H_

#include <cstddef>
#include <cstdint>
#include <vector>

namespace mumla {
namespace audio {

/**
 * Pre-Speech Lookahead Ring Buffer.
 *
 * Stores recent audio frames in a fixed-size FIFO ring buffer during silence.
 * Upon speech onset detection, flushes all pre-speech frames in chronological
 * order to the encoder, preventing word-onset clipping of quiet consonants
 * and fricatives. Zero dynamic allocations on the audio path after initialization.
 */
class PreSpeechRingBuffer {
public:
    static constexpr size_t DEFAULT_CAPACITY_FRAMES = 8; // 8 * 10ms = 80ms lookahead
    static constexpr size_t SAMPLES_PER_FRAME = 480;      // 10ms @ 48kHz

    explicit PreSpeechRingBuffer(size_t frameCapacity = DEFAULT_CAPACITY_FRAMES,
                                 size_t frameSize = SAMPLES_PER_FRAME);
    ~PreSpeechRingBuffer() = default;

    /**
     * Pushes a new frame into the circular buffer. Overwrites oldest frame if full.
     */
    void push(const int16_t* pcm, size_t sampleCount);

    /**
     * Flushes all stored frames in chronological order to the provided consumer.
     * Clears the buffer count after flushing.
     *
     * Templated rather than std::function: the capture thread calls this on the
     * realtime path, where type erasure could allocate.
     */
    template <typename Consumer>
    void flush(Consumer&& consumer) {
        if (m_count == 0) {
            return;
        }
        const size_t startIndex = (m_head + m_capacity - m_count) % m_capacity;
        for (size_t i = 0; i < m_count; i++) {
            const size_t index = (startIndex + i) % m_capacity;
            consumer(&m_storage[index * m_frameSize], m_frameSize);
        }
        m_count = 0;
    }

    /**
     * Resets the buffer state.
     */
    void clear();

    size_t getCount() const { return m_count; }
    size_t getCapacity() const { return m_capacity; }
    size_t getFrameSize() const { return m_frameSize; }

private:
    size_t m_capacity;
    size_t m_frameSize;
    std::vector<int16_t> m_storage;
    size_t m_head;
    size_t m_count;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_PRE_SPEECH_RING_BUFFER_H_
