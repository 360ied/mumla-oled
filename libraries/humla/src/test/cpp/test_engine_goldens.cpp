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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

// Golden engine fixtures (step 5a.1).
//
// The scenario definitions below are API-independent: fixed PCM scripts and
// control snapshots for the input engine, fixed packet/op scripts for the
// output engine. They were recorded from the pre-refactor engines and the
// refactored engines must reproduce them exactly (see
// docs/target-architecture/voice.md#behavior-pinning).
//
// Recording mode: run the suite with MUMLA_RECORD_ENGINE_GOLDENS=<dir> to
// rewrite the fixtures in <dir>. Verification mode reads them from
// MUMLA_GOLDEN_DIR. Recording from a changed engine is only legitimate as a
// separate, reviewed change.
//
// Recorded results are split by kind (packets vs talk transitions) instead of
// interleaved, so the output-stream ordering rules of the two engine
// generations (callbacks vs result structs) cannot masquerade as a behavior
// difference: the per-kind order and the frame index of every record are what
// get pinned.

#include "AudioInputEngine.h"
#include "AudioOutputEngine.h"
#include "HysteresisVad.h"
#include "OpusVoiceDecoder.h"
#include "OpusVoiceEncoder.h"
#include "RnnoiseProcessor.h"
#include "TestHarness.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

