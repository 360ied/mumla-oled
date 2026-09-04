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
 * Dual-Threshold Hysteresis Voice Activity Detector.
 *
 * Implements activation ($VAD_{\max}$) and deactivation ($VAD_{\min}$) thresholds
 * with a configurable hold hangover timer.
 */
public class HysteresisVad {
    public static final float DEFAULT_VAD_MAX = 0.35f;
    public static final float DEFAULT_VAD_MIN = 0.25f;
    public static final int DEFAULT_HOLD_FRAMES = 25; // 250ms @ 10ms/frame
    public static final float DEFAULT_SQUELCH_MIN_DB = -65.0f; // Squelch noise floor in dBFS

    private float mVadMax;
    private float mVadMin;
    private int mHoldFrames;
    private int mCurrentHold;
    private boolean mSpeaking;
    private float mPeakEnergy;
    private float mLastSpeechProb;
    private float mSquelchMinDb;

    public HysteresisVad() {
        this(DEFAULT_VAD_MAX, DEFAULT_VAD_MIN, DEFAULT_HOLD_FRAMES, DEFAULT_SQUELCH_MIN_DB);
    }

    public HysteresisVad(float vadMax, float vadMin, int holdFrames) {
        this(vadMax, vadMin, holdFrames, DEFAULT_SQUELCH_MIN_DB);
    }

    public HysteresisVad(float vadMax, float vadMin, int holdFrames, float squelchMinDb) {
        mVadMax = vadMax;
        mVadMin = vadMin;
        mHoldFrames = holdFrames;
        mCurrentHold = 0;
        mSpeaking = false;
        mPeakEnergy = 0.0f;
        mLastSpeechProb = 0.0f;
        mSquelchMinDb = squelchMinDb;
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

        // 2. Determine activation score (Pure Neural Probability with Hard Squelch Gate)
        float score = 0.0f;
        if (peakDb >= mSquelchMinDb) {
            if (neuralSpeechProb >= 0.0f) {
                score = neuralSpeechProb;
            } else {
                score = mPeakEnergy;
            }
        } else {
            score = 0.0f;
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

    public synchronized void setSquelchMinDb(float squelchMinDb) {
        mSquelchMinDb = squelchMinDb;
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

    public synchronized float getVadMax() {
        return mVadMax;
    }

    public synchronized float getVadMin() {
        return mVadMin;
    }

    public synchronized float getSquelchMinDb() {
        return mSquelchMinDb;
    }
}
