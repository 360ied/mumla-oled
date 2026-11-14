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

#include "VoiceCodec.h"

#include <cstring>

#include <pb_decode.h>
#include <pb_encode.h>

#include "generated/MumbleUDP.pb.h"

namespace mumla {
namespace voice_codec {

namespace {

// Mumble variable-length integer encoding (PacketBuffer.writeLong/readLong,
// byte-for-byte). All shifts are logical on the unsigned view; the low 8 bits
// after masking match Kotlin's arithmetic shifts exactly.

size_t varintSize(int64_t value) {
    int64_t i = value;
    size_t size = 0;
    if (i < 0 && ~i < 0x100000000LL) {
        i = ~i;
        if (i <= 0x3) {
            return 1;
        }
        size += 1;  // 0xF8 prefix, then the transformed value below
    }
    uint64_t u = static_cast<uint64_t>(i);
    // Note: after the negative transform above, i >= 0, so the unsigned view
    // is the value itself. For very negative inputs (skipped transform),
    // u is the two's complement, which the 64-bit branch below encodes.
    if (u <= 0x7FULL) {
        return size + 1;
    } else if (u <= 0x3FFFULL) {
        return size + 2;
    } else if (u <= 0x1FFFFFULL) {
        return size + 3;
    } else if (u <= 0xFFFFFFFULL) {
        return size + 4;
    } else if (u <= 0xFFFFFFFFULL) {
        return size + 5;
    }
    return size + 9;
}

bool writeVarint(int64_t value, uint8_t* out, size_t capacity, size_t* written) {
    size_t need = varintSize(value);
    if (need > capacity) {
        return false;
    }
    int64_t i = value;
    size_t pos = 0;
    if (i < 0 && ~i < 0x100000000LL) {
        i = ~i;
        if (i <= 0x3) {
            out[pos++] = static_cast<uint8_t>(0xFC | i);
            *written = pos;
            return true;
        }
        out[pos++] = 0xF8;
    }
    uint64_t u = static_cast<uint64_t>(i);
    if (u <= 0x7FULL) {
        out[pos++] = static_cast<uint8_t>(u);
    } else if (u <= 0x3FFFULL) {
        out[pos++] = static_cast<uint8_t>((u >> 8) | 0x80);
        out[pos++] = static_cast<uint8_t>(u & 0xFF);
    } else if (u <= 0x1FFFFFULL) {
        out[pos++] = static_cast<uint8_t>((u >> 16) | 0xC0);
        out[pos++] = static_cast<uint8_t>((u >> 8) & 0xFF);
        out[pos++] = static_cast<uint8_t>(u & 0xFF);
    } else if (u <= 0xFFFFFFFULL) {
        out[pos++] = static_cast<uint8_t>((u >> 24) | 0xE0);
        out[pos++] = static_cast<uint8_t>((u >> 16) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 8) & 0xFF);
        out[pos++] = static_cast<uint8_t>(u & 0xFF);
    } else if (u <= 0xFFFFFFFFULL) {
        out[pos++] = 0xF0;
        out[pos++] = static_cast<uint8_t>((u >> 24) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 16) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 8) & 0xFF);
        out[pos++] = static_cast<uint8_t>(u & 0xFF);
    } else {
        out[pos++] = 0xF4;
        out[pos++] = static_cast<uint8_t>((u >> 56) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 48) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 40) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 32) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 24) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 16) & 0xFF);
        out[pos++] = static_cast<uint8_t>((u >> 8) & 0xFF);
        out[pos++] = static_cast<uint8_t>(u & 0xFF);
    }
    *written = pos;
    return true;
}