namespace {

using mumla::audio::AudioInputEngine;
using mumla::audio::AudioOutputEngine;
using mumla::audio::HysteresisVad;
using mumla::audio::InputMode;
using mumla::audio::OpusVoiceEncoder;

constexpr size_t kFrameSamples = 480; // 10 ms @ 48 kHz
constexpr int kRenderSamples = 960;   // 20 ms, the Kotlin render quantum
constexpr int kBitrate = 40000;
constexpr double kPi = 3.14159265358979323846;

// ---------------------------------------------------------------------------
// Deterministic signals
// ---------------------------------------------------------------------------

uint32_t nextRandom(uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

void fillSilence(int16_t* pcm) {
    std::memset(pcm, 0, kFrameSamples * sizeof(int16_t));
}

// Steady low-level dither: RMS far below the -65 dBFS squelch floor, so the
// VAD and the RNNoise squelch bypass see non-digital silence.
void fillQuietDither(int16_t* pcm, uint32_t seed) {
    uint32_t state = seed | 1u;
    for (size_t i = 0; i < kFrameSamples; ++i) {
        pcm[i] = static_cast<int16_t>(static_cast<int>(nextRandom(state) % 21u) - 10);
    }
}

// Voiced-like speech: three harmonics under a syllable-rate envelope. The
// absolute sample clock (not the frame index) drives the phase, so the
// waveform is continuous across frames and independent of where a scenario
// starts.
void fillVoicedFrame(int16_t* pcm, uint64_t absoluteFrame, int amplitude) {
    for (size_t i = 0; i < kFrameSamples; ++i) {
        const double t =
            static_cast<double>(absoluteFrame * kFrameSamples + i) / 48000.0;
        const double envelope = 0.55 + 0.45 * std::sin(2.0 * kPi * 3.5 * t);
        const double v = envelope *
                         (0.55 * std::sin(2.0 * kPi * 170.0 * t) +
                          0.30 * std::sin(2.0 * kPi * 340.0 * t) +
                          0.15 * std::sin(2.0 * kPi * 680.0 * t));
        long sample = std::lround(v * amplitude);
        if (sample > 32767) sample = 32767;
        if (sample < -32768) sample = -32768;
        pcm[i] = static_cast<int16_t>(sample);
    }
}

// ---------------------------------------------------------------------------
// Scenario definitions
// ---------------------------------------------------------------------------

// The input engine's control surface, independent of its generation: the
// pre-refactor engine receives these through setters, the refactored one as a
// per-frame InputConfig. Moves are expressed relative to the previous frame
// (the driver applies only the changes), exactly how a control thread would
// have driven the setters.
struct InputControl {
    InputMode mode = InputMode::VOICE_ACTIVITY;
    bool muted = false;
    bool ptt = false;
    int bitrate = kBitrate;
    int framesPerPacket = 2;
    float amplitudeBoost = 1.0f;
    bool rnnoise = true;
    bool leveler = true;
    float vadMax = HysteresisVad::DEFAULT_VAD_MAX;
    float vadMin = HysteresisVad::DEFAULT_VAD_MIN;
    uint32_t vadHold = HysteresisVad::DEFAULT_HOLD_FRAMES;
    float squelchDb = HysteresisVad::DEFAULT_SQUELCH_MIN_DB;
};

struct InputFrame {
    std::vector<int16_t> pcm;
    InputControl control;
};

struct InputScenario {
    std::string name;
    std::vector<InputFrame> frames;
};

enum class PcmKind { SILENCE, QUIET, VOICED };

InputScenario makeInputScenario(const std::string& name) {
    InputScenario scenario;
    scenario.name = name;
    InputControl control;

    // Frames are appended with an explicit kind; the control snapshot is
    // whatever was last edited.
    int amplitude = 2600;
    auto append = [&](int count, PcmKind kind, uint32_t ditherSeed) {
        for (int i = 0; i < count; ++i) {
            InputFrame frame;
            frame.pcm.resize(kFrameSamples);
            const uint64_t absoluteFrame = scenario.frames.size();
            switch (kind) {
                case PcmKind::SILENCE:
                    fillSilence(frame.pcm.data());
                    break;
                case PcmKind::QUIET:
                    fillQuietDither(frame.pcm.data(),
                                    ditherSeed + static_cast<uint32_t>(absoluteFrame) * 7919u);
                    break;
                case PcmKind::VOICED:
                    fillVoicedFrame(frame.pcm.data(), absoluteFrame, amplitude);
                    break;
            }
            frame.control = control;
            scenario.frames.push_back(std::move(frame));
        }
    };

    if (name == "input-vad-speech") {
        // VAD onset after a squelched pre-roll (pre-speech ring fills), a
        // voiced burst, then silence long enough to run the 25-frame hold out
        // and emit the terminator on an even packet boundary.
        append(12, PcmKind::QUIET, 11u);
        append(30, PcmKind::VOICED, 0u);
        append(48, PcmKind::SILENCE, 0u);
    } else if (name == "input-vad-prespeech-flush") {
        // 8-frame pre-speech buffer flushed at onset with one frame per
        // packet, so the flush alone releases 8 packets.
        control.framesPerPacket = 1;
        append(16, PcmKind::SILENCE, 0u);
        append(14, PcmKind::VOICED, 0u);
        append(30, PcmKind::SILENCE, 0u);
    } else if (name == "input-ptt-toggles") {
        // Push-to-talk: press, release with hold, re-assert during the hold,
        // release, a one-frame micro tap, and a long tail through the hold
        // expiry.
        control.mode = InputMode::PUSH_TO_TALK;
        append(5, PcmKind::VOICED, 0u);
        control.ptt = true;
        append(7, PcmKind::VOICED, 0u);
        control.ptt = false;
        append(2, PcmKind::VOICED, 0u);
        control.ptt = true;
        append(1, PcmKind::VOICED, 0u);
        control.ptt = false;
        append(26, PcmKind::VOICED, 0u);
        control.ptt = true;
        append(1, PcmKind::VOICED, 0u);
        control.ptt = false;
        append(38, PcmKind::VOICED, 0u);
    } else if (name == "input-mid-utterance-mute") {
        // Client mute mid-utterance flushes the partial accumulator as a
        // terminator; the 4-frame packet size leaves frames banked at the
        // mute, and un-muting clears them.
        control.framesPerPacket = 4;
        append(10, PcmKind::QUIET, 23u);
        append(10, PcmKind::VOICED, 0u);
        control.muted = true;
        append(4, PcmKind::VOICED, 0u);
        control.muted = false;
        append(6, PcmKind::VOICED, 0u);
        append(20, PcmKind::SILENCE, 0u);
        append(12, PcmKind::VOICED, 0u);
        append(20, PcmKind::SILENCE, 0u);
    } else if (name == "input-continuous-and-toggles") {
        // Continuous mode with mid-stream configuration moves: ring buffer
        // clearing on mode changes, packet-size changes with a non-empty
        // accumulator, denoiser and leveler toggles, amplitude boost, VAD
        // retune and a mute.
        control.mode = InputMode::CONTINUOUS;
        append(5, PcmKind::VOICED, 0u);
        control.amplitudeBoost = 2.5f;
        append(3, PcmKind::VOICED, 0u);
        control.framesPerPacket = 6;
        append(4, PcmKind::VOICED, 0u);
        control.rnnoise = false;
        append(5, PcmKind::VOICED, 0u);
        control.vadHold = 5;
        append(3, PcmKind::VOICED, 0u);
        control.leveler = false;
        append(3, PcmKind::VOICED, 0u);
        control.squelchDb = -40.0f;
        append(2, PcmKind::VOICED, 0u);
        control.rnnoise = true;
        append(4, PcmKind::VOICED, 0u);
        control.amplitudeBoost = 1.0f;
        append(3, PcmKind::VOICED, 0u);
        control.muted = true;
        append(4, PcmKind::VOICED, 0u);
        control.muted = false;
        control.framesPerPacket = 1;
        append(3, PcmKind::VOICED, 0u);
        control.leveler = true;
        append(3, PcmKind::VOICED, 0u);
        control.mode = InputMode::VOICE_ACTIVITY;
        control.vadHold = HysteresisVad::DEFAULT_HOLD_FRAMES;
        control.squelchDb = HysteresisVad::DEFAULT_SQUELCH_MIN_DB;
        append(6, PcmKind::QUIET, 31u);
        append(10, PcmKind::VOICED, 0u);
        append(40, PcmKind::SILENCE, 0u);
    } else {
        std::fprintf(stderr, "unknown input golden scenario %s\n", name.c_str());
        std::abort();
    }
    return scenario;
}

// One voice's Opus packet stream for output scenarios. The packets are
// produced by the production encoder from a deterministic tone, so the
// fixture pins encoder output too: changing it shows up as a golden failure.
struct VoicePackets {
    double frequency;
    std::vector<std::vector<uint8_t>> packets;
};

VoicePackets makeVoicePackets(double frequency, int packetCount) {
    OpusVoiceEncoder encoder(kBitrate);
    VoicePackets voice;
    voice.frequency = frequency;
    std::vector<int16_t> pcm(kFrameSamples);
    std::vector<uint8_t> buffer(1024);
    for (int packet = 0; packet < packetCount; ++packet) {
        for (size_t i = 0; i < kFrameSamples; ++i) {
            const double t = static_cast<double>(packet * kFrameSamples + i) /
                             48000.0;
            pcm[i] = static_cast<int16_t>(
                std::lround(6000.0 * std::sin(2.0 * kPi * frequency * t)));
        }
        const int encoded =
            encoder.encode(pcm.data(), pcm.size(), buffer.data(), buffer.size());
        if (encoded <= 0) {
            std::fprintf(stderr, "golden packet encode failed for %s\n",
                         std::to_string(frequency).c_str());
            std::abort();
        }
        voice.packets.emplace_back(buffer.begin(), buffer.begin() + encoded);
    }
    return voice;
}

struct OutputOp {
    enum class Type { QUEUE, RENDER };
    Type type = Type::RENDER;
    // QUEUE
    size_t voice = 0;
    size_t packet = 0;
    int32_t session = 0;
    uint32_t sequence = 0;
    int flags = 0;
    bool terminator = false;
    bool emptyPayload = false; // terminator with no Opus data
    // RENDER
    size_t samples = kRenderSamples;
};

struct OutputScenario {
    std::string name;
    std::vector<VoicePackets> voices;
    std::vector<OutputOp> ops;
};

OutputScenario makeOutputScenario(const std::string& name) {
    OutputScenario scenario;
    scenario.name = name;
    auto queue = [&](size_t voice, size_t packet, int32_t session,
                     uint32_t sequence, int flags = 0,
                     bool terminator = false, bool emptyPayload = false) {
        OutputOp op;
        op.type = OutputOp::Type::QUEUE;
        op.voice = voice;
        op.packet = packet;
        op.session = session;
        op.sequence = sequence;
        op.flags = flags;
        op.terminator = terminator;
        op.emptyPayload = emptyPayload;
        scenario.ops.push_back(op);
    };
    auto render = [&](int count, size_t samples = kRenderSamples) {
        for (int i = 0; i < count; ++i) {
            OutputOp op;
            op.type = OutputOp::Type::RENDER;
            op.samples = samples;
            scenario.ops.push_back(op);
        }
    };

    if (name == "output-in-order") {
        // Three voices, packets in order, one render per round: covers the
        // startup gate (5 packets queued before the first audio), mixing and
        // talk transitions.
        scenario.voices.push_back(makeVoicePackets(220.0, 20));
        scenario.voices.push_back(makeVoicePackets(330.0, 20));
        scenario.voices.push_back(makeVoicePackets(440.0, 20));
        render(4); // nothing queued: silent quanta
        for (size_t packet = 0; packet < 20; ++packet) {
            for (size_t voice = 0; voice < scenario.voices.size(); ++voice) {
                queue(voice, packet, 100 + static_cast<int32_t>(voice) * 100,
                      static_cast<uint32_t>(packet));
            }
            render(1);
        }
        render(4);
    } else if (name == "output-reordered") {
        // Two voices: one arrives shuffled (swap pairs and hold back every
        // third packet until after its successor), one arrives sorted.
        scenario.voices.push_back(makeVoicePackets(500.0, 16));
        scenario.voices.push_back(makeVoicePackets(620.0, 16));
        render(2);
        for (size_t round = 0; round < 8; ++round) {
            const size_t late = round * 2;
            const size_t early = late + 1;
            queue(0, early, 300, static_cast<uint32_t>(early));
            queue(0, late, 300, static_cast<uint32_t>(late));
            queue(1, late, 400, static_cast<uint32_t>(late));
            queue(1, early, 400, static_cast<uint32_t>(early));
            render(1);
        }
        render(4);
    } else if (name == "output-lost") {
        // Drop the fifth packet of a single voice, then a two-packet burst:
        // covers FEC recovery (the encoder sends LBRR), concealment and the
        // miss expiry.
        scenario.voices.push_back(makeVoicePackets(280.0, 24));
        render(2);
        for (size_t packet = 0; packet < 24; ++packet) {
            const bool lost = (packet % 5 == 4) || (packet >= 12 && packet <= 13);
            if (!lost) {
                queue(0, packet, 500, static_cast<uint32_t>(packet));
            }
            render(1);
        }
        render(6);
    } else if (name == "output-terminator") {
        // A payload-carrying terminator, then a payload-free one: the voice
        // must drain its buffered packets, fade out and report PASSIVE.
        scenario.voices.push_back(makeVoicePackets(360.0, 8));
        render(4);
        for (size_t packet = 0; packet < 5; ++packet) {
            queue(0, packet, 600, static_cast<uint32_t>(packet));
        }
        render(1);
        queue(0, 0, 600, 5, 0, true, true); // empty terminator marker
        render(6);
    } else if (name == "output-talk-flags") {
        // Talk-context flags map to talk states: normal, shout, whisper,
        // listen (shown talking) and the invalid target (passive).
        scenario.voices.push_back(makeVoicePackets(260.0, 10));
        scenario.voices.push_back(makeVoicePackets(390.0, 10));
        scenario.voices.push_back(makeVoicePackets(520.0, 10));
        scenario.voices.push_back(makeVoicePackets(650.0, 10));
        render(4);
        for (size_t packet = 0; packet < 5; ++packet) {
            queue(0, packet, 700, static_cast<uint32_t>(packet), 0);
            queue(1, packet, 800, static_cast<uint32_t>(packet), 1);
            queue(2, packet, 900, static_cast<uint32_t>(packet), 2);
            queue(3, packet, 1000, static_cast<uint32_t>(packet), 0xFF);
            render(1);
        }
        render(2);
    } else if (name == "output-many-senders") {
        // 33 concurrent senders force the 32-voice pool to evict; the 33rd
        // voice's talk state must be reported PASSIVE. Two waves of packets
        // keep the eviction path exercised after the voices exist.
        for (int voice = 0; voice < 33; ++voice) {
            scenario.voices.push_back(makeVoicePackets(180.0 + voice * 20.0, 6));
        }
        render(4);
        for (size_t voice = 0; voice < scenario.voices.size(); ++voice) {
            queue(voice, 0, 1100 + static_cast<int32_t>(voice),
                  static_cast<uint32_t>(voice));
        }
        render(2);
        for (size_t voice = 0; voice < scenario.voices.size(); ++voice) {
            queue(voice, 1, 1100 + static_cast<int32_t>(voice), 1);
        }
        render(2);
        for (size_t voice = 0; voice < scenario.voices.size(); ++voice) {
            for (size_t packet = 2; packet < 6; ++packet) {
                queue(voice, packet, 1100 + static_cast<int32_t>(voice),
                      static_cast<uint32_t>(packet));
            }
        }
        render(6);
    } else {
        std::fprintf(stderr, "unknown output golden scenario %s\n", name.c_str());
        std::abort();
    }
    return scenario;
}

// ---------------------------------------------------------------------------
// Recorded results, serialization and comparison
// ---------------------------------------------------------------------------

struct RecordedPacket {
    uint32_t frameIndex;
    int frames;
    bool terminator;
    uint64_t frameNumber;
    std::vector<uint8_t> data;
};

struct RecordedTalk {
    uint32_t frameIndex;
    bool talking;
    float peakEnergy;
};

struct InputResult {
    std::vector<RecordedPacket> packets;
    std::vector<RecordedTalk> talks;
};

struct RecordedRender {
    uint32_t returned;
    std::vector<int16_t> samples;
};

struct RecordedEvent {
    int32_t session;
    int32_t state;
};

struct OutputResult {
    std::vector<RecordedRender> renders;
    std::vector<RecordedEvent> events;
};

class Writer {
public:
    void u8(uint8_t v) { m_bytes.push_back(v); }
    void u32(uint32_t v) {
        for (int i = 0; i < 4; ++i) {
            m_bytes.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFFu));
        }
    }
    void u64(uint64_t v) {
        for (int i = 0; i < 8; ++i) {
            m_bytes.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFFu));
        }
    }
    void i16(int16_t v) {
        const uint16_t bits = static_cast<uint16_t>(v);
        m_bytes.push_back(static_cast<uint8_t>(bits & 0xFFu));
        m_bytes.push_back(static_cast<uint8_t>((bits >> 8) & 0xFFu));
    }
    void i32(int32_t v) { u32(static_cast<uint32_t>(v)); }
    void f32(float v) {
        uint32_t bits = 0;
        std::memcpy(&bits, &v, sizeof(bits));
        u32(bits);
    }
    void bytes(const void* data, size_t size) {
        const auto* p = static_cast<const uint8_t*>(data);
        m_bytes.insert(m_bytes.end(), p, p + size);
    }
    const std::vector<uint8_t>& data() const { return m_bytes; }

