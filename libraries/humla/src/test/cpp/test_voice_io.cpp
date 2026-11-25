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
// VoiceIo owns the output engine; the AAudio render callback (and the JNI
// test entry) is the only path into it. Producers push frames or removals;
// render drains both rings, mixes whole 10 ms frames, and folds talk
// transitions into a level table. Step 5b removed the render idle wake.

#include "TestHarness.h"
#include "CryptStateOCB2.h"
#include "VoiceCodec.h"
#include "VoiceIo.h"
#include "VoiceNotifier.h"
#include "VoicePolicy.h"
#include "VoiceReceive.h"
#include "VoiceRings.h"
#include "VoiceTalk.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {

using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::audio::IOutputDecoder;
using mumla::audio::OutputTalkState;
using humla::crypto::CryptStateOCB2;
using mumla::voice_io::PolicyCell;
using mumla::voice_io::PolicySnapshot;
using mumla::voice_io::VoiceReceive;
using mumla::voice_io::SpscRing;
using mumla::voice_io::TalkCell;
using mumla::voice_io::TalkPublication;
using mumla::voice_io::VoiceIo;
using mumla::voice_io::VoiceNotifier;
using mumla::voice_io::kInboundRingCapacity;
using mumla::voice_io::kTunnelRingCapacity;

constexpr int kFrame = AudioOutputEngine::FRAME_SIZE;

/** Fake decoder without libopus: every packet is one 10 ms frame of level. */
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
    std::cout << "  [PASS] testRemoveUserClearsTalkLevel" << std::endl;
}

void testRenderSplitsLargeRequest() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    pushBurst(io, 55, 0, 25, /*inbound=*/true);
    // 200 ms exceeds the 60 ms engine quantum: VoiceIo splits it into
    // several engine renders. 25 frames cover the whole request, so the
    // voice stays talking throughout the split and the assertion below
    // pins that every quantum's talk events folded into one table.
    const size_t want = static_cast<size_t>(kFrame) * 20;
    std::vector<int16_t> pcm(want);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), want, &result);
    TEST_ASSERT_EQ(result.samples, want);
    // The multi-quantum fold must carry the talk table, not just audio.
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 55, OutputTalkState::TALKING));
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

void testWhisperFlagMapsToWhispering() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    for (uint32_t i = 0; i < 8; ++i) {
        TEST_ASSERT_TRUE(io.pushInbound(makePacket(67, i, /*flags=*/2)));
    }
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 67, OutputTalkState::WHISPERING));
    std::cout << "  [PASS] testWhisperFlagMapsToWhispering" << std::endl;
}

void testTalkCellPublishesLatest() {
    g_testCount++;
    TalkCell cell;
    VoiceIo::TalkSnapshot talk;
    talk.entries[0].session = 9;
    talk.entries[0].state = OutputTalkState::TALKING;
    talk.count = 1;
    cell.publish(talk, 1, 2, 3);
    TalkPublication out;
    TEST_ASSERT_TRUE(cell.tryRead(&out));
    TEST_ASSERT_EQ(out.version, 1u);
    TEST_ASSERT_EQ(out.talk.count, 1u);
    TEST_ASSERT_EQ(out.talk.entries[0].session, 9);
    TEST_ASSERT_EQ(out.droppedInbound, 1u);
    TEST_ASSERT_EQ(out.droppedTunnel, 2u);
    TEST_ASSERT_EQ(out.droppedJitter, 3u);
    // A newer publication replaces the older one wholesale.
    VoiceIo::TalkSnapshot empty;
    cell.publish(empty, 4, 5, 6);
    TEST_ASSERT_TRUE(cell.tryRead(&out));
    TEST_ASSERT_EQ(out.version, 2u);
    TEST_ASSERT_EQ(out.talk.count, 0u);
    TEST_ASSERT_EQ(out.droppedInbound, 4u);
    std::cout << "  [PASS] testTalkCellPublishesLatest" << std::endl;
}

