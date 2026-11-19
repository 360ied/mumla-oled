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
 * PolicyCell: one JNI writer, lock-free readers, retry on torn read.
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
