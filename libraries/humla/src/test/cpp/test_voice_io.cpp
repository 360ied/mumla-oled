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

// Host tests for the 5a.3a native render core: the SPSC rings and VoiceIo.
// VoiceIo owns the output engine; the render thread is the only path into
// it. Producers push frames or removals; render drains both rings, mixes in
// quanta of at most 60 ms, and folds talk transitions into a level table.

#include "TestHarness.h"
#include "VoiceIo.h"
#include "VoiceRings.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <vector>

namespace {

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::audio::IOutputDecoder;
using mumla::audio::OutputTalkState;
using mumla::voice_io::SpscRing;
using mumla::voice_io::VoiceIo;
using mumla::voice_io::kInboundRingCapacity;
using mumla::voice_io::kTunnelRingCapacity;

constexpr int kFrame = AudioOutputEngine::FRAME_SIZE;

/** Fake decoder without libopus: every packet is one 10 ms frame of level. */
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

AudioOutputEngine::DecoderFactory fakeFactory(float level = 0.5f) {
    return [level] { return std::make_unique<FakeDecoder>(level); };
}

InboundPacket makePacket(int32_t session, uint32_t sequence, int flags = 0,
                         bool terminator = false) {
    InboundPacket packet;
    packet.session = session;
    packet.sequence = sequence;
    packet.flags = flags;
    packet.isTerminator = terminator;
    // Non-empty payload (except terminators): the engine drops empty
    // non-terminators rather than giving jitter timing a fabricated span.
    static const uint8_t kPayload[8] = {0xF8, 1, 2, 3, 4, 5, 6, 7};
    if (!terminator) {
        packet.length = sizeof(kPayload);
        std::memcpy(packet.data, kPayload, sizeof(kPayload));
    } else {
        packet.length = 0;
    }
    return packet;
}

/** Queues enough frames (margin + 1) to open the engine startup gate. */
void pushBurst(VoiceIo& io, int32_t session, uint32_t firstSeq, int frames,
               bool inbound = true) {
    for (int i = 0; i < frames; ++i) {
        const bool ok = inbound
                            ? io.pushInbound(makePacket(session, firstSeq + i))
                            : io.pushTunnel(makePacket(session, firstSeq + i));
        TEST_ASSERT_TRUE(ok);
    }
}

bool snapshotHas(const VoiceIo::TalkSnapshot& snapshot, int32_t session,
                 OutputTalkState state) {
    for (size_t i = 0; i < snapshot.count; ++i) {
        if (snapshot.entries[i].session == session &&
            snapshot.entries[i].state == state) {
            return true;
        }
    }
    return false;
}

void testRingPreservesOrder() {
    g_testCount++;
    SpscRing<InboundPacket, 8> ring;
    for (uint32_t i = 0; i < 8; ++i) {
        TEST_ASSERT_TRUE(ring.tryPush(makePacket(7, i)));
    }
    for (uint32_t i = 0; i < 8; ++i) {
        InboundPacket out;
        TEST_ASSERT_TRUE(ring.tryPop(&out));
        TEST_ASSERT_EQ(out.session, 7);
        TEST_ASSERT_EQ(out.sequence, i);
    }
    InboundPacket out;
    TEST_ASSERT_FALSE(ring.tryPop(&out));
    std::cout << "  [PASS] testRingPreservesOrder" << std::endl;
}

void testRingFullDropsNewest() {
    g_testCount++;
    SpscRing<InboundPacket, 4> ring;
    for (uint32_t i = 0; i < 4; ++i) {
        TEST_ASSERT_TRUE(ring.tryPush(makePacket(1, i)));
    }
    // Full: the new item is dropped, never displacing an old one.
    TEST_ASSERT_FALSE(ring.tryPush(makePacket(1, 99)));
    InboundPacket out;
    TEST_ASSERT_TRUE(ring.tryPop(&out));
    TEST_ASSERT_EQ(out.sequence, 0u);
    // Room again after one pop.
    TEST_ASSERT_TRUE(ring.tryPush(makePacket(1, 100)));
    std::cout << "  [PASS] testRingFullDropsNewest" << std::endl;
}

void testSilentRenderReportsZero() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.samples, 0u);
    TEST_ASSERT_FALSE(result.hasActiveVoices);
    TEST_ASSERT_EQ(result.talk.count, 0u);
    TEST_ASSERT_EQ(result.droppedInbound, 0u);
    TEST_ASSERT_EQ(result.droppedTunnel, 0u);
    TEST_ASSERT_EQ(result.droppedJitter, 0u);
    std::cout << "  [PASS] testSilentRenderReportsZero" << std::endl;
}

void testRenderDrainsBothRings() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    pushBurst(io, 11, 0, 8, /*inbound=*/true);
    pushBurst(io, 22, 0, 8, /*inbound=*/false);
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(result.samples > 0);
    TEST_ASSERT_TRUE(result.hasActiveVoices);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 11, OutputTalkState::TALKING));
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 22, OutputTalkState::TALKING));
    TEST_ASSERT_EQ(result.talk.count, 2u);
    std::cout << "  [PASS] testRenderDrainsBothRings" << std::endl;
}

