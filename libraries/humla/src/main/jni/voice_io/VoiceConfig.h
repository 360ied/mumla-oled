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

#ifndef MUMLA_VOICE_CONFIG_H_
#define MUMLA_VOICE_CONFIG_H_

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace mumla {
namespace voice_io {

// Capture gate (step 5a.4): the transmit side of VoicePolicy. Published by
// the JNI setPolicy path (same writer as the sender-filter PolicyCell, one
// call publishes both), read by the capture thread each frame and by the
// send thread for its drop-oldest bound. Separate from the filter lists so
// the existing PolicyCell and its tests stay untouched; the collector lag
// is the same one hop the filter already accepts.
struct GateSnapshot {
    bool transmitAllowed = false;
    bool pttPressed = false;
    int32_t voiceTarget = 0;
    int32_t bitrate = 40000;
    int32_t framesPerPacket = 2;
};

/**
 * Single-writer sequence lock for the capture gate.
 *
 * One writer (the JNI setPolicy call), readers on the capture thread
 * (realtime) and the send thread (non-realtime). The writer never waits;
 * readers copy and retry when they catch the writer mid-update. The copy
 * is a few words, cheap enough to retry.
 */
class GateCell {
public:
    GateCell() = default;

    GateCell(const GateCell&) = delete;
    GateCell& operator=(const GateCell&) = delete;

    // Writer (JNI) only.
    void publish(bool transmitAllowed, bool pttPressed, int32_t voiceTarget,
                 int32_t bitrate, int32_t framesPerPacket) {
        const uint32_t seq = m_seq.load(std::memory_order_relaxed);
        m_seq.store(seq + 1, std::memory_order_release); // odd: writing
        m_data.transmitAllowed = transmitAllowed;
        m_data.pttPressed = pttPressed;
        m_data.voiceTarget = voiceTarget;
        m_data.bitrate = bitrate;
        m_data.framesPerPacket = framesPerPacket;
        m_seq.store(seq + 2, std::memory_order_release); // even: done
    }

    // Reader only. Returns false when the writer was mid-update; the caller
    // retries (send thread) or keeps its last good copy (capture thread,
    // which must never wait).
    bool tryRead(GateSnapshot* out) const {
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
    GateSnapshot m_data;
};

// Capture settings (step 5a.4): the VoiceSettings side. Published by the
// JNI setSettings path, read by the capture thread each frame (to build
// InputConfig) and by the render thread (half-duplex only).
struct SettingsSnapshot {
    int32_t inputMode = 0; // 0 VAD, 1 PTT, 2 continuous (matches InputMode)
    float vadMax = 1.0f;
    float vadMin = 0.7f;
    float amplitudeBoost = 1.0f;
    bool adaptiveLevelerEnabled = true;
    // Effective value (raw && PTT), gated in Kotlin like the deleted
    // builder path, so native render reads one flag.
    bool halfDuplex = false;
};

/**
 * Single-writer sequence lock for the capture settings. Same protocol as
 * GateCell: one JNI writer, lock-free readers, retry on torn read.
 */
class SettingsCell {
public:
    SettingsCell() = default;

    SettingsCell(const SettingsCell&) = delete;
    SettingsCell& operator=(const SettingsCell&) = delete;

    // Writer (JNI) only.
    void publish(int32_t inputMode, float vadMax, float vadMin,
                 float amplitudeBoost, bool adaptiveLevelerEnabled,
                 bool halfDuplex) {
        const uint32_t seq = m_seq.load(std::memory_order_relaxed);
        m_seq.store(seq + 1, std::memory_order_release); // odd: writing
        m_data.inputMode = inputMode;
        m_data.vadMax = vadMax;
        m_data.vadMin = vadMin;
        m_data.amplitudeBoost = amplitudeBoost;
        m_data.adaptiveLevelerEnabled = adaptiveLevelerEnabled;
        m_data.halfDuplex = halfDuplex;
        m_seq.store(seq + 2, std::memory_order_release); // even: done
    }

    // Reader only. Returns false when the writer was mid-update.
    bool tryRead(SettingsSnapshot* out) const {
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
    SettingsSnapshot m_data;
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_CONFIG_H_
