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

package se.lublin.humla.audio;

/**
 * Soft-Knee Saturation Limiter.
 *
 * Applies a smooth C1-continuous hyperbolic tangent saturation curve when boosting
 * audio amplitude to eliminate harsh square-wave digital distortion.
 */
public final class SoftLimiter {
    public static final float MAX_AMPLITUDE = 32767.0f;
    public static final float KNEE_THRESHOLD = (2.0f / 3.0f) * MAX_AMPLITUDE; // 21844.67f
    public static final float HEADROOM = MAX_AMPLITUDE - KNEE_THRESHOLD;      // 10922.33f

    private SoftLimiter() {}

    public static short processSample(short sample, float boostMultiplier) {
        if (boostMultiplier == 1.0f) {
            return sample;
        }

        float val = (float) sample * boostMultiplier;
        float absVal = Math.abs(val);

        if (absVal <= KNEE_THRESHOLD) {
            return (short) val;
        }

        float sign = (val >= 0.0f) ? 1.0f : -1.0f;
        float over = absVal - KNEE_THRESHOLD;
        float saturated = KNEE_THRESHOLD + HEADROOM * (float) Math.tanh(over / HEADROOM);

        return (short) (sign * Math.min(saturated, MAX_AMPLITUDE));
    }

    public static void processBuffer(short[] pcm, int offset, int length, float boostMultiplier) {
        if (pcm == null || length <= 0 || boostMultiplier == 1.0f) {
            return;
        }
        for (int i = offset; i < offset + length; i++) {
            pcm[i] = processSample(pcm[i], boostMultiplier);
        }
    }
}