void testTalkCellNeverTornUnderThreads() {
    g_testCount++;
    TalkCell cell;
    constexpr int kVersions = 2000;
    std::atomic<bool> done{false};
    std::atomic<int> badReads{0};
    // Writer: every version carries count copies of its own session id, so
    // any mix of two versions is observable as a mismatch.
    std::thread writer([&] {
        for (int v = 1; v <= kVersions; ++v) {
            VoiceIo::TalkSnapshot talk;
            const int n = v % (static_cast<int>(VoiceIo::TalkSnapshot::kMaxEntries) + 1);
            for (int i = 0; i < n; ++i) {
                talk.entries[i].session = v;
                talk.entries[i].state = OutputTalkState::TALKING;
            }
            talk.count = static_cast<size_t>(n);
            cell.publish(talk, static_cast<uint64_t>(v), 0, 0);
        }
        done.store(true);
    });
    // Three readers, as the spec's sequence-lock bar demands.
    auto reader = [&] {
        TalkPublication out;
        while (!done.load()) {
            if (!cell.tryRead(&out)) {
                continue;
            }
            for (size_t i = 0; i < out.talk.count; ++i) {
                if (out.talk.entries[i].session !=
                    static_cast<int32_t>(out.droppedInbound)) {
                    badReads.fetch_add(1);
                    break;
                }
            }
        }
    };
    std::thread r1(reader), r2(reader), r3(reader);
    writer.join();
    r1.join();
    r2.join();
    r3.join();
    TEST_ASSERT_EQ(badReads.load(), 0);
    TalkPublication last;
    TEST_ASSERT_TRUE(cell.tryRead(&last));
    TEST_ASSERT_EQ(last.version, static_cast<uint64_t>(kVersions));
    std::cout << "  [PASS] testTalkCellNeverTornUnderThreads" << std::endl;
}

void testNotifierDeliversOncePerChange() {
    g_testCount++;
    TalkCell cell;
    int calls = 0;
    TalkPublication last;
    VoiceNotifier notifier(&cell, nullptr, [&](const TalkPublication& pub) {
        calls++;
        last = pub;
    });
    VoiceIo::TalkSnapshot talk;
    talk.entries[0].session = 3;
    talk.entries[0].state = OutputTalkState::WHISPERING;
    talk.count = 1;
    cell.publish(talk, 0, 0, 0);
    TEST_ASSERT_TRUE(notifier.notifyNow());
    TEST_ASSERT_EQ(calls, 1);
    TEST_ASSERT_EQ(last.talk.count, 1u);
    // No change since the last delivery: no second Kotlin call.
    TEST_ASSERT_FALSE(notifier.notifyNow());
    TEST_ASSERT_EQ(calls, 1);
    VoiceIo::TalkSnapshot empty;
    cell.publish(empty, 0, 0, 0);
    TEST_ASSERT_TRUE(notifier.notifyNow());
    TEST_ASSERT_EQ(calls, 2);
    TEST_ASSERT_EQ(last.talk.count, 0u);
    std::cout << "  [PASS] testNotifierDeliversOncePerChange" << std::endl;
}

void testNotifierStalledResumesWithLatest() {
    g_testCount++;
    TalkCell cell;
    int calls = 0;
    TalkPublication last;
    VoiceNotifier notifier(&cell, nullptr, [&](const TalkPublication& pub) {
        calls++;
        last = pub;
    });
    // Stalled: two publications, no delivery; the resume carries the latest.
    VoiceIo::TalkSnapshot v1;
    v1.entries[0].session = 1;
    v1.entries[0].state = OutputTalkState::TALKING;
    v1.count = 1;
    cell.publish(v1, 0, 0, 0);
    VoiceIo::TalkSnapshot v2;
    v2.entries[0].session = 2;
    v2.entries[0].state = OutputTalkState::SHOUTING;
    v2.count = 1;
    cell.publish(v2, 0, 0, 7);
    TEST_ASSERT_TRUE(notifier.notifyNow());
    TEST_ASSERT_EQ(calls, 1);
    TEST_ASSERT_EQ(last.talk.entries[0].session, 2);
    TEST_ASSERT_EQ(last.droppedJitter, 7u);
    std::cout << "  [PASS] testNotifierStalledResumesWithLatest" << std::endl;
}

void testRenderPublishesTalkCell() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    TalkCell cell;
    io.setTalkCell(&cell);
    pushBurst(io, 88, 0, 8, /*inbound=*/true);
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TalkPublication pub;
    TEST_ASSERT_TRUE(cell.tryRead(&pub));
    TEST_ASSERT_TRUE(pub.version >= 1u);
    TEST_ASSERT_TRUE(snapshotHas(pub.talk, 88, OutputTalkState::TALKING));
    std::cout << "  [PASS] testRenderPublishesTalkCell" << std::endl;
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
    std::cout << "  [PASS] testClearEmptiesTalkLevels" << std::endl;
}

void testVoiceIoEnginesArePrivatelyOwned() {
    g_testCount++;
    // Compile-time half of the 5a.3a done-when lifecycle pin: the render
    // core owns its output engine privately (unique_ptr, non-copyable), so
    // no second caller path can reach the same engine instance. Fails at
    // compile time if VoiceIo ever becomes copyable or shared.
    static_assert(!std::is_copy_constructible<VoiceIo>::value,
                  "VoiceIo must stay non-copyable: one render thread per engine");
    static_assert(!std::is_copy_assignable<VoiceIo>::value,
                  "VoiceIo must stay non-copyable: one render thread per engine");
    // Runtime half: two instances share no engine state. Voice pushed to
    // one never appears in the other; a shared or static engine would leak
    // session 41 into the second render.
    VoiceIo first(fakeFactory());
    VoiceIo second(fakeFactory());
    pushBurst(first, 41, 0, 8, /*inbound=*/true);
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    first.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 41, OutputTalkState::TALKING));
    second.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.talk.count, 0u);
    TEST_ASSERT_EQ(result.samples, 0u);
    std::cout << "  [PASS] testVoiceIoEnginesArePrivatelyOwned" << std::endl;
}

