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

// Host tests for the 5a.4 native send half: gate/settings cells, the capture
// entry point (any block size, exact 10 ms frames, self-talking flag), the
// outbound queue (full-ring drop-newest, 200 ms drop-oldest), and the native
// half-duplex render rule. Step 5b adds the AAudio callback bodies: render
// through the two-frame (20 ms) carry buffer and disconnect delivery by the
// notifier.

#include "TestHarness.h"
#include "AudioInputEngine.h"
#include "VoiceConfig.h"
#include "VoiceIo.h"
#include "VoiceNotifier.h"
#include "VoiceReceive.h"
#include "VoiceSend.h"
#include "VoiceStreamContext.h"
#include "VoiceTalk.h"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <new>
#include <thread>
#include <vector>

namespace {
size_t g_allocations = 0;
} // namespace

extern "C" {
void* __real_malloc(size_t size);
void* __real_calloc(size_t count, size_t size);
void* __real_realloc(void* ptr, size_t size);
void __real_free(void* ptr);

void* __wrap_malloc(size_t size) {
    ++g_allocations;
    return __real_malloc(size);
}

void* __wrap_calloc(size_t count, size_t size) {
    ++g_allocations;
    return __real_calloc(count, size);
}

void* __wrap_realloc(void* ptr, size_t size) {
    ++g_allocations;
    return __real_realloc(ptr, size);
}

void __wrap_free(void* ptr) { __real_free(ptr); }
} // extern "C"

void* operator new(size_t size) {
    ++g_allocations;
    void* ptr = __real_malloc(size == 0 ? 1 : size);
    if (ptr == nullptr) throw std::bad_alloc();
    return ptr;
}

void* operator new[](size_t size) {
    ++g_allocations;
    void* ptr = __real_malloc(size == 0 ? 1 : size);
    if (ptr == nullptr) throw std::bad_alloc();
    return ptr;
}

void operator delete(void* ptr) noexcept { __real_free(ptr); }
void operator delete[](void* ptr) noexcept { __real_free(ptr); }
void operator delete(void* ptr, size_t) noexcept { __real_free(ptr); }
void operator delete[](void* ptr, size_t) noexcept { __real_free(ptr); }

namespace {

using mumla::audio::AudioInputEngine;
using mumla::audio::AudioOutputEngine;
using mumla::audio::IDenoiser;
using mumla::audio::IOutputDecoder;
using mumla::audio::IVoiceEncoder;
using mumla::audio::InboundPacket;
using mumla::voice_io::PolicyCell;
using mumla::voice_io::PolicySnapshot;
using mumla::voice_io::OutboundPacket;
using mumla::voice_io::SettingsCell;
using mumla::voice_io::SettingsSnapshot;
using mumla::voice_io::TalkCell;
using mumla::voice_io::TalkPublication;
using mumla::voice_io::VoiceIo;
using mumla::voice_io::VoiceNotifier;
using mumla::voice_io::VoiceReceive;
using mumla::voice_io::VoiceSend;
using mumla::voice_io::VoiceStreamContext;
using mumla::voice_io::kOutboundRingCapacity;
using mumla::voice_io::kStreamChunkSamples;
using mumla::voice_io::renderStreamFrames;

/** Deterministic fake encoder: 32 bytes per encode, no allocation. */
class FakeVoiceEncoder : public IVoiceEncoder {
public:
    explicit FakeVoiceEncoder(int bitrate = 40000) : m_bitrate(bitrate) {}

    int encode(const int16_t* pcm, size_t sampleCount, uint8_t* outBuffer,
               size_t maxBytes) override {
        if (pcm == nullptr || outBuffer == nullptr || maxBytes == 0) return -1;
        if (sampleCount != 480 && sampleCount != 960 && sampleCount != 1920 &&
            sampleCount != 2880) {
            return -1;
        }
        m_encodeCount++;
        const size_t n = std::min(maxBytes, static_cast<size_t>(32));
        for (size_t i = 0; i < n; ++i) {
            outBuffer[i] = static_cast<uint8_t>((m_encodeCount + i) & 0xFF);
        }
        return static_cast<int>(n);
    }