private:
    std::vector<uint8_t> m_bytes;
};

class Reader {
public:
    explicit Reader(const std::vector<uint8_t>& bytes) : m_bytes(bytes) {}

    bool ok() const { return m_ok; }
    size_t offset() const { return m_offset; }

    uint8_t u8() {
        if (m_offset + 1 > m_bytes.size()) {
            m_ok = false;
            return 0;
        }
        return m_bytes[m_offset++];
    }
    uint32_t u32() {
        if (m_offset + 4 > m_bytes.size()) {
            m_ok = false;
            return 0;
        }
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) {
            v |= static_cast<uint32_t>(m_bytes[m_offset + i]) << (8 * i);
        }
        m_offset += 4;
        return v;
    }
    uint64_t u64() {
        if (m_offset + 8 > m_bytes.size()) {
            m_ok = false;
            return 0;
        }
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) {
            v |= static_cast<uint64_t>(m_bytes[m_offset + i]) << (8 * i);
        }
        m_offset += 8;
        return v;
    }
    int16_t i16() {
        if (m_offset + 2 > m_bytes.size()) {
            m_ok = false;
            return 0;
        }
        const uint16_t bits = static_cast<uint16_t>(m_bytes[m_offset]) |
                              (static_cast<uint16_t>(m_bytes[m_offset + 1]) << 8);
        m_offset += 2;
        return static_cast<int16_t>(bits);
    }
    int32_t i32() { return static_cast<int32_t>(u32()); }
    float f32() {
        const uint32_t bits = u32();
        float v = 0.0f;
        std::memcpy(&v, &bits, sizeof(v));
        return v;
    }
    bool bytes(void* out, size_t size) {
        if (m_offset + size > m_bytes.size()) {
            m_ok = false;
            return false;
        }
        std::memcpy(out, m_bytes.data() + m_offset, size);
        m_offset += size;
        return true;
    }

