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

#ifndef MUMLA_VOICE_SEND_H_
#define MUMLA_VOICE_SEND_H_

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <semaphore.h>
#include <thread>

#include "AudioInputEngine.h"
#include "VoiceCodec.h"
#include "VoiceConfig.h"
#include "VoicePolicy.h"
#include "VoiceRings.h"

namespace mumla {
namespace voice_io {

class VoiceReceive;

// Outbound queue capacity (step 5a.4): 400 ms of packets at the smallest
// frame size (1 frame per packet, 10 ms each) is 40 packets, the largest
// count any valid FPP needs. At larger FPP the same 40 slots hold more
// than 400 ms, so the drop-newest path below never runs in practice: the
// send thread keeps the queue within its 200 ms drop-oldest bound first.
inline constexpr size_t kOutboundRingCapacity = 40;
// Ping queue capacity: pings are infrequent (UDP keepalive cadence); 4
// slots cover a burst without ever blocking the control thread.
inline constexpr size_t kPingRingCapacity = 4;
// Framed packet ceiling (spec voice.md queues table): one full 1024-byte Opus
// payload (AudioInputEngine::kMaxOpusBufferBytes) plus the 1-byte type and
// varint/protobuf headers stays under 1100; legacy is smaller. Sizing from
// the payload bound instead of the deleted Kotlin bridge's 2048-byte buffer
// keeps 40 ring slots at ~44 KB instead of ~82 KB on the realtime path.
inline constexpr size_t kMaxFramedBytes = 1100;
// 200 ms drop-oldest bound (today's HumlaUDP head-drop queue).
inline constexpr int kOutboundKeepMs = 200;

// One framed datagram payload (unencrypted) plus its audio duration. The
// duration pins the drop-oldest bound when the frames-per-packet changes
// mid-queue: each packet carries the FPP it was framed with.
struct OutboundPacket {
    uint8_t data[kMaxFramedBytes] = {};
    size_t length = 0;
    uint8_t frames = 2;
};

/**
 * Native voice send half (step 5a.4).
 *
 * Owns the input engine, the outbound queue, and the send thread. The
 * capture thread (Kotlin AudioRecord thread in 5a, native in 5b) feeds
 * arbitrary PCM blocks through captureFrame, which carries the remainder
 * into exact 10 ms engine frames, frames each encoded packet, and pushes
 * it to the outbound ring. The send thread waits on a semaphore, drops
 * every queued packet older than 200 ms of audio, then encrypts and sends
 * (UDP) or hands to Kotlin (tunnel).
 *
 * Threading: captureFrame runs on the realtime capture thread (never
 * blocks, never allocates, never calls into Kotlin); the send thread is
 * non-realtime and is the only caller into Kotlin (tunnel sink) plus
 * OCB2 encrypt and sendto; the control thread starts/stops, sets keys
 * through VoiceReceive, and publishes gate/settings/format/route while
 * capture is stopped (Start/Stop ordering) or lock-free (seqlocks).
 * The input engine is single-threaded by contract: only captureFrame
 * touches it while capture runs; control touches it only while stopped.
 *
 * Allocation: everything is preallocated at construction/Start. The
 * realtime path (captureFrame) does no allocation: ring slots are fixed,
 * framing writes into the slot, the engine writes into its own buffers.
 */
class VoiceSend {
public:
    using EncoderFactory = std::function<std::unique_ptr<mumla::audio::IVoiceEncoder>()>;
    using DenoiserFactory = std::function<std::unique_ptr<mumla::audio::IDenoiser>()>;
    // Tunnel sink (non-realtime send thread only): hands a framed packet
    // to Kotlin for TCP delivery. Set once on the control thread before
    // start; never called on the realtime path.
    using TunnelSink = std::function<void(const uint8_t* data, size_t length)>;

    VoiceSend(PolicyCell* policy, SettingsCell* settings, VoiceReceive* receive,
              EncoderFactory encoderFactory = nullptr,
              DenoiserFactory denoiserFactory = nullptr);
    ~VoiceSend();

    VoiceSend(const VoiceSend&) = delete;
    VoiceSend& operator=(const VoiceSend&) = delete;

    // Control thread only, while capture is stopped (Start/Stop ordering).
    // Format fixed per session from the transport (issue 17); route follows
    // the transport's SetVoiceRoute; the tunnel sink is set once before start.
    void setFormat(voice_codec::UdpFormat format, uint32_t legacyVersion);
    void setUseUdp(bool udp) { m_useUdp.store(udp, std::memory_order_release); }
    void setTunnelSink(TunnelSink sink) { m_tunnelSink = std::move(sink); }

