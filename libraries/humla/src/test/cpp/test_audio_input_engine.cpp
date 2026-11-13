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
#include <utility>
#include <vector>

namespace {

using mumla::audio::AudioInputEngine;
using mumla::audio::IDenoiser;
using mumla::audio::InputConfig;
using mumla::audio::InputFrameResult;
using mumla::audio::InputMode;
using mumla::audio::IVoiceEncoder;

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
    void reset() override { m_resetCount++; }

    void setSpeechProb(float prob) { m_speechProb = prob; }
    const std::vector<int16_t>& getLastInSamples() const { return m_lastInSamples; }
    int getResetCount() const { return m_resetCount; }

private:
    float m_speechProb;
    bool m_enabled;
    bool m_hasModel;
    std::vector<int16_t> m_lastInSamples;
    int m_resetCount = 0;
};

struct PacketRecord {
    std::vector<uint8_t> data;
    int frames;
    bool isTerminator;
    uint64_t frameNumber;
};

/**
 * Test rig: owns the engine and the configuration snapshot the capture thread
 * would assemble each frame. "Setters" here mutate the snapshot; the engine
 * only sees it on the next processFrame, which is the contract.
 */
class InputRig {
public:
    InputRig(std::unique_ptr<IVoiceEncoder> encoder,
             std::unique_ptr<IDenoiser> denoiser,
             int framesPerPacket = 2, float amplitudeBoost = 1.0f,
             bool adaptiveLevelerEnabled = false,
             InputMode mode = InputMode::VOICE_ACTIVITY)
        : m_engine(std::move(encoder), std::move(denoiser)) {
        m_config.framesPerPacket = framesPerPacket;
        m_config.amplitudeBoost = amplitudeBoost;
        m_config.adaptiveLevelerEnabled = adaptiveLevelerEnabled;
        m_config.mode = mode;
    }

    void setInputMode(InputMode mode) { m_config.mode = mode; }
    void setPttTalking(bool pressed) { m_config.pttPressed = pressed; }
    void setMuted(bool muted) { m_config.transmitAllowed = !muted; }
    void setBitrate(int bitrate) { m_config.bitrate = bitrate; }
    void setFramesPerPacket(int framesPerPacket) {
        m_config.framesPerPacket = framesPerPacket;
    }
    void setAmplitudeBoost(float boost) { m_config.amplitudeBoost = boost; }
    void setRnnoiseEnabled(bool enabled) { m_config.rnnoiseEnabled = enabled; }
    void setAdaptiveLevelerEnabled(bool enabled) {
        m_config.adaptiveLevelerEnabled = enabled;
    }
    void setVadThresholds(float vadMax, float vadMin) {
        m_config.vadMax = vadMax;
        m_config.vadMin = vadMin;
    }
    void setVadHoldFrames(uint32_t holdFrames) {
        m_config.vadHoldFrames = holdFrames;
    }
    void setVadSquelchFloor(float minDb) { m_config.vadSquelchFloorDb = minDb; }

    bool isMuted() const { return !m_config.transmitAllowed; }
    bool isPttTalking() const { return m_config.pttPressed; }
    InputMode inputMode() const { return m_config.mode; }
    int framesPerPacket() const { return m_config.framesPerPacket; }

    void processFrame(const int16_t* pcm, size_t samples) {
        m_engine.processFrame(pcm, samples, m_config, &m_result);
        if (m_result.talkingChanged) {
            talkEvents.emplace_back(m_result.talking, m_result.peakEnergy);
        }
        for (size_t i = 0; i < m_result.packetCount; ++i) {
            const auto& packet = m_result.packets[i];
            packets.push_back({std::vector<uint8_t>(packet.data,
                                                    packet.data + packet.size),
                               packet.frames, packet.isTerminator,
                               packet.frameNumber});
        }
    }

    void reset() { m_engine.reset(); }
    bool hasRnnoiseModel() const { return m_engine.hasRnnoiseModel(); }
    void setRnnoiseModel(const uint8_t* data, size_t size) {
        m_engine.setRnnoiseModel(data, size);
    }

    std::vector<PacketRecord> packets;
    std::vector<std::pair<bool, float>> talkEvents;