    void setBitrate(int bitrate) override { m_bitrate = bitrate; }
    int getBitrate() const override { return m_bitrate; }
    void reset() override {}

private:
    int m_bitrate;
    int m_encodeCount = 0;
};

VoiceSend::EncoderFactory fakeEncoderFactory() {
    return [] { return std::make_unique<FakeVoiceEncoder>(); };
}

void publishOpenGate(PolicyCell* policy, int32_t fpp = 2) {
    int32_t empty[1] = {0};
    policy->publish(empty, 0, empty, 0, false,
                    /*transmitAllowed=*/true, /*pttPressed=*/false,
                    /*voiceTarget=*/0, /*bitrate=*/40000, fpp);
}

void publishContinuousSettings(SettingsCell* settings) {
    settings->publish(/*inputMode=*/2, /*vadMax=*/1.0f, /*vadMin=*/0.7f,
                      /*amplitudeBoost=*/1.0f,
                      /*adaptiveLevelerEnabled=*/true, /*halfDuplex=*/false);
}

void testPolicyGatePublishesAndReads() {
    g_testCount++;
    PolicyCell cell;
    PolicySnapshot out;
    TEST_ASSERT_TRUE(cell.tryRead(&out));
    TEST_ASSERT_FALSE(out.transmitAllowed);
    int32_t empty[1] = {0};
    cell.publish(empty, 0, empty, 0, false, true, true, 7, 24000, 4);
    TEST_ASSERT_TRUE(cell.tryRead(&out));
    TEST_ASSERT_TRUE(out.transmitAllowed);
    TEST_ASSERT_TRUE(out.pttPressed);
    TEST_ASSERT_EQ(out.voiceTarget, 7);
    TEST_ASSERT_EQ(out.bitrate, 24000);
    TEST_ASSERT_EQ(out.framesPerPacket, 4);
    std::cout << "  [PASS] testPolicyGatePublishesAndReads" << std::endl;
}

void testSettingsCellPublishesAndReads() {
    g_testCount++;
    SettingsCell cell;
    SettingsSnapshot out;
    TEST_ASSERT_TRUE(cell.tryRead(&out));
    TEST_ASSERT_EQ(out.inputMode, 0);
    cell.publish(1, 0.8f, 0.56f, 2.0f, false, true);
    TEST_ASSERT_TRUE(cell.tryRead(&out));
    TEST_ASSERT_EQ(out.inputMode, 1);
    TEST_ASSERT_NEAR(out.vadMax, 0.8f, 1e-6);
    TEST_ASSERT_NEAR(out.vadMin, 0.56f, 1e-6);
    TEST_ASSERT_NEAR(out.amplitudeBoost, 2.0f, 1e-6);
    TEST_ASSERT_FALSE(out.adaptiveLevelerEnabled);
    TEST_ASSERT_TRUE(out.halfDuplex);
    std::cout << "  [PASS] testSettingsCellPublishesAndReads" << std::endl;
}

void testCaptureAcceptsAnyBlockSize() {
    g_testCount++;
    // Continuous mode transmits every frame, so every 480-sample frame must
    // produce packets regardless of how the caller slices the blocks. Feed
    // 100 + 380 (one frame across two calls) + 960 (two frames) + 20 (tail
    // carried, no frame yet): exactly 3 frames processed, tail held.
    PolicyCell policy;
    SettingsCell settings;
    publishOpenGate(&policy, 2);
    publishContinuousSettings(&settings);
    VoiceReceive* noReceive = nullptr;
    VoiceSend send(&policy, &settings, noReceive, fakeEncoderFactory(), nullptr);
    send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
    std::vector<int16_t> block(480, 1000);
    // 100 samples: no frame yet.
    send.captureFrame(block.data(), 100);
    TEST_ASSERT_EQ(send.outboundSizeForTest(), 0u);
    TEST_ASSERT_EQ(send.droppedOutbound(), 0u);
    // 380 more: completes the first frame (FPP=2 accumulates, no packet yet
    // since 1 frame < 2 FPP).
    send.captureFrame(block.data(), 380);
    TEST_ASSERT_EQ(send.droppedOutbound(), 0u);
    const size_t afterOne = send.outboundSizeForTest();
    // 960 samples = 2 frames: total 3 frames -> 1 packet at FPP=2 (2 frames
    // per packet, 1 frame remainder carried).
    send.captureFrame(block.data(), 960);
    const size_t afterThree = send.outboundSizeForTest();
    TEST_ASSERT_EQ(afterOne, 0u);
    TEST_ASSERT_EQ(afterThree, 1u);
    // 20-sample tail: carried, no new frame.
    send.captureFrame(block.data(), 20);
    TEST_ASSERT_EQ(send.outboundSizeForTest(), afterThree);
    // Flush the carry with 460 more (20 + 460 = 480): one more frame.
    // Total 4 frames at FPP=2 -> 2 packets, no remainder.
    send.captureFrame(block.data(), 460);
    TEST_ASSERT_EQ(send.outboundSizeForTest(), 2u);
    std::cout << "  [PASS] testCaptureAcceptsAnyBlockSize" << std::endl;
}

void testCaptureGateClosesOnMute() {
    g_testCount++;
    // Continuous mode with an open gate transmits; closing transmitAllowed
    // mid-utterance flushes one terminator and then goes silent. The
    // self-talking flag follows the same edge.
    PolicyCell policy;
    SettingsCell settings;
    publishOpenGate(&policy, 1);
    publishContinuousSettings(&settings);
    VoiceSend send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr);
    send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
    std::vector<int16_t> pcm(480, 2000);
    for (int i = 0; i < 4; ++i) {
        send.captureFrame(pcm.data(), pcm.size());
    }
    TEST_ASSERT_TRUE(send.selfTalking());
    TEST_ASSERT_TRUE(send.outboundSizeForTest() > 0);
    // Drain the talking packets so only the mute edge remains observable.
    OutboundPacket tmp;
    while (send.popOutboundForTest(&tmp)) {
    }
    const uint64_t dropsBefore = send.droppedOutbound();
    int32_t emptyMute[1] = {0};
    policy.publish(emptyMute, 0, emptyMute, 0, false, false, false, 0, 40000, 1);
    send.captureFrame(pcm.data(), pcm.size());
    TEST_ASSERT_FALSE(send.selfTalking());
    // Exactly one terminator for the mute edge, then silence.
    TEST_ASSERT_EQ(send.outboundSizeForTest(), 1u);
    TEST_ASSERT_EQ(send.droppedOutbound(), dropsBefore);
    send.captureFrame(pcm.data(), pcm.size());
    TEST_ASSERT_EQ(send.outboundSizeForTest(), 1u);
    std::cout << "  [PASS] testCaptureGateClosesOnMute" << std::endl;
}