void testClearWhileStoppedPublishesEmptyAndEngineSurvives() {
    g_testCount++;
    // Control-plane lifecycle pin: clear() runs on the control thread only
    // while render is stopped (the JNI layer asserts !renderActive in debug
    // builds; Kotlin joins the render thread before destroy). It publishes
    // the emptied table so no snapshot from the dead engine can re-light an
    // indicator, and the engine stays usable on the next render.
    VoiceIo io(fakeFactory());
    TalkCell cell;
    io.setTalkCell(&cell);
    pushBurst(io, 77, 0, 8, /*inbound=*/true);
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(snapshotHas(result.talk, 77, OutputTalkState::TALKING));
    io.clear();
    TalkPublication pub;
    TEST_ASSERT_TRUE(cell.tryRead(&pub));
    TEST_ASSERT_EQ(pub.talk.count, 0u);
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.talk.count, 0u);
    std::cout << "  [PASS] testClearWhileStoppedPublishesEmptyAndEngineSurvives"
              << std::endl;
}

void testPolicyDropsMutedOverUdpAndTunnel() {
    g_testCount++;
    PolicyCell cell;
    const int32_t muted[] = {7};
    const int32_t known[] = {7, 8};
    cell.publish(muted, 1, known, 2, false, false, false, 0, 40000, 2);
    PolicySnapshot snap;
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_TRUE(PolicyCell::shouldDrop(snap, 7));
    TEST_ASSERT_FALSE(PolicyCell::shouldDrop(snap, 8));
    std::cout << "  [PASS] testPolicyDropsMutedOverUdpAndTunnel" << std::endl;
}

void testPolicyUnknownHoldsNoSlot() {
    g_testCount++;
    PolicyCell cell;
    const int32_t muted[] = {};
    const int32_t known[] = {11};
    cell.publish(muted, 0, known, 1, false, false, false, 0, 40000, 2);
    PolicySnapshot snap;
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_TRUE(PolicyCell::shouldDrop(snap, 99));
    TEST_ASSERT_FALSE(PolicyCell::shouldDrop(snap, 11));
    std::cout << "  [PASS] testPolicyUnknownHoldsNoSlot" << std::endl;
}

void testPolicyKnownOverflowFailsOpen() {
    g_testCount++;
    PolicyCell cell;
    int32_t known[300];
    for (int i = 0; i < 300; ++i) known[i] = 1000 + i;
    cell.publish(nullptr, 0, known, 300, false, false, false, 0, 40000, 2);
    PolicySnapshot snap;
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_TRUE(snap.knownOverflow);
    TEST_ASSERT_EQ(snap.knownCount, mumla::voice_io::kPolicyListCapacity);
    // Overflow fails open: unknown passes, muted still drops.
    TEST_ASSERT_FALSE(PolicyCell::shouldDrop(snap, 9999));
    const int32_t muted[] = {9999};
    cell.publish(muted, 1, known, 300, false, false, false, 0, 40000, 2);
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_TRUE(PolicyCell::shouldDrop(snap, 9999));
    TEST_ASSERT_FALSE(PolicyCell::shouldDrop(snap, 8888));
    std::cout << "  [PASS] testPolicyKnownOverflowFailsOpen" << std::endl;
}