    void clear() {
        packets.clear();
        talkEvents.clear();
    }

private:
    AudioInputEngine m_engine;
    InputConfig m_config;
    InputFrameResult m_result;
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
// Test 1: Configuration is sampled per frame and transitions are applied
// -----------------------------------------------------------------------------
void testInputConfigTransitions() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    FakeVoiceEncoder* encoderPtr = encoder.get();
    auto denoiser = std::make_unique<FakeDenoiser>();
    FakeDenoiser* denoiserPtr = denoiser.get();
    InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                 InputMode::CONTINUOUS);

    // Default packet size: 2 frames.
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_EQ(rig.packets[0].frames, 2);

    // Accepted packet sizes are exactly Opus's 10/20/40/60 ms.
    const int accepted[] = {1, 2, 4, 6};
    for (int framesPerPacket : accepted) {
        rig.setFramesPerPacket(framesPerPacket);
        rig.clear();
        for (int i = 0; i < framesPerPacket; ++i) {
            rig.processFrame(frame.data(), frame.size());
        }
        TEST_ASSERT_EQ(rig.packets.size(), 1u);
        TEST_ASSERT_EQ(rig.packets[0].frames, framesPerPacket);
    }
    // An unsupported size is ignored, keeping the last applied one.
    rig.setFramesPerPacket(3);
    rig.clear();
    for (int i = 0; i < 6; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_EQ(rig.packets[0].frames, 6);
    TEST_ASSERT_EQ(rig.framesPerPacket(), 3); // ignored on the engine side

    // Bitrate, mode, denoiser and leveler reach their owners on the next frame.
    rig.setBitrate(64000);
    rig.setInputMode(InputMode::PUSH_TO_TALK);
    rig.setRnnoiseEnabled(false);
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(encoderPtr->getBitrate(), 64000);
    TEST_ASSERT_EQ(static_cast<int>(rig.inputMode()),
                   static_cast<int>(InputMode::PUSH_TO_TALK));
    TEST_ASSERT_FALSE(denoiserPtr->isEnabled());

    rig.setRnnoiseEnabled(true);
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_TRUE(denoiserPtr->isEnabled());

    // The model is owned by the denoiser and queried through the engine.
    const uint8_t model[4] = {1, 2, 3, 4};
    TEST_ASSERT_FALSE(rig.hasRnnoiseModel());
    rig.setRnnoiseModel(model, sizeof(model));
    TEST_ASSERT_TRUE(rig.hasRnnoiseModel());

    std::cout << "  [PASS] testInputConfigTransitions" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 2: PTT State Transitions and Packet Delivery
// -----------------------------------------------------------------------------
void testPttStateTransitions() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    // Press PTT
    rig.setPttTalking(true);
    TEST_ASSERT_TRUE(rig.isPttTalking());

    // Feed frame 1: speech onset. Since the lookahead ring buffer was empty,
    // frame 1 goes to the accumulator (count = 1), and the transition reports
    // talking.
    auto audio1 = generateSineFrame(1);
    rig.processFrame(audio1.data(), audio1.size());
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents[0].first); // isTalking == true
    TEST_ASSERT_EQ(rig.packets.size(), 0u); // accumulator has 1 of 2 frames

    // Feed frame 2: accumulator reaches 2 frames -> dispatches 1 packet
    auto audio2 = generateSineFrame(2);
    rig.processFrame(audio2.data(), audio2.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_FALSE(rig.packets[0].isTerminator);
    TEST_ASSERT_EQ(rig.packets[0].frames, 2);

    std::cout << "  [PASS] testPttStateTransitions" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 3: PTT Release Hangover Timing (PTT-06, 15 frames = 150ms)
// -----------------------------------------------------------------------------
void testPttReleaseHangoverTiming() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    // Press PTT and process 2 frames (1 regular packet)
    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_FALSE(rig.packets[0].isTerminator);
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents[0].first);

    // Release PTT: the next frame observes it.
    rig.setPttTalking(false);

    // PTT release hangover is exactly 15 frames (150ms). During these 15
    // frames transmission must continue seamlessly.
    for (int h = 1; h <= 15; ++h) {
        rig.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);
    }

    // Total frames since start = 2 + 15 = 17 frames: 8 full packets, 1 frame
    // banked.
    TEST_ASSERT_EQ(rig.packets.size(), 8u);
    for (size_t i = 0; i < 8; ++i) {
        TEST_ASSERT_FALSE(rig.packets[i].isTerminator);
    }

    // The 16th frame after release: hangover expired; transmission halts and
    // the terminator packet flushes.
    rig.processFrame(frame.data(), frame.size());

    TEST_ASSERT_EQ(rig.talkEvents.size(), 2u);
    TEST_ASSERT_FALSE(rig.talkEvents[1].first); // isTalking == false
    TEST_ASSERT_EQ(rig.packets.size(), 9u);
    TEST_ASSERT_TRUE(rig.packets[8].isTerminator);

    std::cout << "  [PASS] testPttReleaseHangoverTiming" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 4: PTT Re-assertion During Hangover Prevents Interruption
