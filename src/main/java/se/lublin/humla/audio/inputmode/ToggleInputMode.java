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

/**
 * Push-To-Talk (PTT) Toggle Input Mode.
 *
 * Modern non-blocking implementation: does not freeze or suspend the audio capture thread,
 * ensuring continuous zero-jitter buffer draining and responsive metering.
 */
public class ToggleInputMode implements IInputMode {
    private volatile boolean mInputOn;

    public ToggleInputMode() {
        mInputOn = false;
    }

    public void toggleTalkingOn() {
        setTalkingOn(!mInputOn);
    }

    public boolean isTalkingOn() {
        return mInputOn;
    }

    public void setTalkingOn(boolean talking) {
        mInputOn = talking;
    }

    @Override
    public boolean shouldTransmit(short[] pcm, int length) {
        return mInputOn;
    }

    @Override
    public void waitForInput() {
        // Non-blocking: no thread freezing
    }
}
