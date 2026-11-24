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
#include "OpusVoiceDecoder.h"
#include "OpusVoiceEncoder.h"
#include "TestHarness.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <cstdint>
#include <cstring>
#include <functional>
#include <iostream>
#include <limits>
#include <memory>
#include <utility>
#include <vector>

namespace {

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::audio::IOutputDecoder;
using mumla::audio::RenderResult;

constexpr int kFrame = AudioOutputEngine::FRAME_SIZE;

/**
 * Fake decoder without libopus: every packet decodes to a constant level,
 * concealment sustains it, and each packet spans exactly one 10 ms frame.
 */
class FakeDecoder : public IOutputDecoder {
public:
    explicit FakeDecoder(float level = 0.5f) : m_level(level) {}

    int decodeFloat(const uint8_t* /*data*/, size_t /*len*/, float* out,
                    int maxSamples) override {
        const int n = std::min<int>(kFrame, maxSamples);
        for (int i = 0; i < n; ++i) {
            out[i] = m_level;
        }
        return n;
    }

    int decodeConcealment(float* out, int frameSize) override {
        for (int i = 0; i < frameSize; ++i) {
            out[i] = m_level;
        }
        return frameSize;
    }

    int packetSampleCount(const uint8_t* /*data*/, size_t /*len*/) const override {
        return kFrame;
    }

    bool isValid() const override { return true; }

    void reset() override {}

private:
    float m_level;
};

/**
 * Variable-span fake decoder: packets decode to a loud constant level for a
 * configurable span, but concealment is silence. Tests that need to tell
 * decoded (or carried-over) audio apart from loss concealment use this: any
 * loud sample must come from a real decode, never from the PLC path.
 */
class VariableSpanDecoder : public IOutputDecoder {
public:
    VariableSpanDecoder(int spanSamples, float level = 0.5f)
        : m_span(spanSamples), m_level(level) {}

    int decodeFloat(const uint8_t* /*data*/, size_t /*len*/, float* out,
                    int maxSamples) override {
        const int n = std::min(m_span, maxSamples);
        for (int i = 0; i < n; ++i) {
            out[i] = m_level;
        }
        return n;
    }

    int decodeConcealment(float* out, int frameSize) override {
        for (int i = 0; i < frameSize; ++i) {
            out[i] = 0.0f;
        }
        return frameSize;
    }

    int packetSampleCount(const uint8_t* /*data*/, size_t /*len*/) const override {
        return m_span;
    }

    bool isValid() const override { return true; }

    void reset() override {}

private:
    int m_span;
    float m_level;
};

/**
 * Two-level fake decoder: real decodes and concealment each produce a
 * distinct constant level, so tests can tell which path filled a slot.
 * Every packet spans exactly one 10 ms frame.
 */
class LevelDecoder : public IOutputDecoder {
public:
    LevelDecoder(float real, float plc) : m_real(real), m_plc(plc) {}

    int decodeFloat(const uint8_t* /*data*/, size_t /*len*/, float* out,
                    int maxSamples) override {
        const int n = std::min<int>(kFrame, maxSamples);
        for (int i = 0; i < n; ++i) {
            out[i] = m_real;
        }
        return n;
    }

    int decodeConcealment(float* out, int frameSize) override {
        for (int i = 0; i < frameSize; ++i) {
            out[i] = m_plc;
        }
        return frameSize;
    }

    int packetSampleCount(const uint8_t* /*data*/, size_t /*len*/) const override {
        return kFrame;
    }

    bool isValid() const override { return true; }

    void reset() override {}

private:
    float m_real;
    float m_plc;
};


struct TalkEvent {
    int32_t session;
    int state;
};

/**
 * Test adapter over the single-threaded engine, mirroring the interim JNI
 * seam: it forwards talk transitions returned by render to a callback, and
 * accepts the byte-pointer queuePacket shape the tests were written against.
 * Events produced by queuePacket, removeUser or clear surface on the next
 * render, exactly as the engine's RenderResult contract specifies.
 */
class TestEngine {
public:
    using TalkCallback = std::function<void(int32_t session, int state)>;

    explicit TestEngine(
        AudioOutputEngine::DecoderFactory factory,
        int marginFrames = AudioOutputEngine::DEFAULT_JITTER_MARGIN_FRAMES)
        : m_engine(std::move(factory), marginFrames) {}

    void setTalkCallback(TalkCallback callback) {
        m_callback = std::move(callback);
    }

    void queuePacket(int32_t session, const uint8_t* data, size_t len,
                     uint32_t sequence, int flags, bool isTerminator) {
        queuePacketGain(session, data, len, sequence, flags, isTerminator,
                        1.0f);
    }

    void queuePacketGain(int32_t session, const uint8_t* data, size_t len,
                         uint32_t sequence, int flags, bool isTerminator,
                         float volumeAdjustment) {
        InboundPacket packet;
        packet.session = session;
        packet.sequence = sequence;
        packet.flags = flags;
        packet.isTerminator = isTerminator;
        packet.volumeAdjustment = volumeAdjustment;
        if (data != nullptr && len > 0 && len <= AudioOutputEngine::MAX_PACKET_BYTES) {
            std::memcpy(packet.data, data, len);
            packet.length = static_cast<uint16_t>(len);
        }
        m_engine.queuePacket(packet);
    }

    size_t renderMix(int16_t* out, size_t samples) {
        RenderResult result;
        m_engine.render(out, samples, &result);
        deliver(result);
        m_lastDroppedPackets = result.droppedPackets;
        return result.samples;
    }

    void removeUser(int32_t session) { m_engine.removeUser(session); }
    void clear() { m_engine.clear(); }
    void setJitterMarginFrames(int frames) {
        m_engine.setJitterMarginFrames(frames);
    }
    size_t activeUserCount() const { return m_engine.activeUserCount(); }
    size_t jitterSlotsInUse() const { return m_engine.jitterSlotsInUse(); }
    uint32_t lastDroppedPackets() const { return m_lastDroppedPackets; }

private:
    void deliver(const RenderResult& result) {
        if (!m_callback) {
            return;
        }
        for (size_t i = 0; i < result.talkEventCount; ++i) {
            m_callback(result.talkEvents[i].session,
                       static_cast<int>(result.talkEvents[i].state));
        }
    }

