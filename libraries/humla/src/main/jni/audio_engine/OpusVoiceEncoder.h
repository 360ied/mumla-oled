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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
