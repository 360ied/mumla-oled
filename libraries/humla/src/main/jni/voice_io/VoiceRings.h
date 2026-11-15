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

#ifndef MUMLA_VOICE_RINGS_H_
#define MUMLA_VOICE_RINGS_H_

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace mumla {
namespace voice_io {

// Inbound and tunnel queue capacities: 256 InboundPackets each, as specified
// in voice.md. At the production frame cadence this holds seconds of audio;
// the rings are backpressure signals (full means the render thread stalled),
// not buffers to grow.
inline constexpr size_t kInboundRingCapacity = 256;
inline constexpr size_t kTunnelRingCapacity = 256;

/**
 * Single-producer single-consumer lock-free ring.
 *
 * One producer calls tryPush, one consumer calls tryPop; any other pairing
 * is a contract violation. Never blocks, never allocates after construction.
 * A full ring drops the new item (tryPush returns false) and the owner
 * counts the drop; the producer never removes old items from a
 * single-producer ring. Head/tail use acquire/release ordering so the
 * session message coroutine's tunnel pushes stay ordered even though it may
 * resume on different dispatcher threads (one logical producer).
 */
template <typename T, size_t Capacity>
class SpscRing {
public:
    SpscRing() = default;

    SpscRing(const SpscRing&) = delete;
    SpscRing& operator=(const SpscRing&) = delete;

    /** Pushes item; returns false when full (caller counts the drop). */
    bool tryPush(const T& item) {
        const uint32_t head = m_head.load(std::memory_order_relaxed);
        const uint32_t tail = m_tail.load(std::memory_order_acquire);
        if (head - tail >= Capacity) {
            return false;
        }
        m_slots[head % Capacity] = item;
        m_head.store(head + 1, std::memory_order_release);
        return true;
    }

    /** Pops the oldest item; returns false when empty. */
    bool tryPop(T* out) {
        const uint32_t tail = m_tail.load(std::memory_order_relaxed);
        const uint32_t head = m_head.load(std::memory_order_acquire);
        if (tail == head) {
            return false;
        }
        *out = m_slots[tail % Capacity];
        m_tail.store(tail + 1, std::memory_order_release);
        return true;
    }

    bool empty() const {
        return m_tail.load(std::memory_order_acquire) ==
               m_head.load(std::memory_order_acquire);
    }

    size_t size() const {
        const uint32_t head = m_head.load(std::memory_order_acquire);
        const uint32_t tail = m_tail.load(std::memory_order_acquire);
        return static_cast<size_t>(head - tail);
    }

    static constexpr size_t capacity() { return Capacity; }

private:
    T m_slots[Capacity] = {};
    // Wrapping u32 cursors: head-tail distance is the occupancy even across
    // wrap (about 4 billion pushes at 50/s); indexing uses modulo.
    std::atomic<uint32_t> m_head{0};
    std::atomic<uint32_t> m_tail{0};
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_RINGS_H_
