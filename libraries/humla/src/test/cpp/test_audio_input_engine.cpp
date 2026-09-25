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

#include "AudioInputEngine.h"
#include "TestHarness.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <vector>

namespace {

using mumla::audio::AudioInputEngine;
using mumla::audio::InputMode;
using mumla::audio::IVoiceEncoder;
using mumla::audio::IDenoiser;
using mumla::audio::DispatchedPacket;

/**
 * Deterministic hermetic mock encoder that writes simulated packet bytes.
 */
class FakeVoiceEncoder : public IVoiceEncoder {
public:
    explicit FakeVoiceEncoder(int bitrate = 40000) : m_bitrate(bitrate), m_encodeCount(0) {}

    int encode(const int16_t* pcm, size_t sampleCount, uint8_t* outBuffer, size_t maxBytes) override {
        if (pcm == nullptr || outBuffer == nullptr || maxBytes == 0) {
            return -1;
        }
        // Strict Opus frame size check (10ms=480, 20ms=960, 40ms=1920, 60ms=2880 @ 48kHz)
        if (sampleCount != 480 && sampleCount != 960 && sampleCount != 1920 && sampleCount != 2880) {
            return -1;
        }
        m_encodeCount++;
        size_t bytesToWrite = std::min(maxBytes, static_cast<size_t>(32));
        for (size_t i = 0; i < bytesToWrite; ++i) {
            outBuffer[i] = static_cast<uint8_t>((m_encodeCount + i) & 0xFF);
        }
        return static_cast<int>(bytesToWrite);
    }

    void setBitrate(int bitrate) override { m_bitrate = bitrate; }
    int getBitrate() const override { return m_bitrate; }
    void reset() override { m_encodeCount = 0; }

    int getEncodeCount() const { return m_encodeCount; }

private:
    int m_bitrate;
    int m_encodeCount;
};

/**
 * Mock denoiser providing controllable speech probability.
 */
class FakeDenoiser : public IDenoiser {
public:
    explicit FakeDenoiser(float speechProb = 0.9f)
        : m_speechProb(speechProb), m_enabled(true), m_hasModel(false) {}

    float process(const int16_t* inPcm, int16_t* outPcm, size_t sampleCount) override {
        if (inPcm != nullptr && sampleCount > 0) {
            m_lastInSamples.assign(inPcm, inPcm + sampleCount);
        } else {
            m_lastInSamples.clear();
        }
        if (inPcm != nullptr && outPcm != nullptr) {
            std::memcpy(outPcm, inPcm, sampleCount * sizeof(int16_t));
        }
        return m_enabled ? m_speechProb : -1.0f;
    }

    void setEnabled(bool enabled) override { m_enabled = enabled; }
    bool isEnabled() const override { return m_enabled; }
    void setModel(const uint8_t* modelData, size_t modelSize) override {
        m_hasModel = (modelData != nullptr && modelSize > 0);
    }
    bool hasModel() const override { return m_hasModel; }
    void reset() override {}

    void setSpeechProb(float prob) { m_speechProb = prob; }
    const std::vector<int16_t>& getLastInSamples() const { return m_lastInSamples; }

private:
    float m_speechProb;
    bool m_enabled;
    bool m_hasModel;
    std::vector<int16_t> m_lastInSamples;
};

struct PacketRecord {
    std::vector<uint8_t> data;
    int frames;
    bool isTerminator;
    uint64_t frameNumber;
};

struct StateCollector {
    std::vector<PacketRecord> packets;
    std::vector<std::pair<bool, float>> talkEvents;

    void reset() {
        packets.clear();
        talkEvents.clear();
    }

    void wire(AudioInputEngine& engine) {
        engine.setPacketCallback([this](const uint8_t* data, size_t size, int frames, bool isTerminator, uint64_t frameNumber) {
            packets.push_back({std::vector<uint8_t>(data, data + size), frames, isTerminator, frameNumber});
        });
        engine.setTalkingCallback([this](bool isTalking, float peakEnergy) {
            talkEvents.emplace_back(isTalking, peakEnergy);
        });
    }
};

std::vector<int16_t> generateSineFrame(int frameIdx, int16_t amplitude = 10000) {
    std::vector<int16_t> frame(AudioInputEngine::SAMPLES_PER_10MS);
    for (size_t i = 0; i < frame.size(); ++i) {
        // Simple triangular / sine pattern
        frame[i] = static_cast<int16_t>(((frameIdx * 480 + i) % 100 < 50 ? amplitude : -amplitude));
    }
    return frame;
}

std::vector<int16_t> generateSilenceFrame() {
    return std::vector<int16_t>(AudioInputEngine::SAMPLES_PER_10MS, 0);
}

// -----------------------------------------------------------------------------
// Test 1: Lifecycle, Default State, and Accessor Sanitization
// -----------------------------------------------------------------------------
void testAudioInputEngineDefaultsAndAccessors() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto denoiser = std::make_unique<FakeDenoiser>();
    AudioInputEngine engine(std::move(encoder), std::move(denoiser), 2, 1.0f, true, InputMode::VOICE_ACTIVITY);