// Returns false on truncation (BufferUnderflow parity: the caller drops).
bool readVarint(const uint8_t* data, size_t length, size_t* offset, int64_t* value) {
    if (*offset >= length) {
        return false;
    }
    uint64_t v = data[(*offset)++];
    int64_t i = 0;
    if ((v & 0x80ULL) == 0x00ULL) {
        i = static_cast<int64_t>(v & 0x7FULL);
    } else if ((v & 0xC0ULL) == 0x80ULL) {
        if (*offset >= length) return false;
        uint64_t n = data[(*offset)++];
        i = static_cast<int64_t>(((v & 0x3FULL) << 8) | n);
    } else if ((v & 0xF0ULL) == 0xF0ULL) {
        uint64_t tmp = v & 0xFCULL;
        if (tmp == 0xF0) {
            if (*offset + 4 > length) return false;
            uint64_t b0 = data[(*offset)++];
            uint64_t b1 = data[(*offset)++];
            uint64_t b2 = data[(*offset)++];
            uint64_t b3 = data[(*offset)++];
            i = static_cast<int64_t>((b0 << 24) | (b1 << 16) | (b2 << 8) | b3);
        } else if (tmp == 0xF4) {
            if (*offset + 8 > length) return false;
            uint64_t b0 = data[(*offset)++];
            uint64_t b1 = data[(*offset)++];
            uint64_t b2 = data[(*offset)++];
            uint64_t b3 = data[(*offset)++];
            uint64_t b4 = data[(*offset)++];
            uint64_t b5 = data[(*offset)++];
            uint64_t b6 = data[(*offset)++];
            uint64_t b7 = data[(*offset)++];
            i = static_cast<int64_t>((b0 << 56) | (b1 << 48) | (b2 << 40) | (b3 << 32) |
                                     (b4 << 24) | (b5 << 16) | (b6 << 8) | b7);
        } else if (tmp == 0xF8) {
            int64_t inner = 0;
            if (!readVarint(data, length, offset, &inner)) return false;
            i = ~inner;
        } else if (tmp == 0xFC) {
            i = ~(static_cast<int64_t>(v & 0x03ULL));
        } else {
            return false;
        }
    } else if ((v & 0xF0ULL) == 0xE0ULL) {
        if (*offset + 3 > length) return false;
        uint64_t b1 = data[(*offset)++];
        uint64_t b2 = data[(*offset)++];
        uint64_t b3 = data[(*offset)++];
        i = static_cast<int64_t>(((v & 0x0FULL) << 24) | (b1 << 16) | (b2 << 8) | b3);
    } else if ((v & 0xE0ULL) == 0xC0ULL) {
        if (*offset + 2 > length) return false;
        uint64_t b1 = data[(*offset)++];
        uint64_t b2 = data[(*offset)++];
        i = static_cast<int64_t>(((v & 0x1FULL) << 16) | (b1 << 8) | b2);
    } else {
        return false;
    }
    *value = i;
    return true;
}

uint8_t applyLegacyCodecWorkaround(uint8_t header, uint32_t version) {
    if (version != kLegacyCodecWorkaroundVersion) {
        return header;
    }
    uint8_t ordinal = static_cast<uint8_t>((header >> 5) & 0x07);
    if (ordinal == kLegacyVoiceCeltBeta) {
        ordinal = kLegacyVoiceCeltAlpha;
    } else if (ordinal == kLegacyVoiceCeltAlpha) {
        ordinal = kLegacyVoiceCeltBeta;
    } else {
        return header;
    }
    return static_cast<uint8_t>(((ordinal << 5) & 0xFF) | (header & 0x1F));
}