// -----------------------------------------------------------------------------
void testPttHangoverReassertionPreventsCutoff() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);

    // Release for 5 frames, then press again before the 150ms hold expires.
    rig.setPttTalking(false);
    for (int i = 0; i < 5; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    rig.setPttTalking(true);
    for (int i = 0; i < 15; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }

    // No deactivation, no terminator.
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents[0].first);
    for (const auto& pkt : rig.packets) {
        TEST_ASSERT_FALSE(pkt.isTerminator);
    }

    std::cout << "  [PASS] testPttHangoverReassertionPreventsCutoff" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 5: A press the capture thread observes arms the full hangover
// -----------------------------------------------------------------------------
void testPttPressObservedForOneFrameStillHolds() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    // A single frame observes the press; the release arrives right after it.
    // The hold must still run its 15 frames, exactly like a micro-tap that
    // the capture thread saw once.
    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents[0].first);

    rig.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u); // still talking through the hold

    // 16th frame after the press: hold expired, terminator emitted.
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.talkEvents.size(), 2u);
    TEST_ASSERT_FALSE(rig.talkEvents.back().first);
    TEST_ASSERT_TRUE(rig.packets.back().isTerminator);

    std::cout << "  [PASS] testPttPressObservedForOneFrameStillHolds" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 6: Terminator Packet Emission on Even Packet Boundaries (PTT-01)
// -----------------------------------------------------------------------------
void testTerminatorPacketEmissionOnEvenBoundary() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size()); // accumulated = 1

    rig.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    // Total transmitted frames = 16, so the accumulator is exactly empty at
    // termination.
    TEST_ASSERT_EQ(rig.packets.size(), 8u);

    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 9u);
    TEST_ASSERT_TRUE(rig.packets[8].isTerminator);
    TEST_ASSERT_EQ(rig.packets[8].frames, 2);

    std::cout << "  [PASS] testTerminatorPacketEmissionOnEvenBoundary" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 7: Terminator Packet Emission on Odd Packet Boundaries
// -----------------------------------------------------------------------------
void testTerminatorPacketEmissionOnOddBoundary() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size()); // accumulated = 0

    rig.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    // 17 transmitted frames: 8 packets, 1 frame banked.
    TEST_ASSERT_EQ(rig.packets.size(), 8u);

    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 9u);
    TEST_ASSERT_TRUE(rig.packets[8].isTerminator);
    TEST_ASSERT_EQ(rig.packets[8].frames, 2);

    std::cout << "  [PASS] testTerminatorPacketEmissionOnOddBoundary" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 8: Single-Frame Packets (framesPerPacket = 1) Terminator Parity
// -----------------------------------------------------------------------------
void testTerminatorPacketWithSingleFramePackets() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 1, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());

    rig.setPttTalking(false);
    for (int i = 0; i < 15; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(rig.packets.size(), 16u); // 16 regular 10ms packets

    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 17u);
    TEST_ASSERT_TRUE(rig.packets[16].isTerminator);
    TEST_ASSERT_EQ(rig.packets[16].frames, 1);

    std::cout << "  [PASS] testTerminatorPacketWithSingleFramePackets" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 9: Pre-Speech Ring Buffer Flushed on VAD Speech Onset (80ms Lookahead)
