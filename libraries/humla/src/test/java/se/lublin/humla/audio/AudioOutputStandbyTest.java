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

package se.lublin.humla.audio;

import junit.framework.TestCase;
import se.lublin.humla.model.User;

/**
 * Unit tests verifying AudioOutput standby timeout and route detection defaults.
 */
public class AudioOutputStandbyTest extends TestCase {

    private static class DummyListener implements AudioOutput.AudioOutputListener {
        @Override
        public void onUserTalkStateUpdated(User user) {}

        @Override
        public User getUser(int session) {
            return null;
        }
    }

    public void testStandbyTimeoutDefaultsWithoutContext() {
        AudioOutput output = new AudioOutput(new DummyListener());
        assertEquals(AudioOutput.STANDBY_TIMEOUT_DEFAULT_MS, output.getStandbyTimeoutMs());
        assertFalse(output.isBluetoothScoActive());
    }

    public void testStandbyTimeoutConstants() {
        assertEquals(3000L, AudioOutput.STANDBY_TIMEOUT_DEFAULT_MS);
        assertEquals(15000L, AudioOutput.STANDBY_TIMEOUT_A2DP_MS);
    }
}