private:
    const std::vector<uint8_t>& m_bytes;
    size_t m_offset = 0;
    bool m_ok = true;
};

constexpr uint32_t kGoldenMagic = 0x314C4F47u; // "GOL1"
constexpr uint32_t kGoldenVersion = 1u;
constexpr uint32_t kKindInput = 1u;
constexpr uint32_t kKindOutput = 2u;

void writeHeader(Writer& writer, uint32_t kind, const std::string& name) {
    writer.u32(kGoldenMagic);
    writer.u32(kGoldenVersion);
    writer.u32(kind);
    writer.u32(static_cast<uint32_t>(name.size()));
    writer.bytes(name.data(), name.size());
}

bool readHeader(Reader& reader, uint32_t kind, const std::string& name) {
    if (reader.u32() != kGoldenMagic || reader.u32() != kGoldenVersion ||
        reader.u32() != kind) {
        return false;
    }
    const uint32_t length = reader.u32();
    if (!reader.ok() || length != name.size()) {
        return false;
    }
    std::string readName(length, '\0');
    if (!reader.bytes(&readName[0], length)) {
        return false;
    }
    return readName == name;
}

void serializeInput(const InputScenario& scenario, const InputResult& result,
                    Writer& writer) {
    writeHeader(writer, kKindInput, scenario.name);
    writer.u32(static_cast<uint32_t>(result.packets.size()));
    for (const auto& packet : result.packets) {
        writer.u32(packet.frameIndex);
        writer.u32(static_cast<uint32_t>(packet.data.size()));
        writer.i32(packet.frames);
        writer.u8(packet.terminator ? 1u : 0u);
        writer.u64(packet.frameNumber);
        writer.bytes(packet.data.data(), packet.data.size());
    }
    writer.u32(static_cast<uint32_t>(result.talks.size()));
    for (const auto& talk : result.talks) {
        writer.u32(talk.frameIndex);
        writer.u8(talk.talking ? 1u : 0u);
        writer.f32(talk.peakEnergy);
    }
}