// -----------------------------------------------------------------------------
void testPreSpeechRingBufferFlushedOnOnset() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::VOICE_ACTIVITY);
    rig.setVadThresholds(0.5f, 0.2f);

    // 8 quiet frames while unmuted below the VAD threshold.
    for (int i = 1; i <= 8; ++i) {
        auto preFrame = generateSineFrame(i, 100);
        rig.processFrame(preFrame.data(), preFrame.size());
    }
    TEST_ASSERT_EQ(rig.packets.size(), 0u);

    // 9th frame with loud speech triggers the onset.
    auto onsetFrame = generateSineFrame(9, 15000);
    rig.processFrame(onsetFrame.data(), onsetFrame.size());

    // 8 buffered frames + the current frame = 4 full packets, all released by
    // the single processFrame call (and it must fit in one result struct).
    TEST_ASSERT_EQ(rig.packets.size(), 4u);
    for (size_t i = 0; i < 4; ++i) {
        TEST_ASSERT_FALSE(rig.packets[i].isTerminator);
        TEST_ASSERT_EQ(rig.packets[i].frames, 2);
    }
    for (size_t i = 1; i < rig.packets.size(); ++i) {
        TEST_ASSERT_TRUE(rig.packets[i].frameNumber > rig.packets[i - 1].frameNumber);
    }

    std::cout << "  [PASS] testPreSpeechRingBufferFlushedOnOnset" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 10: Mute gates audio from the next frame and aborts the hangover
// -----------------------------------------------------------------------------
void testMuteGatesAudioImmediately() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents.back().first);

    // Mute: the next frame's configuration carries it. That frame flushes the
    // accumulator as a terminator and reports the stop; the muted frame
    // itself produces nothing else.
    rig.setMuted(true);
    TEST_ASSERT_TRUE(rig.isMuted());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 2u);
    TEST_ASSERT_TRUE(rig.packets.back().isTerminator);
    TEST_ASSERT_FALSE(rig.talkEvents.back().first);

    // Subsequent frames while muted emit nothing.
    for (int i = 0; i < 10; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(rig.packets.size(), 2u);

    // Release PTT while muted, then unmute: the hangover must not resume.
    rig.setPttTalking(false);
    rig.setMuted(false);
    for (int i = 0; i < 5; ++i) {
        rig.processFrame(frame.data(), frame.size());
    }
    TEST_ASSERT_EQ(rig.packets.size(), 2u);

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
        InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                     InputMode::CONTINUOUS);

        auto frame = generateSineFrame(1);
        rig.processFrame(frame.data(), frame.size());
        rig.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(rig.packets.size(), 1u);
        TEST_ASSERT_TRUE(rig.talkEvents.front().first);
    }

    // VAD Mode with Mock Denoiser
    {
        auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
        auto denoiser = std::make_unique<FakeDenoiser>(0.9f);
        FakeDenoiser* denoiserPtr = denoiser.get();
        InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                     InputMode::VOICE_ACTIVITY);

        auto frame = generateSineFrame(1);
        rig.processFrame(frame.data(), frame.size());
        rig.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(rig.packets.size(), 1u);
        TEST_ASSERT_TRUE(rig.talkEvents.front().first);

        // Drop the speech probability: the VAD holds, then terminates.
        denoiserPtr->setSpeechProb(0.0f);
        auto silence = generateSilenceFrame();
        for (int i = 0; i < 50; ++i) {
            rig.processFrame(silence.data(), silence.size());
        }
        TEST_ASSERT_FALSE(rig.talkEvents.back().first);
        TEST_ASSERT_TRUE(rig.packets.back().isTerminator);
    }

    std::cout << "  [PASS] testContinuousAndVadModes" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 12: Engine Reset Clears State and Frame Counters