void testSenderFilterEndToEndUdpAndTunnel() {
    g_testCount++;
    // Done-when (e): muted silent over UDP and tunnel, unknown holds no
    // slot, overflow fail-open — through handlePlaintext/handleTunnelled,
    // not just the shouldDrop predicate.
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    const int32_t muted[] = {7};
    const int32_t known[] = {7, 8};
    policy.publish(muted, 1, known, 2, false, false, false, 0, 40000, 2);
    int udpSurvivors = 0;
    int tunnelSurvivors = 0;
    bool lastTunnel = false;
    rx.setVoiceSink([&](const InboundPacket* frames, size_t count, bool tunnel) {
        (void)frames;
        if (tunnel) tunnelSurvivors += static_cast<int>(count);
        else udpSurvivors += static_cast<int>(count);
        lastTunnel = tunnel;
    });
    // Minimal legacy receive datagram: header opus/target0, varint
    // session, varint seq, varint size header (4-byte opus, no terminator),
    // 4-byte opus payload. Varints are full Mumble encoding so large
    // session IDs (e.g. 9999) travel intact, as on the wire.
    auto putVarint = [](uint64_t u, uint8_t* out) -> size_t {
        if (u <= 0x7FULL) {
            out[0] = static_cast<uint8_t>(u);
            return 1;
        }
        if (u <= 0x3FFFULL) {
            out[0] = static_cast<uint8_t>((u >> 8) | 0x80);
            out[1] = static_cast<uint8_t>(u & 0xFF);
            return 2;
        }
        if (u <= 0x1FFFFFULL) {
            out[0] = static_cast<uint8_t>((u >> 16) | 0xC0);
            out[1] = static_cast<uint8_t>((u >> 8) & 0xFF);
            out[2] = static_cast<uint8_t>(u & 0xFF);
            return 3;
        }
        // Sessions are int32: the 4-byte form covers the whole range.
        out[0] = 0xF0;
        out[1] = static_cast<uint8_t>((u >> 24) & 0xFF);
        out[2] = static_cast<uint8_t>((u >> 16) & 0xFF);
        out[3] = static_cast<uint8_t>((u >> 8) & 0xFF);
        out[4] = static_cast<uint8_t>(u & 0xFF);
        return 5;
    };
    auto buildLegacy = [&](int32_t session, uint32_t seq, uint8_t* out) -> size_t {
        size_t pos = 0;
        out[pos++] = static_cast<uint8_t>((mumla::voice_codec::kLegacyVoiceOpus << 5) & 0xFF);
        pos += putVarint(static_cast<uint64_t>(static_cast<int64_t>(session)), out + pos);
        pos += putVarint(seq, out + pos);
        pos += putVarint(4, out + pos);
        out[pos++] = 0xF8; out[pos++] = 1; out[pos++] = 2; out[pos++] = 3;
        return pos;
    };
    uint8_t dg[16];
    // Muted session over UDP: understood but silent (no sink, no slot).
    TEST_ASSERT_TRUE(rx.handlePlaintext(dg, buildLegacy(7, 1, dg)));
    TEST_ASSERT_EQ(udpSurvivors, 0);
    // Allowed session over UDP: one survivor on the UDP path.
    TEST_ASSERT_TRUE(rx.handlePlaintext(dg, buildLegacy(8, 2, dg)));
    TEST_ASSERT_EQ(udpSurvivors, 1);
    TEST_ASSERT_FALSE(lastTunnel);
    // Unknown session over UDP: dropped, no audio and no slot.
    TEST_ASSERT_TRUE(rx.handlePlaintext(dg, buildLegacy(99, 3, dg)));
    TEST_ASSERT_EQ(udpSurvivors, 1);
    // Muted session over tunnel: silent on the tunnel path too.
    TEST_ASSERT_TRUE(rx.handleTunnelled(dg, buildLegacy(7, 4, dg)));
    TEST_ASSERT_EQ(tunnelSurvivors, 0);
    // Allowed session over tunnel: one survivor flagged tunnel.
    TEST_ASSERT_TRUE(rx.handleTunnelled(dg, buildLegacy(8, 5, dg)));
    TEST_ASSERT_EQ(tunnelSurvivors, 1);
    TEST_ASSERT_TRUE(lastTunnel);
    // Overflow fail-open: unknown passes, muted still drops (both paths).
    // Wire sessions use the real large IDs now that varints are encoded
    // properly, mirroring the predicate test's 9999/8888 pair.
    int32_t many[300];
    for (int i = 0; i < 300; ++i) many[i] = 1000 + i;
    policy.publish(nullptr, 0, many, 300, false, false, false, 0, 40000, 2);
    TEST_ASSERT_TRUE(rx.handlePlaintext(dg, buildLegacy(8888, 6, dg)));
    TEST_ASSERT_EQ(udpSurvivors, 2);
    const int32_t muted9999[] = {9999};
    policy.publish(muted9999, 1, many, 300, false, false, false, 0, 40000, 2);
    TEST_ASSERT_TRUE(rx.handlePlaintext(dg, buildLegacy(9999, 7, dg)));
    TEST_ASSERT_EQ(udpSurvivors, 2);
    TEST_ASSERT_TRUE(rx.handleTunnelled(dg, buildLegacy(9999, 8, dg)));
    TEST_ASSERT_EQ(tunnelSurvivors, 1);
    std::cout << "  [PASS] testSenderFilterEndToEndUdpAndTunnel" << std::endl;
}

void testPolicyGuardsReceiveVisibleOnly() {
    g_testCount++;
    // Pins the 5a.4 lock-scope rule: the policy cell carries VoicePolicy
    // (filter lists plus transmit gate: allowed, PTT, target, bitrate, FPP)
    // and nothing settings-side (no input mode, VAD, boost, leveler,
    // half-duplex — those live in SettingsCell).
    static_assert(sizeof(PolicySnapshot) <=
                  (256 + 256) * sizeof(int32_t) + 64,
                  "PolicySnapshot must stay filter lists plus gate words");
    PolicyCell cell;
    const int32_t muted[] = {3};
    const int32_t known[] = {3, 4};
    cell.publish(muted, 1, known, 2, false, false, false, 0, 40000, 2);
    PolicySnapshot snap;
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_EQ(snap.mutedCount, 1u);
    TEST_ASSERT_EQ(snap.knownCount, 2u);
    std::cout << "  [PASS] testPolicyGuardsReceiveVisibleOnly" << std::endl;
}

