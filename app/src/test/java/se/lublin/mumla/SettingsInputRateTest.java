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

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;

import junit.framework.TestCase;

/**
 * Verifies the input-sample-rate parse contract (ODD-27):
 * {@link Settings#getInputSampleRate()} must mirror the
 * {@link Settings#getFramesPerPacket()} fallback and never throw
 * {@link NumberFormatException} on a corrupted {@code PREF_INPUT_RATE}
 * string, since the connect path reads it on every connect.
 */
public class SettingsInputRateTest extends TestCase {

    public void testDefaultRateWhenUnset() {
        Settings settings = Settings.createForTesting(TestDataStores.create());
        assertEquals("Unset rate must resolve to the 48 kHz default", 48000,
                settings.getInputSampleRate());
    }

    public void testValidRateStringRoundTrip() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_INPUT_RATE), "22050");
        assertEquals("Valid rate string must parse through", 22050,
                settings.getInputSampleRate());
    }

    public void testCorruptedRateStringFallsBack() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_INPUT_RATE), "fast");
        assertEquals("Garbage rate string must fall back, not throw", 48000,
                settings.getInputSampleRate());
    }

    public void testEmptyRateStringFallsBack() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_INPUT_RATE), "");
        assertEquals("Empty rate string must fall back, not throw", 48000,
                settings.getInputSampleRate());
    }

    public void testOverflowingRateStringFallsBack() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_INPUT_RATE), "9999999999");
        assertEquals("Out-of-int-range rate must fall back, not throw", 48000,
                settings.getInputSampleRate());
    }

    public void testWhitespacePaddedRateStringFallsBack() {
        // parseInt does not trim: padded input falls back rather than passing.
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_INPUT_RATE), " 48000 ");
        assertEquals("Padded rate string must fall back, not throw", 48000,
                settings.getInputSampleRate());
    }
}
