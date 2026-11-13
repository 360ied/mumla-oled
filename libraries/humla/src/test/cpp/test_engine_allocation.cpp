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

// Steady-state allocation test (step 5a.1).
//
// The test binary replaces the global operator new/delete and wraps the C
// allocator (malloc/calloc/realloc), so every allocation made by the engines,
// libopus or RNNoise is counted. After warm-up, processFrame, queuePacket and
// render must not change the count: every buffer, voice, decoder and packet
// slot comes from construction.

#include "AudioInputEngine.h"
#include "AudioOutputEngine.h"
#include "OpusVoiceDecoder.h"
#include "OpusVoiceEncoder.h"
#include "RnnoiseProcessor.h"
#include "TestHarness.h"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <new>
#include <utility>
#include <vector>

namespace {

size_t g_allocations = 0;

} // namespace

// --- Counting allocator ----------------------------------------------------

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

void __wrap_free(void* ptr) {
    __real_free(ptr);
}
} // extern "C"

// Replacing the global operators covers C++ containers and std::function,
// whose internals live in libstdc++ and never reach the wrapped malloc.
void* operator new(size_t size) {
    ++g_allocations;
    void* ptr = __real_malloc(size == 0 ? 1 : size);
    if (ptr == nullptr) {
        throw std::bad_alloc();
    }
    return ptr;
}

void* operator new[](size_t size) {
    ++g_allocations;
    void* ptr = __real_malloc(size == 0 ? 1 : size);
    if (ptr == nullptr) {
        throw std::bad_alloc();
    }
    return ptr;
}

void operator delete(void* ptr) noexcept { __real_free(ptr); }
void operator delete[](void* ptr) noexcept { __real_free(ptr); }
void operator delete(void* ptr, size_t) noexcept { __real_free(ptr); }
void operator delete[](void* ptr, size_t) noexcept { __real_free(ptr); }

namespace {

using mumla::audio::AudioInputEngine;
using mumla::audio::AudioOutputEngine;
using mumla::audio::InboundPacket;
using mumla::audio::InputConfig;
using mumla::audio::InputFrameResult;
using mumla::audio::InputMode;
using mumla::audio::RenderResult;

constexpr size_t kFrameSamples = AudioInputEngine::SAMPLES_PER_10MS;
constexpr int kRenderSamples = 960;

std::vector<uint8_t> loadRnnoiseModel() {
    std::ifstream in(MUMLA_RNNOISE_MODEL_PATH, std::ios::binary);
    if (!in) {
        return {};
    }
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                                std::istreambuf_iterator<char>());
}

// The counting harness itself must be live, or the assertions below would pass
// vacuously.
void testAllocationCountingHarness() {
    g_testCount++;
    const size_t before = g_allocations;
    {
        std::vector<int> probe(1024, 1);
        TEST_ASSERT_EQ(probe.size(), static_cast<size_t>(1024));
    }
    TEST_ASSERT_TRUE(g_allocations > before);

    // A volatile size keeps the compiler from eliding the malloc/free pair,
    // so this also proves the C allocator goes through the wrapper (libopus
    // and RNNoise reach it that way).
    const size_t beforeMalloc = g_allocations;
    volatile size_t size = 64;
    void* raw = std::malloc(size);
    TEST_ASSERT(raw != nullptr);
    std::free(raw);
    TEST_ASSERT_TRUE(g_allocations > beforeMalloc);

    // Third-party C code (libopus, RNNoise) reaches the C allocator through
    // wrapped references, so creating a decoder must be counted.
    const size_t beforeDecoder = g_allocations;
    {
        auto decoder = mumla::audio::makeOpusOutputDecoder();
        TEST_ASSERT(decoder != nullptr);
    }
    TEST_ASSERT_TRUE(g_allocations > beforeDecoder);
    std::cout << "  [PASS] testAllocationCountingHarness" << std::endl;
}

