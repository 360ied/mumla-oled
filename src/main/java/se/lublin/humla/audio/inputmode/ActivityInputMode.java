/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.audio.inputmode;

import se.lublin.humla.audio.HysteresisVad;

/**
 * Modern Voice Activity Detection (VAD) Input Mode.
 *
 * Uses dual-threshold hysteresis (activation threshold vadMax, deactivation threshold vadMin)
 * with a 250ms hangover hold timer.
 */
public class ActivityInputMode implements IInputMode {
    private final HysteresisVad mVad;

    public ActivityInputMode(float detectionThreshold) {
        float vadMax = Math.max(0.0f, Math.min(detectionThreshold, 1.0f));
        float vadMin = Math.max(0.0f, vadMax * 0.7f);
        mVad = new HysteresisVad(vadMax, vadMin, HysteresisVad.DEFAULT_HOLD_FRAMES);
    }

    @Override
    public boolean shouldTransmit(short[] pcm, int length) {
        return mVad.process(pcm, 0, length, -1.0f);
    }

    @Override
    public void waitForInput() {
    }

    public void setThreshold(float threshold) {
        float vadMax = Math.max(0.0f, Math.min(threshold, 1.0f));
        float vadMin = Math.max(0.0f, vadMax * 0.7f);
        mVad.setThresholds(vadMax, vadMin);
    }

    public HysteresisVad getVad() {
        return mVad;
    }
}
