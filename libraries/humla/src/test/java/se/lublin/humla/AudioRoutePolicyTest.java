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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import se.lublin.humla.route.RouteControl;

/**
 * Pins the pure audio-route truth table in {@link RouteControl}.
 * Confirmed SCO forces the voice triple (voice source/mode/usage for
 * platform AEC); otherwise a present media sink (BT A2DP / wired headset)
 * selects the media path, and its absence keeps voice.
 *
 * <p>The full service cannot be constructed on the JVM (it needs a platform
 * {@code Context} and main-looper {@code Handler}; this module is JUnit-only
 * with no Robolectric), so this truth table is the JVM-testable seam for the
 * route matrix, pinning RouteControl.computeVoiceRoute.
 */
public class AudioRoutePolicyTest {

    @Test
    public void phoneAloneStaysVoice() {
        assertTrue("No SCO and no media sink must stay voice",
                RouteControl.computeVoiceRoute(false, false));
    }

    @Test
    public void scoForcesVoiceWithoutMediaSink() {
        assertTrue("Confirmed SCO must select voice",
                RouteControl.computeVoiceRoute(true, false));
    }

    @Test
    public void scoForcesVoiceOverMediaSink() {
        assertTrue("Confirmed SCO must win over a present media sink",
                RouteControl.computeVoiceRoute(true, true));
    }

    @Test
    public void mediaSinkWithoutScoSelectsMedia() {
        assertFalse("A present media sink without SCO must select media",
                RouteControl.computeVoiceRoute(false, true));
    }
}