    TEST_ASSERT_EQ(static_cast<int>(engine.getInputMode()), static_cast<int>(InputMode::VOICE_ACTIVITY));
    TEST_ASSERT_FALSE(engine.isPttTalking());
    TEST_ASSERT_FALSE(engine.isMuted());
    TEST_ASSERT_EQ(engine.getFramesPerPacket(), 2);
    TEST_ASSERT_EQ(engine.getBitrate(), 40000);
    TEST_ASSERT_NEAR(engine.getAmplitudeBoost(), 1.0f, 0.001f);
    TEST_ASSERT_TRUE(engine.isAdaptiveLevelerEnabled());
    TEST_ASSERT_TRUE(engine.isRnnoiseEnabled());

    // Sanitized frames per packet: 1, 2, 4, 6 accepted; 3, 5, 8 rejected
    engine.setFramesPerPacket(1);
    TEST_ASSERT_EQ(engine.getFramesPerPacket(), 1);
    engine.setFramesPerPacket(4);
    TEST_ASSERT_EQ(engine.getFramesPerPacket(), 4);
    engine.setFramesPerPacket(6);
    TEST_ASSERT_EQ(engine.getFramesPerPacket(), 6);
    engine.setFramesPerPacket(3); // invalid, unchanged
    TEST_ASSERT_EQ(engine.getFramesPerPacket(), 6);
    engine.setFramesPerPacket(2);
    TEST_ASSERT_EQ(engine.getFramesPerPacket(), 2);

    // Bitrate accessor
    engine.setBitrate(64000);
    TEST_ASSERT_EQ(engine.getBitrate(), 64000);

    // Mode switching
    engine.setInputMode(InputMode::PUSH_TO_TALK);
    TEST_ASSERT_EQ(static_cast<int>(engine.getInputMode()), static_cast<int>(InputMode::PUSH_TO_TALK));

    // Amplitude boost
    engine.setAmplitudeBoost(1.5f);
    TEST_ASSERT_NEAR(engine.getAmplitudeBoost(), 1.5f, 0.001f);

    // Constructor default and invalid argument fallback to DEFAULT_FRAMES_PER_PACKET
    AudioInputEngine defaultEngine;
    TEST_ASSERT_EQ(defaultEngine.getFramesPerPacket(), AudioInputEngine::DEFAULT_FRAMES_PER_PACKET);

    auto fallbackEncoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto fallbackDenoiser = std::make_unique<FakeDenoiser>();
    AudioInputEngine fallbackEngine(std::move(fallbackEncoder), std::move(fallbackDenoiser), 0);
    TEST_ASSERT_EQ(fallbackEngine.getFramesPerPacket(), AudioInputEngine::DEFAULT_FRAMES_PER_PACKET);

    std::cout << "  [PASS] testAudioInputEngineDefaultsAndAccessors" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 2: PTT State Transitions and Packet Delivery
// -----------------------------------------------------------------------------
void testPttStateTransitions() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    // Press PTT
    engine.setPttTalking(true);
    TEST_ASSERT_TRUE(engine.isPttTalking());

    // Feed frame 1: Speech onset occurs!
    // Since lookahead ring buffer was empty, frame 1 goes to accumulator (count = 1).
    // Onset triggers talking callback (isTalking = true).
    auto audio1 = generateSineFrame(1);
    engine.processFrame(audio1.data(), audio1.size());
    TEST_ASSERT_EQ(collector.talkEvents.size(), 1);
    TEST_ASSERT_TRUE(collector.talkEvents[0].first); // isTalking == true
    TEST_ASSERT_EQ(collector.packets.size(), 0); // accumulator has 1 of 2 frames

