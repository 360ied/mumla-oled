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

#ifndef MUMLA_AUDIO_OUTPUT_ENGINE_H_
#define MUMLA_AUDIO_OUTPUT_ENGINE_H_

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>

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

// Voice pool size. Also bounds the talk-event arrays: a quantum can produce
// at most two transitions per voice (a context change plus its PASSIVE).
inline constexpr int kMaxVoices = 32;

struct InboundPacket {
    int32_t session = 0;
    uint32_t sequence = 0;
    int flags = 0; // talk context
    bool isTerminator = false;
    uint16_t length = 0;
    uint8_t data[2048]; // MAX_PACKET_BYTES; unused when length is 0
};

struct TalkEvent {
    int32_t session = 0;
    OutputTalkState state = OutputTalkState::PASSIVE;
};

struct RenderResult {
    size_t samples = 0; // 0 when silent, as today
    size_t talkEventCount = 0;
    TalkEvent talkEvents[kMaxVoices * 2];
    uint32_t droppedPackets = 0; // jitter slot pool exhausted
};

/**
 * Native audio output engine: jitter buffering, Opus decode, in-band FEC
 * recovery, loss concealment, loss-boundary crossfading, fading, mixing and
 * soft saturation.
 *
 * Single-threaded by contract: the render thread drives the instance
 * (`queuePacket`, `render`, `removeUser`, `clear`, `setJitterMarginFrames`).
 * There are no locks, no atomics and no callbacks, and nothing allocates
 * after construction: the voice pool, decoders, jitter buffers, scratch
 * buffers and session lookup are all created up front.
 *
 * Talk transitions are never reported inline: they accumulate and are
 * returned by the next `render`, in occurrence order, so no listener code can
 * run inside the engine.
 */
class AudioOutputEngine {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples, 10 ms
    static constexpr size_t MAX_PACKET_BYTES = 2048;
    // Largest Opus bundle the engine accepts: 120 ms = 12 x 10 ms frames.
    static constexpr int MAX_DECODE_SAMPLES = FRAME_SIZE * 12; // 5760 samples
    // Largest single render quantum the scratch buffers cover: 60 ms. The
    // I/O layer never asks for more; a debug assert pins that contract.
    static constexpr size_t MAX_QUANTUM_SAMPLES = FRAME_SIZE * 6;
    // A fresh voice stays silent (emitting zero PCM) until the jitter buffer
    // holds margin+1 frames of queued audio — upstream parity with desktop
    // Mumble's AudioOutputSpeech startup availability gate, which keeps the
    // mixer from outrunning the packet arrival clock at the start of every
    // talk burst. If the gate never fills (a trickle or a lone blip), it
    // force-starts the voice after this many 10 ms frames so the miss expiry
    // can retire it; upstream grants its gate the same 20 frames.
    static constexpr int GATE_TIMEOUT_FRAMES = 20;
    static constexpr int DEAD_MISS_FRAMES = 10;
    static constexpr int MAX_VOICES = kMaxVoices;
    // Pending transitions between renders: two per voice from the mix plus one
    // per evicted session from queuePacket. Anything beyond this between two
    // renders is not drained into a later result and is dropped.
    static constexpr int MAX_PENDING_TALK_EVENTS = MAX_VOICES * 4;
    static constexpr int MAX_CONSECUTIVE_DECODE_ERRORS = 5;
    static constexpr int DEFAULT_JITTER_MARGIN_FRAMES = 4;
    // Equal-power crossfade applied at real<->concealment boundaries so loss
    // gaps do not click: 96 samples = 2 ms at 48 kHz.
    static constexpr int XFADE_SAMPLES = 96;
    static constexpr double kPi = 3.14159265358979323846;

    using DecoderFactory = std::function<std::unique_ptr<IOutputDecoder>()>;

    /**
     * @param decoderFactory Creates the per-voice decoders, one per pool
     *        slot, at construction. Production passes a factory for real Opus
     *        decoders (see OpusVoiceDecoder.h); tests inject fakes. Null means
     *        no voice can be created, which keeps this object linkable
     *        without libopus.
     * @param jitterMarginFrames Initial jitter margin in 10 ms frames
     *        (40 ms by default); see setJitterMarginFrames.
     */
    explicit AudioOutputEngine(
        DecoderFactory decoderFactory = nullptr,
        int jitterMarginFrames = DEFAULT_JITTER_MARGIN_FRAMES);
    ~AudioOutputEngine();

