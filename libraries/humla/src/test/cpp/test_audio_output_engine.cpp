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
#include "TestHarness.h"

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

private:
    float m_level;
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

void queueOne(AudioOutputEngine& engine, int32_t session, uint32_t seq,
              int flags = 0, bool terminator = false) {
    uint8_t payload[8] = {0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68};
    engine.queuePacket(session, payload, sizeof(payload), seq, flags,
                       terminator);
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
    int peak = 0;
    for (int16_t s : out) {
        peak = std::max<int>(peak, std::abs(s));
    }
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

} // namespace

void run_audio_output_engine_tests() {
    std::cout << "--- AudioOutputEngine Tests ---" << std::endl;
    testSilentWithNoUsers();
    testQueuedPacketProducesAudioAndTalkState();
    testTerminatorEndsUserAsPassive();
    testOverlappingSpeakersCompressInsteadOfWrapping();
    testMixingIsCommutative();
    testLossConcealmentBridgesGapsThenExpires();
}