void testOutboundFullDropsNewest() {
    g_testCount++;
    // Ring holds 40; the 41st push drops the new item and counts it. The
    // send thread is not running here, so no drop-oldest interferes: this
    // pins the drop-newest counter alone.
    PolicyCell policy;
    SettingsCell settings;
    publishOpenGate(&policy, 1);
    publishContinuousSettings(&settings);
    VoiceSend send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr);
    send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
    std::vector<int16_t> pcm(480, 3000);
    // FPP=1: every frame emits one packet. 40 frames fill the ring exactly.
    for (size_t i = 0; i < kOutboundRingCapacity; ++i) {
        send.captureFrame(pcm.data(), pcm.size());
    }
    TEST_ASSERT_EQ(send.outboundSizeForTest(), kOutboundRingCapacity);
    TEST_ASSERT_EQ(send.droppedOutbound(), 0u);
    send.captureFrame(pcm.data(), pcm.size());
    TEST_ASSERT_EQ(send.outboundSizeForTest(), kOutboundRingCapacity);
    TEST_ASSERT_EQ(send.droppedOutbound(), 1u);
    std::cout << "  [PASS] testOutboundFullDropsNewest" << std::endl;
}

void testDropOldestKeeps200ms() {
    g_testCount++;
    // Done-when pin (spec: skip every queued packet older than 200 ms):
    // fill the ring with send stopped, then start the send thread with a
    // recording tunnel sink and assert the kept newest suffix plus the
    // dropped prefix. Age = sum of newer durations (each packet carries its
    // FPP), keep while age <= 200 ms.
    // FPP=1 (10 ms): 40 packets fill the ring; keep newest 21 (ages 0..200),
    // drop oldest 19. Count-bound would keep 20 — duration keeps 21.
    {
        PolicyCell policy;
        SettingsCell settings;
        publishOpenGate(&policy, 1);
        publishContinuousSettings(&settings);
        VoiceSend send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr);
        send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
        send.setUseUdp(false);
        std::vector<OutboundPacket> sent;
        // NOTE: send thread not running yet, so the tunnel sink below only
        // runs after start(); no locking needed for the test's own vector
        // because the test waits for the drain before reading it.
        send.setTunnelSink([&](const uint8_t* data, size_t length) {
            OutboundPacket pkt;
            pkt.length = length > sizeof(pkt.data) ? sizeof(pkt.data) : length;
            std::memcpy(pkt.data, data, pkt.length);
            sent.push_back(pkt);
        });
        std::vector<int16_t> pcm(480, 3000);
        for (size_t i = 0; i < kOutboundRingCapacity; ++i) {
            send.captureFrame(pcm.data(), pcm.size());
        }
        TEST_ASSERT_EQ(send.outboundSizeForTest(), kOutboundRingCapacity);
        TEST_ASSERT_EQ(send.droppedOutbound(), 0u);
        send.start();
        // Wait for the drain (send thread pops all, keeps newest 200 ms).
        for (int i = 0; i < 200 && send.outboundSizeForTest() > 0; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        // Extra tick so the final sink append lands before we read `sent`.
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        send.stop();
        TEST_ASSERT_EQ(sent.size(), 21u);
        TEST_ASSERT_EQ(send.droppedOutbound(), 19u);
        TEST_ASSERT_EQ(send.outboundSizeForTest(), 0u);
    }
    // FPP=6 (60 ms): keep newest 4 (ages 0,60,120,180), drop 36 of 40.
    {
        PolicyCell policy;
        SettingsCell settings;
        publishOpenGate(&policy, 6);
        publishContinuousSettings(&settings);
        VoiceSend send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr);
        send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
        send.setUseUdp(false);
        std::vector<OutboundPacket> sent;
        send.setTunnelSink([&](const uint8_t* data, size_t length) {
            OutboundPacket pkt;
            pkt.length = length > sizeof(pkt.data) ? sizeof(pkt.data) : length;
            std::memcpy(pkt.data, data, pkt.length);
            sent.push_back(pkt);
        });
        std::vector<int16_t> pcm(480, 3000);
        // 40 packets at FPP=6 needs 240 frames (2400 ms audio).
        for (size_t i = 0; i < kOutboundRingCapacity * 6; ++i) {
            send.captureFrame(pcm.data(), pcm.size());
        }
        TEST_ASSERT_EQ(send.outboundSizeForTest(), kOutboundRingCapacity);
        send.start();
        for (int i = 0; i < 200 && send.outboundSizeForTest() > 0; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        send.stop();
        TEST_ASSERT_EQ(sent.size(), 4u);
        // 40 staged, 4 kept and sent, 36 dropped as older than 200 ms.
        TEST_ASSERT_EQ(send.droppedOutbound(), 36u);
    }
    std::cout << "  [PASS] testDropOldestKeeps200ms" << std::endl;
}

