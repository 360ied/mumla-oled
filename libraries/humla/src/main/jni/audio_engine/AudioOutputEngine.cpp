/*
 * Copyright (C) 2026 Mumla Developers
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
#include <cmath>
#include <cstring>

namespace mumla {
namespace audio {
namespace {

constexpr int kTerminatorFlagBit = 0x100;
// Delay adaptation is gated on quiet frames, approximating upstream: loud
// on-time audio must not shrink the buffer out from under itself.
constexpr float kQuietGateLevel = 0.1f;
// Saturation knee at -6 dB; linear below. See saturateSample.
constexpr float kSaturationKnee = 0.5f;

} // namespace

struct AudioOutputEngine::Voice {
    static constexpr size_t kNoDebt = static_cast<size_t>(-1);
    int32_t session = 0;
    std::unique_ptr<IOutputDecoder> decoder;
    JitterBuffer* jitter = nullptr;
    int lastFlags = 0;
    int lastReportedState = -1;
    bool started = false;
    bool hasTerminator = false;
    int quietFrames = 0;
    int missCount = 0;
    int consecutiveErrors = 0;
    // Decoded tail that did not fit the previous quantum, plus how much of
    // it was already consumed. Served before touching the jitter buffer.
    std::vector<float> carry;
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
};

AudioOutputEngine::AudioOutputEngine(DecoderFactory decoderFactory)
    : m_decoderFactory(std::move(decoderFactory)) {
    // Preallocate every scratch buffer so the audio thread never grows one:
    // worst-case quantum for the mix/voice buffers, worst-case 120 ms Opus
    // bundle for the decode scratch.
    m_mix.assign(MAX_QUANTUM_SAMPLES, 0.0f);
    m_voiceScratch.assign(MAX_QUANTUM_SAMPLES, 0.0f);
    m_frameScratch.assign(MAX_DECODE_SAMPLES, 0.0f);
    m_deadSessions.reserve(MAX_VOICES);
    m_pendingTalks.reserve(MAX_VOICES);
    const float mul = static_cast<float>(kPi) / (2.0f * FRAME_SIZE);
    m_fadeIn.resize(FRAME_SIZE);
    m_fadeOut.resize(FRAME_SIZE);
    for (int i = 0; i < FRAME_SIZE; ++i) {
        const float v = std::sin(static_cast<float>(i) * mul);
        m_fadeIn[i] = v;
        m_fadeOut[FRAME_SIZE - i - 1] = v;
    }
    // Equal-power crossfade: cos^2 + sin^2 == 1 holds perceived loudness
    // flat across the blend, unlike a linear crossfade which dips mid-way.
    const float xmul = static_cast<float>(kPi) / (2.0f * XFADE_SAMPLES);
    m_xfadeIn.resize(XFADE_SAMPLES);
    m_xfadeOut.resize(XFADE_SAMPLES);
    for (int i = 0; i < XFADE_SAMPLES; ++i) {
        m_xfadeIn[i] = std::sin(static_cast<float>(i) * xmul);
        m_xfadeOut[i] = std::cos(static_cast<float>(i) * xmul);
    }
}

AudioOutputEngine::~AudioOutputEngine() {
    // Locked teardown; callers must have quiesced renderMix/queuePacket (and
    // any talk callback in flight) before destruction runs. No talk
    // callbacks are emitted from the destructor: reporting PASSIVE for dying
    // voices would run listener code on a half-destroyed object.
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& entry : m_voices) {
        jitter_buffer_destroy(entry.second->jitter);
        entry.second->jitter = nullptr;
    }
    m_voices.clear();
}

void AudioOutputEngine::setTalkCallback(OutputTalkCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_talkCallback = std::move(callback);
}

void AudioOutputEngine::setJitterMarginFrames(int frames) {
    std::lock_guard<std::mutex> lock(m_mutex);
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

void AudioOutputEngine::recordTalkLocked(
    Voice* voice, int32_t session, OutputTalkState state,
    std::vector<std::pair<int32_t, int>>* pendingEvents) {
    const int ordinal = static_cast<int>(state);
    if (voice->lastReportedState == ordinal) {
        return;
    }
    voice->lastReportedState = ordinal;
    pendingEvents->emplace_back(session, ordinal);
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
        voice->carry.assign(src + copy, src + copy + tail);
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

void AudioOutputEngine::queuePacket(int32_t session, const uint8_t* data,
                                    size_t len, uint32_t sequence, int flags,
                                    bool isTerminator) {
    if (len > MAX_PACKET_BYTES) {
        return;
    }
    if (data == nullptr || len == 0) {
        // Empty end-of-speech marker (terminator with no Opus payload): no
        // bytes to buffer, just flag the voice so renderMix drains instead
        // of running the miss-expiry. Unknown sessions have nothing to end.
        if (!isTerminator) {
            return;
        }
        std::lock_guard<std::mutex> lock(m_mutex);
        auto found = m_voices.find(session);
        if (found != m_voices.end()) {
            found->second->hasTerminator = true;
        }
        return;
    }
    if (!m_decoderFactory) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_voices.find(session);
    if (it == m_voices.end()) {
        if (m_voices.size() >= static_cast<size_t>(MAX_VOICES)) {
            // No insertion-order tracking on the session map, so evict the
            // highest session id as the newest-voice approximation: long-
            // connected speakers keep their jitter history, and a join flood
            // cannot push out the whole channel.
            auto newest = std::prev(m_voices.end());
            jitter_buffer_destroy(newest->second->jitter);
            m_voices.erase(newest);
        }
        auto voice = std::make_unique<Voice>();
        voice->session = session;
        voice->decoder = m_decoderFactory();
        if (!voice->decoder || !voice->decoder->isValid()) {
            return;
        }
        voice->jitter = jitter_buffer_init(FRAME_SIZE);
        if (!voice->jitter) {
            return;
        }
        int margin = FRAME_SIZE * m_jitterMarginFrames;
        jitter_buffer_ctl(voice->jitter, JITTER_BUFFER_SET_MARGIN, &margin);
        it = m_voices.emplace(session, std::move(voice)).first;
    }
    Voice* voice = it->second.get();

    // Drop packets the decoder cannot size: a fabricated span corrupts
    // jitter timing for the whole voice.
    const int span = voice->decoder->packetSampleCount(data, len);
    if (span <= 0) {
        return;
    }
    // Sequence counts 10 ms frames; timestamp and span share sample units,
    // so bundled datagrams imply consecutive seq (seq+i per chained frame)
    // on the Java side. Wrap-around of either is benign: the jitter buffer
    // only compares differences.
    const auto timestamp = static_cast<uint32_t>(FRAME_SIZE * sequence);

    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(const_cast<uint8_t*>(data));
    packet.len = static_cast<uint32_t>(len);
    packet.timestamp = timestamp;
    packet.span = static_cast<uint32_t>(span);
    // JitterBufferPacket.sequence is 16 bit; mask explicitly so the
    // truncation is visible here rather than buried in a narrowing cast.
    packet.sequence = static_cast<uint16_t>(sequence & 0xFFFFu);
    packet.user_data = (flags & 0xFF) | (isTerminator ? kTerminatorFlagBit : 0);
    // The jitter buffer copies packet bytes when no destroy callback is set.
    jitter_buffer_put(voice->jitter, &packet);
}

size_t AudioOutputEngine::renderMix(int16_t* out, size_t numSamples) {
    if (out == nullptr || numSamples == 0) {
        return 0;
    }
    std::unique_lock<std::mutex> lock(m_mutex);
    if (m_voices.empty()) {
        return 0;
    }

    // Reuse the preallocated scratch; only a quantum beyond the design
    // maximum takes the slow-path resize.
    if (m_mix.size() < numSamples) {
        m_mix.resize(numSamples);
    }
    std::fill(m_mix.begin(), m_mix.begin() + numSamples, 0.0f);
    if (m_voiceScratch.size() < numSamples) {
        m_voiceScratch.resize(numSamples);
    }

    m_deadSessions.clear();
    m_pendingTalks.clear();
    bool anyMixed = false;

    for (auto& entry : m_voices) {
        Voice* voice = entry.second.get();
        std::fill(m_voiceScratch.begin(), m_voiceScratch.begin() + numSamples,
                  0.0f);
        size_t filled = 0;
        const bool startedAtEntry = voice->started;
        bool producedAudio = false;

        while (filled < numSamples) {
            // Serve decoded carryover before touching the jitter buffer.
            if (voice->carryPos < voice->carry.size()) {
                const size_t avail = voice->carry.size() - voice->carryPos;
                const size_t count = std::min(avail, numSamples - filled);
                appendVoiceChunk(voice, voice->carry.data() + voice->carryPos,
                                 static_cast<int>(count),
                                 m_voiceScratch.data(), numSamples, filled,
                                 voice->carryConcealed, m_xfadeIn.data(),
                                 m_xfadeOut.data());
                voice->carryPos += count;
                producedAudio = true;
                if (voice->carryPos >= voice->carry.size()) {
                    voice->carry.clear();
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
            const int result =
                jitter_buffer_get(voice->jitter, &packet, FRAME_SIZE, &startOffset);
            if (result == JITTER_BUFFER_OK) {
                voice->missCount = 0;
                voice->quietFrames = 0;
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
                            packet.len, m_frameScratch.data(), FRAME_SIZE, 1);
                        if (fec == FRAME_SIZE) {
                            writeChunkAt(voice, m_voiceScratch.data(), debtPos,
                                         m_frameScratch.data(), fec, false,
                                         m_xfadeIn.data(), m_xfadeOut.data());
                            recovered = true;
                        }
                    }
                    if (!recovered) {
                        const int debtPlc = voice->decoder->decodeConcealment(
                            m_frameScratch.data(), FRAME_SIZE);
                        if (debtPlc > 0) {
                            writeChunkAt(voice, m_voiceScratch.data(), debtPos,
                                         m_frameScratch.data(), debtPlc, true,
                                         m_xfadeIn.data(), m_xfadeOut.data());
                        }
                        // debtPlc <= 0: wedged decoder; the slot stays the
                        // reserved silence and expiry advances regardless.
                    }
                }
                const int decoded = voice->decoder->decodeFloat(
                    reinterpret_cast<uint8_t*>(packet.data), packet.len,
                    m_frameScratch.data(), MAX_DECODE_SAMPLES, 0);
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
                    appendVoiceChunk(voice, m_frameScratch.data(), decoded,
                                     m_voiceScratch.data(), numSamples, filled,
                                     false, m_xfadeIn.data(),
                                     m_xfadeOut.data());
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
                        m_frameScratch.data(), FRAME_SIZE);
                    jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
                    jitter_buffer_tick(voice->jitter);
                    if (concealed > 0) {
                        appendVoiceChunk(voice, m_frameScratch.data(),
                                         concealed, m_voiceScratch.data(),
                                         numSamples, filled, true,
                                         m_xfadeIn.data(), m_xfadeOut.data());
                    }
                    // concealed <= 0: wedged decoder; this slot stays scratch
                    // silence and expiry still advances via the tick above.
                }
                continue;
            }

            // Missing or late packet.
            if (!voice->started) {
                // Pre-roll silence until the jitter buffer has something to
                // play, matching the legacy startup gate without the model
                // object's moving average.
                const size_t chunk =
                    std::min<size_t>(FRAME_SIZE, numSamples - filled);
                filled += chunk;
                if (++voice->quietFrames >= STARTUP_QUIET_FRAMES) {
                    voice->started = true;
                }
                continue;
            }
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
                    m_frameScratch.data(), FRAME_SIZE);
                if (debtPlc > 0) {
                    writeChunkAt(voice, m_voiceScratch.data(), debtPos,
                                 m_frameScratch.data(), debtPlc, true,
                                 m_xfadeIn.data(), m_xfadeOut.data());
                }
            }
            if (filled + FRAME_SIZE <= numSamples) {
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
                m_frameScratch.data(), FRAME_SIZE);
            jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
            jitter_buffer_tick(voice->jitter);
            if (concealed > 0) {
                appendVoiceChunk(voice, m_frameScratch.data(), concealed,
                                 m_voiceScratch.data(), numSamples, filled,
                                 true, m_xfadeIn.data(), m_xfadeOut.data());
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
            const int debtPlc = voice->decoder->decodeConcealment(
                m_frameScratch.data(), FRAME_SIZE);
            if (debtPlc > 0) {
                writeChunkAt(voice, m_voiceScratch.data(), debtPos,
                             m_frameScratch.data(), debtPlc, true,
                             m_xfadeIn.data(), m_xfadeOut.data());
            }
        }
        // A terminator drains instead of cutting: the voice lives until its
        // buffered packets (peeked via the available count) and any decoded
        // carryover are played out. Only then does this quantum carry the
        // fade-out and the voice earns PASSIVE.
        const bool carryDrained =
            voice->carryPos >= voice->carry.size();
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
            snapshotTail(voice, m_voiceScratch.data(), filled);
            recordTalkLocked(voice, voice->session,
                             talkStateForFlags(voice->lastFlags), &m_pendingTalks);
            anyMixed = true;
        }

        if (finishing) {
            m_deadSessions.push_back(voice->session);
        }
    }

    for (int32_t session : m_deadSessions) {
        auto it = m_voices.find(session);
        if (it != m_voices.end()) {
            recordTalkLocked(it->second.get(), session,
                             OutputTalkState::PASSIVE, &m_pendingTalks);
            jitter_buffer_destroy(it->second->jitter);
            m_voices.erase(it);
        }
    }

    for (size_t i = 0; i < numSamples; ++i) {
        const float sample = saturateSample(m_mix[i]) * 32767.0f;
        if (!std::isfinite(sample)) {
            out[i] = 0;
            continue;
        }
        const long rounded = std::lroundf(sample);
        const long clamped =
            std::clamp<long>(rounded, -32768L, 32767L);
        out[i] = static_cast<int16_t>(clamped);
    }

    OutputTalkCallback callback = m_talkCallback;
    std::vector<std::pair<int32_t, int>> events;
    events.swap(m_pendingTalks);
    lock.unlock();
    if (callback) {
        for (const auto& event : events) {
            callback(event.first, event.second);
        }
    }
    if (!anyMixed) {
        // Nothing audible this quantum (pre-roll gating before the jitter
        // buffer releases its first packet): report silence so Java idles
        // instead of writing zeros.
        return 0;
    }
    return numSamples;
}

void AudioOutputEngine::removeUser(int32_t session) {
    std::vector<std::pair<int32_t, int>> pending;
    OutputTalkCallback callback;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        auto it = m_voices.find(session);
        if (it == m_voices.end()) {
            return;
        }
        callback = m_talkCallback;
        if (it->second->lastReportedState != -1 &&
            it->second->lastReportedState !=
                static_cast<int>(OutputTalkState::PASSIVE)) {
            recordTalkLocked(it->second.get(), session,
                             OutputTalkState::PASSIVE, &pending);
        }
        jitter_buffer_destroy(it->second->jitter);
        m_voices.erase(it);
    }
    if (callback) {
        for (const auto& event : pending) {
            callback(event.first, event.second);
        }
    }
}

void AudioOutputEngine::clear() {
    std::vector<std::pair<int32_t, int>> pending;
    OutputTalkCallback callback;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        callback = m_talkCallback;
        for (auto& entry : m_voices) {
            Voice* voice = entry.second.get();
            if (voice->lastReportedState != -1 &&
                voice->lastReportedState !=
                    static_cast<int>(OutputTalkState::PASSIVE)) {
                recordTalkLocked(voice, entry.first, OutputTalkState::PASSIVE,
                                 &pending);
            }
            jitter_buffer_destroy(voice->jitter);
            voice->jitter = nullptr;
        }
        m_voices.clear();
    }
    if (callback) {
        for (const auto& event : pending) {
            callback(event.first, event.second);
        }
    }
}

size_t AudioOutputEngine::activeUserCount() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_voices.size();
}

} // namespace audio
} // namespace mumla
