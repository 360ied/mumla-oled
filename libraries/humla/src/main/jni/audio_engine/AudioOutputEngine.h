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

#ifndef MUMLA_AUDIO_OUTPUT_ENGINE_H_
#define MUMLA_AUDIO_OUTPUT_ENGINE_H_

#include <cstddef>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

// Speex jitter buffer (C API, global scope to match speex_jitter.h).
struct JitterBuffer_;
typedef struct JitterBuffer_ JitterBuffer;

namespace mumla {
namespace audio {

/**
 * Talk states reported to Java. Ordinals must match TalkState.java order:
 * TALKING, SHOUTING, PASSIVE, WHISPERING.
 */
enum class OutputTalkState : int {
    TALKING = 0,
    SHOUTING = 1,
    PASSIVE = 2,
    WHISPERING = 3,
};

using OutputTalkCallback = std::function<void(int32_t session, int stateOrdinal)>;

/**
 * Abstract Opus decoder used by the output engine. The production
 * implementation is OpusVoiceDecoder; tests inject fakes without linking
 * libopus.
 */
class IOutputDecoder {
public:
    virtual ~IOutputDecoder() = default;
    virtual int decodeFloat(const uint8_t* data, size_t len, float* out,
                            int maxSamples, int decodeFec) = 0;
    virtual int decodeConcealment(float* out, int frameSize) = 0;
    virtual int packetSampleCount(const uint8_t* data, size_t len) const = 0;
    virtual bool isValid() const = 0;
};

/**
 * Native Audio Output Engine.
 *
 * Replaces the legacy Java decode thread pool, per-user speech objects, and
 * hard-clipping mixer. Java parses transport headers and forwards clean Opus
 * payloads; everything from jitter buffering through decode, loss
 * concealment, fading, mixing, and soft saturation lives here.
 *
 * Threading: queuePacket may be called from network threads while renderMix
 * runs on the audio thread. A single mutex guards all voice state. Decode of
 * a handful of mono streams is cheap enough to run inline in renderMix, which
 * keeps the callback free of cross-thread handoff latency.
 */
class AudioOutputEngine {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples, 10 ms
    static constexpr size_t MAX_PACKET_BYTES = 2048;
    static constexpr int MAX_DECODE_SAMPLES = FRAME_SIZE * 6; // 60 ms bundle
    static constexpr int STARTUP_QUIET_FRAMES = 20;
    static constexpr int DEAD_MISS_FRAMES = 10;

    using DecoderFactory =
        std::function<std::unique_ptr<IOutputDecoder>()>;

    /**
     * @param decoderFactory Creates per-user decoders. Production passes a
     *        factory for real Opus decoders (see OpusVoiceDecoder.h); tests
     *        inject fakes. Null means no voices can be created, which keeps
     *        this object linkable without libopus.
     */
    explicit AudioOutputEngine(DecoderFactory decoderFactory = nullptr);
    ~AudioOutputEngine();

    // Non-copyable
    AudioOutputEngine(const AudioOutputEngine&) = delete;
    AudioOutputEngine& operator=(const AudioOutputEngine&) = delete;

    void setTalkCallback(OutputTalkCallback callback);

    /**
     * Enqueues one Opus payload for a user. The jitter buffer copies packet
     * bytes, so the caller retains ownership of data.
     */
    void queuePacket(int32_t session, const uint8_t* data, size_t len,
                     uint32_t sequence, int flags, bool isTerminator);

    /**
     * Renders mixed 16-bit PCM.
     *
     * @param out Destination buffer with room for numSamples samples.
     * @return numSamples when voices are active, 0 when silent so the caller
     *         can idle instead of spinning on silence.
     */
    size_t renderMix(int16_t* out, size_t numSamples);

    void removeUser(int32_t session);
    void clear();
    size_t activeUserCount() const;

private:
    struct Voice;

    static OutputTalkState talkStateForFlags(int flags);
    void emitTalkLocked(int32_t session, OutputTalkCallback& callback,
                        OutputTalkState state);

    mutable std::mutex m_mutex;
    std::map<int32_t, std::unique_ptr<Voice>> m_voices;
    DecoderFactory m_decoderFactory;
    OutputTalkCallback m_talkCallback;

    std::vector<float> m_mix;
    std::vector<float> m_voiceScratch;
    std::vector<float> m_frameScratch;
    std::vector<float> m_fadeIn;
    std::vector<float> m_fadeOut;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_AUDIO_OUTPUT_ENGINE_H_
