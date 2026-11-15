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

#include "VoiceReceive.h"

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>

#include "VoiceIo.h"

namespace mumla {
namespace voice_io {

namespace {

uint64_t nowUs() {
    using namespace std::chrono;
    return static_cast<uint64_t>(
        duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count());
}

// Today's resync rule (HumlaUDP receiver): 5 s since last good decrypt and
// 5 s since the last request, then exactly one request.
constexpr uint64_t kResyncGoodWindowUs = 5000000;
constexpr uint64_t kResyncRequestGapUs = 5000000;
constexpr size_t kMaxDatagram = 2048;

} // namespace

VoiceReceive::VoiceReceive(VoiceIo* io, PolicyCell* policy)
    : m_io(io), m_policy(policy) {}

VoiceReceive::~VoiceReceive() { stop(); }

void VoiceReceive::setFormat(voice_codec::UdpFormat format, uint32_t legacyVersion) {
    m_format = format;
    m_legacyVersion = legacyVersion;
}

bool VoiceReceive::setKeys(const uint8_t key[16], const uint8_t eiv[16],
                           const uint8_t div[16]) {
    return m_crypt.setKey(key, eiv, div);
}

bool VoiceReceive::setDecryptIv(const uint8_t div[16]) {
    return m_crypt.setDecryptIV(div);
}

void VoiceReceive::clearKeys() {
    m_crypt.clear();
}

void VoiceReceive::getEncryptIv(uint8_t out[16]) const {
    m_crypt.getEncryptIV(out);
}

void VoiceReceive::getStats(int32_t* good, int32_t* late, int32_t* lost,
                            int32_t* resync) const {
    if (good) *good = m_crypt.getGood();
    if (late) *late = m_crypt.getLate();
    if (lost) *lost = m_crypt.getLost();
    if (resync) *resync = m_crypt.getResync();
}

bool VoiceReceive::handlePlaintext(const uint8_t* data, size_t length) {
    if (data == nullptr || length == 0 || length > kMaxDatagram) {
        return false;
    }
    voice_codec::ParsedDatagram parsed;
    if (!voice_codec::parseDatagram(m_format, data, length, m_legacyVersion, &parsed)) {
        return false;
    }
    if (parsed.kind == voice_codec::ParsedDatagram::PING) {
        if (m_pingSink) {
            PingReport report;
            report.timestampMicros = parsed.pingTimestamp;
            report.serverVersionV2 = parsed.serverVersionV2;
            report.maxBandwidth = parsed.maxBandwidthPerUser;
            m_pingSink(report);
        }
        return true;
    }
    if (parsed.kind != voice_codec::ParsedDatagram::VOICE) {
        return true;
    }
    PolicySnapshot snapshot;
    if (m_policy != nullptr) {
        // Seqlock reader: retry while the writer is publishing or a
        // concurrent publish landed mid-copy (voice.md policy handoff).
        // Both callers are non-realtime and the writer holds no locks, so
        // this terminates after at most a few copies.
        while (!m_policy->tryRead(&snapshot)) {
        }
    }
    mumla::audio::InboundPacket survivors[voice_codec::kMaxFramesPerDatagram];
    size_t survivorCount = 0;
    for (size_t i = 0; i < parsed.frameCount; ++i) {
        const auto& frame = parsed.frames[i];
        if (m_policy != nullptr && PolicyCell::shouldDrop(snapshot, frame.session)) {
            continue;
        }
        if (m_io != nullptr) {
            m_io->pushInbound(frame);
        }
        if (survivorCount < voice_codec::kMaxFramesPerDatagram) {
            survivors[survivorCount++] = frame;
        }
    }
    if (m_voiceSink && survivorCount > 0) {
        // Tunnel flag: queueTunnelled runs on the session coroutine (tunnel
        // ring, ordered after removals); the receive loop runs UDP
        // (inbound ring). Distinguishing here keeps one wake path per ring.
        // handlePlaintext is the UDP path; tunnel uses handleTunnelled.
        m_voiceSink(survivors, survivorCount, false);
    }
    return true;
}

bool VoiceReceive::shouldRequestResync(uint64_t nowUs, int64_t lastGoodElapsedUs,
                                        uint64_t lastRequestUs) {
    if (lastGoodElapsedUs < 0) return false;
    if (static_cast<uint64_t>(lastGoodElapsedUs) <= kResyncGoodWindowUs) return false;
    return nowUs - lastRequestUs > kResyncRequestGapUs;
}

bool VoiceReceive::handleCrypted(const uint8_t* data, size_t length) {
    if (data == nullptr || length < 4 || length > kMaxDatagram + 4) return false;
    uint8_t plain[kMaxDatagram + 4];
    if (m_crypt.decrypt(data, plain, static_cast<uint32_t>(length))) {
        return handlePlaintext(plain, length - 4);
    }
    const uint64_t now = nowUs();
    const int64_t lastGood = m_crypt.getLastGoodElapsedUs();
    const uint64_t lastReq = m_lastRequestUs.load(std::memory_order_relaxed);
    if (shouldRequestResync(now, lastGood, lastReq)) {
        m_lastRequestUs.store(now, std::memory_order_relaxed);
        if (m_resyncSink) m_resyncSink();
    }
    return false;
}

bool VoiceReceive::handleTunnelled(const uint8_t* data, size_t length) {
    if (data == nullptr || length == 0 || length > kMaxDatagram) {
        return false;
    }
    voice_codec::ParsedDatagram parsed;
    if (!voice_codec::parseDatagram(m_format, data, length, m_legacyVersion, &parsed)) {
        return false;
    }
    if (parsed.kind == voice_codec::ParsedDatagram::PING) {
        if (m_pingSink) {
            PingReport report;
            report.timestampMicros = parsed.pingTimestamp;
            report.serverVersionV2 = parsed.serverVersionV2;
            report.maxBandwidth = parsed.maxBandwidthPerUser;
            m_pingSink(report);
        }
        return true;
    }
    if (parsed.kind != voice_codec::ParsedDatagram::VOICE) {
        return true;
    }
    PolicySnapshot snapshot;
    if (m_policy != nullptr) {
        // Seqlock reader: retry while the writer is publishing or a
        // concurrent publish landed mid-copy (voice.md policy handoff).
        // Both callers are non-realtime and the writer holds no locks, so
        // this terminates after at most a few copies.
        while (!m_policy->tryRead(&snapshot)) {
        }
    }
    mumla::audio::InboundPacket survivors[voice_codec::kMaxFramesPerDatagram];
    size_t survivorCount = 0;
    for (size_t i = 0; i < parsed.frameCount; ++i) {
        const auto& frame = parsed.frames[i];
        if (m_policy != nullptr && PolicyCell::shouldDrop(snapshot, frame.session)) {
            continue;
        }
        if (m_io != nullptr) {
            m_io->pushTunnel(frame);
        }
        if (survivorCount < voice_codec::kMaxFramesPerDatagram) {
            survivors[survivorCount++] = frame;
        }
    }
    if (m_voiceSink && survivorCount > 0) {
        m_voiceSink(survivors, survivorCount, true);
    }
    return true;
}

bool VoiceReceive::sendDatagram(const uint8_t* plain, size_t length) {
    const int sock = m_sock.load(std::memory_order_acquire);
    if (sock < 0 || !m_running.load(std::memory_order_acquire)) {
        return false;
    }
    if (plain == nullptr || length == 0 || length > kMaxDatagram) {
        return false;
    }
    uint8_t crypted[kMaxDatagram + 4];
    if (!m_crypt.encrypt(plain, crypted, static_cast<uint32_t>(length))) {
        return false;
    }
    const ssize_t sent =
        send(sock, reinterpret_cast<const char*>(crypted), length + 4, 0);
    return sent == static_cast<ssize_t>(length + 4);
}

bool VoiceReceive::start(const char* host, uint16_t port) {
    if (m_running.load(std::memory_order_acquire)) {
        return true;
    }
    // Reclaim a stale fd from an abnormal loop exit without an
    // intervening stop(): control thread only, no receiver running.
    int stale = m_sock.exchange(-1, std::memory_order_acq_rel);
    if (stale >= 0) {
        shutdown(stale, SHUT_RDWR);
        close(stale);
    }
    if (host == nullptr || port == 0) {
        return false;
    }
    struct addrinfo hints = {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    struct addrinfo* list = nullptr;
    char service[16];
    snprintf(service, sizeof(service), "%u", port);
    if (getaddrinfo(host, service, &hints, &list) != 0 || list == nullptr) {
        if (m_unavailableSink) m_unavailableSink();
        return false;
    }
    int sock = -1;
    for (struct addrinfo* ai = list; ai != nullptr; ai = ai->ai_next) {
        sock = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (sock < 0) continue;
        if (connect(sock, ai->ai_addr, ai->ai_addrlen) == 0) break;
        close(sock);
        sock = -1;
    }
    freeaddrinfo(list);
    if (sock < 0) {
        if (m_unavailableSink) m_unavailableSink();
        return false;
    }
    m_sock.store(sock, std::memory_order_release);
    m_running.store(true, std::memory_order_release);
    m_thread = std::thread(&VoiceReceive::loop, this);
    return true;
}

void VoiceReceive::stop() {
    bool expected = true;
    m_running.compare_exchange_strong(expected, false, std::memory_order_acq_rel,
                                        std::memory_order_relaxed);
    // Unblock recv even when already stopped (idempotent shutdown).
    int sock = m_sock.load(std::memory_order_acquire);
    if (sock >= 0) {
        shutdown(sock, SHUT_RDWR);
    }
    if (m_thread.joinable()) {
        m_thread.join();
    }
    // Close after the join so the fd number cannot be recycled while the
    // receiver still holds its copy; exchange makes it idempotent.
    int toClose = m_sock.exchange(-1, std::memory_order_acq_rel);
    if (toClose >= 0) {
        close(toClose);
    }
}

void VoiceReceive::loop() {
    if (m_onStart) m_onStart();
    uint8_t crypted[kMaxDatagram + 4];
    uint8_t plain[kMaxDatagram + 4];
    while (m_running.load(std::memory_order_acquire)) {
        const int sock = m_sock.load(std::memory_order_acquire);
        if (sock < 0) break;
        const ssize_t got = recv(sock, reinterpret_cast<char*>(crypted), sizeof(crypted), 0);
        if (got <= 0) {
            if (!m_running.load(std::memory_order_acquire)) break;
            // Socket error while running: report once; the transport
            // switches to tunnel, as after its 20 s rule.
            if (m_unavailableSink) m_unavailableSink();
            // Abnormal exit: clear liveness (control closes the socket in
            // stop()) so a later start() restarts instead of succeeding
            // on a dead thread.
            m_running.store(false, std::memory_order_release);
            break;
        }
        if (!m_crypt.isValid() || got < 5) {
            continue;
        }
        if (m_crypt.decrypt(crypted, plain, static_cast<uint32_t>(got))) {
            const uint32_t plainLen = static_cast<uint32_t>(got) - 4;
            handlePlaintext(plain, plainLen);
            continue;
        }
        // Decrypt failure: exactly one rate-limited resync request.
        const uint64_t now = nowUs();
        const int64_t lastGood = m_crypt.getLastGoodElapsedUs();
        const uint64_t lastReq = m_lastRequestUs.load(std::memory_order_relaxed);
        if (shouldRequestResync(now, lastGood, lastReq)) {
            m_lastRequestUs.store(now, std::memory_order_relaxed);
            if (m_resyncSink) m_resyncSink();
        }
    }
    if (m_onExit) m_onExit();
}

} // namespace voice_io
} // namespace mumla
