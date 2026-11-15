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
#include "CryptStateOCB2.h"
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
#include <iostream>
#include <memory>
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
    VoiceNotifier notifier(&cell, [&](const TalkPublication& pub) {
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
    VoiceNotifier notifier(&cell, [&](const TalkPublication& pub) {
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
    TEST_ASSERT_FALSE(result.hasActiveVoices);
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
    TEST_ASSERT_FALSE(result.hasActiveVoices);
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
    TEST_ASSERT_FALSE(result.hasActiveVoices);
    std::cout << "  [PASS] testClearWhileStoppedPublishesEmptyAndEngineSurvives"
              << std::endl;
}

void testAwaitWorkPushInboundWakes() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    std::atomic<bool> woke{false};
    std::thread waiter([&] { woke.store(io.awaitWork(-1)); });
    // Let the waiter enter the semaphore before pushing.
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    TEST_ASSERT_TRUE(io.pushInbound(makePacket(91, 0)));
    waiter.join();
    TEST_ASSERT_TRUE(woke.load());
    std::cout << "  [PASS] testAwaitWorkPushInboundWakes" << std::endl;
}

void testAwaitWorkPushTunnelWakes() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    std::atomic<bool> woke{false};
    std::thread waiter([&] { woke.store(io.awaitWork(-1)); });
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    TEST_ASSERT_TRUE(io.pushTunnel(makePacket(92, 0)));
    waiter.join();
    TEST_ASSERT_TRUE(woke.load());
    std::cout << "  [PASS] testAwaitWorkPushTunnelWakes" << std::endl;
}

void testAwaitWorkStopWakes() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    std::atomic<bool> done{false};
    std::thread waiter([&] {
        io.awaitWork(-1);
        done.store(true);
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    io.wakeForStop();
    waiter.join();
    TEST_ASSERT_TRUE(done.load());
    std::cout << "  [PASS] testAwaitWorkStopWakes" << std::endl;
}

void testAwaitWorkTimeoutFires() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    const auto start = std::chrono::steady_clock::now();
    TEST_ASSERT_FALSE(io.awaitWork(20));
    const auto elapsed = std::chrono::steady_clock::now() - start;
    TEST_ASSERT_TRUE(elapsed >= std::chrono::milliseconds(15));
    TEST_ASSERT_TRUE(elapsed < std::chrono::milliseconds(500));
    std::cout << "  [PASS] testAwaitWorkTimeoutFires" << std::endl;
}

void testAwaitWorkStressLosesNoWakeup() {
    g_testCount++;
    VoiceIo io(fakeFactory());
    constexpr int kRounds = 200;
    for (int i = 0; i < kRounds; ++i) {
        std::atomic<bool> woke{false};
        std::thread waiter([&] { woke.store(io.awaitWork(1000)); });
        // Push immediately, with and without a settle delay on alternate
        // rounds: either the push lands before the waiter's ring check
        // (returns without waiting) or it sees idle and posts.
        if ((i & 1) == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        TEST_ASSERT_TRUE(io.pushInbound(makePacket(93, static_cast<uint32_t>(i))));
        waiter.join();
        TEST_ASSERT_TRUE(woke.load());
        // Drain so the next round starts empty.
        std::vector<int16_t> pcm(kFrame * 2);
        VoiceIo::RenderOut result;
        io.render(pcm.data(), pcm.size(), &result);
    }
    std::cout << "  [PASS] testAwaitWorkStressLosesNoWakeup" << std::endl;
}

void testPolicyDropsMutedOverUdpAndTunnel() {
    g_testCount++;
    PolicyCell cell;
    const int32_t muted[] = {7};
    const int32_t known[] = {7, 8};
    cell.publish(muted, 1, known, 2, false);
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
    cell.publish(muted, 0, known, 1, false);
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
    cell.publish(nullptr, 0, known, 300, false);
    PolicySnapshot snap;
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_TRUE(snap.knownOverflow);
    TEST_ASSERT_EQ(snap.knownCount, mumla::voice_io::kPolicyListCapacity);
    // Overflow fails open: unknown passes, muted still drops.
    TEST_ASSERT_FALSE(PolicyCell::shouldDrop(snap, 9999));
    const int32_t muted[] = {9999};
    cell.publish(muted, 1, known, 300, false);
    TEST_ASSERT_TRUE(cell.tryRead(&snap));
    TEST_ASSERT_TRUE(PolicyCell::shouldDrop(snap, 9999));
    TEST_ASSERT_FALSE(PolicyCell::shouldDrop(snap, 8888));
    std::cout << "  [PASS] testPolicyKnownOverflowFailsOpen" << std::endl;
}

void testPolicyGuardsReceiveVisibleOnly() {
    g_testCount++;
    // Pins the 5a.3b done-when lock-scope rule: the policy cell carries
    // exactly the receive-visible sender-filter lists (muted + known) and
    // nothing transmit-side (no bitrate, frames, voice target, PTT).
    static_assert(sizeof(PolicySnapshot) <=
                  (256 + 256) * sizeof(int32_t) + 32,
                  "PolicySnapshot must stay the two filter lists plus flags");
    PolicyCell cell;
    const int32_t muted[] = {3};
    const int32_t known[] = {3, 4};
    cell.publish(muted, 1, known, 2, false);
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
    // Fixture for the 5a.3b done-when crypt-count parity: the Kotlin path
    // reports these counts for the same scripted sequence through the same
    // OCB2 engine (in-order, replayed, lost), so the native path must
    // reproduce them or the transport's health check mis-falls-back.
    VoiceIo io(fakeFactory());
    PolicyCell policy;
    VoiceReceive rx(&io, &policy);
    const uint8_t key[16] = {16, 32, 48, 64, 80, 96, 112, 128,
                             144, 160, 176, 192, 208, 224, 240, 0};
    TEST_ASSERT_TRUE(rx.setKeys(key, key, key));
    CryptStateOCB2 sender;
    TEST_ASSERT_TRUE(sender.setKey(key, key, key));
    const uint8_t secret[10] = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'};
    uint8_t packet[14];
    TEST_ASSERT_TRUE(sender.encrypt(secret, packet, 10));
    rx.handleCrypted(packet, 14);
    int32_t good = 0, late = 0, lost = 0, resync = 0;
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 1);
    // Replay rejected: good stays.
    rx.handleCrypted(packet, 14);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 1);
    // Skip 5 (lost 4 on the next good).
    for (int i = 0; i < 5; ++i) {
        TEST_ASSERT_TRUE(sender.encrypt(secret, packet, 10));
    }
    rx.handleCrypted(packet, 14);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 2);
    TEST_ASSERT_EQ(lost, 4);
    // Forced decrypt failure with a recent good decrypt: the rate limiter
    // suppresses the resync request (the 5 s gate is pinned pure above).
    int resyncs = 0;
    rx.setResyncSink([&] { resyncs++; });
    uint8_t tampered[14];
    memcpy(tampered, packet, 14);
    tampered[5] ^= 0xFF;
    TEST_ASSERT_FALSE(rx.handleCrypted(tampered, 14));
    TEST_ASSERT_EQ(resyncs, 0);
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 2);
    // Rekey (new IV after the resync round trip): voice resumes. Rekey
    // resets the counters on both sides (as Kotlin setKeys does), so good
    // restarts at 1 with the first post-rekey decrypt.
    const uint8_t key2[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    TEST_ASSERT_TRUE(rx.setKeys(key2, key2, key2));
    TEST_ASSERT_TRUE(sender.setKey(key2, key2, key2));
    TEST_ASSERT_TRUE(sender.encrypt(secret, packet, 10));
    TEST_ASSERT_TRUE(rx.handleCrypted(packet, 14));
    rx.getStats(&good, &late, &lost, &resync);
    TEST_ASSERT_EQ(good, 1);
    TEST_ASSERT_EQ(lost, 0);
    std::cout << "  [PASS] testReceiveCryptCountsMatchKotlin" << std::endl;
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
    std::cout << "  [PASS] testReceiveStopLeavesNothing" << std::endl;
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
    testWhisperFlagMapsToWhispering();
    testTalkCellPublishesLatest();
    testTalkCellNeverTornUnderThreads();
    testNotifierDeliversOncePerChange();
    testNotifierStalledResumesWithLatest();
    testRenderPublishesTalkCell();
    testClearEmptiesTalkLevels();
    testVoiceIoEnginesArePrivatelyOwned();
    testClearWhileStoppedPublishesEmptyAndEngineSurvives();
    testAwaitWorkPushInboundWakes();
    testAwaitWorkPushTunnelWakes();
    testAwaitWorkStopWakes();
    testAwaitWorkTimeoutFires();
    testAwaitWorkStressLosesNoWakeup();
    testPolicyDropsMutedOverUdpAndTunnel();
    testPolicyUnknownHoldsNoSlot();
    testPolicyKnownOverflowFailsOpen();
    testPolicyGuardsReceiveVisibleOnly();
    testReceiveResyncGateRateLimited();
    testReceiveCryptCountsMatchKotlin();
    testReceiveStopLeavesNothing();
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
