/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef MUMLA_OPUS_VOICE_ENCODER_H_
#define MUMLA_OPUS_VOICE_ENCODER_H_

#include <cstddef>
#include <cstdint>

struct OpusEncoder;

namespace mumla {
namespace audio {

/**
 * Native Opus Voice Encoder with Mandatory Hard Constant Bitrate (CBR).
 *
 * Configures libopus in VOIP application mode with complexity 10, voice signal
 * weighting, in-band forward error correction (FEC), and strictly enforced
 * Hard Constant Bitrate (VBR=0) to prevent packet-length side-channel attacks.
 */
class OpusVoiceEncoder {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int CHANNELS = 1;
    static constexpr int DEFAULT_BITRATE = 40000; // 40 kbps

    explicit OpusVoiceEncoder(int bitrate = DEFAULT_BITRATE);
    ~OpusVoiceEncoder();

    // Non-copyable
    OpusVoiceEncoder(const OpusVoiceEncoder&) = delete;
    OpusVoiceEncoder& operator=(const OpusVoiceEncoder&) = delete;

    /**
     * Encodes 10ms/20ms/40ms PCM samples into an Opus packet.
     *
     * @param pcm Input 16-bit PCM samples.
     * @param sampleCount Number of samples (e.g. 480 for 10ms, 960 for 20ms).
     * @param outBuffer Output buffer for compressed packet bytes.
     * @param maxBytes Maximum capacity of outBuffer.
     * @return Number of compressed bytes written, or negative on error.
     */
    int encode(const int16_t* pcm, size_t sampleCount, uint8_t* outBuffer, size_t maxBytes);

    void setBitrate(int bitrate);
    int getBitrate() const;

    void reset();

    bool isValid() const { return m_encoder != nullptr; }

private:
    OpusEncoder* m_encoder;
    int m_bitrate;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_OPUS_VOICE_ENCODER_H_