// -----------------------------------------------------------------------------
void testAudioInputEngineReset() {
    g_testCount++;

    auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
    auto denoiser = std::make_unique<FakeDenoiser>();
    FakeDenoiser* denoiserPtr = denoiser.get();
    InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);

    // Reset clears the hangover and the engine's view of the pressed state, so
    // the next frame re-arms push-to-talk.
    rig.reset();
    TEST_ASSERT_EQ(denoiserPtr->getResetCount(), 1);

    rig.clear();
    rig.processFrame(frame.data(), frame.size());
    // The still-pressed snapshot re-arms the hold: talking resumes at once.
    TEST_ASSERT_EQ(rig.talkEvents.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents[0].first);

    rig.clear();
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_EQ(rig.packets[0].frameNumber, 0);

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
        InputRig rig(std::move(encoder), nullptr, 4, 1.0f, false,
                     InputMode::PUSH_TO_TALK);

        rig.setPttTalking(true);
        auto frame = generateSineFrame(1);
        for (int i = 0; i < 4; ++i) {
            rig.processFrame(frame.data(), frame.size());
        }
        TEST_ASSERT_EQ(rig.packets.size(), 1u);
        TEST_ASSERT_EQ(rig.packets[0].frames, 4);
        TEST_ASSERT_FALSE(rig.packets[0].isTerminator);

        rig.setPttTalking(false);
        for (int i = 0; i < 15; ++i) {
            rig.processFrame(frame.data(), frame.size());
        }
        // 19 active frames = 4 full packets.
        TEST_ASSERT_EQ(rig.packets.size(), 4u);

        // Cutoff frame: the 3 banked frames are padded to 4.
        rig.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(rig.packets.size(), 5u);
        TEST_ASSERT_TRUE(rig.packets[4].isTerminator);
        TEST_ASSERT_EQ(rig.packets[4].frames, 4);
        TEST_ASSERT_FALSE(rig.talkEvents.back().first);
    }

    // Subtest B: N=6 (60ms packets, 2880 samples)
    {
        auto encoder = std::make_unique<FakeVoiceEncoder>(40000);
        InputRig rig(std::move(encoder), nullptr, 6, 1.0f, false,
                     InputMode::PUSH_TO_TALK);

        rig.setPttTalking(true);
        auto frame = generateSineFrame(1);
        for (int i = 0; i < 6; ++i) {
            rig.processFrame(frame.data(), frame.size());
        }
        TEST_ASSERT_EQ(rig.packets.size(), 1u);
        TEST_ASSERT_EQ(rig.packets[0].frames, 6);

        rig.setPttTalking(false);
        for (int i = 0; i < 15; ++i) {
            rig.processFrame(frame.data(), frame.size());
        }
        // 21 active frames = 3 full packets.
        TEST_ASSERT_EQ(rig.packets.size(), 3u);

        rig.processFrame(frame.data(), frame.size());
        TEST_ASSERT_EQ(rig.packets.size(), 4u);
        TEST_ASSERT_TRUE(rig.packets[3].isTerminator);
        TEST_ASSERT_EQ(rig.packets[3].frames, 6);
        TEST_ASSERT_FALSE(rig.talkEvents.back().first);
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
    InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    // 1. Start talking in PTT mode.
    rig.setPttTalking(true);
    auto frame = generateSineFrame(1);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_TRUE(rig.talkEvents.back().first);

    // 2. Switch to CONTINUOUS while speaking and release PTT: continuous mode
    // keeps transmitting.
    rig.setInputMode(InputMode::CONTINUOUS);
    rig.setPttTalking(false);
    rig.processFrame(frame.data(), frame.size());
    rig.processFrame(frame.data(), frame.size());
    TEST_ASSERT_EQ(rig.packets.size(), 2u);
    TEST_ASSERT_TRUE(rig.talkEvents.back().first);

    // 3. Switch to VOICE_ACTIVITY with silence: VAD terminates the stream.
    rig.setInputMode(InputMode::VOICE_ACTIVITY);
    denoiserPtr->setSpeechProb(0.0f);
    auto silence = generateSilenceFrame();
    for (int i = 0; i < 50; ++i) {
        rig.processFrame(silence.data(), silence.size());
    }
    TEST_ASSERT_FALSE(rig.talkEvents.back().first);
    TEST_ASSERT_TRUE(rig.packets.back().isTerminator);

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
    InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                 InputMode::VOICE_ACTIVITY);

    // 1. Ambient noise below the squelch floor (amplitude 10 -> ~-70 dBFS).
    auto ambientBelowSquelch = generateSineFrame(0, 10);
    rig.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());

    // The denoiser receives pure silence to advance the overlap-add delay
    // while its recurrent inference is bypassed.
    TEST_ASSERT_EQ(denoiserPtr->getLastInSamples().size(), 480u);
    bool allZeros = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            allZeros = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(allZeros);

    // 2. Active speech above the squelch reaches the denoiser.
    auto speechFrame = generateSineFrame(1);
    rig.processFrame(speechFrame.data(), speechFrame.size());
    bool hasNonZero = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            hasNonZero = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(hasNonZero);

    // 3. During the VAD hold, a low-energy frame is not squelch-bypassed.
    rig.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool hangoverReceivedRealAudio = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            hangoverReceivedRealAudio = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(hangoverReceivedRealAudio);

    // Tick through the hold, then the bypass resumes.
    for (int i = 0; i < 30; ++i) {
        rig.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    }
    rig.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool squelchResumedZeros = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            squelchResumedZeros = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(squelchResumedZeros);

    // 4. CONTINUOUS mode never squelch-bypasses.
    rig.setInputMode(InputMode::CONTINUOUS);
    rig.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
    bool continuousReceivedRealAudio = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            continuousReceivedRealAudio = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(continuousReceivedRealAudio);

    // 5. PUSH_TO_TALK with the button pressed does not bypass either.
    rig.setInputMode(InputMode::PUSH_TO_TALK);
    rig.setPttTalking(true);
    rig.processFrame(ambientBelowSquelch.data(), ambientBelowSquelch.size());
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
    InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    auto loudAmbientFrame = generateSineFrame(1, 10000);

    // Idle PTT: the denoiser gets static zeroes.
    rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool allZerosInIdle = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            allZerosInIdle = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(allZerosInIdle);
    TEST_ASSERT_EQ(rig.packets.size(), 0u);

    // Pressing PTT delivers real audio immediately.
    rig.setPttTalking(true);
    rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool nonZeroWhenPttActive = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            nonZeroWhenPttActive = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(nonZeroWhenPttActive);

    // After the release hold expires, the bypass resumes.
    rig.setPttTalking(false);
    for (int i = 0; i < 20; ++i) {
        rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    }
    rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
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
    InputRig rig(std::move(encoder), nullptr, 2, 1.0f, false,
                 InputMode::PUSH_TO_TALK);

    // 8 frames of ambient noise while PTT is unpressed: nothing is buffered.
    for (int i = 1; i <= 8; ++i) {
        auto preFrame = generateSineFrame(i, 5000);
        rig.processFrame(preFrame.data(), preFrame.size());
    }
    TEST_ASSERT_EQ(rig.packets.size(), 0u);

    // Press PTT and feed 1 frame: 1 frame banked, no packet yet.
    rig.setPttTalking(true);
    auto frame1 = generateSineFrame(9, 5000);
    rig.processFrame(frame1.data(), frame1.size());
    TEST_ASSERT_EQ(rig.packets.size(), 0u);

    // The 2nd talking frame completes the packet.
    auto frame2 = generateSineFrame(10, 5000);
    rig.processFrame(frame2.data(), frame2.size());
    TEST_ASSERT_EQ(rig.packets.size(), 1u);
    TEST_ASSERT_EQ(rig.packets[0].frames, 2);

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
    InputRig rig(std::move(encoder), std::move(denoiser), 2, 1.0f, false,
                 InputMode::VOICE_ACTIVITY);

    auto loudAmbientFrame = generateSineFrame(1, 10000);

    // Unmuted: the denoiser sees real audio.
    rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool nonZeroWhenUnmuted = false;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            nonZeroWhenUnmuted = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(nonZeroWhenUnmuted);

    // Muted: the bypass feeds static zeroes even for a loud frame.
    rig.setMuted(true);
    rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
    bool allZerosWhenMuted = true;
    for (int16_t s : denoiserPtr->getLastInSamples()) {
        if (s != 0) {
            allZerosWhenMuted = false;
            break;
        }
    }
    TEST_ASSERT_TRUE(allZerosWhenMuted);

    // Unmuted again: real audio resumes.
    rig.setMuted(false);
    rig.processFrame(loudAmbientFrame.data(), loudAmbientFrame.size());
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
    testInputConfigTransitions();
    testPttStateTransitions();
    testPttReleaseHangoverTiming();
    testPttHangoverReassertionPreventsCutoff();
    testPttPressObservedForOneFrameStillHolds();
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