bool deserializeInput(const InputScenario& scenario, const std::vector<uint8_t>& bytes,
                      InputResult* result) {
    Reader reader(bytes);
    if (!readHeader(reader, kKindInput, scenario.name)) {
        return false;
    }
    const uint32_t packetCount = reader.u32();
    for (uint32_t i = 0; i < packetCount && reader.ok(); ++i) {
        RecordedPacket packet;
        packet.frameIndex = reader.u32();
        const uint32_t size = reader.u32();
        packet.frames = reader.i32();
        packet.terminator = reader.u8() != 0;
        packet.frameNumber = reader.u64();
        packet.data.resize(size);
        if (size > 0) {
            reader.bytes(packet.data.data(), size);
        }
        result->packets.push_back(std::move(packet));
    }
    const uint32_t talkCount = reader.u32();
    for (uint32_t i = 0; i < talkCount && reader.ok(); ++i) {
        RecordedTalk talk;
        talk.frameIndex = reader.u32();
        talk.talking = reader.u8() != 0;
        talk.peakEnergy = reader.f32();
        result->talks.push_back(talk);
    }
    return reader.ok();
}

void serializeOutput(const OutputScenario& scenario, const OutputResult& result,
                     Writer& writer) {
    writeHeader(writer, kKindOutput, scenario.name);
    writer.u32(static_cast<uint32_t>(result.renders.size()));
    for (const auto& render : result.renders) {
        writer.u32(render.returned);
        for (int16_t sample : render.samples) {
            writer.i16(sample);
        }
    }
    writer.u32(static_cast<uint32_t>(result.events.size()));
    for (const auto& event : result.events) {
        writer.i32(event.session);
        writer.i32(event.state);
    }
}

