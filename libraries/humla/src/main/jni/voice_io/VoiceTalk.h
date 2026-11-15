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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef MUMLA_VOICE_TALK_H_
#define MUMLA_VOICE_TALK_H_

#include <atomic>
#include <cstdint>

#include "VoiceIo.h"

namespace mumla {
namespace voice_io {

// Talk snapshot plus cumulative drop counts as one publishable unit. The
// version counts publications; the notifier delivers only when it changes,
// so one delivery is exactly one Kotlin call.
struct TalkPublication {
    uint64_t version = 0;
    VoiceIo::TalkSnapshot talk;
    uint64_t droppedInbound = 0;
    uint64_t droppedTunnel = 0;
    uint64_t droppedJitter = 0;
};

/**
 * Single-writer single-reader sequence lock for the talk snapshot.
 *
 * Render is the only writer (publish per render), the notifier thread the
 * only reader. The writer never waits; the reader retries when it catches
 * the writer mid-update, so it never observes a torn table. Readers copy a
 * few dozen entries plus counters — cheap enough to retry.
 */
class TalkCell {
public:
    TalkCell() = default;

    TalkCell(const TalkCell&) = delete;
    TalkCell& operator=(const TalkCell&) = delete;

    // Render thread only.
    void publish(const VoiceIo::TalkSnapshot& talk, uint64_t droppedInbound,
                 uint64_t droppedTunnel, uint64_t droppedJitter) {
        const uint32_t seq = m_seq.load(std::memory_order_relaxed);
        m_seq.store(seq + 1, std::memory_order_release); // odd: writing
        m_data.talk = talk;
        m_data.droppedInbound = droppedInbound;
        m_data.droppedTunnel = droppedTunnel;
        m_data.droppedJitter = droppedJitter;
        m_data.version = m_nextVersion++;
        m_seq.store(seq + 2, std::memory_order_release); // even: done
    }

    // Notifier thread only. Returns false when the writer was mid-update;
    // the caller retries on the next tick and keeps the older snapshot,
    // which a slow reader replaces wholesale — never a contradiction.
    bool tryRead(TalkPublication* out) const {
        const uint32_t seq0 = m_seq.load(std::memory_order_acquire);
        if (seq0 & 1) {
            return false;
        }
        *out = m_data;
        std::atomic_thread_fence(std::memory_order_acquire);
        return seq0 == m_seq.load(std::memory_order_acquire);
    }

private:
    mutable std::atomic<uint32_t> m_seq{0};
    TalkPublication m_data;
    uint64_t m_nextVersion = 1;
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_TALK_H_