    // Non-copyable
    AudioOutputEngine(const AudioOutputEngine&) = delete;
    AudioOutputEngine& operator=(const AudioOutputEngine&) = delete;

    /**
     * Sets the jitter buffer margin in 10 ms frames, applied to voices that
     * take a pool slot after the call. The margin both seeds the Speex buffer
     * and sizes the startup gate (margin+1 frames of queued audio must arrive
     * before a fresh voice plays). It is a floor: Speex still grows effective
     * buffering via adaptation when the link needs it.
     */
    void setJitterMarginFrames(int frames);

    /**
     * Enqueues one Opus payload for a user. The packet bytes are copied into
     * the jitter buffer, so the caller keeps ownership of `packet.data`.
     * Packets whose sample count cannot be determined are dropped rather than
     * given a fabricated span, since a wrong span corrupts jitter timing for
     * the whole voice.
     */
    void queuePacket(const InboundPacket& packet);

    /**
     * Renders mixed 16-bit PCM into `out`.
     *
     * Returns the talk transitions accumulated since the previous call (queue
     * time evictions, removals, clears and mix-time changes) in `result`, in
     * occurrence order. `result->samples` is `samples` when voices are active
     * and 0 when silent, so the caller can idle instead of spinning on
     * silence; silent and zero-length calls still deliver pending events.
     *
     * `samples` must not exceed MAX_QUANTUM_SAMPLES (asserted in debug
     * builds); a larger request is clamped.
     */
    void render(int16_t* out, size_t samples, RenderResult* result);

    void removeUser(int32_t session);
    void clear();
    size_t activeUserCount() const;

private:
    struct Voice;

    static OutputTalkState talkStateForFlags(int flags);
    // Records a talk transition (deduped against the last reported state) into
    // the pending array. No listener runs inside the engine.
    void recordTalk(Voice* voice, int32_t session, OutputTalkState state);
    // Appends one decoded/concealed chunk into the voice scratch area,
    // stashing any overflow in the voice carryover buffer; filled advances.
    // When the chunk type (concealed vs real, FEC recovery counts as real)
    // differs from the previously appended chunk, the boundary is blended
    // with the precomputed equal-power crossfade instead of stepping.
    static void appendVoiceChunk(Voice* voice, const float* src, int count,
                                 float* scratch, size_t numSamples,
                                 size_t& filled, bool concealed,
                                 const float* xfadeIn, const float* xfadeOut);
    // Overwrites a previously reserved FEC-debt slot (see render) with a
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

    // Session lookup: a fixed array of (session, pool slot) pairs kept sorted
    // by session id, so the render loop visits voices in the same ascending
    // order the session map used to supply.
    struct SessionSlot {
        int32_t session;
        int slot;
    };
    int findLookupIndex(int32_t session) const;
    // Takes a pool slot for a new session, evicting the highest session id
    // when all slots are in use (the newest-voice approximation, as before),
    // and resets the slot's decoder and jitter buffer for reuse.
    Voice* acquireVoice(int32_t session);
    void releaseVoice(int32_t session);

    DecoderFactory m_decoderFactory;
    int m_jitterMarginFrames = DEFAULT_JITTER_MARGIN_FRAMES;

    Voice* m_pool[MAX_VOICES] = {}; // owned
    SessionSlot m_lookup[MAX_VOICES] = {};
    size_t m_lookupCount = 0;

    // Talk transitions accumulated since the last render, in occurrence
    // order. Drained by render, which also bounds the array.
    TalkEvent m_pendingTalks[MAX_PENDING_TALK_EVENTS] = {};
    size_t m_pendingTalkCount = 0;

    float m_mix[MAX_QUANTUM_SAMPLES] = {};
    float m_voiceScratch[MAX_QUANTUM_SAMPLES] = {};
    float m_frameScratch[MAX_DECODE_SAMPLES] = {};
    float m_fadeIn[FRAME_SIZE] = {};
    float m_fadeOut[FRAME_SIZE] = {};
    float m_xfadeIn[XFADE_SAMPLES] = {};
    float m_xfadeOut[XFADE_SAMPLES] = {};
    int32_t m_deadSessions[MAX_VOICES] = {};
    size_t m_deadSessionCount = 0;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_AUDIO_OUTPUT_ENGINE_H_