void testReceiveResyncGateRateLimited() {
    g_testCount++;
    // Exactly one rate-limited request: long-good then failure fires once,
    // an immediate second failure is suppressed, a failure after the gap
    // fires again. Times in microseconds.
    TEST_ASSERT_TRUE(VoiceReceive::shouldRequestResync(6000000, 6000000, 0));
    TEST_ASSERT_FALSE(VoiceReceive::shouldRequestResync(6000001, 6000000, 6000000));
    TEST_ASSERT_TRUE(VoiceReceive::shouldRequestResync(11000001, 9000000, 6000000));
    // Recent good never fires, and no last-good never fires.
    TEST_ASSERT_FALSE(VoiceReceive::shouldRequestResync(9000000, 1000000, 0));
    TEST_ASSERT_FALSE(VoiceReceive::shouldRequestResync(9000000, -1, 0));
    std::cout << "  [PASS] testReceiveResyncGateRateLimited" << std::endl;
}

void testReceiveCryptCountsMatchKotlin() {
    g_testCount++;
    // Done-when crypt-count parity, recorded in
    // test/fixtures/crypt-counts/5a.3b-kotlin-parity.txt: the Kotlin path
    // reports good/late/lost/resync for this scripted sequence through the
    // same OCB2 engine, so the native path must reproduce the tuple or the
    // transport's health check mis-falls-back. The fixture file is the
    // record; the expectations below are asserted against both the live
    // counters and the file so the two cannot drift apart.
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    const uint8_t key[16] = {16, 32, 48, 64, 80, 96, 112, 128,
                             144, 160, 176, 192, 208, 224, 240, 0};
    TEST_ASSERT_TRUE(rx.setKeys(key, key, key));
    CryptStateOCB2 sender;
    TEST_ASSERT_TRUE(sender.setKey(key, key, key));
    const uint8_t secret[10] = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'};
    // Six sender packets s1..s6; delivered out of order below.
    uint8_t sent[6][14];
    for (int i = 0; i < 6; ++i) {
        TEST_ASSERT_TRUE(sender.encrypt(secret, sent[i], 10));
    }
    int32_t good = 0, late = 0, lost = 0, resync = 0;
    // 1. In order: good=1.
    rx.handleCrypted(sent[0], 14);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 1);
    TEST_ASSERT_EQ(late, 0);
    TEST_ASSERT_EQ(lost, 0);
    TEST_ASSERT_EQ(resync, 0);
    // 2. Replay rejected: counters hold.
    rx.handleCrypted(sent[0], 14);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 1);
    TEST_ASSERT_EQ(late, 0);
    TEST_ASSERT_EQ(lost, 0);
    // 3. Skip s2..s5, deliver s6: lost 4 on the next good.
    rx.handleCrypted(sent[5], 14);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 2);
    TEST_ASSERT_EQ(lost, 4);
    // 4. Late s4 after s6: the fixture tuple (good=3, late=1, lost=3).
    rx.handleCrypted(sent[3], 14);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 3);
    TEST_ASSERT_EQ(late, 1);
    TEST_ASSERT_EQ(lost, 3);
    TEST_ASSERT_EQ(resync, 0);
#ifdef MUMLA_CRYPT_COUNT_FIXTURE_DIR
    // The file is authoritative: live counters must equal the record.
    {
        std::filesystem::path file =
            std::filesystem::path(MUMLA_CRYPT_COUNT_FIXTURE_DIR) /
            "5a.3b-kotlin-parity.txt";
        std::ifstream in(file);
        TEST_ASSERT_TRUE(in.good());
        int expGood = -1, expLate = -1, expLost = -1, expResync = -1;
        std::string line;
        while (std::getline(in, line)) {
            if (line.rfind("good=", 0) == 0) expGood = std::stoi(line.substr(5));
            else if (line.rfind("late=", 0) == 0) expLate = std::stoi(line.substr(5));
            else if (line.rfind("lost=", 0) == 0) expLost = std::stoi(line.substr(5));
            else if (line.rfind("resync=", 0) == 0) expResync = std::stoi(line.substr(7));
        }
        TEST_ASSERT_EQ(expGood, 3);
        TEST_ASSERT_EQ(expLate, 1);
        TEST_ASSERT_EQ(expLost, 3);
        TEST_ASSERT_EQ(expResync, 0);
        TEST_ASSERT_EQ(good, expGood);
        TEST_ASSERT_EQ(late, expLate);
        TEST_ASSERT_EQ(lost, expLost);
        TEST_ASSERT_EQ(resync, expResync);
    }
