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
 * Speech-Gated Adaptive RMS Voice Leveler (Java Reference & Test Implementation).
 *
 * Automatically balances microphone loudness to a nominal conversational target (-18 dBFS)
 * using an Exponential Moving Average (EMA) of speech energy. Adapts only during active
 * speech to eliminate background noise pumping during pauses. Employs a slew-rate limiter
 * and sample-interpolated gain application for click-free transitions, coupled with
 * SoftLimiter saturation protection.
 */
public class AdaptiveLeveler {
    public static final int SAMPLES_PER_10MS = 480; // 10ms @ 48kHz

    // Nominal target RMS level for 16-bit PCM: -18 dBFS relative to 32767 full-scale peak.
    public static final float DEFAULT_TARGET_RMS = 4125.0f;

    // Operational gain boundaries: -12 dB (0.25x) to +12 dB (4.0x).
    public static final float MIN_GAIN = 0.25f;
    public static final float MAX_GAIN = 4.0f;

    // Neural speech probability gate threshold (from RNNoise).
    public static final float SPEECH_PROB_THRESHOLD = 0.30f;

    // Minimum frame RMS energy threshold to prevent adapting on faint ambient room noise.
    public static final float MIN_SPEECH_RMS = 150.0f;

    // Fallback RMS energy threshold for speech gating when RNNoise is disabled (speechProb < 0).
    public static final float ENERGY_GATE_FALLBACK = 400.0f;

    // Smoothing coefficient alpha for ~2.5s time constant over 100 frames/sec (10ms frames).
    public static final float EMA_ALPHA = 0.004f;

    // Maximum gain adjustment per 10ms frame (~0.05 dB/frame, ~5 dB/s) for click-free transitions.
    public static final float MAX_GAIN_SLEW_PER_FRAME = 0.006f;

    private boolean mEnabled;
    private float mTargetRms;
    private float mSmoothedRms;
    private float mCurrentGain;
    private float mTargetGain;

    public AdaptiveLeveler() {
        this(true, DEFAULT_TARGET_RMS);
    }

    public AdaptiveLeveler(boolean enabled, float targetRms) {
        mEnabled = enabled;
        mTargetRms = targetRms > 0.0f ? targetRms : DEFAULT_TARGET_RMS;
        mSmoothedRms = mTargetRms;
        mCurrentGain = 1.0f;
        mTargetGain = 1.0f;
    }

    /**
     * Ingests and processes a 10ms frame in-place with nominal unity amplitude boost.
     *
     * @param pcm Array of 16-bit PCM samples.
     * @param offset Offset into buffer.
     * @param length Number of samples in the frame.
     * @param speechProb Neural speech probability from RNNoise (0.0 - 1.0, or < 0 if unavailable).
     */
    public void process(short[] pcm, int offset, int length, float speechProb) {
        process(pcm, offset, length, speechProb, 1.0f);
    }

    /**
     * Ingests and processes a 10ms frame in-place with single-pass amplitude boost and soft saturation.
     *
     * @param pcm Array of 16-bit PCM samples.
     * @param offset Offset into buffer.
     * @param length Number of samples in the frame.
     * @param speechProb Neural speech probability from RNNoise (0.0 - 1.0, or < 0 if unavailable).
     * @param amplitudeBoost Static user amplitude boost multiplier.
     */
    public void process(short[] pcm, int offset, int length, float speechProb, float amplitudeBoost) {
        if (!mEnabled || pcm == null || length <= 0) {
            return;
        }

        // 1. Calculate frame RMS energy
        double sumSquares = 0.0;
        for (int i = offset; i < offset + length; i++) {
            double s = pcm[i];
            sumSquares += s * s;
        }
        float frameRms = (float) Math.sqrt(sumSquares / length);

        // 2. Speech-Gating: only adapt when speech is active and above the ambient noise floor
        boolean isSpeech = (speechProb >= 0.0f) ? (speechProb >= SPEECH_PROB_THRESHOLD) : (frameRms >= ENERGY_GATE_FALLBACK);

        if (isSpeech && frameRms >= MIN_SPEECH_RMS) {
            // Update long-term smoothed speech RMS via Exponential Moving Average (EMA)
            mSmoothedRms = (1.0f - EMA_ALPHA) * mSmoothedRms + EMA_ALPHA * frameRms;
            if (mSmoothedRms > 1.0f) {
                float rawTarget = mTargetRms / mSmoothedRms;
                mTargetGain = Math.max(MIN_GAIN, Math.min(rawTarget, MAX_GAIN));
            }
        }

        // 3. Slew rate limiter: transition smoothly towards target gain to prevent clicks
        float prevGain = mCurrentGain;
        if (mTargetGain > mCurrentGain) {
            mCurrentGain = Math.min(mCurrentGain + MAX_GAIN_SLEW_PER_FRAME, mTargetGain);
        } else if (mTargetGain < mCurrentGain) {
            mCurrentGain = Math.max(mCurrentGain - MAX_GAIN_SLEW_PER_FRAME, mTargetGain);
        }

        // 4. Unified single-pass sample scaling & SoftLimiter saturation
        float boost = (amplitudeBoost > 0.0f) ? amplitudeBoost : 1.0f;
        if (prevGain == 1.0f && mCurrentGain == 1.0f && boost == 1.0f) {
            return;
        }

        float gainStep = (length > 1) ? (mCurrentGain - prevGain) / (float) (length - 1) : 0.0f;
        float gain = prevGain;

        for (int i = offset; i < offset + length; i++) {
            float effectiveGain = gain * boost;
            pcm[i] = SoftLimiter.processSample(pcm[i], effectiveGain);
            gain += gainStep;
        }
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setTargetRms(float targetRms) {
        if (targetRms > 0.0f) {
            mTargetRms = targetRms;
            mSmoothedRms = targetRms;
            mTargetGain = 1.0f;
            mCurrentGain = 1.0f;
        }
    }

    public float getTargetRms() {
        return mTargetRms;
    }

    public float getCurrentGain() {
        return mCurrentGain;
    }

    public float getSmoothedRms() {
        return mSmoothedRms;
    }

    public void reset() {
        mSmoothedRms = mTargetRms;
        mCurrentGain = 1.0f;
        mTargetGain = 1.0f;
    }
}