bool parseLegacyVoice(const uint8_t* data,
                      size_t length,
                      uint32_t workaroundVersion,
                      ParsedDatagram* out) {
    if (length < 1) return false;
    uint8_t header = applyLegacyCodecWorkaround(data[0], workaroundVersion);
    uint8_t dataType = static_cast<uint8_t>((header >> 5) & 0x07);
    if (dataType != kLegacyVoiceOpus) {
        out->kind = ParsedDatagram::UNKNOWN;
        out->frameCount = 0;
        return true;
    }
    int msgFlags = header & 0x1F;
    size_t offset = 1;
    int64_t sessionLong = 0;
    int64_t seqLong = 0;
    if (!readVarint(data, length, &offset, &sessionLong)) return false;
    if (!readVarint(data, length, &offset, &seqLong)) return false;
    int32_t session = static_cast<int32_t>(sessionLong);
    uint32_t seqBase = static_cast<uint32_t>(seqLong);
    out->session = session;
    out->frameCount = 0;
    while (offset < length && out->frameCount < kMaxFramesPerDatagram) {
        size_t left = length - offset;
        // Legacy positional trailer: exactly 3 floats (12 bytes) after at
        // least one frame ends parsing, as today. Intentional tolerance
        // divergence (upstream would discard the whole packet) is preserved.
        if (out->frameCount > 0 && left == 3 * 4) {
            break;
        }
        int64_t headerLong = 0;
        if (!readVarint(data, length, &offset, &headerLong)) {
            // Malformed mid-packet: drop the whole datagram. (Kotlin queued
            // the valid prefix but never signalled it, so nothing played;
            // dropping all here is the cleaner equivalent.)
            out->frameCount = 0;
            return false;
        }
        uint64_t headerU = static_cast<uint64_t>(headerLong);
        size_t size = static_cast<size_t>(headerU & 0x1FFFULL);
        bool isTerminator = (headerU & 0x2000ULL) != 0;
        if (size == 0 && isTerminator) {
            audio::InboundPacket& frame = out->frames[out->frameCount++];
            frame.session = session;
            frame.sequence = seqBase + static_cast<uint32_t>(out->frameCount - 1);
            frame.flags = msgFlags;
            frame.isTerminator = true;
            frame.length = 0;
            continue;
        }
        if (size == 0 || size > length - offset) {
            break;
        }
        if (size > sizeof(audio::InboundPacket::data)) {
            break;
        }
        audio::InboundPacket& frame = out->frames[out->frameCount++];
        frame.session = session;
        frame.sequence = seqBase + static_cast<uint32_t>(out->frameCount - 1);
        frame.flags = msgFlags;
        frame.isTerminator = isTerminator;
        frame.length = static_cast<uint16_t>(size);
        std::memcpy(frame.data, data + offset, size);
        offset += size;
    }
    if (out->frameCount == kMaxFramesPerDatagram) {
        // Count frames beyond the twelfth without storing them. Stops at the
        // positional trailer, a size overrun, or malformed bytes; the stored
        // twelve stay valid regardless.
        while (offset < length) {
            if (length - offset == 3 * 4) break;
            int64_t h = 0;
            if (!readVarint(data, length, &offset, &h)) break;
            uint64_t hu = static_cast<uint64_t>(h);
            size_t sz = static_cast<size_t>(hu & 0x1FFFULL);
            bool term = (hu & 0x2000ULL) != 0;
            if (sz == 0 && term) {
                out->droppedFrames++;
                continue;
            }
            if (sz == 0 || sz > length - offset) break;
            if (sz > sizeof(audio::InboundPacket::data)) break;
            offset += sz;
            out->droppedFrames++;
        }
    }
    out->kind = ParsedDatagram::VOICE;
    return true;
}

bool parseLegacyPing(const uint8_t* data, size_t length, ParsedDatagram* out) {
    if (length < 1) return false;
    size_t offset = 1;
    int64_t timestamp = 0;
    if (readVarint(data, length, &offset, &timestamp)) {
        out->kind = ParsedDatagram::PING;
        out->pingTimestamp = static_cast<uint64_t>(timestamp);
        out->serverVersionV2 = 0;
        out->maxBandwidthPerUser = 0;
        return true;
    }
    // Bug-for-bug fallback (HumlaConnection parity): the varint timestamp
    // above failed, so decode 8 fixed bytes big-endian.
    if (length >= 9) {
        uint64_t fixed = 0;
        for (int i = 1; i <= 8; ++i) {
            fixed = (fixed << 8) | data[i];
        }
        out->kind = ParsedDatagram::PING;
        out->pingTimestamp = fixed;
        out->serverVersionV2 = 0;
        out->maxBandwidthPerUser = 0;
        return true;
    }
    return false;
}

