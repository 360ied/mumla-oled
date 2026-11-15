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

#ifndef MUMLA_VOICE_POLICY_H_
#define MUMLA_VOICE_POLICY_H_

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace mumla {
namespace voice_io {

// Sender-filter lists (step 5a.3b): the native receive thread and the tunnel
// caller drop voice from a locally muted sender or an unknown session, as
// today's AudioOutput does. Capacity 256 each: readers copy the whole
// snapshot per datagram. Overflow fails in opposite directions: a longer
// muted list keeps the first 256 and logs (some muted users are heard); a
// longer known list sets the overflow flag, and while set the
// unknown-sender check is skipped and only the muted check applies.
inline constexpr size_t kPolicyListCapacity = 256;

struct PolicySnapshot {
    int32_t muted[kPolicyListCapacity] = {};
    size_t mutedCount = 0;
    int32_t known[kPolicyListCapacity] = {};
    size_t knownCount = 0;
    // True when the known list was truncated: fail open on unknown senders.
    bool knownOverflow = false;
};

/**
 * Single-writer sequence lock for the sender-filter policy.
 *
 * One writer (the JNI setPolicy call), any number of readers (receive,
 * tunnel caller). The writer never waits; readers copy and retry when they
 * catch the writer mid-update, so they never observe a torn list. At most
 * a few dozen entries plus counters: cheap to copy per datagram.
 */
class PolicyCell {
public:
    PolicyCell() = default;

    PolicyCell(const PolicyCell&) = delete;
    PolicyCell& operator=(const PolicyCell&) = delete;

    // Writer (JNI) only. Truncates overlong lists per the overflow rule.
    void publish(const int32_t* muted, size_t mutedCount, const int32_t* known,
                 size_t knownCount, bool knownOverflow);

    // Reader only. Returns false when the writer was mid-update; the caller
    // retries (receive path) or drops (never blocks a realtime thread).
    bool tryRead(PolicySnapshot* out) const;

    // Sender filter: true when voice from session must be dropped.
    static bool shouldDrop(const PolicySnapshot& snapshot, int32_t session);

private:
    mutable std::atomic<uint32_t> m_seq{0};
    PolicySnapshot m_data;
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_POLICY_H_
