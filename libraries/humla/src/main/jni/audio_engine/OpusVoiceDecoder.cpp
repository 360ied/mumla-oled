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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "OpusVoiceDecoder.h"
#include "AudioOutputEngine.h"
#include <opus.h>

namespace mumla {
namespace audio {
namespace {

class OpusOutputDecoderAdapter : public IOutputDecoder {
public:
    int decodeFloat(const uint8_t* data, size_t len, float* out,
                    int maxSamples, int decodeFec) override {
        return m_decoder.decodeFloat(data, len, out, maxSamples, decodeFec);
    }
    int decodeConcealment(float* out, int frameSize) override {
        return m_decoder.decodeConcealment(out, frameSize);
    }
    void reset() override { m_decoder.reset(); }
    int packetSampleCount(const uint8_t* data, size_t len) const override {
        return OpusVoiceDecoder::packetSampleCount(data, len);
    }
    bool isValid() const override { return m_decoder.isValid(); }

private:
    OpusVoiceDecoder m_decoder;
};

} // namespace

std::unique_ptr<IOutputDecoder> makeOpusOutputDecoder() {
    return std::make_unique<OpusOutputDecoderAdapter>();
}

OpusVoiceDecoder::OpusVoiceDecoder()
    : m_decoder(nullptr) {
    int error = OPUS_OK;
    m_decoder = opus_decoder_create(SAMPLE_RATE, CHANNELS, &error);
    if (error != OPUS_OK || m_decoder == nullptr) {
        m_decoder = nullptr;
        return;
    }
    // Mono output: default decoder phase handling is fine. (The bundled
    // opus predates OPUS_SET_PHASE_INVERSION_DISABLED; do not re-add
    // without bumping the in-tree codec.)
}

OpusVoiceDecoder::~OpusVoiceDecoder() {
    if (m_decoder != nullptr) {
        opus_decoder_destroy(m_decoder);
        m_decoder = nullptr;
    }
}

int OpusVoiceDecoder::decodeFloat(const uint8_t* data, size_t len, float* out,
                                  int maxSamples, int decodeFec) {
    if (m_decoder == nullptr || out == nullptr || maxSamples <= 0) {
        return OPUS_BAD_ARG;
    }
    return opus_decode_float(m_decoder, data, static_cast<opus_int32>(len),
                             out, maxSamples, decodeFec);
}

int OpusVoiceDecoder::decodeConcealment(float* out, int frameSize) {
    return decodeFloat(nullptr, 0, out, frameSize, 0);
}

int OpusVoiceDecoder::packetSampleCount(const uint8_t* data, size_t len) {
    if (data == nullptr || len == 0) {
        return OPUS_BAD_ARG;
    }
    return opus_packet_get_nb_samples(data, static_cast<opus_int32>(len),
                                      SAMPLE_RATE);
}

void OpusVoiceDecoder::reset() {
    if (m_decoder != nullptr) {
        opus_decoder_ctl(m_decoder, OPUS_RESET_STATE);
    }
}

} // namespace audio
} // namespace mumla
