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