#endif
    // 5. Forced decrypt failure with a recent good decrypt: the rate
    // limiter suppresses the resync request (the 5 s gate is pinned pure
    // in testReceiveResyncGateRateLimited; firing is pinned next).
    int resyncs = 0;
    rx.setResyncSink([&] { resyncs++; });
    uint8_t tampered[14];
    memcpy(tampered, sent[5], 14);
    tampered[5] ^= 0xFF;
    TEST_ASSERT_FALSE(rx.handleCrypted(tampered, 14));
    TEST_ASSERT_EQ(resyncs, 0);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 3);
    // 6. Rekey (new IV after the resync round trip): voice resumes. Rekey
    // resets the counters on both sides (as Kotlin setKeys does), so good
    // restarts at 1 with the first post-rekey decrypt.
    const uint8_t key2[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    TEST_ASSERT_TRUE(rx.setKeys(key2, key2, key2));
    TEST_ASSERT_TRUE(sender.setKey(key2, key2, key2));
    uint8_t fresh[14];
    TEST_ASSERT_TRUE(sender.encrypt(secret, fresh, 10));
    TEST_ASSERT_TRUE(rx.handleCrypted(fresh, 14));
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 1);
    TEST_ASSERT_EQ(lost, 0);
    TEST_ASSERT_EQ(late, 0);
    std::cout << "  [PASS] testReceiveCryptCountsMatchKotlin" << std::endl;
}

void testReceiveResyncFiresExactlyOnce() {
    g_testCount++;
    // Done-when resync recovery (firing half): a forced decrypt failure
    // with an old good decrypt raises exactly one rate-limited request;
    // an immediate second failure is suppressed; after the gap it fires
    // again; voice resumes after the new IV. Backdates the clocks instead
    // of sleeping out the 5 s windows (test-only setters, never live).
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    const uint8_t key[16] = {21, 22, 23, 24, 25, 26, 27, 28,
                             29, 30, 31, 32, 33, 34, 35, 36};
    TEST_ASSERT_TRUE(rx.setKeys(key, key, key));
    CryptStateOCB2 sender;
    TEST_ASSERT_TRUE(sender.setKey(key, key, key));
    const uint8_t secret[10] = {'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'};
    uint8_t packet[14];
    TEST_ASSERT_TRUE(sender.encrypt(secret, packet, 10));
    rx.handleCrypted(packet, 14);
    int32_t good = 0;
    rx.getStats(&good, nullptr, nullptr, nullptr);
    TEST_ASSERT_EQ(good, 1);
    // Age the good decrypt past the 5 s window; the request clock starts
    // at zero (long ago), so the next failure must fire exactly once.
    rx.backdateLastGoodForTest(6000000);
    int resyncs = 0;
    rx.setResyncSink([&] { resyncs++; });
    uint8_t tampered[14];
    memcpy(tampered, packet, 14);
    tampered[5] ^= 0xFF;
    TEST_ASSERT_FALSE(rx.handleCrypted(tampered, 14));
    TEST_ASSERT_EQ(resyncs, 1);
    // Immediate second failure: suppressed by the 5 s request gap.
    TEST_ASSERT_FALSE(rx.handleCrypted(tampered, 14));
    TEST_ASSERT_EQ(resyncs, 1);
    // After the gap (clocks rewound for determinism): fires again.
    rx.setLastRequestUsForTest(0);
    rx.backdateLastGoodForTest(6000000);
    TEST_ASSERT_FALSE(rx.handleCrypted(tampered, 14));
    TEST_ASSERT_EQ(resyncs, 2);
    // New IV after the round trip: voice resumes at good=1.
    const uint8_t key2[16] = {41, 42, 43, 44, 45, 46, 47, 48,
                              49, 50, 51, 52, 53, 54, 55, 56};
    TEST_ASSERT_TRUE(rx.setKeys(key2, key2, key2));
    TEST_ASSERT_TRUE(sender.setKey(key2, key2, key2));
    uint8_t fresh[14];
    TEST_ASSERT_TRUE(sender.encrypt(secret, fresh, 10));
    rx.handleCrypted(fresh, 14);
    rx.getStats(&good, nullptr, nullptr, nullptr);
    TEST_ASSERT_EQ(good, 1);
    std::cout << "  [PASS] testReceiveResyncFiresExactlyOnce" << std::endl;
}