bool parseProtobufVoice(const uint8_t* data, size_t length, ParsedDatagram* out) {
    if (length < 1 || data[0] != 0x00) return false;
    MumbleUDP_Audio msg = MumbleUDP_Audio_init_zero;
    pb_istream_t stream = pb_istream_from_buffer(data + 1, length - 1);
    if (!pb_decode(&stream, MumbleUDP_Audio_fields, &msg)) {
        return false;
    }
    // Positional data and volume_adjustment are parsed and ignored, as today.
    if (msg.opus_data.size == 0 && !msg.is_terminator) {
        out->kind = ParsedDatagram::VOICE;
        out->session = static_cast<int32_t>(msg.sender_session);
        out->frameCount = 0;
        return true;
    }
    if (msg.opus_data.size > sizeof(audio::InboundPacket::data)) {
        return false;
    }
    out->kind = ParsedDatagram::VOICE;
    out->session = static_cast<int32_t>(msg.sender_session);
    out->frameCount = 1;
    audio::InboundPacket& frame = out->frames[0];
    frame.session = static_cast<int32_t>(msg.sender_session);
    frame.sequence = static_cast<uint32_t>(msg.frame_number);
    if (msg.which_Header == MumbleUDP_Audio_context_tag) {
        frame.flags = static_cast<int>(msg.Header.context);
    } else {
        frame.flags = 0;
    }
    frame.isTerminator = msg.is_terminator;
    frame.length = static_cast<uint16_t>(msg.opus_data.size);
    if (msg.opus_data.size > 0) {
        std::memcpy(frame.data, msg.opus_data.bytes, msg.opus_data.size);
    }
    return true;
}

bool parseProtobufPing(const uint8_t* data, size_t length, ParsedDatagram* out) {
    if (length < 1 || data[0] != 0x01) return false;
    MumbleUDP_Ping msg = MumbleUDP_Ping_init_zero;
    pb_istream_t stream = pb_istream_from_buffer(data + 1, length - 1);
    if (!pb_decode(&stream, MumbleUDP_Ping_fields, &msg)) {
        return false;
    }
    out->kind = ParsedDatagram::PING;
    out->pingTimestamp = msg.timestamp;
    out->serverVersionV2 = msg.server_version_v2;
    out->maxBandwidthPerUser = msg.max_bandwidth_per_user;
    return true;
}

}  // namespace

