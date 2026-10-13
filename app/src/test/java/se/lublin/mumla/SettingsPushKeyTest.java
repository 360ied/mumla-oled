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

import android.view.KeyEvent;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;

import junit.framework.TestCase;

/**
 * Verifies the push-to-talk keycode contract: the "no key" sentinel is
 * {@link Settings#DEFAULT_PUSH_KEY} ({@code -1}), and {@code KEYCODE_UNKNOWN}
 * ({@code 0}) or any legacy stored {@code 0} value from a "Reset Key" press
 * must never be treated as a bound PTT key (ODD-07).
 */
public class SettingsPushKeyTest extends TestCase {

    public void testDefaultPushKeySentinel() {
        assertEquals("No-key sentinel must stay -1", -1, Settings.DEFAULT_PUSH_KEY);
    }

    public void testGetPushToTalkKey_Unconfigured() {
        Settings settings = Settings.createForTesting(TestDataStores.create());
        assertEquals("Unconfigured preference must resolve to -1", -1, settings.getPushToTalkKey());
    }

    public void testGetPushToTalkKey_BoundKeyRoundTrip() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.intKey(Settings.PREF_PUSH_KEY), KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, settings.getPushToTalkKey());
    }

    public void testGetPushToTalkKey_LegacyZeroReset_StaysStored() {
        // A client version before ODD-07 may have persisted 0 via the old Reset
        // path. The stored value is preserved (no migration), but
        // Settings.isPttKeyBound treats it as unbound, and the dialog
        // normalizes it to -1 on load so storage converges on next edit.
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.intKey(Settings.PREF_PUSH_KEY), 0);
        assertEquals("Legacy stored 0 must read back as stored", 0, settings.getPushToTalkKey());
    }

    public void testResetSentinelRoundTrip() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);
        TestDataStores.put(store, TestDataStores.intKey(Settings.PREF_PUSH_KEY), Settings.DEFAULT_PUSH_KEY);
        assertEquals("Reset path must persist the -1 sentinel",
                Settings.DEFAULT_PUSH_KEY, settings.getPushToTalkKey());
    }

    /** Regression check for the ODD-07 activity guard semantics, via production code. */
    public void testPttGuardSemantics_AgainstLegacyZeroAndUnknown() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        for (int keyCode : new int[] {0 /* KEYCODE_UNKNOWN */, Settings.DEFAULT_PUSH_KEY}) {
            TestDataStores.put(store, TestDataStores.intKey(Settings.PREF_PUSH_KEY), keyCode);
            int pttKey = settings.getPushToTalkKey();
            assertFalse("KEYCODE_UNKNOWN/sentinel must never match PTT",
                    Settings.isPttKeyBound(pttKey, keyCode));
        }

        // A real key must still match, and only its exact code.
        TestDataStores.put(store, TestDataStores.intKey(Settings.PREF_PUSH_KEY), KeyEvent.KEYCODE_VOLUME_UP);
        int pttKey = settings.getPushToTalkKey();
        assertTrue(Settings.isPttKeyBound(pttKey, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(Settings.isPttKeyBound(pttKey, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse("Unrelated stored key must not match sentinel press",
                Settings.isPttKeyBound(pttKey, Settings.DEFAULT_PUSH_KEY));
        assertFalse("Sentinel store must not match unknown press",
                Settings.isPttKeyBound(Settings.DEFAULT_PUSH_KEY, 0));
        assertFalse("Legacy zero store must not match real key press",
                Settings.isPttKeyBound(0, KeyEvent.KEYCODE_VOLUME_UP));
    }
}