void testHalfDuplexRenderSilences() {
    g_testCount++;
    // Native half duplex (step 5a.4): render writes silence instead of the
    // mix while the effective flag is set and capture reports self-talking.
    // Fake decoder outputs a constant level, so any non-silent render must
    // exceed 100 peak; the muted render must be all zeros.
    auto fakeFactory = [] {
        struct FakeDecoder : public mumla::audio::IOutputDecoder {
            int decodeFloat(const uint8_t*, size_t, float* out, int maxSamples,
                            int) override {
                const int n = std::min<int>(480, maxSamples);
                for (int i = 0; i < n; ++i) out[i] = 0.5f;
                return n;
            }
            int decodeConcealment(float* out, int frameSize) override {
                for (int i = 0; i < frameSize; ++i) out[i] = 0.5f;
                return frameSize;
            }
            int packetSampleCount(const uint8_t*, size_t) const override { return 480; }
            bool isValid() const override { return true; }
            void reset() override {}
        };
        return std::make_unique<FakeDecoder>();
    };
    VoiceIo io(fakeFactory);
    SettingsCell settings;
    std::atomic<bool> selfTalking{false};
    io.setSettingsCell(&settings);
    io.setSelfTalking(&selfTalking);
    settings.publish(1, 1.0f, 0.7f, 1.0f, true, true);

    mumla::audio::InboundPacket packet;
    packet.session = 9;
    packet.sequence = 0;
    packet.flags = 0;
    packet.isTerminator = false;
    static const uint8_t kPayload[8] = {0xF8, 1, 2, 3, 4, 5, 6, 7};
    packet.length = sizeof(kPayload);
    std::memcpy(packet.data, kPayload, sizeof(kPayload));
    for (int i = 0; i < 6; ++i) {
        packet.sequence = static_cast<uint32_t>(i);
        TEST_ASSERT_TRUE(io.pushInbound(packet));
    }
    std::vector<int16_t> pcm(960, 0);
    VoiceIo::RenderOut result;
    // Not self-talking: remote voice plays (non-silent).
    selfTalking.store(false);
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(result.samples > 0);
    int peak = 0;
    for (size_t i = 0; i < result.samples; ++i) {
        peak = std::max<int>(peak, std::abs(static_cast<int>(pcm[i])));
    }
    TEST_ASSERT_TRUE(peak > 100);
    // Self-talking with half duplex: silence, but voices stay registered.
    for (int i = 6; i < 12; ++i) {
        packet.sequence = static_cast<uint32_t>(i);
        io.pushInbound(packet);
    }
    selfTalking.store(true);
    std::fill(pcm.begin(), pcm.end(), 0);
    io.render(pcm.data(), pcm.size(), &result);
    TEST_ASSERT_TRUE(result.samples > 0);
    for (size_t i = 0; i < result.samples; ++i) {
        TEST_ASSERT_EQ(pcm[i], 0);
    }
    std::cout << "  [PASS] testHalfDuplexRenderSilences" << std::endl;
}

