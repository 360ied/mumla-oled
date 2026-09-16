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
#include <utility>
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
    virtual void reset() = 0;
};

/**
 * Native Audio Output Engine.
 *
 * Replaces the legacy Java decode thread pool, per-user speech objects, and
 * hard-clipping mixer. Java parses transport headers and forwards clean Opus
 * payloads; everything from jitter buffering through decode, in-band FEC
 * recovery, loss concealment, loss-boundary crossfading, fading, mixing,
 * and soft saturation lives here.
 *
 * Threading: queuePacket may be called from network threads while renderMix
 * runs on the audio thread. A single mutex guards all voice state. Decode of
 * a handful of mono streams is cheap enough to run inline in renderMix, which
 * keeps the callback free of cross-thread handoff latency. Talk callbacks are
 * never invoked while holding the mutex; they are collected under the lock
 * and emitted from a copied list after unlock.
 */
class AudioOutputEngine {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples, 10 ms
    static constexpr size_t MAX_PACKET_BYTES = 2048;
    // Largest Opus bundle the engine accepts: 120 ms = 12 x 10 ms frames.
    static constexpr int MAX_DECODE_SAMPLES = FRAME_SIZE * 12; // 5760 samples
    // Largest single renderMix quantum the scratch buffers cover without
    // regrowing: 60 ms. Larger quanta still work via a slow-path resize.
    static constexpr size_t MAX_QUANTUM_SAMPLES = FRAME_SIZE * 6;
    // Two pre-roll frames (20 ms): just enough to avoid blurting on a truly
    // empty buffer. Buffered packets play immediately with no gating, and the
    // jitter margin below does the real startup buffering.
    static constexpr int STARTUP_QUIET_FRAMES = 2;
    static constexpr int DEAD_MISS_FRAMES = 10;
    static constexpr int MAX_VOICES = 32;
    static constexpr int MAX_CONSECUTIVE_DECODE_ERRORS = 5;
    // Equal-power crossfade applied at real<->concealment boundaries so loss
    // gaps do not click: 96 samples = 2 ms at 48 kHz.
    static constexpr int XFADE_SAMPLES = 96;
    static constexpr double kPi = 3.14159265358979323846;

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
     * Sets the jitter buffer margin in 10 ms frames. Defaults to 4 frames
     * (40 ms); applied to voices created after the call. The margin is a
     * floor: Speex still grows effective buffering via adaptation when the
     * link needs it.
     */
    void setJitterMarginFrames(int frames);

    /**
     * Enqueues one Opus payload for a user. The jitter buffer copies packet
     * bytes, so the caller retains ownership of data. Packets whose sample
     * count cannot be determined are dropped rather than given a fabricated
     * span, since a wrong span corrupts jitter timing for the whole voice.
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
    // Records a talk transition into pendingEvents (deduped against the last
    // reported state) instead of invoking the callback inline, so the caller
    // can emit events after releasing the lock.
    static void recordTalkLocked(
        Voice* voice, int32_t session, OutputTalkState state,
        std::vector<std::pair<int32_t, int>>* pendingEvents);
    // Appends one decoded/concealed chunk into the voice scratch area,
    // stashing any overflow in the voice carryover buffer; filled advances.
    // When the chunk type (concealed vs real, FEC recovery counts as real)
    // differs from the previously appended chunk, the boundary is blended
    // with the precomputed equal-power crossfade instead of stepping.
    static void appendVoiceChunk(Voice* voice, const float* src, int count,
                                 float* scratch, size_t numSamples,
                                 size_t& filled, bool concealed,
                                 const float* xfadeIn, const float* xfadeOut);
    // Overwrites a previously reserved FEC-debt slot (see renderMix) with a
    // recovered or concealed chunk, blending the boundary as above. The slot
    // always holds exactly FRAME_SIZE samples; filled is unchanged.
    static void writeChunkAt(Voice* voice, float* scratch, size_t pos,
                             const float* src, int count, bool concealed,
                             const float* xfadeIn, const float* xfadeOut);
    // Snapshots the last emitted voice samples for cross-quantum blending.
    static void snapshotTail(Voice* voice, const float* scratch, size_t filled);
    // Soft-knee bus saturation, documented in the .cpp.
    static float saturateSample(float m);
    int jitterBufferedCount(JitterBuffer* jitter) const;

    mutable std::mutex m_mutex;
    std::map<int32_t, std::unique_ptr<Voice>> m_voices;
    DecoderFactory m_decoderFactory;
    OutputTalkCallback m_talkCallback;
    int m_jitterMarginFrames = 4;

    std::vector<float> m_mix;
    std::vector<float> m_voiceScratch;
    std::vector<float> m_frameScratch;
    std::vector<float> m_fadeIn;
    std::vector<float> m_fadeOut;
    std::vector<float> m_xfadeIn;
    std::vector<float> m_xfadeOut;
    std::vector<int32_t> m_deadSessions;
    // Reused across renderMix calls so the audio thread never allocates per
    // quantum. Only touched by renderMix (single-render-thread discipline).
    std::vector<std::pair<int32_t, int>> m_pendingTalks;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_AUDIO_OUTPUT_ENGINE_H_
