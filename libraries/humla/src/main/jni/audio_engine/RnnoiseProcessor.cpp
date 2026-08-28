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

#include "RnnoiseProcessor.h"
#include <rnnoise.h>

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// Completes the opaque RNNModel handle declared in rnnoise.h. This mirrors the
// exact layout of `struct RNNModel` in rnnoise/src/denoise.c (submodule pinned
// at v0.2-14-gd983458; keep in sync if the pin ever moves).
//
// We cannot use rnnoise_model_from_buffer(): it never initializes the FILE*
// member, so rnnoise_model_free() later calls fclose() on uninitialized heap
// garbage. Depending on what the recycled allocation contains, fclose() either
// blocks forever on a bogus internal lock (UI freeze) or segfaults inside
// __FILE_close(). Observed as a consistent freeze/crash on disconnect, when
// NativeAudioInputEngine.nativeDestroy() tears the engine down on the main
// thread. Constructing the handle ourselves with every field initialized makes
// rnnoise_model_free() safe.
struct RNNModel {
    const void *const_blob;
    void *blob;
    int blob_len;
    FILE *file;
};

namespace mumla {
namespace audio {

static RNNModel *ModelFromBuffer(const uint8_t *data, size_t size) {
    if (data == nullptr || size == 0 || size > static_cast<size_t>(INT_MAX)) {
        return nullptr;
    }
    RNNModel *model = static_cast<RNNModel *>(malloc(sizeof(*model)));
    if (model == nullptr) {
        return nullptr;
    }
    model->const_blob = data;
    model->blob = nullptr;
    model->blob_len = static_cast<int>(size);
    model->file = nullptr;
    return model;
}

RnnoiseProcessor::RnnoiseProcessor(bool enabled, const uint8_t* modelData, size_t modelSize)
    : m_state(nullptr),
      m_model(nullptr),
      m_modelData(modelData),
      m_modelSize(modelSize),
      m_enabled(enabled),
      m_floatIn(FRAME_SIZE, 0.0f),
      m_floatOut(FRAME_SIZE, 0.0f) {
    if (m_enabled) {
        initModel();
    }
}

RnnoiseProcessor::~RnnoiseProcessor() {
    cleanupModel();
}

void RnnoiseProcessor::initModel() {
    if (m_state != nullptr) return;
    if (m_modelData != nullptr && m_modelSize > 0) {
        if (m_model == nullptr) {
            m_model = ModelFromBuffer(m_modelData, m_modelSize);
        }
        if (m_model != nullptr) {
            m_state = rnnoise_create(m_model);
        }
    }
}

void RnnoiseProcessor::cleanupModel() {
    if (m_state != nullptr) {
        rnnoise_destroy(m_state);
        m_state = nullptr;
    }
    if (m_model != nullptr) {
        rnnoise_model_free(m_model);
        m_model = nullptr;
    }
}

void RnnoiseProcessor::setModel(const uint8_t* modelData, size_t modelSize) {
    cleanupModel();
    m_modelData = modelData;
    m_modelSize = modelSize;
    if (m_enabled) {
        initModel();
    }
}

void RnnoiseProcessor::setEnabled(bool enabled) {
    if (m_enabled == enabled) {
        return;
    }
    m_enabled = enabled;
    if (m_enabled) {
        initModel();
    } else {
        cleanupModel();
    }
}

void RnnoiseProcessor::reset() {
    if (m_state != nullptr) {
        rnnoise_destroy(m_state);
        m_state = nullptr;
        if (m_model != nullptr) {
            m_state = rnnoise_create(m_model);
        }
    }
}

float RnnoiseProcessor::process(const int16_t* inPcm, int16_t* outPcm, size_t sampleCount) {
    if (inPcm == nullptr || outPcm == nullptr || sampleCount == 0) {
        return -1.0f;
    }

    if (!m_enabled || m_state == nullptr || sampleCount != FRAME_SIZE) {
        std::memcpy(outPcm, inPcm, sampleCount * sizeof(int16_t));
        return -1.0f;
    }

    // Convert short PCM -> float
    for (size_t i = 0; i < FRAME_SIZE; ++i) {
        m_floatIn[i] = static_cast<float>(inPcm[i]);
    }

    // Run RNNoise GRU
    float speechProb = rnnoise_process_frame(m_state, m_floatOut.data(), m_floatIn.data());

    // Convert float -> short PCM with clipping
    for (size_t i = 0; i < FRAME_SIZE; ++i) {
        float sample = m_floatOut[i];
        sample = std::max(-32768.0f, std::min(sample, 32767.0f));
        outPcm[i] = static_cast<int16_t>(sample);
    }

    return speechProb;
}

} // namespace audio
} // namespace mumla
