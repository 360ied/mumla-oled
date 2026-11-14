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

#include "AudioOutputEngine.h"
#include "speex_jitter.h"

#include <algorithm>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <new>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace mumla {
namespace audio {
namespace {

constexpr int kTerminatorFlagBit = 0x100;
// Delay adaptation is gated on quiet frames, approximating upstream: loud
// on-time audio must not shrink the buffer out from under itself.
constexpr float kQuietGateLevel = 0.1f;
// Saturation knee at -6 dB; linear below. See saturateSample.
constexpr float kSaturationKnee = 0.5f;
constexpr size_t kRenderEventCapacity = AudioOutputEngine::MAX_VOICES * 2;

} // namespace

struct AudioOutputEngine::Voice {
    static constexpr size_t kNoDebt = static_cast<size_t>(-1);
    int32_t session = 0;
    std::unique_ptr<IOutputDecoder> decoder;
    JitterBuffer* jitter = nullptr;
    // False when the decoder or jitter buffer could not be created at
    // construction; such a slot is never handed to a session.
    bool usable = false;
    int lastFlags = 0;
    int lastReportedState = -1;
    bool started = false;
    bool hasTerminator = false;
    // Startup gate, upstream parity (AudioOutputSpeech holds a fresh voice
    // silent on jitter-buffer availability until its moving average is
    // met): frames the gate has held so far, the total audio queued for
    // this burst (union of accepted packet intervals, so in-transit loss
    // never inflates the measured cushion), and the newest accepted end
    // that grows the union. Counting duration, not packets, keeps bundled
    // multi-frame datagrams gating by duration.
    int gateWaitedFrames = 0;
    uint32_t gateQueuedSamples = 0;
    uint32_t gateMaxEndTs = 0;
    bool gateAnchored = false;
    // Terminator latched at queue time for the gate bypass only: the drain
    // flag (hasTerminator) latches at dequeue, which a still-gated voice
    // never reaches — so a payload-carrying terminator as the burst's first
    // packet would otherwise sit out the full gate timeout.
    bool gateTerminatorQueued = false;
    int missCount = 0;
    int consecutiveErrors = 0;
    // Decoded tail that did not fit the previous quantum, plus how much of
    // it was already consumed. Served before touching the jitter buffer.
    // Fixed storage: at most one decode chunk (MAX_DECODE_SAMPLES).
    float carry[MAX_DECODE_SAMPLES] = {};
    size_t carrySize = 0;
    size_t carryPos = 0;
    // Whether the carried-over tail is concealment (vs real decode); served
    // chunks blend at real<->concealment boundaries like any other chunk.
    bool carryConcealed = false;
    // Chunk-type tracker for loss-boundary crossfades. FEC-recovered audio
    // counts as real; the silent FEC-debt placeholder never touches this.
    bool hasPrevChunk = false;
    bool prevChunkConcealed = false;
    // Last emitted samples for cross-quantum blending when a quantum starts
    // mid-transition (scratch is zeroed per quantum, so without this the
    // first chunk could not blend against the previous quantum's tail).
    float xfadeTail[XFADE_SAMPLES] = {};
    bool hasXfadeTail = false;
    // Scratch index of a reserved silent slot for a missing frame, or kNoDebt.
    // Concealment is deferred one frame so the next buffered packet — which
    // on lossy-but-alive links is usually already in the jitter buffer — can
    // reconstruct it via Opus in-band FEC. Quantum-local: always resolved or
    // PLC-filled before the quantum ends, never carried across quanta.
    size_t fecDebtPos = kNoDebt;
    // Arrival timestamp of the voice's first packet (monotonic ns), for the
    // one-shot queue-to-first-audio delay log below. Zero until set.
    int64_t firstPacketNs = 0;
    bool delayLogged = false;

