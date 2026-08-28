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
