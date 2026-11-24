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

// Step 5b callback bodies: host-compiled so the native suite drives exactly
// the code AAudio's data callbacks run. No AAudio calls, no JNI, no JVM.

#include "VoiceStreamContext.h"

#include <algorithm>
#include <cstring>

#include "VoiceIo.h"
#include "VoiceSend.h"

namespace mumla {
namespace voice_io {

namespace {

bool rangeTouchesNonSilentFrame(uint64_t mask, size_t begin, size_t end) {
    if (begin >= end) {
        return false;
    }
    const size_t first = begin / kStreamFrameSamples;
    const size_t last = (end - 1) / kStreamFrameSamples;
    for (size_t frame = first; frame <= last; ++frame) {
        if (frame < 64 && (mask & (1ULL << frame)) != 0) {
            return true;
        }
    }
    return false;
}

} // namespace

bool renderStreamFrames(VoiceStreamContext* ctx, int16_t* out, size_t samples) {
    if (ctx == nullptr || ctx->io == nullptr || out == nullptr || samples == 0) {
        return true;
    }
    RenderGuard guard(&ctx->renderActive);
    bool silent = true;
    size_t written = 0;
    while (written < samples) {
        if (ctx->renderCarryRemaining == 0) {
            VoiceIo::RenderOut result;
            // One whole 10 ms frame per call: the startup gate counts
            // samples / FRAME_SIZE.
            ctx->io->render(ctx->renderCarry, kStreamChunkSamples, &result);
            ctx->lastDroppedInbound.store(result.droppedInbound,
                                          std::memory_order_relaxed);
            ctx->lastDroppedTunnel.store(result.droppedTunnel,
                                         std::memory_order_relaxed);
            ctx->lastDroppedJitter.store(result.droppedJitter,
                                         std::memory_order_relaxed);
            if (ctx->send != nullptr) {
                ctx->lastDroppedOutbound.store(ctx->send->droppedOutbound(),
                                               std::memory_order_relaxed);
            }
            // VoiceIo::render zero-fills a silent quantum, so copying the
            // carry buffer writes silence too. The callback consults the
            // per-frame report for exactly the samples it copies.
            ctx->renderCarryNonSilentFrames = result.nonSilentFrames;
            ctx->renderCarryOffset = 0;
            ctx->renderCarryRemaining = kStreamChunkSamples;
        }
        const size_t take = std::min(samples - written, ctx->renderCarryRemaining);
        const size_t begin = ctx->renderCarryOffset;
        const size_t end = begin + take;
        if (rangeTouchesNonSilentFrame(ctx->renderCarryNonSilentFrames, begin,
                                       end)) {
            silent = false;
        }
        std::memcpy(out + written, ctx->renderCarry + ctx->renderCarryOffset,
                    take * sizeof(int16_t));
        ctx->renderCarryOffset += take;
        ctx->renderCarryRemaining -= take;
        written += take;
    }
    return silent;
}

void captureStreamFrames(VoiceStreamContext* ctx, const int16_t* pcm,
                         size_t samples) {
    if (ctx == nullptr || ctx->send == nullptr || pcm == nullptr ||
        samples == 0) {
        return;
    }
    CaptureGuard guard(&ctx->captureActive);
    ctx->send->captureFrame(pcm, samples);
    ctx->lastDroppedOutbound.store(ctx->send->droppedOutbound(),
                                   std::memory_order_relaxed);
}

void resetRenderCarry(VoiceStreamContext* ctx) {
    if (ctx == nullptr) {
        return;
    }
    std::memset(ctx->renderCarry, 0, sizeof(ctx->renderCarry));
    ctx->renderCarryOffset = 0;
    ctx->renderCarryRemaining = 0;
    ctx->renderCarryNonSilentFrames = 0;
}

} // namespace voice_io
} // namespace mumla
