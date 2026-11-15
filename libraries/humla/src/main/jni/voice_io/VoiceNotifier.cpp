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

#include "VoiceNotifier.h"

namespace mumla {
namespace voice_io {

VoiceNotifier::VoiceNotifier(const TalkCell* cell, Sink sink,
                             std::chrono::milliseconds interval)
    : m_cell(cell), m_sink(std::move(sink)), m_interval(interval) {}

VoiceNotifier::~VoiceNotifier() { stop(); }

void VoiceNotifier::start() {
    bool expected = false;
    if (!m_running.compare_exchange_strong(expected, true)) {
        return;
    }
    m_thread = std::thread(&VoiceNotifier::loop, this);
}

void VoiceNotifier::stop() {
    bool expected = true;
    if (!m_running.compare_exchange_strong(expected, false)) {
        return;
    }
    if (m_thread.joinable()) {
        m_thread.join();
    }
}

bool VoiceNotifier::notifyNow() {
    TalkPublication pub;
    if (!m_cell->tryRead(&pub)) {
        return false;
    }
    if (pub.version == m_deliveredVersion) {
        return false;
    }
    m_deliveredVersion = pub.version;
    m_sink(pub);
    return true;
}

void VoiceNotifier::loop() {
    while (m_running.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(m_interval);
        if (!m_running.load(std::memory_order_relaxed)) {
            break;
        }
        notifyNow();
    }
}

} // namespace voice_io
} // namespace mumla