    AudioOutputEngine m_engine;
    TalkCallback m_callback;
    uint32_t m_lastDroppedPackets = 0;
};

std::unique_ptr<TestEngine> makeEngine(float level,
                                       std::vector<TalkEvent>* events) {
    auto engine = std::make_unique<TestEngine>(
        [level] { return std::make_unique<FakeDecoder>(level); });
    engine->setTalkCallback([events](int32_t session, int state) {
        if (events != nullptr) {
            events->push_back({session, state});
        }
    });
    return engine;
}

std::unique_ptr<TestEngine> makeSpanEngine(int spanSamples, float level,
                                           std::vector<TalkEvent>* events) {
    auto engine = std::make_unique<TestEngine>(
        [spanSamples, level] {
            return std::make_unique<VariableSpanDecoder>(spanSamples, level);
        });
    engine->setTalkCallback([events](int32_t session, int state) {
        if (events != nullptr) {
            events->push_back({session, state});
        }
    });
    return engine;
}

std::unique_ptr<TestEngine> makeLevelEngine(float real, float plc,
                                            std::vector<TalkEvent>* events) {
    auto engine = std::make_unique<TestEngine>([real, plc] {
        return std::make_unique<LevelDecoder>(real, plc);
    });
    engine->setTalkCallback([events](int32_t session, int state) {
        if (events != nullptr) {
            events->push_back({session, state});
        }
    });
    return engine;
}

void queueOne(TestEngine& engine, int32_t session, uint32_t seq,
              int flags = 0, bool terminator = false) {
    uint8_t payload[8] = {0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68};
    engine.queuePacket(session, payload, sizeof(payload), seq, flags,
                       terminator);
}

void queueOneGain(TestEngine& engine, int32_t session, uint32_t seq,
                  float volumeAdjustment, int flags = 0,
                  bool terminator = false) {
    uint8_t payload[8] = {0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68};
    engine.queuePacketGain(session, payload, sizeof(payload), seq, flags,
                           terminator, volumeAdjustment);
}

int peakAbs(const std::vector<int16_t>& out) {
    int peak = 0;
    for (int16_t s : out) {
        peak = std::max<int>(peak, std::abs(s));
    }
    return peak;
}

// Zero jitter margin: the startup gate opens on the first queued frame, so
// tests exercise post-gate decode/mix/fade behavior without first filling
// the default (margin+1)-frame gate span.
void openGateOnFirstFrame(TestEngine& engine) {
    engine.setJitterMarginFrames(0);
}

void testSilentWithNoUsers() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    std::vector<int16_t> out(kFrame * 2, 0x1234);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()), 0u);
    TEST_ASSERT_TRUE(events.empty());
    std::cout << "  [PASS] testSilentWithNoUsers" << std::endl;
}

void testQueuedPacketProducesAudioAndTalkState() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 7, 0);
    std::vector<int16_t> out(kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(kFrame));
    // Constant 0.5 with fade-in over the block: starts near zero, ends loud.
    TEST_ASSERT(out.front() < out.back());
    TEST_ASSERT(out.back() > 8000);
    TEST_ASSERT_FALSE(events.empty());
    TEST_ASSERT_EQ(events.front().session, 7);
    TEST_ASSERT_EQ(events.front().state, 0); // TALKING
    std::cout << "  [PASS] testQueuedPacketProducesAudioAndTalkState" << std::endl;
}

void testTerminatorEndsUserAsPassive() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 9, 0, 0, true);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(events.back().session, 9);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    // After cleanup the engine is silent again instead of spinning.
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()), 0u);
    std::cout << "  [PASS] testTerminatorEndsUserAsPassive" << std::endl;
}

void testOverlappingSpeakersCompressInsteadOfWrapping() {
    g_testCount++;
    std::vector<TalkEvent> events;
    // Each voice at 0.8: linear sum 1.6 would hard-clip or wrap with int16.
    auto engine = makeEngine(0.8f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 1, 0);
    queueOne(*engine, 2, 0);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    int peak = peakAbs(out);
    // Soft knee keeps the bus hot but bounded: louder than one full-scale
    // voice would be linearly, yet strictly inside int16 range.
    TEST_ASSERT(peak > 28000 && peak <= 32767);
    std::cout << "  [PASS] testOverlappingSpeakersCompressInsteadOfWrapping (peak: "
              << peak << ")" << std::endl;
}

void testMixingIsCommutative() {
    g_testCount++;
    std::vector<int16_t> outA(kFrame, 0), outB(kFrame, 0);
    {
        std::vector<TalkEvent> events;
        auto engine = makeEngine(0.4f, &events);
        openGateOnFirstFrame(*engine);
        queueOne(*engine, 1, 0);
        queueOne(*engine, 2, 0);
        engine->renderMix(outA.data(), outA.size());
    }
    {
        std::vector<TalkEvent> events;
        auto engine = makeEngine(0.4f, &events);
        queueOne(*engine, 2, 0);
        openGateOnFirstFrame(*engine);
        queueOne(*engine, 1, 0);
        engine->renderMix(outB.data(), outB.size());
    }
    TEST_ASSERT_EQ(outA.size(), outB.size());
    for (size_t i = 0; i < outA.size(); ++i) {
        TEST_ASSERT_EQ(outA[i], outB[i]);
    }
    std::cout << "  [PASS] testMixingIsCommutative" << std::endl;
}

void testLossConcealmentBridgesGapsThenExpires() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 5, 0);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    // No further packets: concealment keeps audio alive briefly ...
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    // ... then the user expires instead of droning on forever.
    for (int i = 0; i < 40; ++i) {
        std::vector<int16_t> chunk(kFrame, 0);
        engine->renderMix(chunk.data(), chunk.size());
    }
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    std::cout << "  [PASS] testLossConcealmentBridgesGapsThenExpires" << std::endl;
}

void testRemoveUserSilencesAndEmitsPassive() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 11, 0);
    queueOne(*engine, 12, 0);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->activeUserCount(), 2u);
    engine->removeUser(11);
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    // The removal's PASSIVE arrives with the next render, never inline.
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(events.back().session, 11);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    // Removing a session that was never queued is a no-op, not a crash.
    engine->removeUser(99);
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    // The remaining user keeps talking; removing them silences the engine.
    engine->removeUser(12);
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()), 0u);
    std::cout << "  [PASS] testRemoveUserSilencesAndEmitsPassive" << std::endl;
}

void testClearResetsAllVoices() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 21, 0);
    queueOne(*engine, 22, 0);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->activeUserCount(), 2u);
    engine->clear();
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    // Silent after reset instead of spinning on stale voices.
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()), 0u);
    // The engine is reusable after a reset.
    queueOne(*engine, 23, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(kFrame));
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    std::cout << "  [PASS] testClearResetsAllVoices" << std::endl;
}

