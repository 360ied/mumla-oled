/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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
