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

// Host interop + fuzz coverage for the real Opus 1.6.1 codec behind
// OpusVoiceDecoder/OpusVoiceEncoder (Phase 3 slice B, findings H12/H11).
//
// The hermetic engine tests use FakeDecoder/FakeVoiceEncoder, so they never
// link libopus. This file links the real 1.6.1 sources (same
// CELT/SILK/Opus lists, flags and fixed-point mode as Android.mk) and
// covers: (1) voice interop — encode→decode round trip through the
// production classes at 48 kHz mono, the only configuration the app uses;
// (2) decodeFloat/packetSampleCount robustness — fixed-seed corpus of empty,
// truncated, oversized and random packets asserting no-crash plus the sane
// return contract AudioOutputEngine relies on (span <= 0 is dropped at
// AudioOutputEngine.cpp).

#include "OpusVoiceDecoder.h"
#include "OpusVoiceEncoder.h"
#include "TestHarness.h"

#include <cmath>
#include <cstdint>
#include <iostream>
#include <random>
#include <vector>

namespace {

using mumla::audio::OpusVoiceDecoder;
using mumla::audio::OpusVoiceEncoder;

constexpr int kSampleRate = 48000;
constexpr int kFrame = 480; // 10 ms @ 48 kHz mono
constexpr int kMaxPacketBytes = 1275; // Opus maximum packet size
constexpr int kMaxDecoded = 5760; // 120 ms @ 48 kHz (decoder max)

// Deterministic 440 Hz sine frame so encode output is stable across runs.
void fillSine(int16_t* pcm, int n, int phaseOffset = 0) {
    for (int i = 0; i < n; ++i) {
        const double t = static_cast<double>(i + phaseOffset) / kSampleRate;
        pcm[i] = static_cast<int16_t>(20000.0 * std::sin(2.0 * M_PI * 440.0 * t));
    }
}

void testEncodeDecodeRoundTrip() {
    g_testCount++;
    OpusVoiceEncoder encoder;
    TEST_ASSERT_TRUE(encoder.isValid());
    OpusVoiceDecoder decoder;
    TEST_ASSERT_TRUE(decoder.isValid());
    if (!encoder.isValid() || !decoder.isValid()) {
        return;
    }

    std::vector<int16_t> pcm(kFrame);
    fillSine(pcm.data(), kFrame);
    std::vector<uint8_t> packet(kMaxPacketBytes);
    const int encoded =
        encoder.encode(pcm.data(), kFrame, packet.data(), packet.size());
    TEST_ASSERT_TRUE(encoded > 0);
    TEST_ASSERT_TRUE(encoded <= kMaxPacketBytes);

    // packetSampleCount must agree with the encoded frame size.
    const int span = OpusVoiceDecoder::packetSampleCount(packet.data(),
                                                         static_cast<size_t>(encoded));
    TEST_ASSERT_EQ(span, kFrame);

    std::vector<float> out(kMaxDecoded, 0.0f);
    const int decoded = decoder.decodeFloat(packet.data(), static_cast<size_t>(encoded),
                                            out.data(), kMaxDecoded, 0);
    TEST_ASSERT_EQ(decoded, kFrame);
    // Decoded output must be finite audio, not garbage or silence-shaped
    // constants: mean absolute amplitude well above zero, no NaN/Inf.
    double sumAbs = 0.0;
    bool allFinite = true;
    for (int i = 0; i < decoded; ++i) {
        if (!std::isfinite(out[i])) {
            allFinite = false;
            break;
        }
        sumAbs += std::fabs(out[i]);
    }
    TEST_ASSERT_TRUE(allFinite);
    TEST_ASSERT_TRUE(decoded > 0 && sumAbs / decoded > 0.01);

    std::cout << "  [PASS] testEncodeDecodeRoundTrip" << std::endl;
}

void testSilenceRoundTrip() {
    g_testCount++;
    OpusVoiceEncoder encoder;
    OpusVoiceDecoder decoder;
    if (!encoder.isValid() || !decoder.isValid()) {
        TEST_ASSERT_TRUE(false);
        return;
    }
    std::vector<int16_t> pcm(kFrame * 2, 0);
    std::vector<uint8_t> packet(kMaxPacketBytes);
    const int encoded = encoder.encode(pcm.data(), static_cast<int>(pcm.size()),
                                       packet.data(), packet.size());
    TEST_ASSERT_TRUE(encoded > 0);
    std::vector<float> out(kMaxDecoded, 1.0f);
    const int decoded = decoder.decodeFloat(packet.data(), static_cast<size_t>(encoded),
                                            out.data(), kMaxDecoded, 0);
    TEST_ASSERT_EQ(decoded, static_cast<int>(pcm.size()));
    for (int i = 0; i < decoded; ++i) {
        TEST_ASSERT_TRUE(std::isfinite(out[i]));
    }
    std::cout << "  [PASS] testSilenceRoundTrip" << std::endl;
}

void testDecodeEmptyPacketIsSane() {
    g_testCount++;
    OpusVoiceDecoder decoder;
    if (!decoder.isValid()) {
        TEST_ASSERT_TRUE(false);
        return;
    }
    // Empty packet is packet-loss concealment: decodeFloat with null/0
    // length asks libopus to synthesize one frame of audio, so a return of
    // exactly the requested frame size with finite samples is the sane
    // contract (decodeConcealment routes through here). Anything else —
    // error or overrun — fails.
    std::vector<float> out(kFrame, 0.0f);
    const int r1 = decoder.decodeFloat(nullptr, 0, out.data(), kFrame, 0);
    TEST_ASSERT_EQ(r1, kFrame);
    const uint8_t oneByte[1] = {0x00};
    const int r2 = decoder.decodeFloat(oneByte, 0, out.data(), kFrame, 0);
    TEST_ASSERT_EQ(r2, kFrame);
    for (int i = 0; i < kFrame; ++i) {
        TEST_ASSERT_TRUE(std::isfinite(out[i]));
    }
    // packetSampleCount rejects null/empty per its guard.
    TEST_ASSERT_TRUE(OpusVoiceDecoder::packetSampleCount(nullptr, 0) < 0);
    TEST_ASSERT_TRUE(OpusVoiceDecoder::packetSampleCount(oneByte, 0) < 0);
    std::cout << "  [PASS] testDecodeEmptyPacketIsSane" << std::endl;
}

void testDecodeTruncatedPacketNoCrash() {
    g_testCount++;
    OpusVoiceEncoder encoder;
    OpusVoiceDecoder decoder;
    if (!encoder.isValid() || !decoder.isValid()) {
        TEST_ASSERT_TRUE(false);
        return;
    }
    std::vector<int16_t> pcm(kFrame);
    fillSine(pcm.data(), kFrame);
    std::vector<uint8_t> packet(kMaxPacketBytes);
    const int encoded =
        encoder.encode(pcm.data(), kFrame, packet.data(), packet.size());
    TEST_ASSERT_TRUE(encoded > 1);

    // Every truncation length must either decode to a non-negative count
    // within the output bound or return a libopus error — never crash,
    // never overflow the output buffer.
    std::vector<float> out(kMaxDecoded, 0.0f);
    for (int len = 1; len < encoded; ++len) {
        const int r = decoder.decodeFloat(packet.data(), static_cast<size_t>(len),
                                          out.data(), kMaxDecoded, 0);
        TEST_ASSERT_TRUE(r <= kMaxDecoded);
        const int span = OpusVoiceDecoder::packetSampleCount(
            packet.data(), static_cast<size_t>(len));
        TEST_ASSERT_TRUE(span <= kMaxDecoded);
        // span <= 0 is the drop contract AudioOutputEngine enforces.
        if (span > 0) {
            TEST_ASSERT_TRUE(r >= 0);
        }
    }
    std::cout << "  [PASS] testDecodeTruncatedPacketNoCrash" << std::endl;
}

void testDecodeOversizedPacketNoCrash() {
    g_testCount++;
    OpusVoiceDecoder decoder;
    if (!decoder.isValid()) {
        TEST_ASSERT_TRUE(false);
        return;
    }
    // Oversized garbage packet (well beyond the 1275-byte Opus max):
    // must return an error, never a positive count, never crash.
    std::vector<uint8_t> big(4096, 0xFF);
    std::vector<float> out(kMaxDecoded, 0.0f);
    const int r = decoder.decodeFloat(big.data(), big.size(), out.data(), kMaxDecoded, 0);
    TEST_ASSERT_TRUE(r <= 0);
    const int span = OpusVoiceDecoder::packetSampleCount(big.data(), big.size());
    TEST_ASSERT_TRUE(span <= 0);
    std::cout << "  [PASS] testDecodeOversizedPacketNoCrash" << std::endl;
}

void testDecodeFuzzFixedSeed() {
    g_testCount++;
    OpusVoiceEncoder encoder;
    OpusVoiceDecoder decoder;
    if (!encoder.isValid() || !decoder.isValid()) {
        TEST_ASSERT_TRUE(false);
        return;
    }
    // One valid packet to seed structured mutations.
    std::vector<int16_t> pcm(kFrame);
    fillSine(pcm.data(), kFrame);
    std::vector<uint8_t> valid(kMaxPacketBytes);
    const int validLen =
        encoder.encode(pcm.data(), kFrame, valid.data(), valid.size());
    TEST_ASSERT_TRUE(validLen > 0);

    // 10k iterations, deterministic seed: random bytes, random lengths,
    // and bit-flips of the valid packet. decodeFloat must never crash,
    // never write past maxSamples, and never return a count above the
    // output bound; packetSampleCount must stay within the decoder max.
    std::mt19937 rng(0x0BADF00D);
    std::uniform_int_distribution<int> lenDist(0, 2048);
    std::uniform_int_distribution<int> byteDist(0, 255);
    std::vector<uint8_t> buf(2048);
    std::vector<float> out(kMaxDecoded, 0.0f);
    for (int iter = 0; iter < 10000; ++iter) {
        const int kind = iter % 3;
        size_t len = 0;
        if (kind == 0) {
            len = static_cast<size_t>(lenDist(rng));
            for (size_t i = 0; i < len; ++i) {
                buf[i] = static_cast<uint8_t>(byteDist(rng));
            }
        } else if (kind == 1) {
            len = static_cast<size_t>(validLen);
            for (int i = 0; i < validLen; ++i) {
                buf[i] = valid[i];
            }
            // Flip two random bytes.
            buf[static_cast<size_t>(byteDist(rng)) % static_cast<size_t>(validLen)] =
                static_cast<uint8_t>(byteDist(rng));
            buf[static_cast<size_t>(byteDist(rng)) % static_cast<size_t>(validLen)] =
                static_cast<uint8_t>(byteDist(rng));
        } else {
            len = static_cast<size_t>(lenDist(rng)) % (static_cast<size_t>(validLen) + 1);
            for (size_t i = 0; i < len; ++i) {
                buf[i] = valid[i];
            }
        }
        const uint8_t* data = len > 0 ? buf.data() : nullptr;
        const int r = decoder.decodeFloat(data, len, out.data(), kMaxDecoded, 0);
        TEST_ASSERT_TRUE(r <= kMaxDecoded);
        if (data != nullptr && len > 0) {
            const int span = OpusVoiceDecoder::packetSampleCount(data, len);
            TEST_ASSERT_TRUE(span <= kMaxDecoded);
        }
        if ((iter % 1000) == 0) {
            decoder.reset();
        }
    }
    std::cout << "  [PASS] testDecodeFuzzFixedSeed" << std::endl;
}

} // namespace

void run_opus_interop_tests() {
    std::cout << "--- Opus Interop + Fuzz Tests (libopus 1.6.1) ---" << std::endl;
    testEncodeDecodeRoundTrip();
    testSilenceRoundTrip();
    testDecodeEmptyPacketIsSane();
    testDecodeTruncatedPacketNoCrash();
    testDecodeOversizedPacketNoCrash();
    testDecodeFuzzFixedSeed();
}