size_t frameVoice(UdpFormat format,
                  const audio::EncodedPacket& packet,
                  uint32_t voiceTarget,
                  uint8_t* out,
                  size_t capacity) {
    if (out == nullptr || capacity == 0) return 0;
    if (packet.size > audio::kMaxOpusBufferBytes) return 0;
    if (format == UdpFormat::LEGACY) {
        uint8_t header =
            static_cast<uint8_t>(((kLegacyVoiceOpus << 5) & 0xFF) | (voiceTarget & 0x1F));
        uint64_t sizeHeader =
            (static_cast<uint64_t>(packet.size) & 0x1FFFULL) | (packet.isTerminator ? 0x2000ULL : 0ULL);
        size_t frameLen = varintSize(static_cast<int64_t>(packet.frameNumber));
        size_t headerLen = varintSize(static_cast<int64_t>(sizeHeader));
        size_t total = 1 + frameLen + headerLen + packet.size;
        // Worst-case guard parity (AudioHandler drops over 1024): the caller
        // sizes the buffer; overflow here is a clean 0, never a throw.
        if (total > capacity) return 0;
        out[0] = header;
        size_t written = 0;
        if (!writeVarint(static_cast<int64_t>(packet.frameNumber), out + 1, capacity - 1, &written)) {
            return 0;
        }
        size_t headerWritten = 0;
        if (!writeVarint(static_cast<int64_t>(sizeHeader), out + 1 + written,
                         capacity - 1 - written, &headerWritten)) {
            return 0;
        }
        if (packet.size > 0) {
            std::memcpy(out + 1 + written + headerWritten, packet.data, packet.size);
        }
        return total;
    }
    if (packet.size > audio::kMaxOpusBufferBytes) return 0;
    MumbleUDP_Audio msg = MumbleUDP_Audio_init_zero;
    if (voiceTarget != 0) {
        msg.which_Header = MumbleUDP_Audio_target_tag;
        msg.Header.target = voiceTarget;
    }
    msg.frame_number = packet.frameNumber;
    msg.opus_data.size = static_cast<pb_size_t>(packet.size);
    if (packet.size > 0) {
        std::memcpy(msg.opus_data.bytes, packet.data, packet.size);
    }
    msg.is_terminator = packet.isTerminator;
    if (capacity < 1) return 0;
    pb_ostream_t stream = pb_ostream_from_buffer(out + 1, capacity - 1);
    if (!pb_encode(&stream, MumbleUDP_Audio_fields, &msg)) {
        return 0;
    }
    out[0] = 0x00;
    return 1 + stream.bytes_written;
}

size_t framePing(UdpFormat format, uint64_t timestampMicros, uint8_t* out, size_t capacity) {
    if (out == nullptr || capacity == 0) return 0;
    if (format == UdpFormat::LEGACY) {
        size_t stampLen = varintSize(static_cast<int64_t>(timestampMicros));
        if (1 + stampLen > capacity) return 0;
        out[0] = static_cast<uint8_t>((kLegacyPing << 5) & 0xFF);
        size_t written = 0;
        if (!writeVarint(static_cast<int64_t>(timestampMicros), out + 1, capacity - 1, &written)) {
            return 0;
        }
        return 1 + written;
    }
    MumbleUDP_Ping msg = MumbleUDP_Ping_init_zero;
    msg.timestamp = timestampMicros;
    if (capacity < 1) return 0;
    pb_ostream_t stream = pb_ostream_from_buffer(out + 1, capacity - 1);
    if (!pb_encode(&stream, MumbleUDP_Ping_fields, &msg)) {
        return 0;
    }
    out[0] = 0x01;
    return 1 + stream.bytes_written;
}

bool parseDatagram(UdpFormat format,
                   const uint8_t* data,
                   size_t length,
                   uint32_t legacyCodecWorkaroundVersion,
                   ParsedDatagram* out) {
    if (data == nullptr || out == nullptr) return false;
    *out = ParsedDatagram();
    if (length == 0) return false;
    if (format == UdpFormat::LEGACY) {
        uint8_t header = applyLegacyCodecWorkaround(data[0], legacyCodecWorkaroundVersion);
        uint8_t dataType = static_cast<uint8_t>((header >> 5) & 0x07);
        if (dataType == kLegacyPing) {
            return parseLegacyPing(data, length, out);
        } else if (dataType == kLegacyVoiceOpus) {
            return parseLegacyVoice(data, length, legacyCodecWorkaroundVersion, out);
        }
        out->kind = ParsedDatagram::UNKNOWN;
        return true;
    }
    uint8_t lead = data[0];
    if (lead == 0x00) {
        return parseProtobufVoice(data, length, out);
    } else if (lead == 0x01) {
        return parseProtobufPing(data, length, out);
    }
    // Protobuf mode: any other lead byte is neither voice nor ping; drop
    // instead of mis-decoding as legacy voice (LegacyVoiceLink parity).
    out->kind = ParsedDatagram::UNKNOWN;
    return true;
}

}  // namespace voice_codec
}  // namespace mumla