void testOutOfOrderAndGapSequence() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    // This test exercises decode-path tolerance of gaps and late arrivals;
    // open the gate on the first frame so the sequence gymnastics below
    // are not confused with startup gating (the gap-inflated span must not
    // be what opens the gate — see testGateHoldsWhenEarlyFramesLost).
    openGateOnFirstFrame(*engine);
    // Gap: seq 0 then seq 5; loss concealment bridges the missing frames.
    queueOne(*engine, 31, 0);
    queueOne(*engine, 31, 5);
    // Late arrival: an older sequence queued after newer ones is tolerated.
    queueOne(*engine, 31, 2);
    std::vector<int16_t> out(kFrame, 0);
    size_t total = 0;
    int loud = 0;
    for (int i = 0; i < 8; ++i) {
        total += engine->renderMix(out.data(), out.size());
        if (peakAbs(out) > 8000) {
            ++loud;
        }
    }
    TEST_ASSERT_EQ(total, static_cast<size_t>(8 * kFrame));
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    TEST_ASSERT(loud > 0);
    std::cout << "  [PASS] testOutOfOrderAndGapSequence" << std::endl;
}

void testFlagsMapToTalkStates() {
    g_testCount++;
    // AudioContext ordinals from the Mumble protocol: NORMAL=0, SHOUT=1,
    // WHISPER=2, LISTEN=3, INVALID=0xFF. Each maps onto the TalkState order
    // TALKING=0, SHOUTING=1, PASSIVE=2, WHISPERING=3. Anything unrecognized
    // renders as TALKING (fail-loud); an explicitly invalid context ends the
    // talk as PASSIVE.
    const int cases[][2] = {
        {0x00, 0}, // NORMAL -> TALKING
        {0x01, 1}, // SHOUT -> SHOUTING
        {0x02, 3}, // WHISPER -> WHISPERING
        {0x03, 0}, // LISTEN -> TALKING
        {0x04, 0}, // unknown -> TALKING
        {0xFF, 2}, // INVALID -> PASSIVE
    };
    for (const auto& c : cases) {
        std::vector<TalkEvent> events;
        auto engine = makeEngine(0.5f, &events);
        openGateOnFirstFrame(*engine);
        queueOne(*engine, 41, 0, c[0]);
        std::vector<int16_t> out(kFrame, 0);
        engine->renderMix(out.data(), out.size());
        TEST_ASSERT_FALSE(events.empty());
        TEST_ASSERT_EQ(events.front().session, 41);
        TEST_ASSERT_EQ(events.front().state, c[1]);
    }
    std::cout << "  [PASS] testFlagsMapToTalkStates" << std::endl;
}

void testPartialRenderOffsetLength() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 51, 0);
    // Render a sub-frame quantum into the middle of a larger buffer guarded
    // by sentinels: only the window may change.
    const int window = kFrame / 2;
    const int pad = 16;
    std::vector<int16_t> out(window + 2 * pad, 0x1234);
    TEST_ASSERT_EQ(engine->renderMix(out.data() + pad, window),
                   static_cast<size_t>(window));
    for (int i = 0; i < pad; ++i) {
        TEST_ASSERT_EQ(out[i], 0x1234);
        TEST_ASSERT_EQ(out[pad + window + i], 0x1234);
    }
    bool anyAudio = false;
    for (int i = 0; i < window; ++i) {
        if (out[pad + i] != 0) {
            anyAudio = true;
        }
    }
    TEST_ASSERT_TRUE(anyAudio);
    std::cout << "  [PASS] testPartialRenderOffsetLength" << std::endl;
}

void testExpiryBoundary() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 61, 0);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    // Exactly DEAD_MISS_FRAMES gap renders: the user is still bridged by
    // concealment and has not expired yet.
    for (int i = 0; i < AudioOutputEngine::DEAD_MISS_FRAMES; ++i) {
        engine->renderMix(out.data(), out.size());
    }
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    // One more miss crosses the boundary: the user expires as PASSIVE.
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(events.back().session, 61);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    std::cout << "  [PASS] testExpiryBoundary" << std::endl;
}

void testCarryoverPreservesOversizedBundle() {
    g_testCount++;
    // One packet decodes to three frames; the render quantum is one frame.
    // The engine must carry the decoded remainder across renders instead of
    // dropping it (concealment here is silence, so every loud render proves
    // carried-over decode output).
    std::vector<TalkEvent> events;
    auto engine = makeSpanEngine(3 * kFrame, 0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 71, 0);
    for (int i = 0; i < 3; ++i) {
        std::vector<int16_t> out(kFrame, 0);
        TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                       static_cast<size_t>(kFrame));
        TEST_ASSERT(peakAbs(out) > 8000);
    }
    std::cout << "  [PASS] testCarryoverPreservesOversizedBundle" << std::endl;
}

void testDecodeCappedAt120ms() {
    g_testCount++;
    // A packet claiming a 200 ms span must be decode-capped at the 120 ms
    // bundle limit (FRAME_SIZE * 12 = 5760 samples): the first 12 quanta are
    // loud, later quanta fall back to (silent) concealment rather than
    // delivering unbounded audio from one packet. Concealment is silence in
    // this decoder, so loudness directly tracks decoded output.
    constexpr int kCapQuanta = 12; // 5760 / 480
    std::vector<TalkEvent> events;
    auto engine = makeSpanEngine(kFrame * 20, 0.5f, &events);
    queueOne(*engine, 81, 0);
    for (int i = 0; i < kCapQuanta; ++i) {
        std::vector<int16_t> out(kFrame, 0);
        TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                       static_cast<size_t>(kFrame));
        TEST_ASSERT(peakAbs(out) > 8000);
    }
    // Past the cap, concealment (silence in this decoder) follows directly:
    // nothing blends the last loud tail into it.
    for (int i = 0; i < 4; ++i) {
        std::vector<int16_t> out(kFrame, 0x1234);
        engine->renderMix(out.data(), out.size());
        TEST_ASSERT_EQ(peakAbs(out), 0);
    }
    std::cout << "  [PASS] testDecodeCappedAt120ms" << std::endl;
}