void testReceiveLoopbackPingFiresSink() {
    g_testCount++;
    // Full native loopback without a socket: frame a legacy ping, encrypt
    // it the way the send seam does, decrypt + parse + dispatch it the way
    // the receive loop does. Voice-data loopback with real Opus is pinned
    // by the UDP interop scenario instead.
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    const uint8_t key[16] = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 9, 8, 7, 6, 5, 4};
    TEST_ASSERT_TRUE(rx.setKeys(key, key, key));
    CryptStateOCB2 sender;
    TEST_ASSERT_TRUE(sender.setKey(key, key, key));
    uint8_t framed[64];
    const size_t framedLen =
        mumla::voice_codec::framePing(mumla::voice_codec::UdpFormat::LEGACY,
                                      12345678, framed, sizeof(framed));
    TEST_ASSERT_TRUE(framedLen > 0);
    uint64_t gotTimestamp = 0;
    rx.setPingSink([&](const VoiceReceive::PingReport& report) {
        gotTimestamp = report.timestampMicros;
    });
    uint8_t crypted[68];
    TEST_ASSERT_TRUE(sender.encrypt(framed, crypted, static_cast<uint32_t>(framedLen)));
    TEST_ASSERT_TRUE(rx.handleCrypted(crypted, framedLen + 4));
    TEST_ASSERT_EQ(gotTimestamp, 12345678u);
    int32_t good = 0;
    rx.getStats(&good, nullptr, nullptr, nullptr);
    TEST_ASSERT_EQ(good, 1);
    std::cout << "  [PASS] testReceiveLoopbackPingFiresSink" << std::endl;
}

void testClearDrainsRingsAndCountsResetDiscards() {
    g_testCount++;
    // Session-start reset: stale unrendered voice must never play into the
    // next session. Drained packets count as drops (arrived, never heard).
    VoiceIo io(fakeFactory());
    for (size_t i = 0; i < 10; ++i) {
        TEST_ASSERT_TRUE(io.pushInbound(makePacket(5, static_cast<uint32_t>(i))));
    }
    for (size_t i = 0; i < 4; ++i) {
        TEST_ASSERT_TRUE(io.pushTunnel(makePacket(6, static_cast<uint32_t>(i))));
    }
    io.clear();
    std::vector<int16_t> pcm(kFrame * 2);
    VoiceIo::RenderOut result;
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_EQ(result.samples, 0u);
    TEST_ASSERT_EQ(result.droppedInbound, 10u);
    TEST_ASSERT_EQ(result.droppedTunnel, 4u);
    TEST_ASSERT_EQ(result.talk.count, 0u);
    std::cout << "  [PASS] testClearDrainsRingsAndCountsResetDiscards" << std::endl;
}

void testReceiveStopLeavesNothing() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    TEST_ASSERT_TRUE(rx.start("127.0.0.1", 9));
    TEST_ASSERT_TRUE(rx.running());
    TEST_ASSERT_TRUE(rx.socketFd() >= 0);
    rx.stop();
    TEST_ASSERT_FALSE(rx.running());
    TEST_ASSERT_EQ(rx.socketFd(), -1);
    // Second stop is safe (idempotent close, no join-from-nowhere).
    rx.stop();
    TEST_ASSERT_FALSE(rx.sendDatagram(nullptr, 0));
    // Restart after stop: the socket is closed and the receive thread was
    // joined, so start binds fresh and the path carries voice again.
    // (Done-when (d) receive half: socket closed, receive thread exited.
    // The session-lifetime notifier stops in nativeStop and restarts in
    // nativeReset; its own start/stop/restart is pinned by the notifier
    // unit tests, and the Reset-on-Start ordering by VoiceEngineTest.)
    TEST_ASSERT_TRUE(rx.start("127.0.0.1", 9));
    TEST_ASSERT_TRUE(rx.running());
    TEST_ASSERT_TRUE(rx.socketFd() >= 0);
    const uint8_t key[16] = {51, 52, 53, 54, 55, 56, 57, 58,
                             59, 60, 61, 62, 63, 64, 65, 66};
    TEST_ASSERT_TRUE(rx.setKeys(key, key, key));
    CryptStateOCB2 sender;
    TEST_ASSERT_TRUE(sender.setKey(key, key, key));
    const uint8_t secret[10] = {'z', 'x', 'c', 'v', 'b', 'n', 'm', '1', '2', '3'};
    uint8_t packet[14];
    TEST_ASSERT_TRUE(sender.encrypt(secret, packet, 10));
    rx.handleCrypted(packet, 14);
    int32_t good = 0;
    rx.getStats(&good, nullptr, nullptr, nullptr);
    TEST_ASSERT_EQ(good, 1);
    rx.stop();
    TEST_ASSERT_FALSE(rx.running());
    TEST_ASSERT_EQ(rx.socketFd(), -1);
    // Notifier thread: start/stop joins the thread; second stop is safe
    // and a restart works, so no thread is left behind either.
    TalkCell cell;
    VoiceNotifier notifier(&cell, nullptr, [](const TalkPublication&) {});
    notifier.start();
    notifier.stop();
    notifier.stop();
    notifier.start();
    notifier.stop();
    std::cout << "  [PASS] testReceiveStopLeavesNothing" << std::endl;
}

