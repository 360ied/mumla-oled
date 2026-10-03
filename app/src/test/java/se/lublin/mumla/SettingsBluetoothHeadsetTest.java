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

package se.lublin.mumla;

import junit.framework.TestCase;

/**
 * Pins the shared {@link Settings} accessor contract behind the unified
 * Bluetooth toggle: the Settings &gt; Audio checkbox and the
 * channel-overflow item both read and write this requested-state
 * preference ({@link Settings#PREF_BLUETOOTH_HEADSET}), never confirmed
 * SCO link state. The overflow checked-state wiring itself lives in
 * ChannelFragment and is not covered here.
 */
public class SettingsBluetoothHeadsetTest extends TestCase {

    public void testPreferenceKey() {
        assertEquals("Both toggles must bind the same preference key",
                "bluetooth_headset", Settings.PREF_BLUETOOTH_HEADSET);
    }

    public void testDefaultOff() {
        assertFalse("Two-way Bluetooth must default to off",
                Settings.DEFAULT_BLUETOOTH_HEADSET);
        Settings settings = Settings.createForTesting(new FakeSharedPreferences());
        assertFalse("Unconfigured preference must resolve to off",
                settings.isBluetoothHeadset());
    }

    public void testRequestedStateRoundTrip() {
        Settings settings = Settings.createForTesting(new FakeSharedPreferences());
        settings.setBluetoothHeadset(true);
        assertTrue("Enabling must persist the request", settings.isBluetoothHeadset());
        settings.setBluetoothHeadset(false);
        assertFalse("Disabling must clear the request", settings.isBluetoothHeadset());
    }
}