void testEmptyTerminatorDrainsVoice() {
    g_testCount++;
    // End-of-speech with no Opus payload (the legacy zero-length terminator)
    // must still end the voice as PASSIVE instead of lingering to the
    // miss-expiry: queue speech, flag the empty terminator, then render
    // until the voice drains. A protobuf empty terminator is dropped by the
    // codec and never reaches the engine.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 91, 0);
    engine->queuePacket(91, nullptr, 0, 1, 0, true);
    std::vector<int16_t> out(kFrame, 0);
    bool drained = false;
    for (int i = 0; i < 60; ++i) {
        engine->renderMix(out.data(), out.size());
        if (engine->activeUserCount() == 0) {
            drained = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(drained);
    TEST_ASSERT_EQ(events.back().session, 91);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    std::cout << "  [PASS] testEmptyTerminatorDrainsVoice" << std::endl;
}

void testEvictsNewestVoiceWhenFull() {
    g_testCount++;
    // At MAX_VOICES the highest session id (newest voice) is evicted, so a
    // join flood cannot push out long-connected speakers.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    for (int32_t s = 1; s <= AudioOutputEngine::MAX_VOICES; ++s) {
        queueOne(*engine, s, 0);
    }
    TEST_ASSERT_EQ(engine->activeUserCount(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    queueOne(*engine, AudioOutputEngine::MAX_VOICES + 1, 0);
    TEST_ASSERT_EQ(engine->activeUserCount(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    // The oldest voice survived eviction; the previous newest did not.
    engine->removeUser(1);
    TEST_ASSERT_EQ(engine->activeUserCount(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES - 1));
    engine->removeUser(AudioOutputEngine::MAX_VOICES);
    TEST_ASSERT_EQ(engine->activeUserCount(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES - 1));
    std::cout << "  [PASS] testEvictsNewestVoiceWhenFull" << std::endl;
}

void testEvictionEmitsPassiveForTalker() {
    g_testCount++;
    // An evicted voice must be reported PASSIVE like removeUser; without
    // that, the UI keeps a talking state for a voice that no longer exists.
    // Eviction happens in queuePacket, so the PASSIVE is queued there and
    // delivered by the next render, ahead of that render's own transitions.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    for (int32_t s = 1; s <= AudioOutputEngine::MAX_VOICES; ++s) {
        queueOne(*engine, s, 0);
    }
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    // One quantum made every buffered voice talk.
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));

    // The 33rd voice evicts session 32 (highest id): PASSIVE(32) is queued and
    // arrives with the next render, followed by the new voice's TALKING.
    queueOne(*engine, AudioOutputEngine::MAX_VOICES + 1, 0);
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES + 2));
    TEST_ASSERT_EQ(events[AudioOutputEngine::MAX_VOICES].session,
                   AudioOutputEngine::MAX_VOICES);
    TEST_ASSERT_EQ(events[AudioOutputEngine::MAX_VOICES].state, 2); // PASSIVE
    TEST_ASSERT_EQ(events[AudioOutputEngine::MAX_VOICES + 1].session,
                   AudioOutputEngine::MAX_VOICES + 1);
    TEST_ASSERT_EQ(events[AudioOutputEngine::MAX_VOICES + 1].state, 0); // TALKING

    // A voice evicted before it ever rendered emits nothing: only voices with
    // a reported state can transition. Queue 35 (evicting 34) and 36
    // (evicting the never-rendered 35) back to back, then render.
    const size_t before = events.size();
    queueOne(*engine, AudioOutputEngine::MAX_VOICES + 2, 0);
    queueOne(*engine, AudioOutputEngine::MAX_VOICES + 3, 0);
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(events.size(), before + 2);
    TEST_ASSERT_EQ(events[before].session, AudioOutputEngine::MAX_VOICES + 1);
    TEST_ASSERT_EQ(events[before].state, 2); // PASSIVE for the rendered voice
    TEST_ASSERT_EQ(events[before + 1].session,
                   AudioOutputEngine::MAX_VOICES + 3);
    TEST_ASSERT_EQ(engine->activeUserCount(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    std::cout << "  [PASS] testEvictionEmitsPassiveForTalker" << std::endl;
}

void testLossConcealsInline() {
    g_testCount++;
    // Gap at seq 1 with seq 2 already buffered: the gap is concealed at once
    // (upstream parity) and the successor still decodes normally. Levels
    // below the saturation knee are linear, so real 0.2 -> 6553 and
    // PLC 0.1 -> 3277 exactly.
    std::vector<TalkEvent> events;
    auto engine = makeLevelEngine(0.2f, 0.1f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 101, 0);
    queueOne(*engine, 101, 2);
    std::vector<int16_t> out(3 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(3 * kFrame));
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 3277);
    TEST_ASSERT_EQ(out[5 * kFrame / 2], 6553);
    std::cout << "  [PASS] testLossConcealsInline" << std::endl;
}

void testBurstLossConcealsEachFrame() {
    g_testCount++;
    // Two consecutive gaps are each concealed in place; the successor after
    // the burst decodes normally.
    std::vector<TalkEvent> events;
    auto engine = makeLevelEngine(0.2f, 0.1f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 106, 0);
    queueOne(*engine, 106, 3);
    std::vector<int16_t> out(4 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(4 * kFrame));
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 3277);
    TEST_ASSERT_EQ(out[5 * kFrame / 2], 3277);
    TEST_ASSERT_EQ(out[7 * kFrame / 2], 6553);
    std::cout << "  [PASS] testBurstLossConcealsEachFrame" << std::endl;
}

void testLossJointsAreNotBlended() {
    g_testCount++;
    // Chunks join as decoded: the concealed slot is uniform PLC to its first
    // and last sample, and the real frames around it are untouched up to the
    // joint. (The removed crossfade replayed the next chunk's first 2 ms over
    // the previous chunk's tail.)
    std::vector<TalkEvent> events;
    auto engine = makeLevelEngine(0.2f, 0.1f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 104, 0);
    queueOne(*engine, 104, 1);
    queueOne(*engine, 104, 3);
    std::vector<int16_t> out(4 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(4 * kFrame));
    for (int i = kFrame; i < 2 * kFrame; ++i) {
        TEST_ASSERT_EQ(out[i], 6553);
    }
    for (int i = 2 * kFrame; i < 3 * kFrame; ++i) {
        TEST_ASSERT_EQ(out[i], 3277);
    }
    for (int i = 3 * kFrame; i < 4 * kFrame; ++i) {
        TEST_ASSERT_EQ(out[i], 6553);
    }
    std::cout << "  [PASS] testLossJointsAreNotBlended" << std::endl;
}