bool deserializeOutput(const OutputScenario& scenario,
                       const std::vector<uint8_t>& bytes, OutputResult* result) {
    Reader reader(bytes);
    if (!readHeader(reader, kKindOutput, scenario.name)) {
        return false;
    }
    const uint32_t renderCount = reader.u32();
    for (uint32_t i = 0; i < renderCount && reader.ok(); ++i) {
        RecordedRender render;
        render.returned = reader.u32();
        render.samples.resize(render.returned);
        for (uint32_t s = 0; s < render.returned && reader.ok(); ++s) {
            render.samples[s] = reader.i16();
        }
        result->renders.push_back(std::move(render));
    }
    const uint32_t eventCount = reader.u32();
    for (uint32_t i = 0; i < eventCount && reader.ok(); ++i) {
        RecordedEvent event;
        event.session = reader.i32();
        event.state = reader.i32();
        result->events.push_back(event);
    }
    return reader.ok();
}

std::string g_recordDir;

// Keeps the fixture tree in sync with the scenario list: a name here that has
// no fixture fails verification (the fixture is the pin) unless recording.
std::vector<uint8_t> readGolden(const std::string& name) {
    const std::string path = std::string(MUMLA_GOLDEN_DIR) + "/" + name + ".golden";
    std::ifstream in(path, std::ios::binary);
    std::vector<uint8_t> bytes;
    if (!in) {
        return bytes;
    }
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                                std::istreambuf_iterator<char>());
}

void writeGolden(const std::string& name, const std::vector<uint8_t>& bytes) {
    std::error_code ec;
    std::filesystem::create_directories(g_recordDir, ec);
    const std::string path = g_recordDir + "/" + name + ".golden";
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(bytes.data()),
              static_cast<std::streamsize>(bytes.size()));
    std::cout << "  recorded " << path << " (" << bytes.size() << " bytes)"
              << std::endl;
}

std::vector<uint8_t> loadRnnoiseModel() {
    std::ifstream in(MUMLA_RNNOISE_MODEL_PATH, std::ios::binary);
    if (!in) {
        return {};
    }
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                                std::istreambuf_iterator<char>());
}

// ---------------------------------------------------------------------------
// Input engine driver
// ---------------------------------------------------------------------------

// Maps a scenario control snapshot onto the engine's per-frame configuration.
mumla::audio::InputConfig toInputConfig(const InputControl& control) {
    mumla::audio::InputConfig config;
    config.mode = control.mode;
    config.transmitAllowed = !control.muted;
    config.pttPressed = control.ptt;
    config.bitrate = control.bitrate;
    config.framesPerPacket = control.framesPerPacket;
    config.amplitudeBoost = control.amplitudeBoost;
    config.rnnoiseEnabled = control.rnnoise;
    config.adaptiveLevelerEnabled = control.leveler;
    config.vadMax = control.vadMax;
    config.vadMin = control.vadMin;
    config.vadHoldFrames = control.vadHold;
    config.vadSquelchFloorDb = control.squelchDb;
    return config;
}

void runInputScenario(const InputScenario& scenario, InputResult* result) {
    const std::vector<uint8_t> model = loadRnnoiseModel();
    if (model.empty()) {
        std::cerr << "  [FAIL] RNNoise model not found at "
                  << MUMLA_RNNOISE_MODEL_PATH << std::endl;
        g_testFailures++;
        return;
    }
    const InputControl& initial = scenario.frames.front().control;
    auto encoder = mumla::audio::makeOpusVoiceEncoder(initial.bitrate);
    auto denoiser = mumla::audio::makeRnnoiseProcessor(
        initial.rnnoise, model.data(), model.size());
    AudioInputEngine engine(std::move(encoder), std::move(denoiser));

    mumla::audio::InputFrameResult frameResult;
    for (size_t frame = 0; frame < scenario.frames.size(); ++frame) {
        const InputFrame& input = scenario.frames[frame];
        engine.processFrame(input.pcm.data(), input.pcm.size(),
                            toInputConfig(input.control), &frameResult);
        if (frameResult.talkingChanged) {
            RecordedTalk talk;
            talk.frameIndex = static_cast<uint32_t>(frame);
            talk.talking = frameResult.talking;
            talk.peakEnergy = frameResult.peakEnergy;
            result->talks.push_back(talk);
        }
        for (size_t i = 0; i < frameResult.packetCount; ++i) {
            const auto& packet = frameResult.packets[i];
            RecordedPacket record;
            record.frameIndex = static_cast<uint32_t>(frame);
            record.frames = packet.frames;
            record.terminator = packet.isTerminator;
            record.frameNumber = packet.frameNumber;
            record.data.assign(packet.data, packet.data + packet.size);
            result->packets.push_back(std::move(record));
        }
    }
}

