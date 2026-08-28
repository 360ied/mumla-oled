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

#include "OpusVoiceEncoder.h"
#include <opus.h>

namespace mumla {
namespace audio {

OpusVoiceEncoder::OpusVoiceEncoder(int bitrate)
    : m_encoder(nullptr),
      m_bitrate(bitrate > 0 ? bitrate : DEFAULT_BITRATE) {
    int error = OPUS_OK;
    m_encoder = opus_encoder_create(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK || m_encoder == nullptr) {
        m_encoder = nullptr;
        return;
    }

    // MANDATORY HARD CONSTANT BITRATE (CBR) - STRICTLY UNCONFIGURABLE
    opus_encoder_ctl(m_encoder, OPUS_SET_VBR(0));
    opus_encoder_ctl(m_encoder, OPUS_SET_VBR_CONSTRAINT(0));

    // Psychoacoustic voice quality optimizations
    opus_encoder_ctl(m_encoder, OPUS_SET_COMPLEXITY(10));
    opus_encoder_ctl(m_encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(m_encoder, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND));

    // Error resilience: In-band FEC + packet loss adaptation
    opus_encoder_ctl(m_encoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(m_encoder, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(m_encoder, OPUS_SET_DTX(0));

    // Set target bitrate
    opus_encoder_ctl(m_encoder, OPUS_SET_BITRATE(m_bitrate));
}

OpusVoiceEncoder::~OpusVoiceEncoder() {
    if (m_encoder != nullptr) {
        opus_encoder_destroy(m_encoder);
        m_encoder = nullptr;
    }
}

int OpusVoiceEncoder::encode(const int16_t* pcm, size_t sampleCount, uint8_t* outBuffer, size_t maxBytes) {
    if (m_encoder == nullptr || pcm == nullptr || outBuffer == nullptr || sampleCount == 0 || maxBytes == 0) {
        return -1;
    }

    int result = opus_encode(m_encoder, pcm, static_cast<int>(sampleCount),
                             outBuffer, static_cast<opus_int32>(maxBytes));
    return result;
}

void OpusVoiceEncoder::setBitrate(int bitrate) {
    if (bitrate <= 0) {
        return;
    }
    m_bitrate = bitrate;
    if (m_encoder != nullptr) {
        opus_encoder_ctl(m_encoder, OPUS_SET_BITRATE(m_bitrate));
    }
}

int OpusVoiceEncoder::getBitrate() const {
    if (m_encoder == nullptr) {
        return m_bitrate;
    }
    opus_int32 bitrate = 0;
    opus_encoder_ctl(m_encoder, OPUS_GET_BITRATE(&bitrate));
    return bitrate;
}

void OpusVoiceEncoder::reset() {
    if (m_encoder != nullptr) {
        opus_encoder_ctl(m_encoder, OPUS_RESET_STATE);
    }
}

} // namespace audio
} // namespace mumla