void testRealOpusLossStaysContinuous() {
    g_testCount++;
    // End to end with the real codec: a 300 Hz tone encoded in 10 ms packets
    // (the production encoder) with one packet dropped. Opus PLC continues
    // the waveform and the next decode resumes from it, so no sample-to-
    // sample step near the loss may be much larger than the tone's own
    // steepest slope. Rendering in 20 ms quanta with the loss in a quantum's
    // second frame is the layout where the removed crossfade replayed 2 ms
    // and stepped back in time at the joint.
    mumla::audio::OpusVoiceEncoder encoder(40000);
    TEST_ASSERT_TRUE(encoder.isValid());
    TestEngine engine([] { return mumla::audio::makeOpusOutputDecoder(); }, 0);
    constexpr int kPackets = 40;
    constexpr int kLost = 21;
    constexpr double kAmplitude = 8000.0;
    constexpr double kHz = 300.0;
    constexpr double kTwoPi = 2.0 * 3.14159265358979323846;
    std::vector<int16_t> pcm(kFrame);
    std::vector<uint8_t> packet(1500);
    for (int seq = 0; seq < kPackets; ++seq) {
        for (int i = 0; i < kFrame; ++i) {
            const double t = static_cast<double>(seq * kFrame + i) / 48000.0;
            pcm[i] = static_cast<int16_t>(
                std::lround(kAmplitude * std::sin(kTwoPi * kHz * t)));
        }
        const int len = encoder.encode(pcm.data(), kFrame, packet.data(),
                                       packet.size());
        TEST_ASSERT_TRUE(len > 0);
        if (seq != kLost) {
            engine.queuePacket(7, packet.data(), static_cast<size_t>(len),
                               static_cast<uint32_t>(seq), 0, false);
        }
    }
    std::vector<int16_t> out;
    std::vector<int16_t> quantum(2 * kFrame);
    for (int q = 0; q < kPackets / 2; ++q) {
        engine.renderMix(quantum.data(), quantum.size());
        out.insert(out.end(), quantum.begin(), quantum.end());
    }
    // The tone's steepest slope after codec scaling, with generous room for
    // PLC's own decay and pitch drift: still far below the half-amplitude
    // jumps a 2 ms replay produces at 300 Hz.
    const double toneStep = kAmplitude * kTwoPi * kHz / 48000.0;
    int maxStep = 0;
    for (int i = (kLost - 2) * kFrame; i < (kLost + 3) * kFrame; ++i) {
        maxStep = std::max(maxStep, std::abs(out[i + 1] - out[i]));
    }
    TEST_ASSERT_TRUE(peakAbs(out) > 4000);
    TEST_ASSERT_TRUE(maxStep < static_cast<int>(2.0 * toneStep));
    std::cout << "  [PASS] testRealOpusLossStaysContinuous (maxStep: "
              << maxStep << ", toneStep: " << static_cast<int>(toneStep)
              << ")" << std::endl;
}

void testLosslessMixIsBitExact() {
    g_testCount++;
    // No loss: steady-state samples are the decoded level, bit-exact.
    std::vector<TalkEvent> events;
    auto engine = makeLevelEngine(0.2f, 0.0f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 105, 0);
    queueOne(*engine, 105, 1);
    queueOne(*engine, 105, 2);
    std::vector<int16_t> out(3 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(3 * kFrame));
    for (int i = kFrame; i < 2 * kFrame; ++i) {
        TEST_ASSERT_EQ(out[i], 6553);
    }
    std::cout << "  [PASS] testLosslessMixIsBitExact" << std::endl;
}

void testStartupGatesUntilMarginQueued() {
    g_testCount++;
    // Startup contract (upstream parity): a fresh voice stays silent until
    // the jitter buffer holds margin+1 frames of audio, emitting zero PCM
    // so the render loop keeps the track primed and write-paced while the
    // buffer fills. Once that span is queued, the first audible frame
    // fades in and the second is bit-exact (0.5 level, linear knee: 16384).
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 111, 0);
    std::vector<int16_t> out(2 * kFrame, 0x1234);
    // One 10 ms frame queued: below the (4+1)-frame default gate. Silent
    // but rendered (the caller keeps feeding the track), still live, and
    // not yet talking.
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT_EQ(peakAbs(out), 0);
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    TEST_ASSERT_TRUE(events.empty());
    // Five frames queued in total: the gate opens and audio flows.
    for (uint32_t i = 1; i <= 4; ++i) {
        queueOne(*engine, 111, i);
    }
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT(out.front() < out[kFrame]); // onset fade-in on frame 1
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 16384); // frame 2 bit-exact
    TEST_ASSERT_EQ(events.front().session, 111);
    TEST_ASSERT_EQ(events.front().state, 0); // TALKING
    std::cout << "  [PASS] testStartupGatesUntilMarginQueued" << std::endl;
}

void testGateTimeoutStartsLoneVoice() {
    g_testCount++;
    // A lone blip that never fills the gate must not park silent forever:
    // after GATE_TIMEOUT_FRAMES of gated quanta the voice force-starts,
    // plays its packet with the onset fade, and expires via the normal
    // miss path from then on.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 112, 0);
    std::vector<int16_t> out(kFrame, 0);
    for (int i = 0; i < AudioOutputEngine::GATE_TIMEOUT_FRAMES - 1; ++i) {
        TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                       static_cast<size_t>(kFrame));
        TEST_ASSERT_EQ(peakAbs(out), 0);
    }
    // The timeout quantum force-starts the voice and plays the packet.
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(kFrame));
    TEST_ASSERT(peakAbs(out) > 8000);
    TEST_ASSERT_EQ(events.front().session, 112);
    TEST_ASSERT_EQ(events.front().state, 0); // TALKING
    std::cout << "  [PASS] testGateTimeoutStartsLoneVoice" << std::endl;
}

void testGateHoldsWhenEarlyFramesLost() {
    g_testCount++;
    // In-transit loss must not open the gate early: the gate counts the
    // union of accepted packet intervals, so a burst whose frames 1-2 are
    // lost (seq 0, 3, 4 arrive) still measures only 3 queued frames at the
    // default margin and keeps holding. A wall-clock span would read the
    // 0->4 span (5 frames) and blurt into concealment.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 113, 0);
    queueOne(*engine, 113, 3);
    queueOne(*engine, 113, 4);
    std::vector<int16_t> out(2 * kFrame, 0x1234);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT_EQ(peakAbs(out), 0); // held: only 3 of 5 frames arrived
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    // A late fill of the hole (seq 2) extends coverage downward only: it
    // must not open the gate (and must not be counted), matching the
    // safe over-hold direction of the monotonic union end.
    queueOne(*engine, 113, 2);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT_EQ(peakAbs(out), 0); // still held: 3 counted frames
    // Two more arriving frames push the counted union to 5 frames and
    // the gate opens with real audio behind it.
    queueOne(*engine, 113, 5);
    queueOne(*engine, 113, 6);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT(peakAbs(out) > 8000);
    std::cout << "  [PASS] testGateHoldsWhenEarlyFramesLost" << std::endl;
}

