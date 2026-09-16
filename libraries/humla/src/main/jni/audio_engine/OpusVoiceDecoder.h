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

#ifndef MUMLA_OPUS_VOICE_DECODER_H_
#define MUMLA_OPUS_VOICE_DECODER_H_

#include <cstddef>
#include <cstdint>
#include <memory>

struct OpusDecoder;

namespace mumla {
namespace audio {

class IOutputDecoder;

/**
 * Native Opus Voice Decoder for the output pipeline.
 *
 * Mono fullband decoder at 48 kHz mirroring OpusVoiceEncoder's configuration.
 * Supports in-band forward error correction decode and standalone
 * packet-loss concealment for missing frames.
 */
class OpusVoiceDecoder {
public:
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int CHANNELS = 1;

    OpusVoiceDecoder();
    ~OpusVoiceDecoder();

    // Non-copyable
    OpusVoiceDecoder(const OpusVoiceDecoder&) = delete;
    OpusVoiceDecoder& operator=(const OpusVoiceDecoder&) = delete;

    /**
     * Decodes one Opus packet into float PCM.
     *
     * @param data Encoded packet bytes (may be null for PLC).
     * @param len Number of encoded bytes (0 with null data runs concealment).
     * @param out Output float PCM buffer.
     * @param maxSamples Maximum samples that fit in out.
     * @param decodeFec Non-zero to decode in-band FEC from a redundant frame.
     * @return Decoded sample count, or negative on error.
     */
    int decodeFloat(const uint8_t* data, size_t len, float* out, int maxSamples,
                    int decodeFec);

    /**
     * Generates one concealment frame for a missing packet.
     */
    int decodeConcealment(float* out, int frameSize);

    /**
     * Returns the PCM sample count carried by an Opus packet, or negative
     * if the packet header is invalid.
     */
    static int packetSampleCount(const uint8_t* data, size_t len);

    void reset();

    bool isValid() const { return m_decoder != nullptr; }

private:
    OpusDecoder* m_decoder;
};

/**
 * Creates an IOutputDecoder backed by libopus for production use.
 * Defined here so the engine core stays linkable without libopus.
 */
std::unique_ptr<IOutputDecoder> makeOpusOutputDecoder();

} // namespace audio
} // namespace mumla

#endif // MUMLA_OPUS_VOICE_DECODER_H_
