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

#include "VoiceConfig.h"
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

VoiceIo::~VoiceIo() = default;

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
    // Half duplex (step 5a.4): while the effective flag is set and the
    // capture thread reports self-talking, write silence instead of the
    // mix. The engines stay unaware of it. Seqlock try-once keeps a torn
    // read from stalling render; the atomic needs no lock on any thread.
    bool halfDuplex = m_lastHalfDuplex;
    if (m_settingsCell != nullptr) {
        SettingsSnapshot fresh;
        if (m_settingsCell->tryRead(&fresh)) {
            halfDuplex = fresh.halfDuplex;
            m_lastHalfDuplex = halfDuplex;
        }
    }
    const bool selfTalking =
        m_selfTalking != nullptr &&
        m_selfTalking->load(std::memory_order_acquire);
    const bool duplexMuted = halfDuplex && selfTalking;
    size_t written = 0;
    bool silent = true;
    result->nonSilentFrames = 0;
    constexpr size_t kFrameSamples =
        static_cast<size_t>(AudioOutputEngine::FRAME_SIZE);
    auto markAllFrames = [&](size_t start, size_t sampleCount) {
        const size_t frameCount =
            (sampleCount + kFrameSamples - 1) / kFrameSamples;
        const size_t baseFrame = start / kFrameSamples;
        for (size_t frame = 0; frame < frameCount; ++frame) {
            const size_t bit = baseFrame + frame;
            if (bit < 64) {
                result->nonSilentFrames |= 1ULL << bit;
            }
        }
    };
    // The engine covers quanta up to MAX_QUANTUM_SAMPLES (60 ms); larger
    // requests split into several render calls. Every quantum's talk events
    // fold into the same level table, so nothing is lost across the split.
    // A quantum the engine reports as silent (0 samples) is zero-filled
    // here; result->samples is 0 only when every quantum was silent. The
    // AAudio callback writes these zeros through silence so the output
    // stream stays active, and the JNI test entry reports silence as 0.
    // nonSilentFrames pins which 10 ms frames within a quantum must be
    // reported as non-silent, so a burst that copies only a silent carried
    // frame can still return 0. A quantum with reported audio but all-zero
    // PCM (startup gating, half duplex, or decoded silence) marks every
    // frame, preserving 5a's pacing contract.
    while (written < samples) {
        size_t want = std::min(samples - written,
                               AudioOutputEngine::MAX_QUANTUM_SAMPLES);
        RenderResult engineResult;
        m_engine->render(out + written, want, &engineResult);
        applyTalkEvents(engineResult);
        m_droppedJitter += engineResult.droppedPackets;
        if (duplexMuted) {
            // Half-duplex self-talk: silence the mix, but still fold talk
            // events above so remote levels keep updating underneath.
            // Report non-silent when the engine had audio, preserving the
            // Kotlin pacing contract (rendered > 0 drives the pacer, not
            // the idle wait), exactly as AudioOutput's fill-after-render.
            std::fill(out + written, out + written + want, 0);
            if (engineResult.samples > 0) {
                silent = false;
                markAllFrames(written, want);
            }
        } else if (engineResult.samples > 0) {
            bool sawNonZero = false;
            const size_t frameCount =
                (want + kFrameSamples - 1) / kFrameSamples;
            for (size_t frame = 0; frame < frameCount; ++frame) {
                const size_t begin = frame * kFrameSamples;
                const size_t end = std::min(want, begin + kFrameSamples);
                bool nonZero = false;
                for (size_t i = begin; i < end; ++i) {
                    if (out[written + i] != 0) {
                        nonZero = true;
                        break;
                    }
                }
                if (nonZero) {
                    sawNonZero = true;
                    const size_t bit = written / kFrameSamples + frame;
                    if (bit < 64) {
                        result->nonSilentFrames |= 1ULL << bit;
                    }
                }
            }
            if (!sawNonZero) {
                markAllFrames(written, want);
            }
            silent = false;
        } else {
            std::fill(out + written, out + written + want, 0);
        }
        written += want;
    }
    result->samples = silent ? 0 : written;
    result->droppedInbound = m_droppedInbound;
    result->droppedTunnel = m_droppedTunnel;
    result->droppedJitter = m_droppedJitter;
    result->talk = m_levels;
    publishTalk();
}

void VoiceIo::clear() {
    // Drain stale rings first (a previous session's unrendered voice must
    // never play into the next one), counting the reset-discards as drops:
    // the audio arrived but will never be heard. Draining queues into the
    // engine and engine.clear() then releases the voices unmixed.
    InboundPacket packet;
    while (m_inbound.tryPop(&packet)) {
        m_droppedInbound++;
    }
    TunnelItem item;
    while (m_tunnel.tryPop(&item)) {
        m_droppedTunnel++;
    }
    m_engine->clear();
    m_levels.count = 0;
    // Publish the emptied table: a control-plane reset while the notifier
    // runs must deliver passive, per voice.md#queues (Kotlin clears the
    // service publisher after the render join; this covers the cell, and
    // the version gate keeps a running notifier from resurrecting stale
    // levels afterwards since no new renders means no new versions).
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