    // Resets every per-burst field. Called when a slot is handed out.
    void resetForReuse() {
        lastFlags = 0;
        lastReportedState = -1;
        started = false;
        hasTerminator = false;
        gateWaitedFrames = 0;
        gateQueuedSamples = 0;
        gateMaxEndTs = 0;
        gateAnchored = false;
        gateTerminatorQueued = false;
        missCount = 0;
        consecutiveErrors = 0;
        carrySize = 0;
        carryPos = 0;
        carryConcealed = false;
        hasPrevChunk = false;
        prevChunkConcealed = false;
        hasXfadeTail = false;
        fecDebtPos = kNoDebt;
        firstPacketNs = 0;
        delayLogged = false;
    }
};

// Monotonic nanoseconds for latency diagnostics. std::chrono keeps this
// host-test-safe; only the log sink itself is Android-gated.
inline int64_t steadyNanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

AudioOutputEngine::AudioOutputEngine(DecoderFactory decoderFactory,
                                     int jitterMarginFrames)
    : m_decoderFactory(std::move(decoderFactory)),
      m_jitterMarginFrames(
          jitterMarginFrames < 0 ? 0 : jitterMarginFrames) {
    // Every table and pool slot is built here: nothing allocates once the
    // render thread starts.
    const float mul = static_cast<float>(kPi) / (2.0f * FRAME_SIZE);
    for (int i = 0; i < FRAME_SIZE; ++i) {
        const float v = std::sin(static_cast<float>(i) * mul);
        m_fadeIn[i] = v;
        m_fadeOut[FRAME_SIZE - i - 1] = v;
    }
    // Equal-power crossfade: cos^2 + sin^2 == 1 holds perceived loudness
    // flat across the blend, unlike a linear crossfade which dips mid-way.
    const float xmul = static_cast<float>(kPi) / (2.0f * XFADE_SAMPLES);
    for (int i = 0; i < XFADE_SAMPLES; ++i) {
        m_xfadeIn[i] = std::sin(static_cast<float>(i) * xmul);
        m_xfadeOut[i] = std::cos(static_cast<float>(i) * xmul);
    }

    // The shared packet store: one allocation for the whole engine, and the
    // free list that hands its slots out. If the store cannot be allocated,
    // the free list stays empty and every packet is dropped (and counted)
    // rather than falling back to per-packet heap traffic.
    m_jitterPool = new (std::nothrow)
        uint8_t[JITTER_SLOT_COUNT * MAX_PACKET_BYTES];
    if (m_jitterPool != nullptr) {
        for (size_t i = 0; i < JITTER_SLOT_COUNT; ++i) {
            m_jitterFree[i] = static_cast<int32_t>(i);
        }
        m_jitterFreeCount = JITTER_SLOT_COUNT;
    }

    for (int i = 0; i < MAX_VOICES; ++i) {
        auto voice = std::make_unique<Voice>();
        if (!m_decoderFactory) {
            break;
        }
        voice->decoder = m_decoderFactory();
        if (!voice->decoder || !voice->decoder->isValid()) {
            continue;
        }
        voice->jitter = jitter_buffer_init(FRAME_SIZE);
        if (voice->jitter == nullptr) {
            continue;
        }
        jitter_buffer_set_packet_allocator(voice->jitter, &AudioOutputEngine::jitterPoolAlloc,
                                           &AudioOutputEngine::jitterPoolFree, this);
        voice->usable = true;
        m_pool[i] = voice.release();
    }
}

AudioOutputEngine::~AudioOutputEngine() {
    // Callers must have quiesced the render thread (and any listener running
    // on it) before destruction. No talk transitions are emitted from the
    // destructor: reporting PASSIVE for dying voices would run listener code
    // on a half-destroyed object.
    for (int i = 0; i < MAX_VOICES; ++i) {
        if (m_pool[i] != nullptr) {
            jitter_buffer_destroy(m_pool[i]->jitter);
            m_pool[i]->jitter = nullptr;
            delete m_pool[i];
            m_pool[i] = nullptr;
        }
    }
    delete[] m_jitterPool;
    m_jitterPool = nullptr;
    m_jitterFreeCount = 0;
}

void AudioOutputEngine::setJitterMarginFrames(int frames) {
    m_jitterMarginFrames = frames < 0 ? 0 : frames;
}

// The UDP voice-target byte shares its numeric space with the protobuf
// AudioContext enum (NORMAL=0, SHOUT=1, WHISPER=2, LISTEN=3, INVALID=0xFF),
// so one mapping serves both transports: the Java layer forwards the raw
// target/context value as flags and the engine interprets it here.
OutputTalkState AudioOutputEngine::talkStateForFlags(int flags) {
    switch (flags & 0xFF) {
        case 0:
            return OutputTalkState::TALKING; // NORMAL
        case 1:
            return OutputTalkState::SHOUTING; // SHOUT
        case 2:
            return OutputTalkState::WHISPERING; // WHISPER
        case 3:
            return OutputTalkState::TALKING; // LISTEN: addressed, shown talking
        case 0xFF:
            return OutputTalkState::PASSIVE; // INVALID
        default:
            // Unknown target/context: show talking (fail-loud) rather than
            // whispering, which would mislabel normal speech.
            return OutputTalkState::TALKING;
    }
}

void AudioOutputEngine::recordTalk(Voice* voice, int32_t session,
                                   OutputTalkState state) {
    const int ordinal = static_cast<int>(state);
    if (voice->lastReportedState == ordinal) {
        return;
    }
    if (m_pendingTalkCount >= MAX_PENDING_TALK_EVENTS) {
        // The array sizes make this unreachable (see the header), but a full
        // array must not lose the transition *and* claim it was reported:
        // leaving lastReportedState alone lets the next opportunity report it.
        return;
    }
    voice->lastReportedState = ordinal;
    m_pendingTalks[m_pendingTalkCount].session = session;
    m_pendingTalks[m_pendingTalkCount].state = state;
    m_pendingTalkCount++;
}

// Soft-knee bus saturation with a 1.0 asymptote. A tail with value 1 and
// slope 1 at full scale under a 1.0 asymptote cannot exist (unit slope at 1
// would exceed 1 immediately), so the knee sits at -6 dB instead: linear at
// or below 0.5, then 1 - 0.5*exp(-2*(|m| - 0.5)) above. The pieces meet
// C1-smooth (value 0.5, slope 1) and approach 1.0 from below. Single
// speakers pass through untouched; overlapping speech compresses instead of
// hard-clipping.
float AudioOutputEngine::saturateSample(float m) {
    const float abs = std::fabs(m);
    if (abs <= kSaturationKnee) {
        return m;
    }
    const float sign = m >= 0.0f ? 1.0f : -1.0f;
    return sign *
           (1.0f - kSaturationKnee * std::exp(-2.0f * (abs - kSaturationKnee)));
}

// Blends a chunk head into its destination when the chunk type flips between
// real and concealment (FEC recovery counts as real). Two-sided when the
// destination already holds a full window: the emitted-side tail is rewoven
// with the new head in place. One-sided from the voice tail snapshot when
// the chunk opens a fresh quantum (the previous tail is already emitted and
// immutable, so only the new side moves, starting from the tail's last
// value to keep the joint C0). Short chunks (< XFADE_SAMPLES) skip blending;
// they only occur on partial-room appends and carry splits.
static void blendHead(float* base, size_t basePos, const float* src,
                      const float* tail, bool hasTail, bool transition,
                      const float* xfadeIn, const float* xfadeOut) {
    constexpr size_t kXf =
        static_cast<size_t>(AudioOutputEngine::XFADE_SAMPLES);
    if (!transition) {
        return;
    }
    if (basePos >= kXf) {
        for (size_t i = 0; i < kXf; ++i) {
            base[basePos - kXf + i] =
                base[basePos - kXf + i] * xfadeOut[i] + src[i] * xfadeIn[i];
        }
    } else if (basePos == 0 && hasTail) {
        // The tail is already emitted and immutable: the new side starts
        // from the tail's last value so the joint stays C0, then ramps to
        // the new chunk. (Pointwise tail[i] would step back 2 ms in time.)
        const float last = tail[kXf - 1];
        for (size_t i = 0; i < kXf; ++i) {
            base[i] = last * xfadeOut[i] + src[i] * xfadeIn[i];
        }
    }
}

void AudioOutputEngine::appendVoiceChunk(Voice* voice, const float* src,
                                         int count, float* scratch,
                                         size_t numSamples, size_t& filled,
                                         bool concealed, const float* xfadeIn,
                                         const float* xfadeOut) {
    if (count <= 0 || src == nullptr) {
        return;
    }
    const size_t room = (filled < numSamples) ? numSamples - filled : 0;
    const size_t copy = std::min<size_t>(static_cast<size_t>(count), room);
    if (copy > 0) {
        std::memcpy(scratch + filled, src, copy * sizeof(float));
        if (static_cast<int>(copy) >= XFADE_SAMPLES) {
            blendHead(scratch, filled, src, voice->xfadeTail,
                      voice->hasXfadeTail,
                      voice->hasPrevChunk &&
                          (concealed != voice->prevChunkConcealed),
                      xfadeIn, xfadeOut);
        }
        filled += copy;
        voice->hasPrevChunk = true;
        voice->prevChunkConcealed = concealed;
    }
    if (static_cast<size_t>(count) > copy) {
        // Retain the unconsumed tail for the next quantum. Carry is always
        // empty here: the fill loop serves it before touching the jitter
        // buffer, so reaching a decode implies it drained.
        const size_t tail = static_cast<size_t>(count) - copy;
        std::memcpy(voice->carry, src + copy, tail * sizeof(float));
        voice->carrySize = tail;
        voice->carryPos = 0;
        voice->carryConcealed = concealed;
    }
}

void AudioOutputEngine::writeChunkAt(Voice* voice, float* scratch, size_t pos,
                                     const float* src, int count,
                                     bool concealed, const float* xfadeIn,
                                     const float* xfadeOut) {
    if (src == nullptr || count <= 0) {
        return;
    }
    std::memcpy(scratch + pos, src, count * sizeof(float));
    if (count >= XFADE_SAMPLES) {
        blendHead(scratch, pos, src, voice->xfadeTail, voice->hasXfadeTail,
                  voice->hasPrevChunk &&
                      (concealed != voice->prevChunkConcealed),
                  xfadeIn, xfadeOut);
    }
    voice->hasPrevChunk = true;
    voice->prevChunkConcealed = concealed;
}

void AudioOutputEngine::snapshotTail(Voice* voice, const float* scratch,
                                     size_t filled) {
    constexpr size_t kXf =
        static_cast<size_t>(AudioOutputEngine::XFADE_SAMPLES);
    if (filled >= kXf) {
        std::memcpy(voice->xfadeTail, scratch + filled - kXf,
                    kXf * sizeof(float));
        voice->hasXfadeTail = true;
    } else if (filled > 0) {
        if (voice->hasXfadeTail) {
            const size_t keep = kXf - filled;
            std::memmove(voice->xfadeTail, voice->xfadeTail + filled,
                         keep * sizeof(float));
            std::memcpy(voice->xfadeTail + keep, scratch,
                        filled * sizeof(float));
        } else {
            // No history yet: oldest samples are zeros, newest is the scratch
            // head at the window end.
            std::memset(voice->xfadeTail, 0, kXf * sizeof(float));
            std::memcpy(voice->xfadeTail + kXf - filled, scratch,
                        filled * sizeof(float));
        }
        voice->hasXfadeTail = true;
    }
}

int AudioOutputEngine::jitterBufferedCount(JitterBuffer* jitter) const {
    int32_t count = 0;
    jitter_buffer_ctl(jitter, JITTER_BUFFER_GET_AVAILABLE_COUNT, &count);
    return count;
}

void* AudioOutputEngine::jitterPoolAlloc(void* ctx, uint32_t length) {
    auto* self = static_cast<AudioOutputEngine*>(ctx);
    if (length == 0 || length > MAX_PACKET_BYTES || self->m_jitterFreeCount == 0) {
        return nullptr;
    }
    const int32_t slot = self->m_jitterFree[--self->m_jitterFreeCount];
    return self->m_jitterPool +
           static_cast<size_t>(slot) * MAX_PACKET_BYTES;
}

void AudioOutputEngine::jitterPoolFree(void* ctx, void* data) {
    auto* self = static_cast<AudioOutputEngine*>(ctx);
    if (data == nullptr || self->m_jitterPool == nullptr) {
        return;
    }
    const auto* bytes = static_cast<const uint8_t*>(data);
    const std::ptrdiff_t offset = bytes - self->m_jitterPool;
    if (offset < 0 ||
        offset % static_cast<std::ptrdiff_t>(MAX_PACKET_BYTES) != 0) {
        return; // Not ours; nothing sensible to recycle.
    }
    const size_t slot =
        static_cast<size_t>(offset) / static_cast<size_t>(MAX_PACKET_BYTES);
    if (slot >= JITTER_SLOT_COUNT || self->m_jitterFreeCount >= JITTER_SLOT_COUNT) {
        return;
    }
    self->m_jitterFree[self->m_jitterFreeCount++] = static_cast<int32_t>(slot);
}

size_t AudioOutputEngine::jitterSlotsInUse() const {
    return JITTER_SLOT_COUNT - m_jitterFreeCount;
}

int AudioOutputEngine::findLookupIndex(int32_t session) const {
    for (size_t i = 0; i < m_lookupCount; ++i) {
        if (m_lookup[i].session == session) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

AudioOutputEngine::Voice* AudioOutputEngine::acquireVoice(int32_t session) {
    if (m_lookupCount >= static_cast<size_t>(MAX_VOICES)) {
        // Evict the highest session id as the newest-voice approximation: the
        // lookup is session-ordered, so long-connected speakers keep their
        // jitter history, and a join flood cannot push out the whole channel.
        // The evicted speaker is reported PASSIVE like removeUser so the UI
        // never keeps a talk state for a voice that no longer exists.
        const SessionSlot victim = m_lookup[m_lookupCount - 1];
        Voice* victimVoice = m_pool[victim.slot];
        if (victimVoice->lastReportedState != -1 &&
            victimVoice->lastReportedState !=
                static_cast<int>(OutputTalkState::PASSIVE)) {
            recordTalk(victimVoice, victim.session, OutputTalkState::PASSIVE);
        }
        releaseVoice(victim.session);
    }
    int slot = -1;
    for (int i = 0; i < MAX_VOICES; ++i) {
        if (m_pool[i] == nullptr || !m_pool[i]->usable) {
            continue;
        }
        bool taken = false;
        for (size_t j = 0; j < m_lookupCount; ++j) {
            if (m_lookup[j].slot == i) {
                taken = true;
                break;
            }
        }
        if (!taken) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        return nullptr;
    }
    Voice* voice = m_pool[slot];
    voice->resetForReuse();
    voice->session = session;
    if (voice->decoder) {
        voice->decoder->reset();
    }
    jitter_buffer_reset(voice->jitter);
    int margin = FRAME_SIZE * m_jitterMarginFrames;
    jitter_buffer_ctl(voice->jitter, JITTER_BUFFER_SET_MARGIN, &margin);

    // Keep the lookup sorted by session id: the render loop then visits
    // voices in ascending session order, as the session map used to.
    size_t pos = m_lookupCount;
    while (pos > 0 && m_lookup[pos - 1].session > session) {
        m_lookup[pos] = m_lookup[pos - 1];
        --pos;
    }
    m_lookup[pos].session = session;
    m_lookup[pos].slot = slot;
    m_lookupCount++;
    voice->firstPacketNs = steadyNanos();
    return voice;
}

void AudioOutputEngine::releaseVoice(int32_t session) {
    const int index = findLookupIndex(session);
    if (index < 0) {
        return;
    }
    const size_t idx = static_cast<size_t>(index);
    Voice* voice = m_pool[m_lookup[idx].slot];
    if (voice != nullptr) {
        voice->session = 0;
        // Return the voice's queued packet bytes to the shared store now. A
        // released voice never renders again, so anything left in its jitter
        // buffer would hold pool slots until this pool slot is reused; the
        // pre-refactor engine destroyed the buffer here and freed them at
        // once. acquireVoice resets the buffer again on reuse.
        if (voice->jitter != nullptr) {
            jitter_buffer_reset(voice->jitter);
        }
    }
    for (size_t i = idx + 1; i < m_lookupCount; ++i) {
        m_lookup[i - 1] = m_lookup[i];
    }
    m_lookupCount--;
}

void AudioOutputEngine::queuePacket(const InboundPacket& packet) {
    if (packet.length > MAX_PACKET_BYTES) {
        return;
    }
    if (packet.length == 0) {
        // Empty end-of-speech marker (terminator with no Opus payload): no
        // bytes to buffer, just flag the voice so render drains instead of
        // running the miss-expiry. Unknown sessions have nothing to end.
        if (!packet.isTerminator) {
            return;
        }
        const int index = findLookupIndex(packet.session);
        if (index >= 0) {
            Voice* voice = m_pool[m_lookup[index].slot];
            voice->hasTerminator = true;
            // This path never reaches the packet insert below, so the gate's
            // own bypass latch must be set here too: a short final burst
            // that never filled the gate still drains.
            voice->gateTerminatorQueued = true;
        }
        return;
    }
    if (!m_decoderFactory) {
        return;
    }

    Voice* voice = nullptr;
    const int existing = findLookupIndex(packet.session);
    if (existing >= 0) {
        voice = m_pool[m_lookup[existing].slot];
    } else {
        voice = acquireVoice(packet.session);
    }
    if (voice == nullptr || voice->decoder == nullptr) {
        return;
    }

    // Drop packets the decoder cannot size: a fabricated span corrupts
    // jitter timing for the whole voice.
    const int span = voice->decoder->packetSampleCount(packet.data,
                                                       packet.length);
    if (span <= 0) {
        return;
    }
    // Sequence counts 10 ms frames; timestamp and span share sample units,
    // so bundled datagrams imply consecutive seq (seq+i per chained frame)
    // on the Java side. Wrap-around of either is benign: the jitter buffer
    // only compares differences.
    const auto timestamp = static_cast<uint32_t>(FRAME_SIZE * packet.sequence);

    JitterBufferPacket jitterPacket;
    jitterPacket.data = reinterpret_cast<char*>(
        const_cast<uint8_t*>(packet.data));
    jitterPacket.len = static_cast<uint32_t>(packet.length);
    jitterPacket.timestamp = timestamp;
    jitterPacket.span = static_cast<uint32_t>(span);
    // JitterBufferPacket.sequence is 16 bit; mask explicitly so the
    // truncation is visible here rather than buried in a narrowing cast.
    jitterPacket.sequence = static_cast<uint16_t>(packet.sequence & 0xFFFFu);
    jitterPacket.user_data =
        (packet.flags & 0xFF) | (packet.isTerminator ? kTerminatorFlagBit : 0);
    // The jitter buffer copies packet bytes into the shared slot pool.
    if (jitter_buffer_put(voice->jitter, &jitterPacket) != JITTER_BUFFER_OK) {
        // Pool exhausted: the packet is dropped and counted. The gate must not
        // credit audio that never arrived, so nothing below runs.
        if (m_droppedPackets < UINT32_MAX) {
            ++m_droppedPackets;
        }
        return;
    }
    // Grow the startup gate's queued-audio union: only newly covered audio
    // counts, so a sequence gap (in-transit loss) never inflates the
    // measured cushion the way a wall-clock span would. Signed diffs keep
    // the comparisons wrap-safe across the uint32 timestamp rollover,
    // speex-style.
    if (!voice->gateAnchored) {
        voice->gateMaxEndTs = timestamp + jitterPacket.span;
        voice->gateQueuedSamples = jitterPacket.span;
        voice->gateAnchored = true;
    } else if (static_cast<int32_t>(timestamp - voice->gateMaxEndTs) >= 0) {
        // Entirely past the current end (a normal in-order arrival, gap or
        // not): all of it is new audio.
        voice->gateQueuedSamples += jitterPacket.span;
        voice->gateMaxEndTs = timestamp + jitterPacket.span;
    } else if (static_cast<int32_t>(timestamp + jitterPacket.span -
                                    voice->gateMaxEndTs) > 0) {
        // Overlaps the covered interval and extends it: only the extension
        // counts (late, out-of-order fills of an earlier hole add nothing).
        voice->gateQueuedSamples += static_cast<uint32_t>(
            static_cast<int32_t>(timestamp + jitterPacket.span -
                                 voice->gateMaxEndTs));
        voice->gateMaxEndTs = timestamp + jitterPacket.span;
    }
    // Latch the gate's terminator bypass at queue time: a payload-carrying
    // terminator as the first packet of a burst must skip the gate, and the
    // dequeue-time hasTerminator latch never runs while gated.
    if (packet.isTerminator) {
        voice->gateTerminatorQueued = true;
    }
}

void AudioOutputEngine::render(int16_t* out, size_t samples,
                               RenderResult* result) {
    if (result == nullptr) {
        return;
    }
    result->samples = 0;
    result->talkEventCount = 0;
    result->droppedPackets = m_droppedPackets;
    m_droppedPackets = 0;

    // Deliver the transitions accumulated since the previous call (queue-time
    // evictions, removals, clears) first; mix-time transitions join the same
    // result below, once this quantum has produced them. Whatever does not
    // fit in the result stays pending for the next render (bounded by
    // MAX_PENDING_TALK_EVENTS).
    auto drainPending = [this, result]() {
        const size_t room = kRenderEventCapacity - result->talkEventCount;
        const size_t drain = std::min(m_pendingTalkCount, room);
        for (size_t i = 0; i < drain; ++i) {
            result->talkEvents[result->talkEventCount++] = m_pendingTalks[i];
        }
        if (drain > 0) {
            for (size_t i = drain; i < m_pendingTalkCount; ++i) {
                m_pendingTalks[i - drain] = m_pendingTalks[i];
            }
            m_pendingTalkCount -= drain;
        }
    };
    drainPending();

    if (out == nullptr || samples == 0) {
        return;
    }
    assert(samples <= MAX_QUANTUM_SAMPLES);
    if (samples > MAX_QUANTUM_SAMPLES) {
        samples = MAX_QUANTUM_SAMPLES;
    }
    if (m_lookupCount == 0) {
        return;
    }

    std::fill(m_mix, m_mix + samples, 0.0f);

    m_deadSessionCount = 0;
    bool anyMixed = false;
    bool anyGating = false;

    // Ascending session order, as the session map supplied before the pool.
    for (size_t sessionIndex = 0; sessionIndex < m_lookupCount;
         ++sessionIndex) {
        Voice* voice = m_pool[m_lookup[sessionIndex].slot];
        // Captured before the gate: a gate-timeout force-start must still
        // count as this voice's first audio, so its onset fades in.
        const bool startedAtEntry = voice->started;
        // Startup gate (upstream parity with AudioOutputSpeech's ts==0
        // availability check): hold a fresh voice silent until margin+1
        // frames of audio are actually queued, counting the union of
        // accepted packet intervals so in-transit loss never opens the
        // gate early. Without it, the first jitter_buffer_get syncs the
        // pointer to the oldest packet
        // and returns it immediately, so the render loop — free-running
        // into a drained AudioTrack after idle — outruns the 10 ms arrival
        // cadence: every empty get becomes a concealment miss, the
        // advancing pointer stales the not-yet-arrived packets (dropped
        // on put), and the poisoned arrival histogram pulls the pointer
        // back as interpolation, i.e. yet more concealment. Gating gives
        // the arrival clock the lead the margin promises. Gated quanta
        // still emit zero PCM so the caller keeps feeding — and pacing
        // on — the track while the buffer fills. A terminator bypasses
        // the gate so a short final burst drains instead of timing out.
        if (!voice->started && !voice->gateTerminatorQueued &&
            voice->gateQueuedSamples <
                static_cast<uint32_t>((m_jitterMarginFrames + 1) *
                                      FRAME_SIZE)) {
            voice->gateWaitedFrames +=
                static_cast<int>(samples / FRAME_SIZE);
            if (voice->gateWaitedFrames < GATE_TIMEOUT_FRAMES) {
                anyGating = true;
                continue;
            }
            // Gate timeout (upstream grants the gate 20 frames as well):
            // the stream trickles in slower than the gate fills. Force-
            // start so the miss expiry below retires the voice instead of
            // parking it silent forever.
            voice->started = true;
        }
        std::fill(m_voiceScratch, m_voiceScratch + samples, 0.0f);
        size_t filled = 0;
        bool producedAudio = false;

        while (filled < samples) {
            // Serve decoded carryover before touching the jitter buffer.
            if (voice->carryPos < voice->carrySize) {
                const size_t avail = voice->carrySize - voice->carryPos;
                const size_t count = std::min(avail, samples - filled);
                appendVoiceChunk(voice, voice->carry + voice->carryPos,
                                 static_cast<int>(count), m_voiceScratch,
                                 samples, filled, voice->carryConcealed,
                                 m_xfadeIn, m_xfadeOut);
                voice->carryPos += count;
                producedAudio = true;
                if (voice->carryPos >= voice->carrySize) {
                    voice->carrySize = 0;
                    voice->carryPos = 0;
                    voice->carryConcealed = false;
                }
                continue;
            }

            char packetBytes[MAX_PACKET_BYTES];
            JitterBufferPacket packet;
            packet.data = packetBytes;
            packet.len = sizeof(packetBytes);
            int32_t startOffset = 0;
            const int getResult = jitter_buffer_get(voice->jitter, &packet,
                                                    FRAME_SIZE, &startOffset);
            if (getResult == JITTER_BUFFER_OK) {
                voice->missCount = 0;
                voice->lastFlags = packet.user_data & 0xFF;
                if (packet.user_data & kTerminatorFlagBit) {
                    voice->hasTerminator = true;
                }
                voice->started = true;
                producedAudio = true;
                // A deferred miss may be recoverable: when this packet starts
                // exactly at the pointer (the common single-loss case) its
                // in-band FEC data reconstructs the missing frame — our
                // encoder always sends LBRR, as do modern desktop clients.
                // Anything else (jitter jump, FEC miss) falls back to
                // concealment for the debt slot below.
                if (voice->fecDebtPos != Voice::kNoDebt) {
                    const size_t debtPos = voice->fecDebtPos;
                    voice->fecDebtPos = Voice::kNoDebt;
                    bool recovered = false;
                    if (startOffset == 0) {
                        const int fec = voice->decoder->decodeFloat(
                            reinterpret_cast<uint8_t*>(packet.data),
                            packet.len, m_frameScratch, FRAME_SIZE, 1);
                        if (fec == FRAME_SIZE) {
                            writeChunkAt(voice, m_voiceScratch, debtPos,
                                         m_frameScratch, fec, false, m_xfadeIn,
                                         m_xfadeOut);
                            recovered = true;
                        }
                    }
                    if (!recovered) {
                        const int debtPlc =
                            voice->decoder->decodeConcealment(m_frameScratch,
                                                              FRAME_SIZE);
                        if (debtPlc > 0) {
                            writeChunkAt(voice, m_voiceScratch, debtPos,
                                         m_frameScratch, debtPlc, true,
                                         m_xfadeIn, m_xfadeOut);
                        }
                        // debtPlc <= 0: wedged decoder; the slot stays the
                        // reserved silence and expiry advances regardless.
                    }
                }
                const int decoded = voice->decoder->decodeFloat(
                    reinterpret_cast<uint8_t*>(packet.data), packet.len,
                    m_frameScratch, MAX_DECODE_SAMPLES, 0);
                if (decoded > 0) {
                    voice->consecutiveErrors = 0;
                    float peak = 0.0f;
                    for (int i = 0; i < decoded; ++i) {
                        peak = std::max(peak, std::fabs(m_frameScratch[i]));
                    }
                    if (peak < kQuietGateLevel) {
                        jitter_buffer_update_delay(voice->jitter, nullptr,
                                                   nullptr);
                    }
                    appendVoiceChunk(voice, m_frameScratch, decoded,
                                     m_voiceScratch, samples, filled, false,
                                     m_xfadeIn, m_xfadeOut);
                    // Round ticks up: a bundle covering a partial frame still
                    // advances past the whole frame.
                    const int ticks = (decoded + FRAME_SIZE - 1) / FRAME_SIZE;
                    for (int i = 0; i < ticks; ++i) {
                        jitter_buffer_tick(voice->jitter);
                    }
                } else {
                    // Failed decode: recycle the decoder before it wedges,
                    // then conceal this slot so rendering still advances.
                    if (++voice->consecutiveErrors >=
                        MAX_CONSECUTIVE_DECODE_ERRORS) {
                        voice->decoder->reset();
                        voice->consecutiveErrors = 0;
                    }
                    const int concealed = voice->decoder->decodeConcealment(
                        m_frameScratch, FRAME_SIZE);
                    jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
                    jitter_buffer_tick(voice->jitter);
                    if (concealed > 0) {
                        appendVoiceChunk(voice, m_frameScratch, concealed,
                                         m_voiceScratch, samples, filled, true,
                                         m_xfadeIn, m_xfadeOut);
                    }
                    // concealed <= 0: wedged decoder; this slot stays scratch
                    // silence and expiry still advances via the tick above.
                }
                continue;
            }

            // Missing or late packet.
            // A miss reserves a silent slot and defers concealment one frame:
            // the next packet is usually already jitter-buffered on a
            // lossy-but-alive link, and the OK path above reconstructs this
            // slot from its in-band FEC data. A nearly-full quantum with no
            // room for a whole slot conceals immediately instead.
            // A miss arriving with debt outstanding settles the old slot
            // first: it is now two frames behind the pointer, beyond what the
            // next packet's FEC can cover (LBRR reconstructs only the
            // immediately previous frame), so the old debt is concealed now
            // and the current miss starts a fresh debt below. Burst loss thus
            // chains correctly instead of playing the wrong frame's audio in
            // a stale slot.
            if (voice->fecDebtPos != Voice::kNoDebt) {
                const size_t debtPos = voice->fecDebtPos;
                voice->fecDebtPos = Voice::kNoDebt;
                const int debtPlc = voice->decoder->decodeConcealment(
                    m_frameScratch, FRAME_SIZE);
                if (debtPlc > 0) {
                    writeChunkAt(voice, m_voiceScratch, debtPos, m_frameScratch,
                                 debtPlc, true, m_xfadeIn, m_xfadeOut);
                }
            }
            if (filled + FRAME_SIZE <= samples) {
                // Scratch is zeroed beyond filled, so the slot starts silent
                // without a fill; a failed recovery keeps it that way.
                voice->fecDebtPos = filled;
                filled += FRAME_SIZE;
                jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
                jitter_buffer_tick(voice->jitter);
                producedAudio = true;
                if (++voice->missCount > DEAD_MISS_FRAMES) {
                    break;
                }
                continue;
            }
            const int concealed = voice->decoder->decodeConcealment(
                m_frameScratch, FRAME_SIZE);
            jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
            jitter_buffer_tick(voice->jitter);
            if (concealed > 0) {
                appendVoiceChunk(voice, m_frameScratch, concealed,
                                 m_voiceScratch, samples, filled, true,
                                 m_xfadeIn, m_xfadeOut);
            }
            // concealed <= 0: keep this slot silent; missCount below still
            // expires the voice so a wedged decoder cannot spin forever.
            producedAudio = true;
            if (++voice->missCount > DEAD_MISS_FRAMES) {
                break;
            }
        }

        // FEC debt never crosses quanta: an unrecovered slot is concealed now
        // so the decoder's PLC state advances exactly as if concealment had
        // run inline, and the next quantum starts from a clean tracker.
        if (voice->fecDebtPos != Voice::kNoDebt) {
            const size_t debtPos = voice->fecDebtPos;
            voice->fecDebtPos = Voice::kNoDebt;
            const int debtPlc =
                voice->decoder->decodeConcealment(m_frameScratch, FRAME_SIZE);
            if (debtPlc > 0) {
                writeChunkAt(voice, m_voiceScratch, debtPos, m_frameScratch,
                             debtPlc, true, m_xfadeIn, m_xfadeOut);
            }
        }
        // A terminator drains instead of cutting: the voice lives until its
        // buffered packets (peeked via the available count) and any decoded
        // carryover are played out. Only then does this quantum carry the
        // fade-out and the voice earn PASSIVE.
        const bool carryDrained = voice->carryPos >= voice->carrySize;
        const bool drained = voice->hasTerminator && carryDrained &&
                             jitterBufferedCount(voice->jitter) == 0;
        const bool finishing =
            drained || voice->missCount > DEAD_MISS_FRAMES;
        if (producedAudio && filled > 0) {
            if (!startedAtEntry) {
                const size_t fade = std::min<size_t>(FRAME_SIZE, filled);
                for (size_t i = 0; i < fade; ++i) {
                    m_voiceScratch[i] *= m_fadeIn[i];
                }
            }
            if (finishing) {
                const size_t fade = std::min<size_t>(FRAME_SIZE, filled);
                const size_t base = filled - fade;
                for (size_t i = 0; i < fade; ++i) {
                    m_voiceScratch[base + i] *= m_fadeOut[i];
                }
            }
            for (size_t i = 0; i < filled; ++i) {
                m_mix[i] += m_voiceScratch[i];
            }
            // Snapshot the emitted (post-fade) tail so the next quantum can
            // blend a boundary chunk against it.
            snapshotTail(voice, m_voiceScratch, filled);
            // One-shot queue-to-first-audio diagnostic: arrival-to-mix delay
            // for the voice lifetime (jitter fill + pre-roll + batching; the
            // AudioTrack sink depth adds on top). Host builds skip the sink.
            if (!voice->delayLogged && voice->firstPacketNs != 0) {
                voice->delayLogged = true;
#ifdef __ANDROID__
                const double delayMs =
                    (steadyNanos() - voice->firstPacketNs) / 1000000.0;
                __android_log_print(
                    ANDROID_LOG_VERBOSE, "AudioOutputEngine",
                    "voice %d first audio %.1f ms after queue (%d buffered)",
                    voice->session, delayMs,
                    jitterBufferedCount(voice->jitter));
#endif
            }
            recordTalk(voice, voice->session, talkStateForFlags(voice->lastFlags));
            anyMixed = true;
        }

        if (finishing) {
            if (m_deadSessionCount < static_cast<size_t>(MAX_VOICES)) {
                m_deadSessions[m_deadSessionCount++] = voice->session;
            }
        }
    }

    for (size_t i = 0; i < m_deadSessionCount; ++i) {
        const int32_t session = m_deadSessions[i];
        const int index = findLookupIndex(session);
        if (index >= 0) {
            Voice* voice = m_pool[m_lookup[index].slot];
            recordTalk(voice, session, OutputTalkState::PASSIVE);
            releaseVoice(session);
        }
    }

    // Mix-time transitions recorded above join this result right behind the
    // queue-time ones.
    drainPending();

    for (size_t i = 0; i < samples; ++i) {
        const float sample = saturateSample(m_mix[i]) * 32767.0f;
        if (!std::isfinite(sample)) {
            out[i] = 0;
            continue;
        }
        const long rounded = std::lroundf(sample);
        const long clamped = std::clamp<long>(rounded, -32768L, 32767L);
        out[i] = static_cast<int16_t>(clamped);
    }

    if (!anyMixed && !anyGating) {
        // Nothing audible this quantum: report silence so Java idles
        // instead of writing zeros. Gated quanta are deliberately emitted
        // as zero PCM — the caller keeps the AudioTrack fed, so its write
        // backpressure paces the render loop while the jitter buffer
        // fills and the first real quantum lands behind a primed track.
        return;
    }
    result->samples = samples;
}

void AudioOutputEngine::removeUser(int32_t session) {
    const int index = findLookupIndex(session);
    if (index < 0) {
        return;
    }
    Voice* voice = m_pool[m_lookup[index].slot];
    if (voice->lastReportedState != -1 &&
        voice->lastReportedState != static_cast<int>(OutputTalkState::PASSIVE)) {
        recordTalk(voice, session, OutputTalkState::PASSIVE);
    }
    releaseVoice(session);
}

void AudioOutputEngine::clear() {
    while (m_lookupCount > 0) {
        const int32_t session = m_lookup[0].session;
        Voice* voice = m_pool[m_lookup[0].slot];
        if (voice->lastReportedState != -1 &&
            voice->lastReportedState !=
                static_cast<int>(OutputTalkState::PASSIVE)) {
            recordTalk(voice, session, OutputTalkState::PASSIVE);
        }
        releaseVoice(session);
    }
}

size_t AudioOutputEngine::activeUserCount() const {
    return m_lookupCount;
}

} // namespace audio
} // namespace mumla
