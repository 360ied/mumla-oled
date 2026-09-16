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
    const float mul = static_cast<float>(kPi) / (2.0f * FRAME_SIZE);
    m_fadeIn.resize(FRAME_SIZE);
    m_fadeOut.resize(FRAME_SIZE);
    for (int i = 0; i < FRAME_SIZE; ++i) {
        const float v = std::sin(static_cast<float>(i) * mul);
        m_fadeIn[i] = v;
        m_fadeOut[FRAME_SIZE - i - 1] = v;
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
            return OutputTalkState::WHISPERING;
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

void AudioOutputEngine::appendVoiceSamples(Voice* voice, const float* src,
                                           int count, float* scratch,
                                           size_t numSamples, size_t& filled) {
    if (count <= 0 || src == nullptr) {
        return;
    }
    const size_t room = (filled < numSamples) ? numSamples - filled : 0;
    const size_t copy = std::min<size_t>(static_cast<size_t>(count), room);
    if (copy > 0) {
        std::memcpy(scratch + filled, src, copy * sizeof(float));
        filled += copy;
    }
    if (static_cast<size_t>(count) > copy) {
        // Retain the unconsumed tail for the next quantum. Carry is always
        // empty here: the fill loop serves it before touching the jitter
        // buffer, so reaching a decode implies it drained.
        const size_t tail = static_cast<size_t>(count) - copy;
        voice->carry.assign(src + copy, src + copy + tail);
        voice->carryPos = 0;
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
    if (data == nullptr || len == 0 || len > MAX_PACKET_BYTES) {
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
            // lowest session id as the oldest-voice approximation.
            auto oldest = m_voices.begin();
            jitter_buffer_destroy(oldest->second->jitter);
            m_voices.erase(oldest);
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
    std::vector<std::pair<int32_t, int>> pendingTalks;

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
                std::memcpy(m_voiceScratch.data() + filled,
                            voice->carry.data() + voice->carryPos,
                            count * sizeof(float));
                voice->carryPos += count;
                filled += count;
                producedAudio = true;
                if (voice->carryPos >= voice->carry.size()) {
                    voice->carry.clear();
                    voice->carryPos = 0;
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
                    appendVoiceSamples(voice, m_frameScratch.data(), decoded,
                                       m_voiceScratch.data(), numSamples,
                                       filled);
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
                    appendVoiceSamples(voice, m_frameScratch.data(), concealed,
                                       m_voiceScratch.data(), numSamples,
                                       filled);
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

            const int concealed = voice->decoder->decodeConcealment(
                m_frameScratch.data(), FRAME_SIZE);
            jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
            jitter_buffer_tick(voice->jitter);
            appendVoiceSamples(voice, m_frameScratch.data(), concealed,
                               m_voiceScratch.data(), numSamples, filled);
            producedAudio = true;
            if (++voice->missCount > DEAD_MISS_FRAMES) {
                break;
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
            recordTalkLocked(voice, voice->session,
                             talkStateForFlags(voice->lastFlags), &pendingTalks);
        }

        if (finishing) {
            m_deadSessions.push_back(voice->session);
        }
    }

    for (int32_t session : m_deadSessions) {
        auto it = m_voices.find(session);
        if (it != m_voices.end()) {
            recordTalkLocked(it->second.get(), session,
                             OutputTalkState::PASSIVE, &pendingTalks);
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
    events.swap(pendingTalks);
    lock.unlock();
    if (callback) {
        for (const auto& event : events) {
            callback(event.first, event.second);
        }
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
