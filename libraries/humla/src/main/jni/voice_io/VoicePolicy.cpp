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

#include "VoicePolicy.h"

#include <algorithm>

namespace mumla {
namespace voice_io {

void PolicyCell::publish(const int32_t* muted, size_t mutedCount,
                         const int32_t* known, size_t knownCount,
                         bool knownOverflow, bool transmitAllowed, bool pttPressed,
                         int32_t voiceTarget, int32_t bitrate, int32_t framesPerPacket) {
    const uint32_t seq = m_seq.load(std::memory_order_relaxed);
    m_seq.store(seq + 1, std::memory_order_release); // odd: writing
    PolicySnapshot next;
    const size_t m = std::min(mutedCount, kPolicyListCapacity);
    for (size_t i = 0; i < m; ++i) {
        next.muted[i] = muted[i];
    }
    next.mutedCount = m;
    const size_t k = std::min(knownCount, kPolicyListCapacity);
    for (size_t i = 0; i < k; ++i) {
        next.known[i] = known[i];
    }
    next.knownCount = k;
    // Overflow flag is sticky truth: truncated known fails open even when
    // the caller also passes the flag from its own truncation.
    next.knownOverflow = knownOverflow || knownCount > kPolicyListCapacity;
    next.transmitAllowed = transmitAllowed;
    next.pttPressed = pttPressed;
    next.voiceTarget = voiceTarget;
    next.bitrate = bitrate;
    next.framesPerPacket = framesPerPacket;
    m_data = next;
    m_seq.store(seq + 2, std::memory_order_release); // even: done
}

bool PolicyCell::tryRead(PolicySnapshot* out) const {
    const uint32_t seq0 = m_seq.load(std::memory_order_acquire);
    if (seq0 & 1) {
        return false;
    }
    *out = m_data;
    std::atomic_thread_fence(std::memory_order_acquire);
    return seq0 == m_seq.load(std::memory_order_acquire);
}

bool PolicyCell::shouldDrop(const PolicySnapshot& snapshot, int32_t session) {
    for (size_t i = 0; i < snapshot.mutedCount; ++i) {
        if (snapshot.muted[i] == session) {
            return true;
        }
    }
    if (snapshot.knownOverflow) {
        return false;
    }
    for (size_t i = 0; i < snapshot.knownCount; ++i) {
        if (snapshot.known[i] == session) {
            return false;
        }
    }
    return true;
}

} // namespace voice_io
} // namespace mumla