// ---------------------------------------------------------------------------
// Output engine driver
// ---------------------------------------------------------------------------

void runOutputScenario(const OutputScenario& scenario, OutputResult* result) {
    AudioOutputEngine engine([] { return mumla::audio::makeOpusOutputDecoder(); });
    std::vector<int16_t> pcm;
    for (const auto& op : scenario.ops) {
        if (op.type == OutputOp::Type::QUEUE) {
            mumla::audio::InboundPacket packet;
            packet.session = op.session;
            packet.sequence = op.sequence;
            packet.flags = op.flags;
            packet.isTerminator = op.terminator;
            if (!op.emptyPayload) {
                const auto& bytes = scenario.voices[op.voice].packets[op.packet];
                std::memcpy(packet.data, bytes.data(), bytes.size());
                packet.length = static_cast<uint16_t>(bytes.size());
            }
            engine.queuePacket(packet);
        } else {
            pcm.assign(op.samples, 0);
            mumla::audio::RenderResult renderResult;
            engine.render(pcm.data(), op.samples, &renderResult);
            RecordedRender render;
            render.returned = static_cast<uint32_t>(renderResult.samples);
            render.samples.assign(pcm.begin(), pcm.begin() + render.returned);
            result->renders.push_back(std::move(render));
            for (size_t i = 0; i < renderResult.talkEventCount; ++i) {
                result->events.push_back(
                    {renderResult.talkEvents[i].session,
                     static_cast<int32_t>(renderResult.talkEvents[i].state)});
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Comparison
// ---------------------------------------------------------------------------

const char* talkStateName(int state) {
    switch (state) {
        case 0: return "TALKING";
        case 1: return "SHOUTING";
        case 2: return "PASSIVE";
        case 3: return "WHISPERING";
        default: return "?";
    }
}

// Reports the first semantic difference; returns true when the two results
// are identical.
bool compareInput(const InputScenario& scenario, const InputResult& expected,
                  const InputResult& actual) {
    if (expected.packets.size() != actual.packets.size()) {
        std::cerr << "  [FAIL] " << scenario.name << ": expected "
                  << expected.packets.size() << " packets, got "
                  << actual.packets.size() << std::endl;
        return false;
    }
    for (size_t i = 0; i < expected.packets.size(); ++i) {
        const auto& e = expected.packets[i];
        const auto& a = actual.packets[i];
        if (e.frameIndex != a.frameIndex || e.frames != a.frames ||
            e.terminator != a.terminator || e.frameNumber != a.frameNumber ||
            e.data != a.data) {
            std::cerr << "  [FAIL] " << scenario.name << ": packet " << i
                      << " (frame " << e.frameIndex << ") differs: expected {"
                      << e.data.size() << " bytes, " << e.frames
                      << " frames, terminator=" << e.terminator
                      << ", frameNumber=" << e.frameNumber << "}, got {"
                      << a.data.size() << " bytes, " << a.frames
                      << " frames, terminator=" << a.terminator
                      << ", frameNumber=" << a.frameNumber << "}"
                      << std::endl;
            return false;
        }
    }
    if (expected.talks.size() != actual.talks.size()) {
        std::cerr << "  [FAIL] " << scenario.name << ": expected "
                  << expected.talks.size() << " talk transitions, got "
                  << actual.talks.size() << std::endl;
        return false;
    }
    for (size_t i = 0; i < expected.talks.size(); ++i) {
        const auto& e = expected.talks[i];
        const auto& a = actual.talks[i];
        if (e.frameIndex != a.frameIndex || e.talking != a.talking ||
            std::memcmp(&e.peakEnergy, &a.peakEnergy, sizeof(float)) != 0) {
            std::cerr << "  [FAIL] " << scenario.name << ": talk transition "
                      << i << " (frame " << e.frameIndex << ") differs: expected "
                      << (e.talking ? "talking" : "silent") << " peak "
                      << e.peakEnergy << ", got "
                      << (a.talking ? "talking" : "silent") << " peak "
                      << a.peakEnergy << std::endl;
            return false;
        }
    }
    return true;
}

bool compareOutput(const OutputScenario& scenario, const OutputResult& expected,
                   const OutputResult& actual) {
    if (expected.renders.size() != actual.renders.size()) {
        std::cerr << "  [FAIL] " << scenario.name << ": expected "
                  << expected.renders.size() << " renders, got "
                  << actual.renders.size() << std::endl;
        return false;
    }
    for (size_t i = 0; i < expected.renders.size(); ++i) {
        const auto& e = expected.renders[i];
        const auto& a = actual.renders[i];
        if (e.returned != a.returned) {
            std::cerr << "  [FAIL] " << scenario.name << ": render " << i
                      << " returned " << a.returned << ", expected "
                      << e.returned << std::endl;
            return false;
        }
        for (size_t s = 0; s < e.samples.size(); ++s) {
            if (e.samples[s] != a.samples[s]) {
                std::cerr << "  [FAIL] " << scenario.name << ": render " << i
                          << " sample " << s << " is " << a.samples[s]
                          << ", expected " << e.samples[s] << std::endl;
                return false;
            }
        }
    }
    if (expected.events.size() != actual.events.size()) {
        std::cerr << "  [FAIL] " << scenario.name << ": expected "
                  << expected.events.size() << " talk events, got "
                  << actual.events.size() << std::endl;
        return false;
    }
    for (size_t i = 0; i < expected.events.size(); ++i) {
        const auto& e = expected.events[i];
        const auto& a = actual.events[i];
        if (e.session != a.session || e.state != a.state) {
            std::cerr << "  [FAIL] " << scenario.name << ": talk event " << i
                      << " is {" << a.session << ", "
                      << talkStateName(a.state) << "}, expected {" << e.session
                      << ", " << talkStateName(e.state) << "}" << std::endl;
            return false;
        }
    }
    return true;
}

const char* const kInputScenarios[] = {
    "input-vad-speech",
    "input-vad-prespeech-flush",
    "input-ptt-toggles",
    "input-mid-utterance-mute",
    "input-continuous-and-toggles",
};

const char* const kOutputScenarios[] = {
    "output-in-order",
    "output-reordered",
    "output-lost",
    "output-terminator",
    "output-talk-flags",
    "output-many-senders",
};

} // namespace

void run_engine_golden_tests() {
    g_testCount++;
    const char* recordDir = std::getenv("MUMLA_RECORD_ENGINE_GOLDENS");
    if (recordDir != nullptr && recordDir[0] != '\0') {
        g_recordDir = recordDir;
    }
    const bool recording = !g_recordDir.empty();

    for (const char* name : kInputScenarios) {
        const InputScenario scenario = makeInputScenario(name);
        InputResult actual;
        runInputScenario(scenario, &actual);
        Writer writer;
        serializeInput(scenario, actual, writer);
        if (recording) {
            writeGolden(scenario.name, writer.data());
            continue;
        }
        const std::vector<uint8_t> bytes = readGolden(scenario.name);
        if (bytes.empty()) {
            std::cerr << "  [FAIL] " << scenario.name
                      << ": missing fixture (re-record with "
                         "MUMLA_RECORD_ENGINE_GOLDENS=<dir>)" << std::endl;
            g_testFailures++;
            continue;
        }
        InputResult expected;
        if (!deserializeInput(scenario, bytes, &expected)) {
            std::cerr << "  [FAIL] " << scenario.name
                      << ": fixture is unreadable or stale" << std::endl;
            g_testFailures++;
            continue;
        }
        if (!compareInput(scenario, expected, actual)) {
            g_testFailures++;
        } else {
            std::cout << "  [PASS] " << scenario.name << " (" << actual.packets.size()
                      << " packets, " << actual.talks.size() << " talk transitions)"
                      << std::endl;
        }
    }

    for (const char* name : kOutputScenarios) {
        const OutputScenario scenario = makeOutputScenario(name);
        OutputResult actual;
        runOutputScenario(scenario, &actual);
        Writer writer;
        serializeOutput(scenario, actual, writer);
        if (recording) {
            writeGolden(scenario.name, writer.data());
            continue;
        }
        const std::vector<uint8_t> bytes = readGolden(scenario.name);
        if (bytes.empty()) {
            std::cerr << "  [FAIL] " << scenario.name
                      << ": missing fixture (re-record with "
                         "MUMLA_RECORD_ENGINE_GOLDENS=<dir>)" << std::endl;
            g_testFailures++;
            continue;
        }
        OutputResult expected;
        if (!deserializeOutput(scenario, bytes, &expected)) {
            std::cerr << "  [FAIL] " << scenario.name
                      << ": fixture is unreadable or stale" << std::endl;
            g_testFailures++;
            continue;
        }
        if (!compareOutput(scenario, expected, actual)) {
            g_testFailures++;
        } else {
            std::cout << "  [PASS] " << scenario.name << " ("
                      << actual.renders.size() << " renders, "
                      << actual.events.size() << " talk events)" << std::endl;
        }
    }

    if (recording) {
        std::cout << "  [RECORDED] engine goldens written to " << g_recordDir
                  << std::endl;
    }
}
