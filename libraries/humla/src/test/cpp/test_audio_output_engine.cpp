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
#include "TestHarness.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <memory>
#include <vector>

namespace {

using mumla::audio::AudioOutputEngine;
using mumla::audio::IOutputDecoder;

constexpr int kFrame = AudioOutputEngine::FRAME_SIZE;

/**
 * Fake decoder without libopus: every packet decodes to a constant level,
 * concealment sustains it, and each packet spans exactly one 10 ms frame.
 */
class FakeDecoder : public IOutputDecoder {
public:
    explicit FakeDecoder(float level = 0.5f) : m_level(level) {}

    int decodeFloat(const uint8_t* /*data*/, size_t /*len*/, float* out,
                    int maxSamples, int /*decodeFec*/) override {
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
                    int maxSamples, int decodeFec) override {
        if (decodeFec != 0) {
            // No LBRR payload exists in this fake: FEC is unavailable, so the
            // engine must fall back to concealment for the debt slot.
            return -1;
        }
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
 * FEC-aware fake decoder: real decodes, FEC recoveries, and concealment each
 * produce a distinct constant level, so tests can tell which path filled a
 * gap slot. fecFails simulates a packet without LBRR data. Every packet
 * spans exactly one 10 ms frame.
 */
class FecAwareDecoder : public IOutputDecoder {
public:
    FecAwareDecoder(float real, float fec, float plc, bool fecFails,
                    int* fecAttempts)
        : m_real(real),
          m_fec(fec),
          m_plc(plc),
          m_fecFails(fecFails),
          m_fecAttempts(fecAttempts) {}

    int decodeFloat(const uint8_t* /*data*/, size_t /*len*/, float* out,
                    int maxSamples, int decodeFec) override {
        if (decodeFec != 0) {
            if (m_fecAttempts != nullptr) {
                ++(*m_fecAttempts);
            }
            if (m_fecFails) {
                return -1;
            }
            const int n = std::min<int>(kFrame, maxSamples);
            for (int i = 0; i < n; ++i) {
                out[i] = m_fec;
            }
            return n;
        }
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
    float m_fec;
    float m_plc;
    bool m_fecFails;
    int* m_fecAttempts;
};


struct TalkEvent {
    int32_t session;
    int state;
};

std::unique_ptr<AudioOutputEngine> makeEngine(float level,
                                              std::vector<TalkEvent>* events) {
    auto engine = std::make_unique<AudioOutputEngine>(
        [level] { return std::make_unique<FakeDecoder>(level); });
    engine->setTalkCallback(
        [events](int32_t session, int state) {
            if (events != nullptr) {
                events->push_back({session, state});
            }
        });
    return engine;
}

std::unique_ptr<AudioOutputEngine> makeSpanEngine(int spanSamples, float level,
                                                  std::vector<TalkEvent>* events) {
    auto engine = std::make_unique<AudioOutputEngine>(
        [spanSamples, level] {
            return std::make_unique<VariableSpanDecoder>(spanSamples, level);
        });
    engine->setTalkCallback(
        [events](int32_t session, int state) {
            if (events != nullptr) {
                events->push_back({session, state});
            }
        });
    return engine;
}

std::unique_ptr<AudioOutputEngine> makeFecEngine(float real, float fec,
                                                float plc, bool fecFails,
                                                int* fecAttempts,
                                                std::vector<TalkEvent>* events) {
    auto engine = std::make_unique<AudioOutputEngine>(
        [real, fec, plc, fecFails, fecAttempts] {
            return std::make_unique<FecAwareDecoder>(real, fec, plc, fecFails,
                                                     fecAttempts);
        });
    engine->setTalkCallback(
        [events](int32_t session, int state) {
            if (events != nullptr) {
                events->push_back({session, state});
            }
        });
    return engine;
}

void queueOne(AudioOutputEngine& engine, int32_t session, uint32_t seq,
              int flags = 0, bool terminator = false) {
    uint8_t payload[8] = {0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68};
    engine.queuePacket(session, payload, sizeof(payload), seq, flags,
                       terminator);
}

int peakAbs(const std::vector<int16_t>& out) {
    int peak = 0;
    for (int16_t s : out) {
        peak = std::max<int>(peak, std::abs(s));
    }
    return peak;
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
        queueOne(*engine, 1, 0);
        queueOne(*engine, 2, 0);
        engine->renderMix(outA.data(), outA.size());
    }
    {
        std::vector<TalkEvent> events;
        auto engine = makeEngine(0.4f, &events);
        queueOne(*engine, 2, 0);
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
    queueOne(*engine, 11, 0);
    queueOne(*engine, 12, 0);
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    TEST_ASSERT_EQ(engine->activeUserCount(), 2u);
    engine->removeUser(11);
    TEST_ASSERT_EQ(engine->activeUserCount(), 1u);
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
    // The first silent quantum still carries the 2 ms loss-boundary blend
    // from the last loud tail into the silence head; past the blend window
    // it is exactly silent, and later quanta are fully silent.
    {
        std::vector<int16_t> out(kFrame, 0x1234);
        engine->renderMix(out.data(), out.size());
        constexpr int kXf = AudioOutputEngine::XFADE_SAMPLES;
        TEST_ASSERT(peakAbs(out) > 0); // blend head present
        for (int i = kXf; i < kFrame; ++i) {
            TEST_ASSERT_EQ(out[i], 0);
        }
    }
    for (int i = 0; i < 3; ++i) {
        std::vector<int16_t> out(kFrame, 0x1234);
        engine->renderMix(out.data(), out.size());
        TEST_ASSERT_EQ(peakAbs(out), 0);
    }
    std::cout << "  [PASS] testDecodeCappedAt120ms" << std::endl;
}

void testEmptyTerminatorDrainsVoice() {
    g_testCount++;
    // End-of-speech with no Opus payload (empty protobuf/UDP terminator)
    // must still end the voice as PASSIVE instead of lingering to the
    // miss-expiry: queue speech, flag the empty terminator, then render
    // until the voice drains.
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
    // Regression test: eviction previously freed the voice silently.
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    for (int32_t s = 1; s <= AudioOutputEngine::MAX_VOICES; ++s) {
        queueOne(*engine, s, 0);
    }
    std::vector<int16_t> out(kFrame, 0);
    engine->renderMix(out.data(), out.size());
    // One quantum made every buffered voice talk.
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    // The 33rd voice evicts session 32 (highest id). Its PASSIVE arrives
    // synchronously from queuePacket on the calling thread.
    queueOne(*engine, AudioOutputEngine::MAX_VOICES + 1, 0);
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES + 1));
    TEST_ASSERT_EQ(events.back().session, AudioOutputEngine::MAX_VOICES);
    TEST_ASSERT_EQ(events.back().state, 2); // PASSIVE
    // The replacement voice (33) never rendered, so evicting it in turn
    // emits nothing: only voices with a reported state can transition.
    queueOne(*engine, AudioOutputEngine::MAX_VOICES + 2, 0);
    TEST_ASSERT_EQ(events.size(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES + 1));
    TEST_ASSERT_EQ(engine->activeUserCount(),
                   static_cast<size_t>(AudioOutputEngine::MAX_VOICES));
    std::cout << "  [PASS] testEvictionEmitsPassiveForTalker" << std::endl;
}

void testFecRecoveryFillsSingleLoss() {
    g_testCount++;
    // Gap at seq 1 with seq 2 already buffered: the engine defers
    // concealment one frame and reconstructs the gap from seq 2's in-band
    // FEC data instead of playing PLC. Levels below the saturation knee are
    // linear, so real 0.2 -> 6553 and FEC 0.3 -> 9830 exactly.
    std::vector<TalkEvent> events;
    int fecAttempts = 0;
    auto engine = makeFecEngine(0.2f, 0.3f, 0.0f, false, &fecAttempts,
                                &events);
    queueOne(*engine, 101, 0);
    queueOne(*engine, 101, 2);
    std::vector<int16_t> out(3 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(3 * kFrame));
    TEST_ASSERT_EQ(fecAttempts, 1);
    // Gap slot carries FEC audio, uniformly: outside the onset fade and with
    // real audio on both sides there is no fade and no crossfade to disturb
    // it (FEC recovery counts as real).
    for (int i = kFrame; i < 2 * kFrame; ++i) {
        TEST_ASSERT_EQ(out[i], 9830);
    }
    // The successor packet still decodes normally after serving its FEC.
    TEST_ASSERT_EQ(out[5 * kFrame / 2], 6553);
    std::cout << "  [PASS] testFecRecoveryFillsSingleLoss" << std::endl;
}

void testFecFailureFallsBackToConcealment() {
    g_testCount++;
    // Same gap, but the successor carries no LBRR data: the debt slot falls
    // back to concealment while the successor itself still plays normally.
    std::vector<TalkEvent> events;
    int fecAttempts = 0;
    auto engine = makeFecEngine(0.2f, 0.3f, 0.0f, true, &fecAttempts,
                                &events);
    queueOne(*engine, 102, 0);
    queueOne(*engine, 102, 2);
    std::vector<int16_t> out(3 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(3 * kFrame));
    TEST_ASSERT_EQ(fecAttempts, 1);
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 0); // gap middle stays PLC silence
    TEST_ASSERT_EQ(out[5 * kFrame / 2], 6553); // successor intact
    std::cout << "  [PASS] testFecFailureFallsBackToConcealment" << std::endl;
}

void testBurstLossQuarantinesStaleDebt() {
    g_testCount++;
    // Two consecutive gaps: the first miss's debt is two frames behind once
    // the successor arrives, beyond LBRR range, so it must be concealed while
    // only the second gap recovers via FEC. A stale-debt bug would play FEC
    // audio into the first gap and PLC into the second — slot-exact levels
    // here discriminate the two.
    std::vector<TalkEvent> events;
    int fecAttempts = 0;
    auto engine = makeFecEngine(0.2f, 0.3f, 0.0f, false, &fecAttempts,
                                &events);
    queueOne(*engine, 106, 0);
    queueOne(*engine, 106, 3);
    std::vector<int16_t> out(4 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(4 * kFrame));
    TEST_ASSERT_EQ(fecAttempts, 1);
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 0); // first gap: settled PLC
    TEST_ASSERT_EQ(out[5 * kFrame / 2], 9830); // second gap: FEC recovery
    TEST_ASSERT_EQ(out[7 * kFrame / 2], 6553); // successor intact
    std::cout << "  [PASS] testBurstLossQuarantinesStaleDebt" << std::endl;
}

