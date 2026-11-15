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

#ifndef MUMLA_VOICE_RECEIVE_H_
#define MUMLA_VOICE_RECEIVE_H_

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <thread>

#include "CryptStateOCB2.h"
#include "VoiceCodec.h"
#include "VoicePolicy.h"

namespace mumla {
namespace voice_io {

class VoiceIo;

/**
 * Native UDP receive half (step 5a.3b item 3).
 *
 * Owns the UDP socket and the receive thread: blocking recvfrom, OCB2
 * decrypt through CryptStateOCB2 directly, codec parseDatagram, sender
 * filter through the policy sequence lock, voice to the inbound ring.
 * Pings reach Kotlin as events; a decrypt failure raises a resync request
 * at today's rate (5 s since last good, 5 s between requests). The 20 s
 * fallback stays in TransportImpl: native reports, the transport decides.
 *
 * Only non-realtime threads call into Kotlin (receive thread via the ping
 * and resync sinks). The render wake post lives in the VoiceIo push
 * functions, so the receive thread never calls Kotlin for it. OCB2 locks
 * stay: taken only here and briefly by Kotlin key changes, never realtime.
 */
class VoiceReceive {
public:
    struct PingReport {
        uint64_t timestampMicros = 0;
        uint64_t serverVersionV2 = 0;
        uint32_t maxBandwidth = 0;
    };
    using PingSink = std::function<void(const PingReport&)>;
    using ResyncSink = std::function<void()>;
    using UnavailableSink = std::function<void()>;
    // Voice frames sink (live 5a.3b path): invoked with the filtered
    // survivors plus the tunnel flag when m_io is null (no shared VoiceIo;
    // render stays per-pipeline until 5a.4). Tests use m_io directly
    // without a sink.
    using VoiceSink = std::function<void(const mumla::audio::InboundPacket* frames,
                                         size_t count, bool tunnel)>;

    VoiceReceive(VoiceIo* io, PolicyCell* policy);
    ~VoiceReceive();

    VoiceReceive(const VoiceReceive&) = delete;
    VoiceReceive& operator=(const VoiceReceive&) = delete;

    // Control thread only. Format fixed per session from the transport.
    void setFormat(voice_codec::UdpFormat format, uint32_t legacyVersion);

    // Keys (CryptSetup with full keys, or resync IV). May race the receive
    // loop: OCB2 serializes internally, never on a realtime thread.
    bool setKeys(const uint8_t key[16], const uint8_t eiv[16], const uint8_t div[16]);
    bool setDecryptIv(const uint8_t div[16]);
    // Session end (Stop, control thread, receive already joined): zero the
    // crypt keys so nothing lingers across sessions. Stats restart at zero.
    void clearKeys();
    void getEncryptIv(uint8_t out[16]) const;
    void getStats(int32_t* good, int32_t* late, int32_t* lost, int32_t* resync) const;

    // Test seam: drive one decrypted datagram through parse + filter + push
    // without a socket. Returns true when understood (voice/ping/drop).
    bool handlePlaintext(const uint8_t* data, size_t length);

    // Test seam: decrypt then handle (exercises crypt counts). Returns
    // decrypt success; on failure applies the same rate-limited resync.
    bool handleCrypted(const uint8_t* data, size_t length);

    // Tunnel path (session message coroutine): parse + filter, push to the
    // tunnel ring when m_io is present, otherwise the voice sink with
    // tunnel=true. Removals stay ordered after tunnelled voice on this ring.
    bool handleTunnelled(const uint8_t* data, size_t length);

    // Pure resync gate for pinning (5 s since last good, 5 s between
    // requests). Live loop and tests share it; no clock inside.
    static bool shouldRequestResync(uint64_t nowUs, int64_t lastGoodElapsedUs,
                                    uint64_t lastRequestUs);

    // Socket lifetime (control thread). start binds an ephemeral UDP socket
    // and connects it to host/port; the receive thread runs until stop.
    // Returns false when the socket cannot be created or connected
    // (VoiceEvent.UdpUnavailable on the live path).
    // Test-only: backdate the crypt last-good clock (see
    // CryptStateOCB2::backdateLastGoodForTest). Never on live paths.
    void backdateLastGoodForTest(uint64_t micros) { m_crypt.backdateLastGoodForTest(micros); }
    void setLastRequestUsForTest(uint64_t v) { m_lastRequestUs.store(v); }

    bool start(const char* host, uint16_t port);
    void stop();

    bool running() const { return m_running.load(std::memory_order_acquire); }
    int socketFd() const { return m_sock.load(std::memory_order_acquire); }

    void setPingSink(PingSink sink) { m_pingSink = std::move(sink); }
    void setResyncSink(ResyncSink sink) { m_resyncSink = std::move(sink); }
    void setUnavailableSink(UnavailableSink sink) { m_unavailableSink = std::move(sink); }
    void setVoiceSink(VoiceSink sink) { m_voiceSink = std::move(sink); }

    // Send seam (item 5): encrypt and sendto on the shared socket. Returns
    // false when down or the packet cannot be framed/encrypted (dropped).
    bool sendDatagram(const uint8_t* plain, size_t length);

private:
    void loop();

    VoiceIo* m_io;
    PolicyCell* m_policy;
    humla::crypto::CryptStateOCB2 m_crypt;
    voice_codec::UdpFormat m_format = voice_codec::UdpFormat::LEGACY;
    uint32_t m_legacyVersion = 0;

    std::atomic<bool> m_running{false};
    // Socket fd shared with the receive thread (loop + sendDatagram).
    // Control writes, receive reads: atomic with release/acquire so stop()
    // never races a live recv/sendto (plain int was a data race).
    std::atomic<int> m_sock{-1};
    std::thread m_thread;

    PingSink m_pingSink;
    ResyncSink m_resyncSink;
    UnavailableSink m_unavailableSink;
    VoiceSink m_voiceSink;
    // Thread hooks run on the receive thread around the loop (start before
    // the first recv, exit after). The JNI layer attaches to the JVM here
    // once per thread lifetime so callbacks can GetEnv safely.
    std::function<void()> m_onStart;
    std::function<void()> m_onExit;

public:
    void setThreadHooks(std::function<void()> onStart, std::function<void()> onExit) {
        m_onStart = std::move(onStart);
        m_onExit = std::move(onExit);
    }

    std::atomic<uint64_t> m_lastRequestUs{0};
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_RECEIVE_H_