void testEmptyTerminatorBypassesGate() {
    g_testCount++;
    // A short final burst (fewer frames than the gate span) must drain on
    // an empty terminator at the default margin, not sit out the gate
    // timeout: the terminator latches the gate bypass at queue time.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 114, 0);
    engine->queuePacket(114, nullptr, 0, 1, 0, true);
    std::vector<int16_t> out(kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(kFrame));
    TEST_ASSERT(peakAbs(out) > 8000); // played despite the sub-span burst
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(events.back().session, 114);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    std::cout << "  [PASS] testEmptyTerminatorBypassesGate" << std::endl;
}

void testPayloadTerminatorFirstPacketBypassesGate() {
    g_testCount++;
    // A payload-carrying terminator as a burst's first packet: the drain
    // flag latches at dequeue, which a gated voice never reaches, so the
    // bypass must come from the queue-time latch. The single frame plays
    // on the first render instead of sitting out 200 ms of gate timeout.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 115, 0, 0, true);
    std::vector<int16_t> out(kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(kFrame));
    TEST_ASSERT(peakAbs(out) > 8000);
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(events.back().session, 115);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    std::cout << "  [PASS] testPayloadTerminatorFirstPacketBypassesGate"
              << std::endl;
}

void testGateArithmeticSurvivesSequenceWrap() {
    g_testCount++;
    // Frame timestamps are FRAME_SIZE * sequence in uint32 sample units
    // and wrap after ~24.9 h of stream time. The gate's signed-diff union
    // arithmetic must keep counting audio correctly across the rollover:
    // a burst straddling the wrap opens the gate exactly like one that
    // does not.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 116, 0xFFFFFFFEu);
    queueOne(*engine, 116, 0xFFFFFFFFu);
    for (uint32_t i = 0; i < 5; ++i) {
        queueOne(*engine, 116, i);
    }
    std::vector<int16_t> out(2 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT(peakAbs(out) > 8000); // 7 frames queued: gate opens
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    std::cout << "  [PASS] testGateArithmeticSurvivesSequenceWrap"
              << std::endl;
}


// The shared packet store is bounded: once every slot is held, further
// packets are dropped and counted rather than growing the heap.
void testJitterPoolExhaustionDropsAndCounts() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);

    // Eight voices queueing 200 packets each: 1600 attempts against 1024
    // slots. No render runs in between, so nothing is consumed.
    const int voices = 8;
    const uint32_t perVoice = 200;
    for (int32_t s = 1; s <= voices; ++s) {
        for (uint32_t p = 0; p < perVoice; ++p) {
            queueOne(*engine, s, p);
        }
    }
    TEST_ASSERT_EQ(engine->jitterSlotsInUse(),
                   AudioOutputEngine::JITTER_SLOT_COUNT);

    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    const uint32_t expectedDrops =
        static_cast<uint32_t>(voices) * perVoice -
        static_cast<uint32_t>(AudioOutputEngine::JITTER_SLOT_COUNT);
    TEST_ASSERT_EQ(engine->lastDroppedPackets(), expectedDrops);

    // Drops are reported once, for the render that followed them.
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->lastDroppedPackets(), 0u);

    // Rendering returns slots to the store, so new packets are accepted again.
    TEST_ASSERT_TRUE(engine->jitterSlotsInUse() <
                     AudioOutputEngine::JITTER_SLOT_COUNT);
    queueOne(*engine, 99, 0);
    TEST_ASSERT_TRUE(engine->activeUserCount() >= 1u);
    std::cout << "  [PASS] testJitterPoolExhaustionDropsAndCounts (" 
              << expectedDrops << " dropped)" << std::endl;
}

// Pool sizing evidence: 32 voices under heavy jitter adaptation for 60
// simulated seconds must never exhaust the store. The peak is printed so the
// slot count can be set from data rather than guessed.
// Releasing a voice returns its queued packet bytes to the shared store at
// once. The pre-refactor engine destroyed the jitter buffer on removeUser and
// clear, and a released voice never renders again, so holding its slots until
// the pool slot is reused would only shrink the store available to live
// speakers.
void testReleasedVoiceReturnsPoolSlots() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);

    for (int i = 0; i < 4; ++i) {
        queueOne(*engine, 11, static_cast<uint32_t>(i));
        queueOne(*engine, 12, static_cast<uint32_t>(i));
    }
    TEST_ASSERT_EQ(engine->jitterSlotsInUse(), 8u);

    engine->removeUser(11);
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
    TEST_ASSERT_EQ(engine->jitterSlotsInUse(), 4u);

    engine->clear();
    TEST_ASSERT_EQ(engine->activeUserCount(), 0u);
    TEST_ASSERT_EQ(engine->jitterSlotsInUse(), 0u);

    std::cout << "  [PASS] testReleasedVoiceReturnsPoolSlots" << std::endl;
}

void testJitterPoolStress() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    // The production margin (4 frames) is kept: the buffer holds the promised
    // cushion, which is what the store must cover per voice.

    constexpr int kVoices = AudioOutputEngine::MAX_VOICES;
    constexpr int kFrames = 6000; // 60 s of 10 ms frames
    uint32_t rng = 0x9E3779B9u;
    auto next = [&rng]() {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        return rng;
    };

    size_t peakSlots = 0;
    uint32_t drops = 0;
    std::vector<int16_t> out(kFrame, 0);
    // One 10 ms packet per voice per 20 ms on average, with deterministic
    // loss, reordering and compression bursts: a burst delivers the next few
    // packets at once and the following rounds are skipped, so the mean rate
    // matches consumption while the buffers repeatedly fill and drain under
    // adaptation.
    uint32_t sequence[kVoices] = {};
    int skipRounds[kVoices] = {};
    for (int frame = 0; frame < kFrames; ++frame) {
        if (frame % 2 == 0) {
            for (int v = 0; v < kVoices; ++v) {
                if (skipRounds[v] > 0) {
                    --skipRounds[v];
                    continue;
                }
                const int burst = (next() % 4 == 0) ? 3 : 1;
                skipRounds[v] = burst - 1;
                for (int k = 0; k < burst; ++k) {
                    const uint32_t roll = next() % 100;
                    if (roll < 3) {
                        sequence[v] += 1; // lost packet: leave a hole
                    }
                    const uint32_t seq = sequence[v];
                    sequence[v] += 1;
                    if (roll >= 3 && roll < 8) {
                        // Reordered: send the next packet first, then this one.
                        queueOne(*engine, 1000 + v, seq + 1);
                    }
                    queueOne(*engine, 1000 + v, seq);
                }
            }
        }
        engine->renderMix(out.data(), out.size());
        drops += engine->lastDroppedPackets();
        peakSlots = std::max(peakSlots, engine->jitterSlotsInUse());
    }

    TEST_ASSERT_EQ(drops, 0u);
    TEST_ASSERT_TRUE(peakSlots > 0u);
    // The production count must keep at least twice the measured peak as
    // headroom, so lowering JITTER_SLOT_COUNT toward the observed demand
    // fails here rather than passing an assertion that only checks the peak
    // fits at all.
    TEST_ASSERT_TRUE(peakSlots * 2 <= AudioOutputEngine::JITTER_SLOT_COUNT);
    std::cout << "  [PASS] testJitterPoolStress (peak " << peakSlots
              << " of " << AudioOutputEngine::JITTER_SLOT_COUNT << " slots)"
              << std::endl;
}


