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

#include "VoiceSend.h"

#include <cerrno>
#include <cstring>

#include "VoiceReceive.h"

namespace mumla {
namespace voice_io {

using mumla::audio::AudioInputEngine;
using mumla::audio::InputConfig;
using mumla::audio::InputFrameResult;
using mumla::audio::InputMode;

namespace {

// Maps the settings input-mode int (0 VAD, 1 PTT, 2 continuous) to the
// engine enum. Out-of-range values fall back to voice activity; the JNI
// layer clamps, so this is defense-in-depth, never a live path.
InputMode mapInputMode(int32_t mode) {
    switch (mode) {
        case 1:
            return InputMode::PUSH_TO_TALK;
        case 2:
            return InputMode::CONTINUOUS;
        case 0:
        default:
            return InputMode::VOICE_ACTIVITY;
    }
}

// Frames-per-packet validity mirrors the engine's own rule (1, 2, 4, 6);
// anything else keeps the last good value instead of stalling the realtime
// path on a zero divisor.
bool validFpp(int32_t fpp) {
    return fpp == 1 || fpp == 2 || fpp == 4 || fpp == 6;
}

} // namespace

VoiceSend::VoiceSend(PolicyCell* policy, SettingsCell* settings, VoiceReceive* receive,
                     EncoderFactory encoderFactory, DenoiserFactory denoiserFactory)
    : m_policy(policy), m_settings(settings), m_receive(receive) {
    std::unique_ptr<mumla::audio::IVoiceEncoder> encoder;
    if (encoderFactory) {
        encoder = encoderFactory();
    }
    std::unique_ptr<mumla::audio::IDenoiser> denoiser;
    if (denoiserFactory) {
        denoiser = denoiserFactory();
    }
    m_engine = std::make_unique<AudioInputEngine>(std::move(encoder), std::move(denoiser));
    sem_init(&m_sendSem, 0, 0);
}

VoiceSend::~VoiceSend() {
    stop();
    sem_destroy(&m_sendSem);
}

void VoiceSend::setFormat(voice_codec::UdpFormat format, uint32_t legacyVersion) {
    m_format.store(format == voice_codec::UdpFormat::PROTOBUF ? 1 : 0,
                   std::memory_order_release);
    m_legacyVersion.store(legacyVersion, std::memory_order_release);
}

void VoiceSend::setDenoiserEnabled(bool enabled) {
    if (m_engine) {
        m_engine->setRnnoiseEnabled(enabled);
    }
}

void VoiceSend::setDenoiserModel(const uint8_t* data, size_t size) {
    if (m_engine) {
        m_engine->setRnnoiseModel(data, size);
    }
}

void VoiceSend::clear() {
    OutboundPacket packet;
    while (m_outbound.tryPop(&packet)) {
        m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
    }
    uint64_t ping;
    while (m_pingQueue.tryPop(&ping)) {
    }
    m_carryCount = 0;
    m_selfTalking.store(false, std::memory_order_release);
    if (m_engine) {
        m_engine->reset();
    }
}

void VoiceSend::captureFrame(const int16_t* pcm, size_t samples) {
    if (m_engine == nullptr) {
        return;
    }
    // Lock-free config read: keep the last good copy when the writer is
    // mid-update, so the realtime path never waits.
    PolicySnapshot gate = m_lastGate;
    if (m_policy != nullptr) {
        PolicySnapshot fresh;
        if (m_policy->tryRead(&fresh)) {
            gate = fresh;
            m_lastGate = fresh;
        }
    }
    SettingsSnapshot settings = m_lastSettings;
    if (m_settings != nullptr) {
        SettingsSnapshot fresh;
        if (m_settings->tryRead(&fresh)) {
            settings = fresh;
            m_lastSettings = fresh;
        }
    }
    if (!validFpp(gate.framesPerPacket)) {
        gate.framesPerPacket = m_lastGate.framesPerPacket;
        if (!validFpp(gate.framesPerPacket)) {
            gate.framesPerPacket = 2;
        }
    }

    InputConfig config;
    config.mode = mapInputMode(settings.inputMode);
    config.transmitAllowed = gate.transmitAllowed;
    config.pttPressed = gate.pttPressed;
    config.bitrate = gate.bitrate;
    config.framesPerPacket = gate.framesPerPacket;
    config.amplitudeBoost = settings.amplitudeBoost;
    config.adaptiveLevelerEnabled = settings.adaptiveLevelerEnabled;
    config.vadMax = settings.vadMax;
    config.vadMin = settings.vadMin;
    // Hold frames and squelch floor stay at engine defaults (I/O layer
    // constants, never in VoiceSettings).

    const int formatInt = m_format.load(std::memory_order_acquire);
    const voice_codec::UdpFormat format =
        formatInt == 1 ? voice_codec::UdpFormat::PROTOBUF : voice_codec::UdpFormat::LEGACY;
    const int32_t voiceTarget = gate.voiceTarget;

    // Carry + new PCM in exact 10 ms frames. The carry holds fewer than 480
    // samples by construction, so one pass over the input plus the carry
    // covers every whole frame without allocating.
    const size_t kFrame = AudioInputEngine::SAMPLES_PER_10MS;
    size_t offset = 0;

    // Fill the carry first when a previous call left a tail.
    if (m_carryCount > 0 && pcm != nullptr && samples > 0) {
        const size_t need = kFrame - m_carryCount;
        const size_t take = samples < need ? samples : need;
        std::memcpy(m_carry + m_carryCount, pcm, take * sizeof(int16_t));
        m_carryCount += take;
        offset += take;
        if (m_carryCount == kFrame) {
            InputFrameResult result;
            m_engine->processFrame(m_carry, kFrame, config, &result);
            if (result.talkingChanged) {
                m_selfTalking.store(result.talking, std::memory_order_release);
            }
            for (size_t i = 0; i < result.packetCount; ++i) {
                OutboundPacket out;
                const size_t written = voice_codec::frameVoice(
                    format, result.packets[i],
                    static_cast<uint32_t>(voiceTarget & 0xFF),
                    m_frameScratch, sizeof(m_frameScratch));
                if (written == 0 || written > sizeof(out.data)) {
                    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                    continue;
                }
                std::memcpy(out.data, m_frameScratch, written);
                out.length = written;
                out.frames = static_cast<uint8_t>(result.packets[i].frames);
                if (!m_outbound.tryPush(out)) {
                    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                    continue;
                }
                sem_post(&m_sendSem);
            }
            m_carryCount = 0;
        }
    }

    auto processOneFrame = [&](const int16_t* frame) {
        InputFrameResult result;
        m_engine->processFrame(frame, kFrame, config, &result);
        if (result.talkingChanged) {
            m_selfTalking.store(result.talking, std::memory_order_release);
        }
        for (size_t i = 0; i < result.packetCount; ++i) {
            OutboundPacket out;
            const size_t written = voice_codec::frameVoice(
                format, result.packets[i],
                static_cast<uint32_t>(voiceTarget & 0xFF),
                m_frameScratch, sizeof(m_frameScratch));
            if (written == 0 || written > sizeof(out.data)) {
                m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
            std::memcpy(out.data, m_frameScratch, written);
            out.length = written;
            out.frames = static_cast<uint8_t>(result.packets[i].frames);
            if (!m_outbound.tryPush(out)) {
                m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
            sem_post(&m_sendSem);
        }
    };

    if (pcm != nullptr) {
        while (offset + kFrame <= samples) {
            processOneFrame(pcm + offset);
            offset += kFrame;
        }
        // Tail becomes the new carry (fewer than 480 samples by construction).
        const size_t tail = samples - offset;
        if (tail > 0) {
            std::memcpy(m_carry, pcm + offset, tail * sizeof(int16_t));
            m_carryCount = tail;
        }
    }
}

bool VoiceSend::enqueuePing(uint64_t timestampMicros) {
    if (m_pingQueue.tryPush(timestampMicros)) {
        sem_post(&m_sendSem);
        return true;
    }
    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
    return false;
}

void VoiceSend::start() {
    if (m_running.exchange(true, std::memory_order_acq_rel)) {
        return;
    }
    m_thread = std::thread(&VoiceSend::loop, this);
}

void VoiceSend::stop() {
    bool expected = true;
    if (!m_running.compare_exchange_strong(expected, false, std::memory_order_acq_rel,
                                           std::memory_order_relaxed)) {
        if (m_thread.joinable()) {
            m_thread.join();
        }
        return;
    }
    sem_post(&m_sendSem);
    if (m_thread.joinable()) {
        m_thread.join();
    }
}

void VoiceSend::loop() {
    if (m_onStart) {
        m_onStart();
    }
    while (true) {
        int r;
        do {
            r = sem_wait(&m_sendSem);
        } while (r != 0 && errno == EINTR);
        if (!m_running.load(std::memory_order_acquire)) {
            break;
        }
        // Pings first: keepalive must not wait behind a voice backlog.
        uint64_t pingTs;
        bool hadPing = false;
        while (m_pingQueue.tryPop(&pingTs)) {
            hadPing = true;
            const int formatInt = m_format.load(std::memory_order_acquire);
            const voice_codec::UdpFormat format =
                formatInt == 1 ? voice_codec::UdpFormat::PROTOBUF
                               : voice_codec::UdpFormat::LEGACY;
            uint8_t framed[32];
            const size_t framedLen = voice_codec::framePing(
                format, pingTs, framed, sizeof(framed));
            if (framedLen == 0) {
                m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
            // Pings are UDP-only (the transport measures UDP health with
            // them); when tunnelled or down, there is nothing to send.
            if (!m_useUdp.load(std::memory_order_acquire)) {
                continue;
            }
            if (m_receive != nullptr) {
                // Reuse the shared socket/crypt path: encrypt + sendto on
                // the receive-owned socket, same as the interim seam.
                // sendDatagram checks running/socket internally.
                if (!m_receive->sendDatagram(framed, framedLen)) {
                    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                }
            }
        }
        (void)hadPing;

        // Drop-oldest by audio age (spec: skip every queued packet older than
        // 200 ms of audio). Drain the ring, keep the newest suffix whose
        // older packets are all within 200 ms (age = sum of newer durations,
        // each packet carrying the FPP it was framed with), drop the older
        // prefix and count it. Non-realtime here, may retry the seqlock and
        // may use a stack scratch: no realtime constraints on this thread.
        // The scratch is fixed-size (40 slots, the ring capacity); the ring
        // itself stays the only allocation-free queue — this drain buffer
        // never leaves the send thread.
        OutboundPacket staged[kOutboundRingCapacity];
        size_t stagedCount = 0;
        {
            OutboundPacket popped;
            while (m_outbound.tryPop(&popped)) {
                if (stagedCount < kOutboundRingCapacity) {
                    staged[stagedCount++] = popped;
                } else {
                    // Should not happen (ring capacity bounds it), but drop
                    // and count rather than overrun the scratch.
                    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                }
            }
        }
        size_t keepFrom = stagedCount; // index of oldest kept (drain all if empty)
        {
            int newerMs = 0;
            // Newest-first: age 0 always kept; older kept while its age
            // (sum of newer durations) is within 200 ms.
            for (size_t i = stagedCount; i > 0; --i) {
                if (newerMs > kOutboundKeepMs) {
                    break;
                }
                keepFrom = i - 1;
                int frames = staged[i - 1].frames;
                if (frames < 1 || frames > 6) {
                    frames = 2; // defensive: valid FPP is 1/2/4/6; never 0 live
                }
                newerMs += frames * 10;
            }
            // Count the dropped prefix (older than 200 ms).
            m_droppedOutbound.fetch_add(static_cast<uint64_t>(keepFrom),
                                        std::memory_order_relaxed);
        }

        // Send the kept suffix in order (usually one packet per wake while
        // talking; a burst after an idle drain). The tunnel sink is copied
        // once per wake (non-realtime thread), not per packet.
        TunnelSink sink = m_tunnelSink;
        for (size_t i = keepFrom; i < stagedCount; ++i) {
            OutboundPacket packet = staged[i];
            if (packet.length == 0 || packet.length > sizeof(packet.data)) {
                m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
            if (m_useUdp.load(std::memory_order_acquire)) {
                if (m_receive != nullptr) {
                    if (!m_receive->sendDatagram(packet.data, packet.length)) {
                        m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                    }
                } else {
                    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                }
            } else {
                if (sink) {
                    sink(packet.data, packet.length);
                } else {
                    m_droppedOutbound.fetch_add(1, std::memory_order_relaxed);
                }
            }
        }
    }
    if (m_onExit) {
        m_onExit();
    }
}

} // namespace voice_io
} // namespace mumla