    // Feed frame 2: accumulator reaches 2 frames -> dispatches 1 packet
    auto audio2 = generateSineFrame(2);
    engine.processFrame(audio2.data(), audio2.size());
    TEST_ASSERT_EQ(collector.packets.size(), 1);
    TEST_ASSERT_FALSE(collector.packets[0].isTerminator);
    TEST_ASSERT_EQ(collector.packets[0].frames, 2);

    std::cout << "  [PASS] testPttStateTransitions" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 3: PTT Release Hangover Timing (PTT-06, 15 frames = 150ms)
// -----------------------------------------------------------------------------
void testPttReleaseHangoverTiming() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    // Press PTT and process 2 frames (1 regular packet)
    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.packets.size(), 1);
    TEST_ASSERT_FALSE(collector.packets[0].isTerminator);
    TEST_ASSERT_EQ(collector.talkEvents.size(), 1);
    TEST_ASSERT_TRUE(collector.talkEvents[0].first);

    // Release PTT!
    engine.setPttTalking(false);
    TEST_ASSERT_FALSE(engine.isPttTalking());

    // PTT release hangover is exactly 15 frames (150ms).
    // During these 15 frames, transmission must continue seamlessly!
    for (int h = 1; h <= 15; ++h) {
        engine.processFrame(frame.data(), frame.size());
        // Stream must still be talking; no deactivation callback yet
        TEST_ASSERT_EQ(collector.talkEvents.size(), 1);
    }

    // After 15 hangover frames:
    // Total frames since start = 2 + 15 = 17 frames.
    // 17 frames at 2 frames/packet = 8 full packets dispatched, 1 frame in accumulator.
    TEST_ASSERT_EQ(collector.packets.size(), 8);
    for (size_t i = 0; i < 8; ++i) {
        TEST_ASSERT_FALSE(collector.packets[i].isTerminator);
    }

    // Now process the 16th frame after release:
    // Hangover has expired! Transmission must halt and emit a terminator packet!
    engine.processFrame(frame.data(), frame.size());

    // Deactivation event must have fired
    TEST_ASSERT_EQ(collector.talkEvents.size(), 2);
    TEST_ASSERT_FALSE(collector.talkEvents[1].first); // isTalking == false

    // Total packets should now be 9: 8 regular packets + 1 terminator packet
    TEST_ASSERT_EQ(collector.packets.size(), 9);
    TEST_ASSERT_TRUE(collector.packets[8].isTerminator);

    std::cout << "  [PASS] testPttReleaseHangoverTiming" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 4: PTT Re-assertion During Hangover Prevents Interruption
// -----------------------------------------------------------------------------
void testPttHangoverReassertionPreventsCutoff() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.talkEvents.size(), 1);

    // User briefly releases button (e.g. for 50ms = 5 frames)
    engine.setPttTalking(false);
    for (int i = 0; i < 5; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }

    // User presses PTT again before 150ms hangover expires
    engine.setPttTalking(true);

    // Feed 15 more frames
    for (int i = 0; i < 15; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }

    // Talking callback should still only have the initial 'true' event
    // No false deactivation occurred!
    TEST_ASSERT_EQ(collector.talkEvents.size(), 1);
    TEST_ASSERT_TRUE(collector.talkEvents[0].first);

    // No terminator packet should have been emitted
    for (const auto& pkt : collector.packets) {
        TEST_ASSERT_FALSE(pkt.isTerminator);
    }

    std::cout << "  [PASS] testPttHangoverReassertionPreventsCutoff" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 5: Rapid Micro-Tap (Press and Release within same frame interval)
// -----------------------------------------------------------------------------
void testPttMicroTapTransmission() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    // Micro-tap: press then immediate release before processFrame runs
    engine.setPttTalking(true);
    engine.setPttTalking(false);

    auto frame = generateSineFrame(1);

    // Frame 1 to 15: hangover window ensures speech is transmitted
    for (int i = 1; i <= 15; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }

    // Initial talking event fired
    TEST_ASSERT_TRUE(collector.talkEvents.size() >= 1);
    TEST_ASSERT_TRUE(collector.talkEvents[0].first);

    // 16th frame: terminates cleanly with terminator packet
    engine.processFrame(frame.data(), frame.size());

    TEST_ASSERT_TRUE(collector.talkEvents.size() >= 2);
    TEST_ASSERT_FALSE(collector.talkEvents.back().first);

    // Must have dispatched packets ending with a terminator
    TEST_ASSERT_TRUE(collector.packets.size() > 0);
    TEST_ASSERT_TRUE(collector.packets.back().isTerminator);

    std::cout << "  [PASS] testPttMicroTapTransmission" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 6: Terminator Packet Emission on Even Packet Boundaries (PTT-01)
