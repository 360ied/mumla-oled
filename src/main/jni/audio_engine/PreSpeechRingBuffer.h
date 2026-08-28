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

#ifndef MUMLA_PRE_SPEECH_RING_BUFFER_H_
#define MUMLA_PRE_SPEECH_RING_BUFFER_H_

#include <cstddef>
#include <cstdint>
#include <functional>
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
     */
    void flush(const std::function<void(const int16_t* pcm, size_t sampleCount)>& consumer);

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
