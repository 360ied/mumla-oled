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

#ifndef MUMLA_RNNOISE_PROCESSOR_H_
#define MUMLA_RNNOISE_PROCESSOR_H_

#include <cstddef>
#include <cstdint>
#include <vector>

struct DenoiseState;
struct RNNModel;

namespace mumla {
namespace audio {

/**
 * RNNoise Neural Noise Suppression Processor.
 *
 * Integrates RNNoise recurrent neural network (GRU) denoiser running natively
 * at 48kHz with 480-sample (10ms) quanta. Returns speech probability.
 */
class RnnoiseProcessor {
public:
    static constexpr size_t FRAME_SIZE = 480; // 10ms @ 48kHz

    explicit RnnoiseProcessor(bool enabled = true, const uint8_t* modelData = nullptr, size_t modelSize = 0);
    ~RnnoiseProcessor();

    // Non-copyable
    RnnoiseProcessor(const RnnoiseProcessor&) = delete;
    RnnoiseProcessor& operator=(const RnnoiseProcessor&) = delete;

    /**
     * Processes a 480-sample frame.
     *
     * @param inPcm Input 16-bit PCM samples.
     * @param outPcm Output 16-bit PCM buffer (size >= 480).
     * @param sampleCount Number of samples (expected 480).
     * @return Neural speech probability in range [0.0, 1.0], or -1.0f if disabled.
     */
    float process(const int16_t* inPcm, int16_t* outPcm, size_t sampleCount);

    void setEnabled(bool enabled);
    bool isEnabled() const { return m_enabled; }

    void setModel(const uint8_t* modelData, size_t modelSize);
    bool hasModel() const { return m_modelData != nullptr && m_modelSize > 0; }

    void reset();

private:
    void initModel();
    void cleanupModel();

    DenoiseState* m_state;
    RNNModel* m_model;
    const uint8_t* m_modelData;
    size_t m_modelSize;
    bool m_enabled;
    std::vector<float> m_floatIn;
    std::vector<float> m_floatOut;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_RNNOISE_PROCESSOR_H_