void testAllocationFreeSteadyState() {
    g_testCount++;
    // Done-when pin: the realtime capture path allocates nothing after
    // warmup. The binary replaces global new/delete and wraps malloc/calloc/
    // realloc (see top of file), so any heap use by processFrame, framing,
    // or ring push changes the count. Setup (construction, model-free fake
    // encoder, first frames, ring drain) happens before recording.
    PolicyCell policy;
    SettingsCell settings;
    publishOpenGate(&policy, 2);
    publishContinuousSettings(&settings);
    VoiceSend send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr);
    send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
    int16_t pcm[480];
    for (int i = 0; i < 480; ++i) pcm[i] = 1000;
    for (int i = 0; i < 5; ++i) send.captureFrame(pcm, 480);
    OutboundPacket drain;
    while (send.popOutboundForTest(&drain)) {
    }
    const size_t before = g_allocations;
    for (int i = 0; i < 20; ++i) send.captureFrame(pcm, 480);
    TEST_ASSERT_EQ(g_allocations, before);
    std::cout << "  [PASS] testAllocationFreeSteadyState" << std::endl;
}

void testSendStartStopIdempotent() {
    g_testCount++;
    // nativeReset's stop-before-clear and nativeStop both rely on this:
    // start() while running is a no-op (no second thread), stop() when
    // stopped only joins if joinable, and a restart works.
    PolicyCell policy;
    SettingsCell settings;
    publishOpenGate(&policy, 2);
    publishContinuousSettings(&settings);
    VoiceSend send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr);
    int starts = 0;
    send.setThreadHooks([&]() { ++starts; }, []() {});
    send.start();
    TEST_ASSERT_TRUE(send.running());
    for (int i = 0; i < 200 && starts == 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    send.start();
    TEST_ASSERT_TRUE(send.running());
    TEST_ASSERT_EQ(starts, 1);
    send.stop();
    TEST_ASSERT_FALSE(send.running());
    send.stop();
    TEST_ASSERT_FALSE(send.running());
    send.start();
    TEST_ASSERT_TRUE(send.running());
    for (int i = 0; i < 200 && starts == 1; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    TEST_ASSERT_EQ(starts, 2);
    send.stop();
    TEST_ASSERT_FALSE(send.running());
    std::cout << "  [PASS] testSendStartStopIdempotent" << std::endl;
}

/** Constant-level decoder; counts FEC attempts and concealment frames. */
class CallbackDecoder : public IOutputDecoder {
public:
    CallbackDecoder(int* fecAttempts, int* concealedFrames, bool payloadLevel)
        : m_fecAttempts(fecAttempts),
          m_concealedFrames(concealedFrames),
          m_payloadLevel(payloadLevel) {}

    int decodeFloat(const uint8_t* data, size_t length, float* out,
                    int maxSamples, int decodeFec) override {
        const int n = std::min<int>(480, maxSamples);
        if (decodeFec != 0 && m_fecAttempts != nullptr) {
            ++(*m_fecAttempts);
        }
        float level = decodeFec != 0 ? 0.3f : 0.2f;
        if (m_payloadLevel && data != nullptr && length > 0 && data[0] == 0) {
            level = 0.0f;
        }
        for (int i = 0; i < n; ++i) out[i] = level;
        return n;
    }

    int decodeConcealment(float* out, int frameSize) override {
        if (m_concealedFrames != nullptr) {
            ++(*m_concealedFrames);
        }
        for (int i = 0; i < frameSize; ++i) out[i] = 0.0f;
        return frameSize;
    }

    int packetSampleCount(const uint8_t*, size_t) const override { return 480; }
    bool isValid() const override { return true; }
    void reset() override {}

private:
    int* m_fecAttempts;
    int* m_concealedFrames;
    bool m_payloadLevel;
};

AudioOutputEngine::DecoderFactory callbackDecoderFactory(
    int* fecAttempts = nullptr, int* concealedFrames = nullptr,
    bool payloadLevel = false) {
    return [fecAttempts, concealedFrames, payloadLevel] {
        return std::make_unique<CallbackDecoder>(fecAttempts,
                                                concealedFrames, payloadLevel);
    };
}

InboundPacket makeInboundPacket(int32_t session, uint32_t sequence) {
    InboundPacket packet;
    packet.session = session;
    packet.sequence = sequence;
    static const uint8_t kPayload[8] = {0xF8, 1, 2, 3, 4, 5, 6, 7};
    packet.length = sizeof(kPayload);
    std::memcpy(packet.data, kPayload, sizeof(kPayload));
    return packet;
}

// A payload whose first byte is zero makes the payload-level decoder emit
// one silent 10 ms frame without changing its timing or sequence.
InboundPacket makeSilentInboundPacket(int32_t session, uint32_t sequence) {
    InboundPacket packet = makeInboundPacket(session, sequence);
    packet.data[0] = 0;
    return packet;
}

/** The real objects behind one VoiceStreamContext, as in JNI. */
struct StreamFixture {
    explicit StreamFixture(int* fecAttempts = nullptr,
                           int* concealedFrames = nullptr,
                           bool payloadLevel = false)
        : io(callbackDecoderFactory(fecAttempts, concealedFrames,
                                    payloadLevel)),
          send(&policy, &settings, nullptr, fakeEncoderFactory(), nullptr),
          streams(&io, &send) {}

    PolicyCell policy;
    SettingsCell settings;
    VoiceIo io;
    VoiceSend send;
    VoiceStreamContext streams;
};

void testRenderCallbackBurstsWriteExactly() {
    g_testCount++;
    // AAudio hands the render callback whatever burst size the device picks.
    // The body renders one 20 ms chunk at a time and copies out exactly the
    // requested number of samples, keeping the remainder for the next call.
    StreamFixture fixture;
    std::vector<int16_t> out(960, 0x1234);
    // Warm up so the first counted calls cannot hide one-time setup.
    TEST_ASSERT_TRUE(renderStreamFrames(&fixture.streams, out.data(), 960));
    TEST_ASSERT_EQ(fixture.streams.renderCarryRemaining, 0u);
    const size_t before = g_allocations;
    const size_t bursts[] = {96, 192, 441, 960};
    for (size_t burst : bursts) {
        std::fill(out.begin(), out.begin() + static_cast<long>(burst), 0x1234);
        const bool silent =
            renderStreamFrames(&fixture.streams, out.data(), burst);
        TEST_ASSERT_TRUE(silent);
        for (size_t i = 0; i < burst; ++i) {
            TEST_ASSERT_EQ(out[i], 0);
        }
    }
    TEST_ASSERT_EQ(g_allocations, before);
    // 96 samples ten times consumes exactly one 960-sample carry chunk; one
    // more 96 leaves 864 samples of the next chunk for later calls.
    mumla::voice_io::resetRenderCarry(&fixture.streams);
    for (int i = 0; i < 10; ++i) {
        renderStreamFrames(&fixture.streams, out.data(), 96);
    }
    TEST_ASSERT_EQ(fixture.streams.renderCarryRemaining, 0u);
    renderStreamFrames(&fixture.streams, out.data(), 96);
    TEST_ASSERT_EQ(fixture.streams.renderCarryRemaining,
                   kStreamChunkSamples - 96u);
    std::cout << "  [PASS] testRenderCallbackBurstsWriteExactly" << std::endl;
}

void testRenderCallbackChunksGateAndFec() {
    g_testCount++;
    // A 20 ms chunk is two whole 10 ms frames: the startup gate counts
    // samples / FRAME_SIZE (so two per chunk), and a first-frame loss can be
    // recovered from the successor's in-band FEC in the same render call.
    {
        StreamFixture fixture;
        TEST_ASSERT_TRUE(fixture.io.pushInbound(makeInboundPacket(9, 0)));
        std::vector<int16_t> out(96);
        int audioChunk = -1;
        constexpr int kCallsPerChunk =
            static_cast<int>(kStreamChunkSamples) / 96;
        constexpr int kChunks = AudioOutputEngine::GATE_TIMEOUT_FRAMES / 2 + 2;
        for (int i = 0; i < kCallsPerChunk * kChunks; ++i) {
            (void)renderStreamFrames(&fixture.streams, out.data(), 96);
            // Gated quanta are reported as non-silent (they keep a sink fed
            // with zeros), so the pin scans PCM instead of the silence flag.
            int peak = 0;
            for (int16_t sample : out) {
                peak = std::max<int>(peak, std::abs(static_cast<int>(sample)));
            }
            if (peak > 100 && audioChunk < 0) {
                audioChunk = i / kCallsPerChunk + 1;
            }
        }
        TEST_ASSERT_EQ(audioChunk,
                       AudioOutputEngine::GATE_TIMEOUT_FRAMES / 2);
    }
    // A gap in a chunk's first frame is recovered by an FEC decode; a gap in
    // its second frame is concealed, because the unrecovered debt slot is
    // settled at the end of the call and the successor arrives in the next
    // chunk. Playback starts at seq 0, so chunks are [0,1], [2,3], [4,5],
    // [6,7], ...: even gaps land in the first frame, odd in the second.
    struct FecOutcome {
        int fec;
        int concealed;
    };
    constexpr int kCallsPerChunk =
        static_cast<int>(kStreamChunkSamples) / 96;
    auto runGap = [kCallsPerChunk](uint32_t gap) {
        int fecAttempts = 0;
        int concealedFrames = 0;
        StreamFixture fixture(&fecAttempts, &concealedFrames);
        for (uint32_t seq = 0; seq <= 12; ++seq) {
            if (seq == gap) continue;
            TEST_ASSERT_TRUE(
                fixture.io.pushInbound(makeInboundPacket(11, seq)));
        }
        std::vector<int16_t> out(96);
        for (int i = 0; i < kCallsPerChunk * 12; ++i) {
            (void)renderStreamFrames(&fixture.streams, out.data(), 96);
        }
        return FecOutcome{fecAttempts, concealedFrames};
    };
    // First-frame loss (gap 6, successor 7 in the same chunk): an FEC decode.
    const FecOutcome firstFrameLoss = runGap(6);
    TEST_ASSERT_EQ(firstFrameLoss.fec, 1);
    // Second-frame loss (gap 7, successor 8 in the next chunk): concealment,
    // no FEC decode.
    const FecOutcome secondFrameLoss = runGap(7);
    TEST_ASSERT_EQ(secondFrameLoss.fec, 0);
    TEST_ASSERT_TRUE(secondFrameLoss.concealed > 0);
    // The split alternates with chunk parity across several gaps, so the pin
    // is about the two-frame chunk structure, not one lucky packet pattern.
    for (uint32_t gap = 2; gap <= 11; ++gap) {
        const FecOutcome outcome = runGap(gap);
        if (gap % 2u == 0u) {
            TEST_ASSERT_TRUE(outcome.fec > 0);
        } else {
            TEST_ASSERT_EQ(outcome.fec, 0);
        }
    }
    std::cout << "  [PASS] testRenderCallbackChunksGateAndFec" << std::endl;
}

void testRenderCallbackSilenceReportCoversCarry() {
    g_testCount++;
    // A burst that only consumes leftover PCM from a non-silent carry frame
    // must not report silence: nativeRender returns 0 on silence, so that
    // would hide the audio from the host and interop test entries.
    StreamFixture fixture;
    for (uint32_t seq = 0; seq <= 5; ++seq) {
        TEST_ASSERT_TRUE(fixture.io.pushInbound(makeInboundPacket(13, seq)));
    }
    std::vector<int16_t> out(96);
    int audibleBursts = 0;
    for (int i = 0; i < 10 * 6; ++i) {
        const bool silent =
            renderStreamFrames(&fixture.streams, out.data(), 96);
        int peak = 0;
        for (int16_t sample : out) {
            peak = std::max<int>(peak, std::abs(static_cast<int>(sample)));
        }
        if (peak > 100) {
            ++audibleBursts;
            TEST_ASSERT_FALSE(silent);
        }
    }
    TEST_ASSERT_TRUE(audibleBursts > 0);
    std::cout << "  [PASS] testRenderCallbackSilenceReportCoversCarry" << std::endl;
}

void testRenderCallbackSilenceReportUsesCopiedFrames() {
    g_testCount++;
    // Even packets decode to one silent 10 ms frame, odd packets to an
    // audible frame. A 20 ms chunk therefore has one silent and one audible
    // frame: 96-sample bursts align with frame boundaries, so a burst from
    // the silent half must report silence even though the chunk has audio.
    StreamFixture fixture(nullptr, nullptr, true);
    for (uint32_t seq = 0; seq <= 11; ++seq) {
        TEST_ASSERT_TRUE(fixture.io.pushInbound(
            seq % 2u == 0u ? makeSilentInboundPacket(23, seq)
                           : makeInboundPacket(23, seq)));
    }
    constexpr int kBurstsPerChunk =
        static_cast<int>(kStreamChunkSamples) / 96;
    std::vector<int16_t> out(96);
    bool foundMixedChunk = false;
    for (int chunk = 0; chunk < 12 && !foundMixedChunk; ++chunk) {
        std::vector<int> peaks(kBurstsPerChunk, 0);
        std::vector<bool> silent(kBurstsPerChunk, true);
        for (int burst = 0; burst < kBurstsPerChunk; ++burst) {
            std::fill(out.begin(), out.end(), 0x1234);
            silent[burst] =
                renderStreamFrames(&fixture.streams, out.data(), 96);
            for (int16_t sample : out) {
                peaks[burst] = std::max<int>(
                    peaks[burst], std::abs(static_cast<int>(sample)));
            }
        }
        bool hasSilentBurst = false;
        bool hasAudibleBurst = false;
        for (int burst = 0; burst < kBurstsPerChunk; ++burst) {
            if (peaks[burst] > 100) {
                hasAudibleBurst = true;
            } else {
                hasSilentBurst = true;
            }
        }
        if (!hasSilentBurst || !hasAudibleBurst) {
            continue;
        }
        foundMixedChunk = true;
        for (int burst = 0; burst < kBurstsPerChunk; ++burst) {
            if (peaks[burst] > 100) {
                TEST_ASSERT_FALSE(silent[burst]);
            } else {
                TEST_ASSERT_TRUE(silent[burst]);
            }
        }
    }
    TEST_ASSERT_TRUE(foundMixedChunk);
    std::cout << "  [PASS] testRenderCallbackSilenceReportUsesCopiedFrames"
              << std::endl;
}

void testRenderCallbackGatingStillReportsNonSilent() {
    g_testCount++;
    // A gated quantum writes zeros but 5a reported it as non-silent so the
    // Kotlin pacer kept the sink fed. The callback must preserve that
    // contract even though the copied PCM is all silence.
    StreamFixture fixture;
    TEST_ASSERT_TRUE(fixture.io.pushInbound(makeInboundPacket(29, 0)));
    std::vector<int16_t> out(96, 0x1234);
    TEST_ASSERT_FALSE(
        renderStreamFrames(&fixture.streams, out.data(), 96));
    for (int16_t sample : out) {
        TEST_ASSERT_EQ(sample, 0);
    }
    std::cout << "  [PASS] testRenderCallbackGatingStillReportsNonSilent"
              << std::endl;
}

void testCaptureCallbackForwardsAnyBlock() {
    g_testCount++;
    StreamFixture fixture;
    publishOpenGate(&fixture.policy, 1);
    publishContinuousSettings(&fixture.settings);
    fixture.send.setFormat(mumla::voice_codec::UdpFormat::LEGACY, 0);
    std::vector<int16_t> pcm(137, 1000);
    TEST_ASSERT_FALSE(fixture.streams.captureActive.load());
    mumla::voice_io::captureStreamFrames(&fixture.streams, pcm.data(), 137);
    TEST_ASSERT_FALSE(fixture.streams.captureActive.load());
    TEST_ASSERT_EQ(fixture.send.outboundSizeForTest(), 0u);
    std::vector<int16_t> rest(343, 1000);
    mumla::voice_io::captureStreamFrames(&fixture.streams, rest.data(), 343);
    // 137 + 343 completes one 480-sample frame; FPP=1 emits one packet.
    TEST_ASSERT_EQ(fixture.send.outboundSizeForTest(), 1u);
    TEST_ASSERT_EQ(fixture.streams.lastDroppedOutbound.load(),
                   fixture.send.droppedOutbound());
    std::cout << "  [PASS] testCaptureCallbackForwardsAnyBlock" << std::endl;
}

void testNotifierDeliversDisconnectWithoutTalkChange() {
    g_testCount++;
    // The disconnect check runs before the talk-cell read that can return
    // early, so a disconnect during silence still reaches Kotlin.
    TalkCell cell;
    std::atomic<uint64_t> disconnect{0};
    std::vector<uint64_t> delivered;
    VoiceNotifier notifier(&cell, nullptr, [](const TalkPublication&) {},
                           &disconnect,
                           [&](uint64_t generation) {
                               delivered.push_back(generation);
                           });
    TEST_ASSERT_FALSE(notifier.notifyNow());
    TEST_ASSERT_TRUE(delivered.empty());
    disconnect.store(7, std::memory_order_release);
    TEST_ASSERT_FALSE(notifier.notifyNow()); // no talk snapshot change
    TEST_ASSERT_EQ(delivered.size(), 1u);
    TEST_ASSERT_EQ(delivered[0], 7u);
    // One delivery per change, not one per tick.
    TEST_ASSERT_FALSE(notifier.notifyNow());
    TEST_ASSERT_EQ(delivered.size(), 1u);
    // nativeReset clears the atomic; the change is delivered and Kotlin
    // skips generation 0 as older than the current stream generation.
    disconnect.store(0, std::memory_order_release);
    TEST_ASSERT_FALSE(notifier.notifyNow());
    TEST_ASSERT_EQ(delivered.size(), 2u);
    TEST_ASSERT_EQ(delivered[1], 0u);
    std::cout << "  [PASS] testNotifierDeliversDisconnectWithoutTalkChange"
              << std::endl;
}

} // namespace

void run_voice_send_tests() {
    testPolicyGatePublishesAndReads();
    testSettingsCellPublishesAndReads();
    testCaptureAcceptsAnyBlockSize();
    testCaptureGateClosesOnMute();
    testOutboundFullDropsNewest();
    testDropOldestKeeps200ms();
    testHalfDuplexRenderSilences();
    testAllocationFreeSteadyState();
    testSendStartStopIdempotent();
    testRenderCallbackBurstsWriteExactly();
    testRenderCallbackChunksGateAndFec();
    testRenderCallbackSilenceReportCoversCarry();
    testRenderCallbackSilenceReportUsesCopiedFrames();
    testRenderCallbackGatingStillReportsNonSilent();
    testCaptureCallbackForwardsAnyBlock();
    testNotifierDeliversDisconnectWithoutTalkChange();
}
