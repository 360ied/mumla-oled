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

#ifndef MUMLA_VOICE_NOTIFIER_H_
#define MUMLA_VOICE_NOTIFIER_H_

#include <chrono>
#include <cstdint>
#include <functional>
#include <thread>

#include "VoiceTalk.h"

namespace mumla {
namespace voice_io {

/**
 * Talk notifier (step 5a.3a).
 *
 * Wakes every interval (20 ms in production), reads the latest talk
 * publication through the sequence lock, and delivers it to the sink in one
 * call — but only when the version changed since the last delivery, so one
 * delivery is exactly one Kotlin call and an idle session costs no wakeups.
 * A talking spell shorter than one interval can go unseen; real
 * transmissions hold far longer (250 ms after voice activity, 150 ms after
 * push-to-talk) and a voice stays talking until it misses 10 frames, so no
 * stale state survives a miss.
 *
 * The sink is a callback so the core stays host-testable: the JNI layer
 * passes a callback that posts the snapshot to Kotlin, and the render thread
 * never calls it. notifyNow() runs one delivery synchronously for tests;
 * production also runs the thread loop. Stop joins the thread; delivery
 * after stop never happens.
 */
class VoiceNotifier {
public:
    using Sink = std::function<void(const TalkPublication&)>;
    static constexpr std::chrono::milliseconds kInterval{20};

    VoiceNotifier(const TalkCell* cell, Sink sink,
                  std::chrono::milliseconds interval = kInterval);

    VoiceNotifier(const VoiceNotifier&) = delete;
    VoiceNotifier& operator=(const VoiceNotifier&) = delete;

    ~VoiceNotifier();

    // Thread hooks run on the notifier thread around the loop (start before
    // the first delivery, exit after the last). The JNI layer attaches to
    // the JVM here once per thread lifetime; host tests leave them empty.
    void setThreadHooks(std::function<void()> onStart, std::function<void()> onExit);

    void start();
    void stop();

    // One delivery attempt, synchronous. Returns true when a newer snapshot
    // reached the sink. Tests drive this directly for determinism.
    bool notifyNow();

private:
    void loop();

    const TalkCell* m_cell;
    Sink m_sink;
    std::chrono::milliseconds m_interval;
    std::function<void()> m_onStart;
    std::function<void()> m_onExit;
    std::thread m_thread;
    std::atomic<bool> m_running{false};
    uint64_t m_deliveredVersion = 0;
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_NOTIFIER_H_
