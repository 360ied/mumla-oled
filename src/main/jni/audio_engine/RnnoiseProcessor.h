/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef MUMLA_RNNOISE_PROCESSOR_H_
#define MUMLA_RNNOISE_PROCESSOR_H_

#include <cstddef>
#include <cstdint>
#include <vector>

struct DenoiseState;

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

    explicit RnnoiseProcessor(bool enabled = true);
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

    void reset();

private:
    DenoiseState* m_state;
    bool m_enabled;
    std::vector<float> m_floatIn;
    std::vector<float> m_floatOut;
};

} // namespace audio
} // namespace mumla

#endif // MUMLA_RNNOISE_PROCESSOR_H_
