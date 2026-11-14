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

#ifndef MUMLA_VOICE_CODEC_H_
#define MUMLA_VOICE_CODEC_H_

// Packet codec (step 5a.2): no-I/O framing for UDP voice and pings.
// See docs/target-architecture/voice.md#packet-codec.
//
// Pure and allocation-free: bytes in, bytes out into caller buffers. Owns no
// threads, sockets, or devices. The JNI bridge (NativeVoiceCodecJni.cpp) is
// the only caller from Kotlin until voice_io lands in 5a.3.

#include <cstddef>
#include <cstdint>

#include "AudioInputEngine.h"
#include "AudioOutputEngine.h"

namespace mumla {
namespace voice_codec {

enum class UdpFormat {
    LEGACY,
    PROTOBUF,
};

// Largest Opus bundle the parser accepts: 12 x 10 ms frames, the output
// engine's largest bundle (MAX_DECODE_SAMPLES). Frames beyond the twelfth
// are dropped, a bound today's Kotlin parser lacks.
inline constexpr size_t kMaxFramesPerDatagram = 12;

// 1.2.2 legacy codec workaround version (applyLegacyCodecWorkaround).
inline constexpr uint32_t kLegacyCodecWorkaroundVersion = 0x10202;

// Legacy wire types (HumlaUDPMessageType order is the wire value).
inline constexpr uint8_t kLegacyVoiceCeltAlpha = 0;
inline constexpr uint8_t kLegacyPing = 1;
inline constexpr uint8_t kLegacyVoiceSpeex = 2;
inline constexpr uint8_t kLegacyVoiceCeltBeta = 3;
inline constexpr uint8_t kLegacyVoiceOpus = 4;

// Outbound voice: one EncodedPacket -> one datagram payload (unencrypted).
// Returns bytes written, 0 on failure (capacity too small, oversize opus).
size_t frameVoice(UdpFormat format,
                  const audio::EncodedPacket& packet,
                  uint32_t voiceTarget,
                  uint8_t* out,
                  size_t capacity);

// Outbound ping. Returns bytes written, 0 on failure.
size_t framePing(UdpFormat format,
                 uint64_t timestampMicros,
                 uint8_t* out,
                 size_t capacity);

struct ParsedDatagram {
    enum Kind {
        VOICE,
        PING,
        UNKNOWN,
    } kind = UNKNOWN;
    int32_t session = 0;
    size_t frameCount = 0;
    audio::InboundPacket frames[kMaxFramesPerDatagram];
    uint64_t pingTimestamp = 0;
    uint64_t serverVersionV2 = 0;  // protobuf ping, 0 if absent
    uint32_t maxBandwidthPerUser = 0;  // protobuf ping, 0 if absent
};

// Inbound: one decrypted datagram -> voice frames, a ping, or nothing.
// legacyCodecWorkaroundVersion keys the 1.2.2 swap (0 disables).
// Returns true when the datagram was understood (voice, ping, or a
// deliberate drop such as an unknown sender handled by the caller);
// false only for malformed input the caller must drop.
bool parseDatagram(UdpFormat format,
                   const uint8_t* data,
                   size_t length,
                   uint32_t legacyCodecWorkaroundVersion,
                   ParsedDatagram* out);

}  // namespace voice_codec
}  // namespace mumla

#endif  // MUMLA_VOICE_CODEC_H_