// -----------------------------------------------------------------------------
void testTerminatorPacketEmissionOnEvenBoundary() {
    g_testCount++;

    // When framesPerPacket = 2:
    // If transmission ends when accumulatedFrames == 0, AudioInputEngine must zero-pad
    // and encode 1 packet of silence with isTerminator = true, rather than dropping it.
    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);

    // Feed 1 frame while talking
    engine.processFrame(frame.data(), frame.size()); // accumulated = 1

    // Release PTT: 15 hangover frames follow
    engine.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }
    // Total frames transmitted = 1 + 15 = 16 frames.
    // 16 % 2 == 0, so accumulatedFrames is EXACTLY 0 at the moment transmission terminates!
    TEST_ASSERT_EQ(collector.packets.size(), 8);

    // Next frame triggers cutoff:
    engine.processFrame(frame.data(), frame.size());

    // Verify terminator packet is NOT dropped! Exactly 1 terminator packet added (total 9)
    TEST_ASSERT_EQ(collector.packets.size(), 9);
    TEST_ASSERT_TRUE(collector.packets[8].isTerminator);
    TEST_ASSERT_EQ(collector.packets[8].frames, 2);

    std::cout << "  [PASS] testTerminatorPacketEmissionOnEvenBoundary" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 7: Terminator Packet Emission on Odd Packet Boundaries
// -----------------------------------------------------------------------------
void testTerminatorPacketEmissionOnOddBoundary() {
    g_testCount++;

    // When framesPerPacket = 2:
    // If transmission ends when accumulatedFrames == 1, AudioInputEngine flushes
    // the underfilled accumulator (zero-padded) with isTerminator = true.
    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);

    // Feed 2 frames while talking
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size()); // accumulated = 0

    // Release PTT: 15 hangover frames follow
    engine.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }
    // Total frames transmitted = 2 + 15 = 17 frames.
    // 17 % 2 == 1, so accumulatedFrames == 1 at termination!
    TEST_ASSERT_EQ(collector.packets.size(), 8);

    // Next frame triggers cutoff:
    engine.processFrame(frame.data(), frame.size());

    // Verify accumulator flushed with terminator
    TEST_ASSERT_EQ(collector.packets.size(), 9);
    TEST_ASSERT_TRUE(collector.packets[8].isTerminator);
    TEST_ASSERT_EQ(collector.packets[8].frames, 2);

    std::cout << "  [PASS] testTerminatorPacketEmissionOnOddBoundary" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 8: Single-Frame Packets (framesPerPacket = 1) Terminator Parity
// -----------------------------------------------------------------------------
void testTerminatorPacketWithSingleFramePackets() {
    g_testCount++;

    // For framesPerPacket = 1 (10ms packets), every packet boundary has accumulatedFrames == 0.
    // PTT-01 previously dropped 100% of terminators in this mode.
    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 1, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);
    engine.processFrame(frame.data(), frame.size());

    engine.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }

    // 16 regular 10ms packets
    TEST_ASSERT_EQ(collector.packets.size(), 16);

    // Cutoff frame
    engine.processFrame(frame.data(), frame.size());

    // Terminator packet must be emitted
    TEST_ASSERT_EQ(collector.packets.size(), 17);
    TEST_ASSERT_TRUE(collector.packets[16].isTerminator);
    TEST_ASSERT_EQ(collector.packets[16].frames, 1);

    std::cout << "  [PASS] testTerminatorPacketWithSingleFramePackets" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 9: Pre-Speech Ring Buffer Flushed on VAD Speech Onset (80ms Lookahead)