void testUnrecoverableLossConcealsSilently() {
    g_testCount++;
    // No successor packet means no FEC attempt at all: the gap is pure
    // concealment, settled at quantum end.
    std::vector<TalkEvent> events;
    int fecAttempts = 0;
    auto engine = makeFecEngine(0.2f, 0.3f, 0.0f, false, &fecAttempts,
                                &events);
    queueOne(*engine, 103, 0);
    std::vector<int16_t> out(2 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT_EQ(fecAttempts, 0);
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 0);
    std::cout << "  [PASS] testUnrecoverableLossConcealsSilently" << std::endl;
}

void testLossBoundaryCrossfadeSmoothsStep() {
    g_testCount++;
    // Real 0.4 stepping straight to silent concealment would jump ~13100
    // counts unblended; the 2 ms equal-power blend keeps every adjacent step
    // around the joint two orders of magnitude below that.
    std::vector<TalkEvent> events;
    int fecAttempts = 0;
    auto engine = makeFecEngine(0.4f, 0.0f, 0.0f, false, &fecAttempts,
                                &events);
    queueOne(*engine, 104, 0);
    std::vector<int16_t> out(2 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT(peakAbs(out) > 8000); // still loud overall
    int maxStep = 0;
    for (size_t i = kFrame - 128; i < kFrame + 128; ++i) {
        maxStep = std::max(maxStep,
                           std::abs(out[i + 1] - out[i]));
    }
    TEST_ASSERT(maxStep < 1500);
    std::cout << "  [PASS] testLossBoundaryCrossfadeSmoothsStep (maxStep: "
              << maxStep << ")" << std::endl;
}

void testLosslessMixHasNoCrossfade() {
    g_testCount++;
    // No loss means no chunk-type transitions: steady-state samples stay
    // bit-exact, proving the crossfade never fires on clean audio.
    std::vector<TalkEvent> events;
    int fecAttempts = 0;
    auto engine = makeFecEngine(0.2f, 0.3f, 0.0f, false, &fecAttempts,
                                &events);
    queueOne(*engine, 105, 0);
    queueOne(*engine, 105, 1);
    queueOne(*engine, 105, 2);
    std::vector<int16_t> out(3 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(3 * kFrame));
    TEST_ASSERT_EQ(fecAttempts, 0);
    for (int i = kFrame; i < 2 * kFrame; ++i) {
        TEST_ASSERT_EQ(out[i], 6553);
    }
    std::cout << "  [PASS] testLosslessMixHasNoCrossfade" << std::endl;
}

void testStartupPlaysImmediatelyAtProductionQuantum() {
    g_testCount++;
    // Startup contract the latency budget depends on: a queued packet plays
    // on the very first render at the production 20 ms quantum (960 samples)
    // under the default jitter margin — no pre-roll gating, no buffering
    // stall. Only the utterance-onset fade-in touches the first frame; the
    // second frame is bit-exact (0.5 level, linear knee: 16384).
    std::vector<TalkEvent> events;
    auto engine = makeEngine(0.5f, &events);
    queueOne(*engine, 111, 0);
    std::vector<int16_t> out(2 * kFrame, 0);
    TEST_ASSERT_EQ(engine->renderMix(out.data(), out.size()),
                   static_cast<size_t>(2 * kFrame));
    TEST_ASSERT_EQ(out[3 * kFrame / 2], 16384);
    std::cout << "  [PASS] testStartupPlaysImmediatelyAtProductionQuantum"
              << std::endl;
}

} // namespace

void run_audio_output_engine_tests() {
    std::cout << "--- AudioOutputEngine Tests ---" << std::endl;
    testSilentWithNoUsers();
    testQueuedPacketProducesAudioAndTalkState();
    testTerminatorEndsUserAsPassive();
    testOverlappingSpeakersCompressInsteadOfWrapping();
    testMixingIsCommutative();
    testStartupPlaysImmediatelyAtProductionQuantum();
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
    testFecRecoveryFillsSingleLoss();
    testFecFailureFallsBackToConcealment();
    testBurstLossQuarantinesStaleDebt();
    testUnrecoverableLossConcealsSilently();
    testLossBoundaryCrossfadeSmoothsStep();
    testLosslessMixHasNoCrossfade();
}