void testReceiveSocketErrorClearsRunning() {
    g_testCount++;
    // Mid-session UDP death (ICMP refused on a connected socket): the
    // loop must report once and mark the receiver dead so a later start()
    // recreates it. A stranded running flag would make start() a no-op
    // success on a dead socket, leaving UDP silently dead until Stop.
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    const uint8_t key[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    TEST_ASSERT_TRUE(rx.setKeys(key, key, key));
    // Port 9 (discard) is closed on the test host; connecting succeeds
    // (UDP), and the first send provokes ICMP refused, which surfaces as
    // a recv error on the connected socket.
    TEST_ASSERT_TRUE(rx.start("127.0.0.1", 9));
    TEST_ASSERT_TRUE(rx.running());
    bool unavailableFired = false;
    rx.setUnavailableSink([&]() { unavailableFired = true; });
    const uint8_t plain[4] = {'p', 'i', 'n', 'g'};
    for (int i = 0; i < 50 && rx.running(); ++i) {
        rx.sendDatagram(plain, sizeof(plain));
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    TEST_ASSERT_TRUE(unavailableFired);
    TEST_ASSERT_FALSE(rx.running());
    // The loop never closes: single-close ownership stays in stop().
    TEST_ASSERT_TRUE(rx.socketFd() >= 0);
    // Recreate after the error: Start-after-error rebinds fresh (the
    // switchback path depends on exactly this).
    TEST_ASSERT_TRUE(rx.start("127.0.0.1", 9));
    TEST_ASSERT_TRUE(rx.running());
    TEST_ASSERT_TRUE(rx.socketFd() >= 0);
    rx.stop();
    TEST_ASSERT_FALSE(rx.running());
    TEST_ASSERT_EQ(rx.socketFd(), -1);
    std::cout << "  [PASS] testReceiveSocketErrorClearsRunning" << std::endl;
}

void testEmptyProtobufTerminatorDeliversNoFrames() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    rx.setFormat(mumla::voice_codec::UdpFormat::PROTOBUF);
    const int32_t known[] = {42};
    policy.publish(nullptr, 0, known, 1, false, false, false, 0, 40000, 2);
    size_t delivered = 0;
    rx.setVoiceSink([&](const InboundPacket* /*frames*/, size_t count,
                        bool /*tunnel*/) { delivered += count; });
    // MumbleUDP.Audio { sender_session = 42, frame_number = 999,
    // is_terminator = true } with no opus_data. Upstream rejects such a
    // packet, so nothing reaches the UDP or tunnel ingest rings: the voice
    // ends by dead-miss expiry instead of an immediate drain.
    const uint8_t empty[] = {0x00, 0x18, 0x2A, 0x20, 0xE7, 0x07,
                             0x80, 0x01, 0x01};
    TEST_ASSERT_TRUE(rx.handlePlaintext(empty, sizeof(empty)));
    TEST_ASSERT_EQ(delivered, static_cast<size_t>(0));
    TEST_ASSERT_TRUE(rx.handleTunnelled(empty, sizeof(empty)));
    TEST_ASSERT_EQ(delivered, static_cast<size_t>(0));
    std::cout << "  [PASS] testEmptyProtobufTerminatorDeliversNoFrames"
              << std::endl;
}

} // namespace

void run_voice_send_tests();

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
    testWhisperFlagMapsToWhispering();
    testTalkCellPublishesLatest();
    testTalkCellNeverTornUnderThreads();
    testNotifierDeliversOncePerChange();
    testNotifierStalledResumesWithLatest();
    testRenderPublishesTalkCell();
    testClearEmptiesTalkLevels();
    testVoiceIoEnginesArePrivatelyOwned();
    testClearWhileStoppedPublishesEmptyAndEngineSurvives();
    testPolicyDropsMutedOverUdpAndTunnel();
    testPolicyUnknownHoldsNoSlot();
    testPolicyKnownOverflowFailsOpen();
    testSenderFilterEndToEndUdpAndTunnel();
    testPolicyGuardsReceiveVisibleOnly();
    testReceiveResyncGateRateLimited();
    testReceiveCryptCountsMatchKotlin();
    testReceiveResyncFiresExactlyOnce();
    testReceiveLoopbackPingFiresSink();
    testClearDrainsRingsAndCountsResetDiscards();
    testReceiveStopLeavesNothing();
    testReceiveSocketErrorClearsRunning();
    testEmptyProtobufTerminatorDeliversNoFrames();
}

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << " Mumla Voice I/O (5a.3a render, 5a.4 send) Tests" << std::endl;
    std::cout << "========================================" << std::endl;
    run_voice_io_tests();
    run_voice_send_tests();
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