// -----------------------------------------------------------------------------
void testPreSpeechRingBufferFlushedOnOnset() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::VOICE_ACTIVITY);
    engine.setVadThresholds(0.5f, 0.2f);
    StateCollector collector;
    collector.wire(engine);

    // Feed 8 frames while unmuted below VAD threshold (amplitude 100 -> quiet, below speech threshold)
    // In VOICE_ACTIVITY mode, these are buffered in the 80ms lookahead ring buffer
    for (int i = 1; i <= 8; ++i) {
        auto preFrame = generateSineFrame(i, 100);
        engine.processFrame(preFrame.data(), preFrame.size());
    }
    TEST_ASSERT_EQ(collector.packets.size(), 0);

    // Feed 9th frame with loud speech (amplitude 15000 -> triggers VAD onset)
    auto onsetFrame = generateSineFrame(9, 15000);
    engine.processFrame(onsetFrame.data(), onsetFrame.size());

    // Onset flushes the 8 buffered frames + processes current frame = 9 frames.
    // 9 frames / 2 frames-per-packet = 4 full packets dispatched immediately!
    TEST_ASSERT_EQ(collector.packets.size(), 4);
    for (size_t i = 0; i < 4; ++i) {
        TEST_ASSERT_FALSE(collector.packets[i].isTerminator);
        TEST_ASSERT_EQ(collector.packets[i].frames, 2);
    }

    // Frame sequence numbers must be monotonic
    for (size_t i = 1; i < collector.packets.size(); ++i) {
        TEST_ASSERT_TRUE(collector.packets[i].frameNumber > collector.packets[i - 1].frameNumber);
    }

    std::cout << "  [PASS] testPreSpeechRingBufferFlushedOnOnset" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 10: Mute Gates Audio Immediately and Aborts Hangover
// -----------------------------------------------------------------------------
void testMuteGatesAudioImmediately() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.packets.size(), 1);
    TEST_ASSERT_TRUE(collector.talkEvents.back().first);

    // Mute immediately gates audio, emits terminator packet, and clears talking state synchronously
    engine.setMuted(true);
    TEST_ASSERT_TRUE(engine.isMuted());
    TEST_ASSERT_EQ(collector.packets.size(), 2);
    TEST_ASSERT_TRUE(collector.packets.back().isTerminator);
    TEST_ASSERT_FALSE(collector.talkEvents.back().first);

    // Subsequent frames while muted (or if AudioRecord is halted): no new packets should be emitted
    for (int i = 0; i < 10; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(collector.packets.size(), 2);

    // Release PTT while muted
    engine.setPttTalking(false);

    // Unmute: hangover must NOT resume or leak audio
    engine.setMuted(false);
    for (int i = 0; i < 5; ++i) {
        engine.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(collector.packets.size(), 2);

    std::cout << "  [PASS] testMuteGatesAudioImmediately" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 11: Continuous Mode and VAD Mode Integration
// -----------------------------------------------------------------------------
void testContinuousAndVadModes() {
    g_testCount++;

    // Continuous Mode
    {
        auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
        AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::CONTINUOUS);
        StateCollector collector;
        collector.wire(engine);

        auto frame = generateSineFrame(1);
        // Transmits regardless of PTT state
        engine.processFrame(frame.data(), frame.size());
        engine.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(collector.packets.size(), 1);
        TEST_ASSERT_TRUE(collector.talkEvents.front().first);
    }

    // VAD Mode with Mock Denoiser
    {
        auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
        auto denoiser = std::make_unique<FakeDenoiser>(0.9f); // high speech probability
        FakeDenoiser* denoiserPtr = denoiser.get();
        AudioInputEngine engine(std::move(encoder), std::move(denoiser), 2, 1.0f, false, InputMode::VOICE_ACTIVITY);
        StateCollector collector;
        collector.wire(engine);

        auto frame = generateSineFrame(1);
        engine.processFrame(frame.data(), frame.size());
        engine.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(collector.packets.size(), 1);
        TEST_ASSERT_TRUE(collector.talkEvents.front().first);

        // Turn off speech probability -> VAD enters hangover and terminates
        denoiserPtr->setSpeechProb(0.0f);
        auto silence = generateSilenceFrame();
        for (int i = 0; i < 50; ++i) {
            engine.processFrame(silence.data(), silence.size());
        }
        TEST_ASSERT_FALSE(collector.talkEvents.back().first);
        TEST_ASSERT_TRUE(collector.packets.back().isTerminator);
    }

    std::cout << "  [PASS] testContinuousAndVadModes" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 12: Engine Reset Clears State and Frame Counters
// -----------------------------------------------------------------------------
void testAudioInputEngineReset() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.packets.size(), 1);

    // Engine reset must explicitly clear PTT talking flag and hold frames
    engine.reset();
    TEST_ASSERT_FALSE(engine.isPttTalking());

    // Next processing starts clean from frame 0
    collector.reset();
    engine.setPttTalking(true);
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.packets.size(), 1);
    TEST_ASSERT_EQ(collector.packets[0].frameNumber, 0);

    std::cout << "  [PASS] testAudioInputEngineReset" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 13: Large Packet Framing (N=4, N=6) and Terminator Boundary Padding
