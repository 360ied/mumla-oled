/*
 * Copyright (C) 2026 Brian Zhu
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

package se.lublin.humla;

import junit.framework.TestCase;

/**
 * Pins the pure audio-route truth table in {@link HumlaService}.
 * Confirmed SCO forces the voice triple (voice source/mode/usage for
 * platform AEC); otherwise a present media sink (BT A2DP / wired headset)
 * selects the media path, and its absence keeps voice.
 *
 * <p>The full service cannot be constructed on the JVM (it needs a platform
 * {@code Context} and main-looper {@code Handler}; this module is JUnit-only
 * with no Robolectric), so this truth table is the JVM-testable seam for the
 * route matrix, mirroring BluetoothScoManager.shouldRetryBringUp.
 */
public class AudioRoutePolicyTest extends TestCase {

    public void testPhoneAloneStaysVoice() {
        assertTrue("No SCO and no media sink must stay voice",
                HumlaService.computeVoiceRoute(false, false));
    }

    public void testScoForcesVoiceWithoutMediaSink() {
        assertTrue("Confirmed SCO must select voice",
                HumlaService.computeVoiceRoute(true, false));
    }

    public void testScoForcesVoiceOverMediaSink() {
        assertTrue("Confirmed SCO must win over a present media sink",
                HumlaService.computeVoiceRoute(true, true));
    }

    public void testMediaSinkWithoutScoSelectsMedia() {
        assertFalse("A present media sink without SCO must select media",
                HumlaService.computeVoiceRoute(false, true));
    }
}
