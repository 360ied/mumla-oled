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

#ifndef MUMLA_VOICE_IO_H_
#define MUMLA_VOICE_IO_H_

#include <cstddef>
#include <cstdint>

#include "AudioOutputEngine.h"
#include "VoiceRings.h"

namespace mumla {
namespace voice_io {

class TalkCell;

/**
 * Native voice I/O core (step 5a.3a: native render).
 *
 * Owns the output engine and the two inbound rings; the render thread is the
 * only thread that touches the engine. Producers never reach the engine:
 * UDP frames arrive via pushInbound, tunnelled frames via pushTunnel, and
 * user removals via pushRemoveUser as a control item on the tunnel ring, so
 * a removal stays ordered after that user's already-queued tunnelled voice.
 * The render entry point drains both rings into queuePacket and renders at
 * most MAX_QUANTUM_SAMPLES (60 ms) per engine call, splitting larger
 * requests. Talk state travels as levels, not events: render folds the
 * engine's per-quantum transitions into a level table (absent means
 * passive) plus cumulative drop counts, which the notifier thread (commit 2)
 * delivers to Kotlin.
 *
 * Threading: pushInbound has one producer (the UDP path), pushTunnel and
 * pushRemoveUser share one logical producer (the session message coroutine),
 * render has one consumer (the render thread). No locks, no allocation on
 * the render path, no Kotlin calls from render (the notifier calls Kotlin,
 * never render). Jitter margin is the I/O layer's constant; half duplex
 * stays in Kotlin's AudioOutput until capture moves in 5a.4.
 */
class VoiceIo {
public:
    // Control item on the tunnel ring: a removal, ordered after tunnelled
    // voice from the leaving user already queued on the same ring.
    struct TunnelItem {
        bool isRemove = false;
        int32_t session = 0;
        mumla::audio::InboundPacket packet;
    };

    // Talk level table: the complete remote talk state after this render.
    // Absent means passive; the notifier publishes the whole table, so a
    // slow reader sees a newer table, never a contradiction or a gap.
    struct TalkSnapshot {
        static constexpr size_t kMaxEntries = mumla::audio::AudioOutputEngine::MAX_VOICES;
        struct Entry {
            int32_t session = 0;
            mumla::audio::OutputTalkState state = mumla::audio::OutputTalkState::PASSIVE;
        };
        Entry entries[kMaxEntries] = {};
        size_t count = 0;
    };

    // Render result for the Kotlin idle wait and the drop-count log.
    struct RenderOut {
        size_t samples = 0; // mixed PCM samples written to out (0 when silent)
        bool hasActiveVoices = false; // the idle wait reads this, not an engine call
        uint64_t droppedInbound = 0; // cumulative full-ring drops
        uint64_t droppedTunnel = 0; // cumulative full-ring drops (voice + removals)
        uint64_t droppedJitter = 0; // cumulative pool-exhausted drops from the engine
        TalkSnapshot talk;
    };

    explicit VoiceIo(mumla::audio::AudioOutputEngine::DecoderFactory factory = nullptr);

    VoiceIo(const VoiceIo&) = delete;
    VoiceIo& operator=(const VoiceIo&) = delete;

    // Producer side (never the render thread). Each ring keeps one producer:
    // overlapping old and new UDP coroutines during a UDP restart are
    // serialized by AudioOutput's monitor in Kotlin, and the tunnel callers
    // share the session message coroutine. False means the ring was full;
    // the item is dropped and counted, never blocks.
    bool pushInbound(const mumla::audio::InboundPacket& packet);
    bool pushTunnel(const mumla::audio::InboundPacket& packet);
    bool pushRemoveUser(int32_t session);

    /**
     * Render entry point (render thread only).
     *
     * Drains the inbound ring then the tunnel ring into queuePacket,
     * applying removals inline, then renders up to `samples` PCM into `out`
     * in quanta of at most MAX_QUANTUM_SAMPLES. Folds the engine's talk
     * transitions into the level table in `result`. Never attaches to the
     * JVM and never calls into Kotlin.
     */
    void render(int16_t* out, size_t samples, RenderOut* result);

    // Attaches the talk cell render publishes into after every render.
    // Control thread only, while render is stopped (no race: render is the
    // only reader of this pointer and it never runs concurrently).
    void setTalkCell(TalkCell* cell) { m_talkCell = cell; }

    // Destroys queued state; called on the control thread only while render
    // is stopped (asserted in debug builds by the JNI lifecycle in commit 2).
    void clear();

private:
    void drainRings();
    void applyTalkEvents(const mumla::audio::RenderResult& engineResult);
    void setTalkLevel(int32_t session, mumla::audio::OutputTalkState state);

    std::unique_ptr<mumla::audio::AudioOutputEngine> m_engine;
    SpscRing<mumla::audio::InboundPacket, kInboundRingCapacity> m_inbound;
    SpscRing<TunnelItem, kTunnelRingCapacity> m_tunnel;
    TalkCell* m_talkCell = nullptr;
    // Level table parallel to the engine's voices, render-thread only.
    TalkSnapshot m_levels;
    uint64_t m_droppedInbound = 0;
    uint64_t m_droppedTunnel = 0;
    uint64_t m_droppedJitter = 0;
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_IO_H_