// Capacity contract for talk events: one queue-time plus one mix-time
// transition per voice must fit one result, so nothing ever spills or is
// dropped. Pinned by arithmetic and behaviorally: a clear of all voices
// followed by a full re-population delivers all 64 transitions in a single
// render's result, queue-time ones first.
void testTalkEventCapacityContract() {
    g_testCount++;
    RenderResult sizing;
    TEST_ASSERT_TRUE(std::size(sizing.talkEvents) >=
                     static_cast<size_t>(2 * AudioOutputEngine::MAX_VOICES));
    TEST_ASSERT_TRUE(static_cast<size_t>(AudioOutputEngine::MAX_PENDING_TALK_EVENTS) >=
                     static_cast<size_t>(2 * AudioOutputEngine::MAX_VOICES));

    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    for (int32_t s = 1; s <= AudioOutputEngine::MAX_VOICES; ++s) {
        queueOne(*engine, s, 0);
    }
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    const size_t talkReported = events.size();
    TEST_ASSERT_EQ(talkReported,
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    for (const auto& event : events) {
        TEST_ASSERT_EQ(event.state, 0); // TALKING
    }

    // Queue-time PASSIVE for every voice, then re-populate and render.
    engine->clear();
    for (int32_t s = 101; s <= 100 + AudioOutputEngine::MAX_VOICES; ++s) {
        queueOne(*engine, s, 0);
    }
    events.clear();
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(2 * AudioOutputEngine::MAX_VOICES));
    for (int i = 0; i < AudioOutputEngine::MAX_VOICES; ++i) {
        TEST_ASSERT_EQ(events[i].state, 2); // PASSIVE, queue-time first
    }
    for (int i = 0; i < AudioOutputEngine::MAX_VOICES; ++i) {
        TEST_ASSERT_EQ(events[AudioOutputEngine::MAX_VOICES + i].state,
                       0); // TALKING, mix-time second
    }
    std::cout << "  [PASS] testTalkEventCapacityContract" << std::endl;
}

// The server's per-packet volume adjustment is a plain gain on that packet's
// decoded audio: half in, half out, with the same fades and mix path.
void testVolumeAdjustmentScalesDecodedAudio() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto unity = makeEngine(0.5f, &events);
    auto half = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*unity);
    openGateOnFirstFrame(*half);
    queueOneGain(*unity, 21, 0, 1.0f);
    queueOneGain(*half, 21, 0, 0.5f);
    std::vector<int16_t> loud(kFrame, 0);
    std::vector<int16_t> quiet(kFrame, 0);
    TEST_ASSERT_EQ(unity->renderMix(loud.data(), loud.size()),
                   static_cast<size_t>(kFrame));
    TEST_ASSERT_EQ(half->renderMix(quiet.data(), quiet.size()),
                   static_cast<size_t>(kFrame));
    // Fades are identical on both sides, so every sample halves, to within
    // the int16 rounding at the output stage.
    for (size_t i = 0; i < loud.size(); ++i) {
        const int expected = loud[i] / 2;
        TEST_ASSERT_TRUE(std::abs(static_cast<int>(quiet[i]) - expected) <= 1);
    }
    TEST_ASSERT_TRUE(peakAbs(quiet) > 0);
    std::cout << "  [PASS] testVolumeAdjustmentScalesDecodedAudio" << std::endl;
}

// A loss keeps the sender's last adjustment: concealment stands in for a
// missing packet, and snapping back to unity there would pump the volume.
void testVolumeAdjustmentSurvivesLoss() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto unity = makeEngine(0.5f, &events);
    auto quarter = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*unity);
    openGateOnFirstFrame(*quarter);
    queueOneGain(*unity, 22, 0, 1.0f);
    queueOneGain(*quarter, 22, 0, 0.25f);
    std::vector<int16_t> scratch(kFrame, 0);
    unity->renderMix(scratch.data(), scratch.size());
    quarter->renderMix(scratch.data(), scratch.size());
    // No packet for the next slot: concealment carries the last gain.
    std::vector<int16_t> loud(kFrame, 0);
    std::vector<int16_t> quiet(kFrame, 0);
    unity->renderMix(loud.data(), loud.size());
    quarter->renderMix(quiet.data(), quiet.size());
    TEST_ASSERT_TRUE(peakAbs(quiet) > 0);
    // Within a tenth: the concealed chunk passes the same fades and the int16
    // output stage rounds, so the quarter gain shows up as a quarter, not
    // exactly.
    const int quarterPeak = peakAbs(quiet) * 4;
    TEST_ASSERT_TRUE(std::abs(quarterPeak - peakAbs(loud)) <=
                     peakAbs(loud) / 10);
    std::cout << "  [PASS] testVolumeAdjustmentSurvivesLoss" << std::endl;
}

// A peer can send a negative, zero or non-finite factor; the engine keeps
// unity rather than inverting or silencing the voice. The parser filters the
// same values, but direct engine callers (and interop) must be safe too.
void testVolumeAdjustmentRejectsInvalidGain() {
    g_testCount++;
    const float invalid[] = {0.0f,
                             -1.0f,
                             -0.0f,
                             std::numeric_limits<float>::quiet_NaN(),
                             std::numeric_limits<float>::infinity(),
                             -std::numeric_limits<float>::infinity()};
    for (float gain : invalid) {
        std::vector<TalkEvent> events;
        auto unity = makeEngine(0.5f, &events);
        auto odd = makeEngine(0.5f, &events);
        openGateOnFirstFrame(*unity);
        openGateOnFirstFrame(*odd);
        queueOneGain(*unity, 23, 0, 1.0f);
        queueOneGain(*odd, 23, 0, gain);
        std::vector<int16_t> loud(kFrame, 0);
        std::vector<int16_t> same(kFrame, 0);
        unity->renderMix(loud.data(), loud.size());
        odd->renderMix(same.data(), same.size());
        for (size_t i = 0; i < loud.size(); ++i) {
            TEST_ASSERT_EQ(same[i], loud[i]);
        }
    }
    std::cout << "  [PASS] testVolumeAdjustmentRejectsInvalidGain" << std::endl;
}

