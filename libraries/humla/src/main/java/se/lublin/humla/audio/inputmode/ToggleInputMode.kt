/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.audio.inputmode

/**
 * Push-To-Talk (PTT) Toggle Input Mode.
 *
 * Modern non-blocking implementation: does not freeze or suspend the audio capture thread,
 * ensuring continuous zero-jitter buffer draining and responsive metering.
 */
class ToggleInputMode : IInputMode {
    @Volatile
    var isTalkingOn: Boolean = false

    fun toggleTalkingOn() {
        isTalkingOn = !isTalkingOn
    }

    override fun shouldTransmit(pcm: ShortArray, length: Int): Boolean {
        return isTalkingOn
    }

    override fun waitForInput() {
        // Non-blocking: no thread freezing
    }
}