// -----------------------------------------------------------------------------
void testLargePacketFramingAndTerminators() {
    g_testCount++;

    // Subtest A: N=4 (40ms packets, 1920 samples)
    {
        auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
        AudioInputEngine engine(std::move(encoder), nullptr, 4, 1.0f, false, InputMode::PUSH_TO_TALK);
        StateCollector collector;
        collector.wire(engine);

        engine.setPttTalking(true);
        auto frame = generateSineFrame(1);
        for (int i = 0; i < 4; ++i) {
            engine.processFrame(frame.data(), frame.size());
        }
        // Exactly 1 packet emitted with 4 frames
        TEST_ASSERT_EQ(collector.packets.size(), 1);
        TEST_ASSERT_EQ(collector.packets[0].frames, 4);
        TEST_ASSERT_FALSE(collector.packets[0].isTerminator);

        // Release PTT: 15 frames hangover countdown
        engine.setPttTalking(false);
        for (int i = 0; i < 15; ++i) {
            engine.processFrame(frame.data(), frame.size());
        }
        // Total active speech frames = 4 + 15 = 19 frames.
        // 19 / 4 = 4 full packets emitted so far.
        TEST_ASSERT_EQ(collector.packets.size(), 4);

        // Frame 16 after release: speech cutoff triggers terminator packet.
        // Remaining 3 frames in accumulator are padded to 4 frames.
        engine.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(collector.packets.size(), 5);
        TEST_ASSERT_TRUE(collector.packets[4].isTerminator);
        TEST_ASSERT_EQ(collector.packets[4].frames, 4);
        TEST_ASSERT_FALSE(collector.talkEvents.back().first);
    }

    // Subtest B: N=6 (60ms packets, 2880 samples)
    {
        auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
        AudioInputEngine engine(std::move(encoder), nullptr, 6, 1.0f, false, InputMode::PUSH_TO_TALK);
        StateCollector collector;
        collector.wire(engine);

        engine.setPttTalking(true);
        auto frame = generateSineFrame(1);
        for (int i = 0; i < 6; ++i) {
            engine.processFrame(frame.data(), frame.size());
        }
        TEST_ASSERT_EQ(collector.packets.size(), 1);
        TEST_ASSERT_EQ(collector.packets[0].frames, 6);

        // Release PTT: 15 frames hangover
        engine.setPttTalking(false);
        for (int i = 0; i < 15; ++i) {
            engine.processFrame(frame.data(), frame.size());
        }
        // Total 21 frames: 21 / 6 = 3 full packets
        TEST_ASSERT_EQ(collector.packets.size(), 3);

        // Frame 16 after release: speech cutoff triggers terminator
        // 3 remaining frames padded to 6 frames
        engine.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(collector.packets.size(), 4);
        TEST_ASSERT_TRUE(collector.packets[3].isTerminator);
        TEST_ASSERT_EQ(collector.packets[3].frames, 6);
        TEST_ASSERT_FALSE(collector.talkEvents.back().first);
    }

    std::cout << "  [PASS] testLargePacketFramingAndTerminators" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 14: Dynamic Input Mode Switching Mid-Speech
// -----------------------------------------------------------------------------
void testDynamicInputModeSwitchingMidSpeech() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto denoiser = std::make_unique<FakeDenoiser>();
    FakeDenoiser* denoiserPtr = denoiser.get();
    AudioInputEngine engine(std::move(encoder), std::move(denoiser), 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    // 1. Start talking in PTT mode
    engine.setPttTalking(true);
    auto frame = generateSineFrame(1);
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.packets.size(), 1);
    TEST_ASSERT_TRUE(collector.talkEvents.back().first);

    // 2. Switch to CONTINUOUS mode while speaking
    engine.setInputMode(InputMode::CONTINUOUS);
    engine.setPttTalking(false); // Release PTT; continuous mode should keep transmitting
    engine.processFrame(frame.data(), frame.size());
    engine.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(collector.packets.size(), 2);
    TEST_ASSERT_TRUE(collector.talkEvents.back().first);

    // 3. Switch to VOICE_ACTIVITY mode with silence
    engine.setInputMode(InputMode::VOICE_ACTIVITY);
    denoiserPtr->setSpeechProb(0.0f);
    auto silence = generateSilenceFrame();
    for (int i = 0; i < 50; ++i) {
        engine.processFrame(silence.data(), silence.size());
    }
    // VAD should detect silence and terminate transmission with terminator packet
    TEST_ASSERT_FALSE(collector.talkEvents.back().first);
    TEST_ASSERT_TRUE(collector.packets.back().isTerminator);

    std::cout << "  [PASS] testDynamicInputModeSwitchingMidSpeech" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 15: Squelch Gate Before RNNoise
// -----------------------------------------------------------------------------
void testSquelchGateBeforeRnnoise() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto denoiser = std::make_unique<FakeDenoiser>(0.95f);
    FakeDenoiser* denoiserPtr = denoiser.get();
    AudioInputEngine engine(std::move(encoder), std::move(denoiser), 2, 1.0f, false, InputMode::VOICE_ACTIVITY);
    StateCollector collector;
    collector.wire(engine);

    // 1. Send ambient noise AC frame below squelch floor (amplitude 10 -> ~-70 dBFS < -65 dBFS)
    auto ambientBelowSquelch = generateSineFrame(0, 10);
    engine.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());

    // Denoiser must have received pure silence (zeros) to advance overlap-add delay
    // while bypassing recurrent GRU inference
    TEST_ASSERT_EQ(denoiserPtr->getLastInSamples().size(), 480u);
    bool allZeros = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            allZeros = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(allZeros);

    // 2. Send active speech frame above squelch (sine wave)
    auto speechFrame = generateSineFrame(1);
    engine.processFrame(speechFrame.data(), speechFrame.size());

    // Denoiser receives active speech PCM
    bool hasNonZero = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            hasNonZero = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(hasNonZero);

    // 3. During VAD hangover (speaking is true), low-energy frame must NOT be squelch-bypassed
    engine.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool hangoverReceivedRealAudio = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            hangoverReceivedRealAudio = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(hangoverReceivedRealAudio);

    // Tick through remaining hangover hold frames (DEFAULT_HOLD_FRAMES = 25)
    for (int i = 0; i < 30; ++i) {
        engine.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    }
    // Now that hangover has expired, low-energy ambient frame must resume squelch bypass
    engine.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool squelchResumedZeros = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            squelchResumedZeros = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(squelchResumedZeros);

    // 4. In CONTINUOUS mode, low-energy frame must NOT be squelch-bypassed
    engine.setInputMode(InputMode::CONTINUOUS);
    engine.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool continuousReceivedRealAudio = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            continuousReceivedRealAudio = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(continuousReceivedRealAudio);

    // 5. In PUSH_TO_TALK mode with PTT active, low-energy frame must NOT be squelch-bypassed
    engine.setInputMode(InputMode::PUSH_TO_TALK);
    engine.setPttTalking(true);
    engine.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool pttReceivedRealAudio = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            pttReceivedRealAudio = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(pttReceivedRealAudio);

    std::cout << "  [PASS] testSquelchGateBeforeRnnoise" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 16: PTT Idle Unconditionally Bypasses RNNoise Regardless of Ambient Noise
