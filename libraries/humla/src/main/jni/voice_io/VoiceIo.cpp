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

#include "VoiceIo.h"

#include <algorithm>
#include <cassert>
#include <cerrno>
#include <ctime>

#include "VoiceTalk.h"

namespace mumla {
namespace voice_io {

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::audio::OutputTalkState;
using mumla::audio::RenderResult;

VoiceIo::VoiceIo(AudioOutputEngine::DecoderFactory factory)
    : m_engine(std::make_unique<AudioOutputEngine>(std::move(factory),
                                                   AudioOutputEngine::DEFAULT_JITTER_MARGIN_FRAMES)) {
    sem_init(&m_sem, 0, 0);
}

VoiceIo::~VoiceIo() { sem_destroy(&m_sem); }

void VoiceIo::postIfIdle() {
    // Sequentially consistent on both sides: a push either lands before
    // render's ring check or sees the flag and carries the wake.
    if (m_idle.exchange(false, std::memory_order_seq_cst)) {
        sem_post(&m_sem);
    }
}

bool VoiceIo::awaitWork(int timeoutMs) {
    m_idle.store(true, std::memory_order_seq_cst);
    // No lost wakeups: a push that landed before this check leaves a
    // non-empty ring; one that lands after sees idle and posts.
    if (!m_inbound.empty() || !m_tunnel.empty()) {
        m_idle.store(false, std::memory_order_seq_cst);
        return true;
    }
    if (timeoutMs < 0) {
        int r;
        do {
            r = sem_wait(&m_sem);
        } while (r != 0 && errno == EINTR);
    } else if (timeoutMs == 0) {
        // Zero timeout: poll only, no wait (keeps the protocol testable
        // without sleeping).
    } else {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        ts.tv_sec += timeoutMs / 1000;
        ts.tv_nsec += static_cast<long>((timeoutMs % 1000) * 1000000L);
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec += 1;
            ts.tv_nsec -= 1000000000L;
        }
        int r;
        do {
#if defined(__ANDROID__) || (defined(__GLIBC__) && (__GLIBC__ > 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ >= 30)))
            r = sem_clockwait(&m_sem, CLOCK_MONOTONIC, &ts);
#else
            // Older host libc without sem_clockwait: fall back to realtime.
            struct timespec rt;
            clock_gettime(CLOCK_REALTIME, &rt);
            rt.tv_sec += timeoutMs / 1000;
            rt.tv_nsec += static_cast<long>((timeoutMs % 1000) * 1000000L);
            if (rt.tv_nsec >= 1000000000L) {
                rt.tv_sec += 1;
                rt.tv_nsec -= 1000000000L;
            }
            r = sem_timedwait(&m_sem, &rt);
#endif
        } while (r != 0 && errno == EINTR);
    }
    // A stop post also lands here; the caller re-renders and observes
    // running == false. Spurious wakes cost one empty quantum.
    if (!m_inbound.empty() || !m_tunnel.empty()) {
        m_idle.store(false, std::memory_order_seq_cst);
        return true;
    }
    // Timeout with both rings still empty: stay idle so the next push
    // still posts; the next await rechecks before waiting again.
    return false;
}

void VoiceIo::wakeForStop() { sem_post(&m_sem); }

bool VoiceIo::pushInbound(const InboundPacket& packet) {
    if (m_inbound.tryPush(packet)) {
        postIfIdle();
        return true;
    }
    m_droppedInbound++;
    postIfIdle();
    return false;
}

bool VoiceIo::pushTunnel(const InboundPacket& packet) {
    TunnelItem item;
    item.isRemove = false;
    item.session = packet.session;
    item.packet = packet;
    if (m_tunnel.tryPush(item)) {
        postIfIdle();
        return true;
    }
    m_droppedTunnel++;
    postIfIdle();
    return false;
}

bool VoiceIo::pushRemoveUser(int32_t session) {
    TunnelItem item;
    item.isRemove = true;
    item.session = session;
    if (m_tunnel.tryPush(item)) {
        postIfIdle();
        return true;
    }
    // A removal that finds the ring full is dropped and counted like any
    // full-ring drop; no slots are reserved for removals. The ring is only
    // full when render stalled, so tunnelled voice is being dropped too, and
    // the engine's dead-voice rule (10 missed frames) releases the slot about
    // 100 ms after its buffered audio runs out.
    m_droppedTunnel++;
    postIfIdle();
    return false;
}

void VoiceIo::drainRings() {
    InboundPacket packet;
    while (m_inbound.tryPop(&packet)) {
        m_engine->queuePacket(packet);
    }
    TunnelItem item;
    while (m_tunnel.tryPop(&item)) {
        if (item.isRemove) {
            m_engine->removeUser(item.session);
        } else {
            m_engine->queuePacket(item.packet);
        }
    }
}

void VoiceIo::setTalkLevel(int32_t session, OutputTalkState state) {
    for (size_t i = 0; i < m_levels.count; ++i) {
        if (m_levels.entries[i].session == session) {
            if (state == OutputTalkState::PASSIVE) {
                m_levels.entries[i] = m_levels.entries[m_levels.count - 1];
                m_levels.count--;
            } else {
                m_levels.entries[i].state = state;
            }
            return;
        }
    }
    if (state != OutputTalkState::PASSIVE && m_levels.count < TalkSnapshot::kMaxEntries) {
        m_levels.entries[m_levels.count].session = session;
        m_levels.entries[m_levels.count].state = state;
        m_levels.count++;
    }
}

void VoiceIo::applyTalkEvents(const RenderResult& engineResult) {
    for (size_t i = 0; i < engineResult.talkEventCount; ++i) {
        setTalkLevel(engineResult.talkEvents[i].session, engineResult.talkEvents[i].state);
    }
}

void VoiceIo::render(int16_t* out, size_t samples, RenderOut* result) {
    assert(out != nullptr || samples == 0);
    assert(result != nullptr);
    drainRings();
    size_t written = 0;
    bool silent = true;
    // The engine covers quanta up to MAX_QUANTUM_SAMPLES (60 ms); larger
    // requests split into several render calls. Every quantum's talk events
    // fold into the same level table, so nothing is lost across the split.
    // A quantum the engine reports as silent (0 samples) is zero-filled
    // here; result->samples is 0 only when every quantum was silent, so
    // the Kotlin idle wait can idle on silence.
    while (written < samples) {
        size_t want = std::min(samples - written,
                               AudioOutputEngine::MAX_QUANTUM_SAMPLES);
        RenderResult engineResult;
        m_engine->render(out + written, want, &engineResult);
        applyTalkEvents(engineResult);
        m_droppedJitter += engineResult.droppedPackets;
        if (engineResult.samples > 0) {
            silent = false;
        } else {
            std::fill(out + written, out + written + want, 0);
        }
        written += want;
    }
    result->samples = silent ? 0 : written;
    result->hasActiveVoices = m_engine->activeUserCount() > 0;
    result->droppedInbound = m_droppedInbound;
    result->droppedTunnel = m_droppedTunnel;
    result->droppedJitter = m_droppedJitter;
    result->talk = m_levels;
    publishTalk();
}

void VoiceIo::clear() {
    m_engine->clear();
    m_levels.count = 0;
    // Publish the emptied table: a control-plane reset while the notifier
    // runs must deliver passive, per voice.md#queues (the destroy path
    // covers Kotlin via clearRemote; this covers the cell).
    publishTalk();
}

bool VoiceIo::talkEqual(const TalkSnapshot& a, const TalkSnapshot& b) {
    if (a.count != b.count) {
        return false;
    }
    for (size_t i = 0; i < a.count; ++i) {
        if (a.entries[i].session != b.entries[i].session ||
            a.entries[i].state != b.entries[i].state) {
            return false;
        }
    }
    return true;
}

void VoiceIo::publishTalk() {
    if (m_talkCell == nullptr) {
        return;
    }
    if (talkEqual(m_levels, m_lastPublishedTalk) &&
        m_droppedInbound == m_lastPublishedDroppedInbound &&
        m_droppedTunnel == m_lastPublishedDroppedTunnel &&
        m_droppedJitter == m_lastPublishedDroppedJitter) {
        return;
    }
    m_talkCell->publish(m_levels, m_droppedInbound, m_droppedTunnel,
                        m_droppedJitter);
    m_lastPublishedTalk = m_levels;
    m_lastPublishedDroppedInbound = m_droppedInbound;
    m_lastPublishedDroppedTunnel = m_droppedTunnel;
    m_lastPublishedDroppedJitter = m_droppedJitter;
}

} // namespace voice_io
} // namespace mumla
