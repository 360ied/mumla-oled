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
 * 2nd-order Direct Form II Transposed Biquad Filter.
 *
 * Configured as a Butterworth High-Pass Filter (Q = 1/sqrt(2) = 0.70710678)
 * to attenuate infrasonic wind buffeting, mechanical vibration, and vehicle
 * chassis rumble (<90 Hz) prior to neural denoising and VAD energy measurement.
 */
public final class BiquadFilter {
    public static final float DEFAULT_SAMPLE_RATE = 48000.0f;
    public static final float DEFAULT_CUTOFF_FREQ = 90.0f;
    public static final float DEFAULT_Q = 0.70710678f;

    private float mB0 = 1.0f;
    private float mB1 = 0.0f;
    private float mB2 = 0.0f;
    private float mA1 = 0.0f;
    private float mA2 = 0.0f;
    private float mZ1 = 0.0f;
    private float mZ2 = 0.0f;

    public BiquadFilter() {
        this(DEFAULT_SAMPLE_RATE, DEFAULT_CUTOFF_FREQ, DEFAULT_Q);
    }

    public BiquadFilter(float sampleRate, float cutoffFreq, float q) {
        configureHighPass(sampleRate, cutoffFreq, q);
    }

    public final void configureHighPass(float sampleRate, float cutoffFreq) {
        configureHighPass(sampleRate, cutoffFreq, DEFAULT_Q);
    }

    public final void configureHighPass(float sampleRate, float cutoffFreq, float q) {
        if (sampleRate <= 0.0f || cutoffFreq <= 0.0f || q <= 0.0f) {
            setPassThrough();
            return;
        }

        float nyquist = sampleRate * 0.499f;
        float fc = Math.min(cutoffFreq, nyquist);

        double w0 = 2.0 * Math.PI * (fc / (double) sampleRate);
        double cosw0 = Math.cos(w0);
        double sinw0 = Math.sin(w0);
        double alpha = sinw0 / (2.0 * (double) q);

        double b0 = (1.0 + cosw0) / 2.0;
        double b1 = -(1.0 + cosw0);
        double b2 = (1.0 + cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;

        mB0 = (float) (b0 / a0);
        mB1 = (float) (b1 / a0);
        mB2 = (float) (b2 / a0);
        mA1 = (float) (a1 / a0);
        mA2 = (float) (a2 / a0);

        reset();
    }

    public final void setPassThrough() {
        mB0 = 1.0f;
        mB1 = 0.0f;
        mB2 = 0.0f;
        mA1 = 0.0f;
        mA2 = 0.0f;
        reset();
    }

    public void process(short[] pcm, int offset, int length) {
        if (pcm == null || length <= 0) {
            return;
        }

        float z1 = mZ1;
        float z2 = mZ2;
        final float b0 = mB0;
        final float b1 = mB1;
        final float b2 = mB2;
        final float a1 = mA1;
        final float a2 = mA2;

        int end = offset + length;
        for (int i = offset; i < end; i++) {
            float x = (float) pcm[i];
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;

            y = Math.max(-32768.0f, Math.min(y, 32767.0f));
            pcm[i] = (short) y;
        }

        mZ1 = z1;
        mZ2 = z2;
    }

    public void reset() {
        mZ1 = 0.0f;
        mZ2 = 0.0f;
    }

    public float getB0() { return mB0; }
    public float getB1() { return mB1; }
    public float getB2() { return mB2; }
    public float getA1() { return mA1; }
    public float getA2() { return mA2; }
}