// -----------------------------------------------------------------------------
void testPttIdleBypassesRnnoiseEvenWithAmbientNoise() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto denoiser = std::make_unique<FakeDenoiser>(0.9f);
    FakeDenoiser* denoiserPtr = denoiser.get();

    AudioInputEngine engine(std::move(encoder), std::move(denoiser), 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    // Loud frame above squelch (peakDb ~ -10 dBFS >> -65 dBFS)
    auto loudAmbientFrame = generateSineFrame(1, 10000);

    // While PTT is idle (unpressed), denoiser MUST be bypassed by feeding static zeroes
    engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool allZerosInIdle = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            allZerosInIdle = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(allZerosInIdle);
    TEST_ASSERT_EQ(collector.packets.size(), 0);

    // Press PTT: denoiser must immediately receive real audio and packets must be emitted
    engine.setPttTalking(true);
    engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool nonZeroWhenPttActive = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            nonZeroWhenPttActive = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(nonZeroWhenPttActive);

    // Release PTT: after hold hangover expires, denoiser must revert to static zeroes
    engine.setPttTalking(false);
    for (int i = 0; i < 20; ++i) {
        engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    }
    engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool zeroesResumedAfterPttRelease = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            zeroesResumedAfterPttRelease = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(zeroesResumedAfterPttRelease);

    std::cout << "  [PASS] testPttIdleBypassesRnnoiseEvenWithAmbientNoise" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 17: PTT Onset Does Not Prepend Idle Noise
// -----------------------------------------------------------------------------
void testPttOnsetDoesNotPrependIdleNoise() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    AudioInputEngine engine(std::move(encoder), nullptr, 2, 1.0f, false, InputMode::PUSH_TO_TALK);
    StateCollector collector;
    collector.wire(engine);

    // Feed 8 frames of ambient noise while PTT is unpressed
    for (int i = 1; i <= 8; ++i) {
        auto preFrame = generateSineFrame(i, 5000);
        engine.processFrame(preFrame.data(), preFrame.size());
    }
    TEST_ASSERT_EQ(collector.packets.size(), 0);

    // Press PTT and feed 1 frame
    engine.setPttTalking(true);
    auto frame1 = generateSineFrame(9, 5000);
    engine.processFrame(frame1.data(), frame1.size());

    // Because PTT does not buffer pre-speech idle noise, exactly 1 frame is accumulated (0 packets sent for 2 fpp)
    TEST_ASSERT_EQ(collector.packets.size(), 0);

    // Feed 2nd frame while talking
    auto frame2 = generateSineFrame(10, 5000);
    engine.processFrame(frame2.data(), frame2.size());

    // Exactly 1 packet emitted with 2 frames
    TEST_ASSERT_EQ(collector.packets.size(), 1);
    TEST_ASSERT_EQ(collector.packets[0].frames, 2);

    std::cout << "  [PASS] testPttOnsetDoesNotPrependIdleNoise" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 18: Muted Bypasses RNNoise Even With Ambient Noise in VAD Mode
// -----------------------------------------------------------------------------
void testMutedBypassesRnnoiseEvenWithAmbientNoise() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto denoiser = std::make_unique<FakeDenoiser>(0.9f);
    FakeDenoiser* denoiserPtr = denoiser.get();

    AudioInputEngine engine(std::move(encoder), std::move(denoiser), 2, 1.0f, false, InputMode::VOICE_ACTIVITY);
    StateCollector collector;
    collector.wire(engine);

    // Loud frame above squelch (peakDb ~ -10 dBFS >> -65 dBFS)
    auto loudAmbientFrame = generateSineFrame(1, 10000);

    // When unmuted in VAD mode, loud ambient frame must NOT bypass denoiser
    engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool nonZeroWhenUnmuted = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            nonZeroWhenUnmuted = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(nonZeroWhenUnmuted);

    // Now mute client: denoiser MUST be bypassed by feeding static zeroes even with loud frame
    engine.setMuted(true);
    engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool allZerosWhenMuted = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            allZerosWhenMuted = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(allZerosWhenMuted);

    // Unmute: denoiser resumes receiving real audio
    engine.setMuted(false);
    engine.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool nonZeroAfterUnmute = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            nonZeroAfterUnmute = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(nonZeroAfterUnmute);

    std::cout << "  [PASS] testMutedBypassesRnnoiseEvenWithAmbientNoise" << std::endl;
}

} // namespace

void run_audio_input_engine_tests() {
    std::cout << "--- AudioInputEngine Tests ---" << std::endl;
    testAudioInputEngineDefaultsAndAccessors();
    testPttStateTransitions();
    testPttReleaseHangoverTiming();
    testPttHangoverReassertionPreventsCutoff();
    testPttMicroTapTransmission();
    testTerminatorPacketEmissionOnEvenBoundary();
    testTerminatorPacketEmissionOnOddBoundary();
    testTerminatorPacketWithSingleFramePackets();
    testPreSpeechRingBufferFlushedOnOnset();
    testMuteGatesAudioImmediately();
    testContinuousAndVadModes();
    testAudioInputEngineReset();
    testLargePacketFramingAndTerminators();
    testDynamicInputModeSwitchingMidSpeech();
    testSquelchGateBeforeRnnoise();
    testPttIdleBypassesRnnoiseEvenWithAmbientNoise();
    testPttOnsetDoesNotPrependIdleNoise();
    testMutedBypassesRnnoiseEvenWithAmbientNoise();
}