void testInboundFullRingDropsCounted() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    for (size_t i = 0; i < kInboundRingCapacity; ++i) {
        TEST_ASSERT_TRUE(io.pushInbound(makePacket(5, static_cast<uint32_t>(i))));
    }
    TEST_ASSERT_FALSE(io.pushInbound(makePacket(5, 100000)));
    TEST_ASSERT_FALSE(io.pushInbound(makePacket(5, 100001)));
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.droppedInbound, 2u);
    TEST_ASSERT_EQ(result.droppedTunnel, 0u);
    // Drops are cumulative across renders.
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.droppedInbound, 2u);
    std::cout << "  [PASS] testInboundFullRingDropsCounted" << std::endl;
}

void testTunnelFullRingDropsCounted() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    for (size_t i = 0; i < kTunnelRingCapacity; ++i) {
        TEST_ASSERT_TRUE(io.pushTunnel(makePacket(6, static_cast<uint32_t>(i))));
    }
    TEST_ASSERT_FALSE(io.pushTunnel(makePacket(6, 100000)));
    // Removals share the tunnel ring and its counter; no slots reserved.
    TEST_ASSERT_FALSE(io.pushRemoveUser(6));
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.droppedTunnel, 2u);
    TEST_ASSERT_EQ(result.droppedInbound, 0u);
    std::cout << "  [PASS] testTunnelFullRingDropsCounted" << std::endl;
}

void testRemoveUserOrderedAfterTunnelVoice() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    pushBurst(io, 33, 0, 8, /*inbound=*/false);
    TEST_ASSERT_TRUE(io.pushRemoveUser(33));
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    // The removal drains in the same render: no level survives (the voice's
    // TALKING and the removal's PASSIVE fold to absent).
    TEST_ASSERT_FALSE(snapshotHas(result.talk, 33, OutputTalkState::TALKING));
    TEST_ASSERT_EQ(result.talk.count, 0u);
    TEST_ASSERT_FALSE(result.hasActiveVoices);
    std::cout << "  [PASS] testRemoveUserOrderedAfterTunnelVoice" << std::endl;
}

void testRemoveUserClearsTalkLevel() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    pushBurst(io, 44, 0, 8, /*inbound=*/true);
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 44, OutputTalkState::TALKING));
    TEST_ASSERT_TRUE(io.pushRemoveUser(44));
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.talk.count, 0u);
    TEST_ASSERT_FALSE(result.hasActiveVoices);
    std::cout << "  [PASS] testRemoveUserClearsTalkLevel" << std::endl;
}

void testRenderSplitsLargeRequest() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    pushBurst(io, 55, 0, 8, /*inbound=*/true);
    // 200 ms exceeds the 60 ms engine quantum: VoiceIo splits it into
    // several engine renders. The voice stays alive on concealment, so the
    // full request mixes.
    const size_t want = static_cast<size_t>(kFrame) * 20;
    std::vector<int16_t> pcm(want);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), want, &result);
    TEST_ASSERT_EQ(result.samples, want);
    std::cout << "  [PASS] testRenderSplitsLargeRequest" << std::endl;
}

void testShoutFlagMapsToShouting() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    for (uint32_t i = 0; i < 8; ++i) {
        TEST_ASSERT_TRUE(io.pushInbound(makePacket(66, i, /*flags=*/1)));
    }
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 66, OutputTalkState::SHOUTING));
    std::cout << "  [PASS] testShoutFlagMapsToShouting" << std::endl;
}

void testClearEmptiesTalkLevels() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    pushBurst(io, 77, 0, 8, /*inbound=*/true);
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 77, OutputTalkState::TALKING));
    io.clear();
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.talk.count, 0u);
    TEST_ASSERT_FALSE(result.hasActiveVoices);
    std::cout << "  [PASS] testClearEmptiesTalkLevels" << std::endl;
}

} // namespace

void run_voice_io_tests() {
    testRingPreservesOrder();
    testRingFullDropsNewest();
    testSilentRenderReportsZero();
    testRenderDrainsBothRings();
    testInboundFullRingDropsCounted();
    testTunnelFullRingDropsCounted();
    testRemoveUserOrderedAfterTunnelVoice();
    testRemoveUserClearsTalkLevel();
    testRenderSplitsLargeRequest();
    testShoutFlagMapsToShouting();
    testClearEmptiesTalkLevels();
}

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << " Mumla Voice I/O (5a.3a render) Tests" << std::endl;
    std::cout << "========================================" << std::endl;
    run_voice_io_tests();
    std::cout << "========================================" << std::endl;
    if (g_testFailures == 0) {
        std::cout << " ALL " << g_testCount << " VOICE I/O TESTS PASSED!" << std::endl;
        std::cout << "========================================" << std::endl;
        return 0;
    }
    std::cerr << " FAILURE: " << g_testFailures << " of " << g_testCount
              << " tests failed!" << std::endl;
    std::cout << "========================================" << std::endl;
    return 1;
}
