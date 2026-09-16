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

constexpr int kJitterMarginSamples = AudioOutputEngine::FRAME_SIZE * 10;
constexpr int kTerminatorFlagBit = 0x100;

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
};

AudioOutputEngine::AudioOutputEngine(DecoderFactory decoderFactory)
    : m_decoderFactory(std::move(decoderFactory)) {
    const float mul = static_cast<float>(M_PI) / (2.0f * FRAME_SIZE);
    m_fadeIn.resize(FRAME_SIZE);
    m_fadeOut.resize(FRAME_SIZE);
    for (int i = 0; i < FRAME_SIZE; ++i) {
        const float v = std::sin(static_cast<float>(i) * mul);
        m_fadeIn[i] = v;
        m_fadeOut[FRAME_SIZE - i - 1] = v;
    }
}

AudioOutputEngine::~AudioOutputEngine() {
    clear();
}

void AudioOutputEngine::setTalkCallback(OutputTalkCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_talkCallback = std::move(callback);
}

OutputTalkState AudioOutputEngine::talkStateForFlags(int flags) {
    switch (flags & 0xFF) {
        case 0:
            return OutputTalkState::TALKING;
        case 1:
            return OutputTalkState::SHOUTING;
        default:
            return OutputTalkState::WHISPERING;
    }
}

void AudioOutputEngine::emitTalkLocked(int32_t session,
                                       OutputTalkCallback& callback,
                                       OutputTalkState state) {
    auto it = m_voices.find(session);
    if (it == m_voices.end()) {
        return;
    }
    const int ordinal = static_cast<int>(state);
    if (it->second->lastReportedState == ordinal) {
        return;
    }
    it->second->lastReportedState = ordinal;
    if (callback) {
        // Callback while holding the engine lock; listeners must not call
        // back into the engine. The JNI layer only records events.
        callback(session, ordinal);
    } else if (m_talkCallback) {
        m_talkCallback(session, ordinal);
    }
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
        int margin = kJitterMarginSamples;
        jitter_buffer_ctl(voice->jitter, JITTER_BUFFER_SET_MARGIN, &margin);
        it = m_voices.emplace(session, std::move(voice)).first;
    }
    Voice* voice = it->second.get();

    int span = voice->decoder->packetSampleCount(data, len);
    if (span <= 0) {
        span = FRAME_SIZE;
    }
    const auto timestamp = static_cast<uint32_t>(FRAME_SIZE * sequence);

    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(const_cast<uint8_t*>(data));
    packet.len = static_cast<uint32_t>(len);
    packet.timestamp = timestamp;
    packet.span = static_cast<uint32_t>(span);
    packet.sequence = sequence;
    packet.user_data = (flags & 0xFF) | (isTerminator ? kTerminatorFlagBit : 0);
    // The jitter buffer copies packet bytes when no destroy callback is set.
    jitter_buffer_put(voice->jitter, &packet);
}

size_t AudioOutputEngine::renderMix(int16_t* out, size_t numSamples) {
    if (out == nullptr || numSamples == 0) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_voices.empty()) {
        return 0;
    }

    m_mix.assign(numSamples, 0.0f);
    if (m_voiceScratch.size() < numSamples) {
        m_voiceScratch.resize(numSamples);
    }
    if (m_frameScratch.size() < static_cast<size_t>(MAX_DECODE_SAMPLES)) {
        m_frameScratch.resize(MAX_DECODE_SAMPLES);
    }

    std::vector<int32_t> deadSessions;
    OutputTalkCallback callback = m_talkCallback;

    for (auto& entry : m_voices) {
        Voice* voice = entry.second.get();
        std::fill(m_voiceScratch.begin(), m_voiceScratch.begin() + numSamples, 0.0f);
        size_t filled = 0;
        const bool startedAtEntry = voice->started;
        bool producedAudio = false;

        while (filled < numSamples) {
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
                jitter_buffer_update_delay(voice->jitter, nullptr, nullptr);
                if (decoded > 0) {
                    const size_t count =
                        std::min<size_t>(decoded, numSamples - filled);
                    std::memcpy(m_voiceScratch.data() + filled,
                                m_frameScratch.data(), count * sizeof(float));
                    filled += count;
                    const int ticks = decoded / FRAME_SIZE;
                    for (int i = 0; i < ticks; ++i) {
                        jitter_buffer_tick(voice->jitter);
                    }
                }
                continue;
            }

            // Missing or late packet.
            if (!voice->started) {
                // Pre-roll silence until the jitter buffer has something to
                // play, matching the legacy startup gate without the model
                // object's moving average.
                const size_t chunk = std::min<size_t>(FRAME_SIZE, numSamples - filled);
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
            if (concealed > 0) {
                const size_t count =
                    std::min<size_t>(concealed, numSamples - filled);
                std::memcpy(m_voiceScratch.data() + filled,
                            m_frameScratch.data(), count * sizeof(float));
                filled += count;
            }
            producedAudio = true;
            if (++voice->missCount > DEAD_MISS_FRAMES) {
                break;
            }
        }

        const bool finishing =
            voice->hasTerminator || voice->missCount > DEAD_MISS_FRAMES;
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
            emitTalkLocked(voice->session, callback,
                           talkStateForFlags(voice->lastFlags));
        }

        if (finishing) {
            deadSessions.push_back(voice->session);
        }
    }

    for (int32_t session : deadSessions) {
        emitTalkLocked(session, callback, OutputTalkState::PASSIVE);
        auto it = m_voices.find(session);
        if (it != m_voices.end()) {
            jitter_buffer_destroy(it->second->jitter);
            m_voices.erase(it);
        }
    }

    // Soft-knee bus saturation: linear at or below full scale, then a smooth
    // compression tail instead of the legacy hard clip. Single speakers pass
    // through untouched; overlapping speech compresses instead of squaring.
    for (size_t i = 0; i < numSamples; ++i) {
        const float m = m_mix[i];
        const float abs = std::fabs(m);
        float shaped = m;
        if (abs > 1.0f) {
            const float sign = m >= 0.0f ? 1.0f : -1.0f;
            shaped = sign * (1.0f + 0.5f * std::tanh(abs - 1.0f));
        }
        float sample = shaped * 32767.0f;
        sample = std::clamp(sample, -32768.0f, 32767.0f);
        out[i] = static_cast<int16_t>(sample);
    }
    return numSamples;
}

void AudioOutputEngine::removeUser(int32_t session) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_voices.find(session);
    if (it != m_voices.end()) {
        if (m_talkCallback && it->second->lastReportedState !=
                                  static_cast<int>(OutputTalkState::PASSIVE)) {
            it->second->lastReportedState =
                static_cast<int>(OutputTalkState::PASSIVE);
            m_talkCallback(session,
                           static_cast<int>(OutputTalkState::PASSIVE));
        }
        jitter_buffer_destroy(it->second->jitter);
        m_voices.erase(it);
    }
}

void AudioOutputEngine::clear() {
    for (auto& entry : m_voices) {
        jitter_buffer_destroy(entry.second->jitter);
        entry.second->jitter = nullptr;
    }
    m_voices.clear();
}

size_t AudioOutputEngine::activeUserCount() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_voices.size();
}

} // namespace audio
} // namespace mumla