// Gains above unity are legal (the server can boost a listener). Below the
// saturation knee the output is linear, so doubling the gain doubles every
// sample through the same fade-in.
void testVolumeAdjustmentAmplifiesAudio() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto unity = makeEngine(0.2f, &events);
    auto louder = makeEngine(0.2f, &events);
    openGateOnFirstFrame(*unity);
    openGateOnFirstFrame(*louder);
    queueOneGain(*unity, 25, 0, 1.0f);
    queueOneGain(*louder, 25, 0, 2.0f);
    std::vector<int16_t> base(kFrame, 0);
    std::vector<int16_t> doubled(kFrame, 0);
    TEST_ASSERT_EQ(unity->renderMix(base.data(), base.size()),
                   static_cast<size_t>(kFrame));
    TEST_ASSERT_EQ(louder->renderMix(doubled.data(), doubled.size()),
                   static_cast<size_t>(kFrame));
    for (size_t i = 0; i < base.size(); ++i) {
        TEST_ASSERT_TRUE(std::abs(static_cast<int>(doubled[i]) -
                                  static_cast<int>(base[i]) * 2) <= 1);
    }
    TEST_ASSERT_TRUE(peakAbs(doubled) > peakAbs(base));
    std::cout << "  [PASS] testVolumeAdjustmentAmplifiesAudio" << std::endl;
}

// Two packets with different gains sit in the jitter buffer at once and are
// dequeued in sequence order even when inserted out of order: the gain must
// follow its own packet, not the insertion order or the last arrival.
void testVolumeAdjustmentFollowsPacketThroughJitter() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto reference = makeEngine(0.5f, &events);
    auto mixed = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*reference);
    openGateOnFirstFrame(*mixed);
    // Insert the later frame first on both engines; the jitter buffer must
    // return the earlier frame first, with its own gain.
    queueOneGain(*reference, 26, 1, 1.0f);
    queueOneGain(*reference, 26, 0, 1.0f);
    queueOneGain(*mixed, 26, 1, 0.25f);
    queueOneGain(*mixed, 26, 0, 1.0f);
    const size_t count = 2 * kFrame;
    std::vector<int16_t> base(count, 0);
    std::vector<int16_t> scaled(count, 0);
    TEST_ASSERT_EQ(reference->renderMix(base.data(), base.size()), count);
    TEST_ASSERT_EQ(mixed->renderMix(scaled.data(), scaled.size()), count);
    // First 10 ms: both gains are unity, so the frame matches exactly despite
    // the shared fade-in. Second 10 ms: only the mixed engine's later frame
    // carries 0.25, to within the int16 output rounding.
    for (int i = 0; i < kFrame; ++i) {
        TEST_ASSERT_EQ(scaled[i], base[i]);
    }
    for (int i = kFrame; i < 2 * kFrame; ++i) {
        TEST_ASSERT_TRUE(std::abs(static_cast<int>(scaled[i]) -
                                  static_cast<int>(base[i]) / 4) <= 1);
    }
    std::cout << "  [PASS] testVolumeAdjustmentFollowsPacketThroughJitter"
              << std::endl;
}

// With no terminator at all (what an empty protobuf terminator now becomes,
// since the codec drops it), the voice ends by the dead-miss expiry rather
// than an immediate drain. Contrast testEmptyTerminatorDrainsVoice, which
// feeds a legacy zero-length terminator straight to the engine.
void testMissingTerminatorExpiresByDeadMiss() {
    g_testCount++;
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    openGateOnFirstFrame(*engine);
    queueOne(*engine, 93, 0);
    std::vector<int16_t> out(kFrame, 0);
    int quanta = 0;
    for (int i = 0; i < 60; ++i) {
        ++quanta;
        engine->renderMix(out.data(), out.size());
        if (engine->activeUserCount() == 0) {
            break;
        }
    }
    TEST_ASSERT_EQ(engine->activeUserCount(), static_cast<size_t>(0));
    // One 10 ms miss per quantum: never the first quantum, and at least the
    // DEAD_MISS_FRAMES misses (~100 ms) that upstream parity accepts.
    TEST_ASSERT_TRUE(quanta > 1);
    TEST_ASSERT_TRUE(quanta >= AudioOutputEngine::DEAD_MISS_FRAMES);
    TEST_ASSERT_TRUE(!events.empty());
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    std::cout << "  [PASS] testMissingTerminatorExpiresByDeadMiss" << std::endl;
}

} // namespace

void run_audio_output_engine_tests() {
    std::cout << "--- AudioOutputEngine Tests ---" << std::endl;
    testSilentWithNoUsers();
    testQueuedPacketProducesAudioAndTalkState();
    testTerminatorEndsUserAsPassive();
    testOverlappingSpeakersCompressInsteadOfWrapping();
    testMixingIsCommutative();
    testStartupGatesUntilMarginQueued();
    testGateTimeoutStartsLoneVoice();
    testGateHoldsWhenEarlyFramesLost();
    testEmptyTerminatorBypassesGate();
    testPayloadTerminatorFirstPacketBypassesGate();
    testGateArithmeticSurvivesSequenceWrap();
    testLossConcealmentBridgesGapsThenExpires();
    testRemoveUserSilencesAndEmitsPassive();
    testClearResetsAllVoices();
    testOutOfOrderAndGapSequence();
    testFlagsMapToTalkStates();
    testPartialRenderOffsetLength();
    testExpiryBoundary();
    testCarryoverPreservesOversizedBundle();
    testDecodeCappedAt120ms();
    testEmptyTerminatorDrainsVoice();
    testEvictsNewestVoiceWhenFull();
    testEvictionEmitsPassiveForTalker();
    testLossConcealsInline();
    testBurstLossConcealsEachFrame();
    testLossJointsAreNotBlended();
    testRealOpusLossStaysContinuous();
    testLosslessMixIsBitExact();
    testVolumeAdjustmentScalesDecodedAudio();
    testVolumeAdjustmentSurvivesLoss();
    testVolumeAdjustmentRejectsInvalidGain();
    testVolumeAdjustmentAmplifiesAudio();
    testVolumeAdjustmentFollowsPacketThroughJitter();
    testMissingTerminatorExpiresByDeadMiss();
    testJitterPoolExhaustionDropsAndCounts();
    testReleasedVoiceReturnsPoolSlots();
    testJitterPoolStress();
    testTalkEventCapacityContract();
}
