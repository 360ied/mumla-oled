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