    // Control plane denoiser (may allocate, never on the capture thread):
    // RNNoise has no allocation-free reset, so enabling or swapping the
    // model recreates its state. Called on the control thread while capture
    // is stopped (Start, or a model-load restart).
    void setDenoiserEnabled(bool enabled);
    void setDenoiserModel(const uint8_t* data, size_t size);

    // Destroys queued state; control thread only while capture and send are
    // stopped. Counts reset-discards as drops (arrived, never sent).
    void clear();

    // Capture entry point (realtime capture thread only). Accepts any block
    // size and feeds the input engine in exact 10 ms frames, carrying the
    // remainder to the next call. Reads gate/settings via seqlock (keeps
    // the last good copy when the writer is mid-update, so it never waits),
    // frames each encoded packet, pushes to the outbound ring (full ring
    // drops the new item and counts it), posts the send semaphore, and
    // stores the self-talking flag on every talk transition. Never attaches
    // to the JVM, never calls into Kotlin, never allocates.
    void captureFrame(const int16_t* pcm, size_t samples);

    // Send thread lifetime (control thread). Start creates the send thread;
    // stop clears running, posts the semaphore, and joins. The socket itself
    // stays owned by VoiceReceive (control stops receive separately).
    // Thread hooks run on the send thread around the loop (start before the
    // first send, exit after). The JNI layer attaches to the JVM here once
    // per thread lifetime so tunnel callbacks can GetEnv safely.
    void setThreadHooks(std::function<void()> onStart, std::function<void()> onExit) {
        m_onStart = std::move(onStart);
        m_onExit = std::move(onExit);
    }
    void start();
    void stop();
    bool running() const { return m_running.load(std::memory_order_acquire); }

    // Control-thread ping enqueue (VoiceCommand.SendPing): the control
    // thread only enqueues; the send thread frames and sends. False when
    // the ping ring is full (counted, never blocks).
    bool enqueuePing(uint64_t timestampMicros);

    // Self-talking flag (capture writer; render and notifier readers).
    // Atomic, lock-free on all three threads.
    bool selfTalking() const { return m_selfTalking.load(std::memory_order_acquire); }
    std::atomic<bool>* selfTalkingAtomic() { return &m_selfTalking; }

    uint64_t droppedOutbound() const { return m_droppedOutbound.load(std::memory_order_relaxed); }

    // Test seams (host tests drive these without threads or sockets).
    size_t outboundSizeForTest() const { return m_outbound.size(); }
    // Drain one outbound packet without sending (pins drop-oldest counting).
    bool popOutboundForTest(OutboundPacket* out) { return m_outbound.tryPop(out); }

private:
    void loop();

    mumla::audio::AudioInputEngine* engine() { return m_engine.get(); }

    PolicyCell* m_policy;
    SettingsCell* m_settings;
    VoiceReceive* m_receive; // socket + crypt owner (control lifetime)

    std::unique_ptr<mumla::audio::AudioInputEngine> m_engine;

    SpscRing<OutboundPacket, kOutboundRingCapacity> m_outbound;
    SpscRing<uint64_t, kPingRingCapacity> m_pingQueue;
    sem_t m_sendSem;
    std::thread m_thread;
    std::atomic<bool> m_running{false};

    TunnelSink m_tunnelSink;

    // Per-session framing (control writes while capture stopped; capture
    // and send read lock-free).
    std::atomic<int> m_format{0}; // 0 legacy, 1 protobuf
    std::atomic<uint32_t> m_legacyVersion{0};
    std::atomic<bool> m_useUdp{false};

    // Self-talking flag (capture writer; render and notifier readers).
    // Atomic, lock-free on all three threads. The control thread resets it
    // in clear() only while capture is stopped (Stop/Reset ordering), so
    // the reset is a serialized lifecycle handoff, never a concurrent
    // second writer: no capture frame is in flight when it runs.
    std::atomic<bool> m_selfTalking{false};

    std::atomic<uint64_t> m_droppedOutbound{0};
    std::function<void()> m_onStart;
    std::function<void()> m_onExit;

    // Last good gate/settings copies for the realtime path: when the
    // seqlock reports a torn read, capture keeps these instead of waiting.
    PolicySnapshot m_lastGate;
    SettingsSnapshot m_lastSettings;

    // Remainder carry: captureFrame accepts any block size; whole 10 ms
    // frames go to the engine immediately, the tail waits here.
    int16_t m_carry[mumla::audio::AudioInputEngine::SAMPLES_PER_10MS] = {};
    size_t m_carryCount = 0;

    // Scratch for one engine frame result + framing (capture thread only,
    // so no sharing; sized for the largest flush: pre-speech 8 + current).
    uint8_t m_frameScratch[kMaxFramedBytes] = {};
};

} // namespace voice_io
} // namespace mumla

#endif // MUMLA_VOICE_SEND_H_