void testInputEngineSteadyStateAllocation() {
    g_testCount++;
    std::vector<uint8_t> model = loadRnnoiseModel();
    TEST_ASSERT_FALSE(model.empty());

    auto encoder = mumla::audio::makeOpusVoiceEncoder(40000);
    auto denoiser = mumla::audio::makeRnnoiseProcessor(true, model.data(),
                                                       model.size());
    AudioInputEngine engine(std::move(encoder), std::move(denoiser));

    int16_t pcm[kFrameSamples];
    for (size_t i = 0; i < kFrameSamples; ++i) {
        pcm[i] = static_cast<int16_t>((i % 64) * 400 - 12000);
    }
    InputConfig config;
    config.mode = InputMode::CONTINUOUS; // transmits every frame
    InputFrameResult result;

    // Warm-up: encoders, denoisers and the accumulator reach steady state.
    for (int i = 0; i < 100; ++i) {
        engine.processFrame(pcm, kFrameSamples, config, &result);
    }

    const size_t before = g_allocations;
    for (int i = 0; i < 300; ++i) {
        engine.processFrame(pcm, kFrameSamples, config, &result);
    }
    // Mute/unmute transitions flush and re-arm; they are allocation-free too.
    config.transmitAllowed = false;
    engine.processFrame(pcm, kFrameSamples, config, &result);
    config.transmitAllowed = true;
    engine.processFrame(pcm, kFrameSamples, config, &result);
    // A VAD onset releases the whole pre-speech ring buffer in one frame.
    config.mode = InputMode::VOICE_ACTIVITY;
    for (int i = 0; i < 20; ++i) {
        engine.processFrame(pcm, kFrameSamples, config, &result);
    }

    TEST_ASSERT_EQ(g_allocations, before);
    std::cout << "  [PASS] testInputEngineSteadyStateAllocation" << std::endl;
}

void testOutputEngineSteadyStateAllocation() {
    g_testCount++;
    AudioOutputEngine engine([] { return mumla::audio::makeOpusOutputDecoder(); });

    // A real Opus packet to queue repeatedly.
    mumla::audio::OpusVoiceEncoder encoder(40000);
    int16_t pcm[kFrameSamples];
    for (size_t i = 0; i < kFrameSamples; ++i) {
        const double t = static_cast<double>(i) / 48000.0;
        pcm[i] = static_cast<int16_t>(
            std::lround(6000.0 * std::sin(2.0 * 3.14159265358979323846 * 440.0 * t)));
    }
    std::vector<uint8_t> packet(1024);
    const int encoded =
        encoder.encode(pcm, kFrameSamples, packet.data(), packet.size());
    TEST_ASSERT_TRUE(encoded > 0);

    InboundPacket inbound;
    inbound.session = 7;
    inbound.flags = 0;
    std::memcpy(inbound.data, packet.data(), static_cast<size_t>(encoded));
    inbound.length = static_cast<uint16_t>(encoded);

    int16_t out[kRenderSamples];
    RenderResult result;

    // Warm-up: the voice pool takes a session, the jitter buffer fills and
    // the first audio is emitted.
    for (uint32_t seq = 0; seq < 60; ++seq) {
        inbound.sequence = seq;
        engine.queuePacket(inbound);
        engine.render(out, kRenderSamples, &result);
    }

    const size_t before = g_allocations;
    for (uint32_t seq = 60; seq < 400; ++seq) {
        inbound.sequence = seq;
        engine.queuePacket(inbound);
        engine.render(out, kRenderSamples, &result);
    }
    // Session churn: pool acquisition, eviction and release paths.
    for (int32_t session = 100; session < 140; ++session) {
        inbound.session = session;
        inbound.isTerminator = session % 2 == 0;
        engine.queuePacket(inbound);
        engine.render(out, kRenderSamples, &result);
        engine.removeUser(session);
        engine.render(out, kRenderSamples, &result);
    }
    engine.clear();
    engine.render(out, kRenderSamples, &result);

    TEST_ASSERT_EQ(g_allocations, before);
    std::cout << "  [PASS] testOutputEngineSteadyStateAllocation" << std::endl;
}

} // namespace

void run_engine_allocation_tests() {
    std::cout << "--- Steady-State Allocation Tests ---" << std::endl;
    testAllocationCountingHarness();
    testInputEngineSteadyStateAllocation();
    testOutputEngineSteadyStateAllocation();
}
