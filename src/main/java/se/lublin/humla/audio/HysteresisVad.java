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
 * Dual-Threshold Hysteresis Voice Activity Detector.
 *
 * Implements activation ($VAD_{\max}$) and deactivation ($VAD_{\min}$) thresholds
 * with a configurable hold hangover timer.
 */
public class HysteresisVad {
    public static final float DEFAULT_VAD_MAX = 0.50f;
    public static final float DEFAULT_VAD_MIN = 0.35f;
    public static final int DEFAULT_HOLD_FRAMES = 25; // 250ms @ 10ms/frame

    private float mVadMax;
    private float mVadMin;
    private int mHoldFrames;
    private int mCurrentHold;
    private boolean mSpeaking;
    private float mPeakEnergy;
    private float mLastSpeechProb;

    public HysteresisVad() {
        this(DEFAULT_VAD_MAX, DEFAULT_VAD_MIN, DEFAULT_HOLD_FRAMES);
    }

    public HysteresisVad(float vadMax, float vadMin, int holdFrames) {
        mVadMax = vadMax;
        mVadMin = vadMin;
        mHoldFrames = holdFrames;
        mCurrentHold = 0;
        mSpeaking = false;
        mPeakEnergy = 0.0f;
        mLastSpeechProb = 0.0f;
    }

    public synchronized boolean process(short[] pcm, int offset, int length, float neuralSpeechProb) {
        if (pcm == null || length <= 0) {
            return false;
        }

        // 1. Calculate RMS energy
        double sum = 1.0;
        for (int i = offset; i < offset + length; i++) {
            double s = pcm[i];
            sum += s * s;
        }
        double micLevel = Math.sqrt(sum / (double) length);
        float peakDb = (float) (20.0 * Math.log10(micLevel / 32768.0));
        peakDb = Math.max(peakDb, -96.0f);
        mPeakEnergy = Math.max(0.0f, Math.min(1.0f + (peakDb / 96.0f), 1.0f));

        mLastSpeechProb = neuralSpeechProb;

        // 2. Score calculation
        float score;
        if (neuralSpeechProb >= 0.0f) {
            score = (0.7f * neuralSpeechProb) + (0.3f * mPeakEnergy);
        } else {
            score = mPeakEnergy;
        }

        // 3. Hysteresis state machine
        boolean detected;
        if (mSpeaking) {
            if (score >= mVadMin) {
                detected = true;
                mCurrentHold = mHoldFrames;
            } else if (mCurrentHold > 0) {
                detected = true;
                mCurrentHold--;
            } else {
                detected = false;
            }
        } else {
            if (score >= mVadMax) {
                detected = true;
                mCurrentHold = mHoldFrames;
            } else {
                detected = false;
            }
        }

        mSpeaking = detected;
        return mSpeaking;
    }

    public synchronized void setThresholds(float vadMax, float vadMin) {
        mVadMax = Math.max(0.0f, Math.min(vadMax, 1.0f));
        mVadMin = Math.max(0.0f, Math.min(vadMin, mVadMax));
    }

    public synchronized void setHoldFrames(int holdFrames) {
        mHoldFrames = Math.max(0, holdFrames);
    }

    public synchronized void reset() {
        mSpeaking = false;
        mCurrentHold = 0;
        mPeakEnergy = 0.0f;
        mLastSpeechProb = 0.0f;
    }

    public synchronized boolean isSpeaking() {
        return mSpeaking;
    }

    public synchronized float getPeakEnergy() {
        return mPeakEnergy;
    }

    public synchronized float getLastSpeechProb() {
        return mLastSpeechProb;
    }
}
