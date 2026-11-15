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

#include "VoiceTalk.h"

namespace mumla {
namespace voice_io {

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::audio::OutputTalkState;
using mumla::audio::RenderResult;

VoiceIo::VoiceIo(AudioOutputEngine::DecoderFactory factory)
    : m_engine(std::make_unique<AudioOutputEngine>(std::move(factory),
                                                   AudioOutputEngine::DEFAULT_JITTER_MARGIN_FRAMES)) {}

bool VoiceIo::pushInbound(const InboundPacket& packet) {
    if (m_inbound.tryPush(packet)) {
        return true;
    }
    m_droppedInbound++;
    return false;
}

bool VoiceIo::pushTunnel(const InboundPacket& packet) {
    TunnelItem item;
    item.isRemove = false;
    item.session = packet.session;
    item.packet = packet;
    if (m_tunnel.tryPush(item)) {
        return true;
    }
    m_droppedTunnel++;
    return false;
}

bool VoiceIo::pushRemoveUser(int32_t session) {
    TunnelItem item;
    item.isRemove = true;
    item.session = session;
    if (m_tunnel.tryPush(item)) {
        return true;
    }
    // A removal that finds the ring full is dropped and counted like any
    // full-ring drop; no slots are reserved for removals. The ring is only
    // full when render stalled, so tunnelled voice is being dropped too, and
    // the engine's dead-voice rule (10 missed frames) releases the slot about
    // 100 ms after its buffered audio runs out.
    m_droppedTunnel++;
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
