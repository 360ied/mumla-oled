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

import android.content.SharedPreferences;
import android.view.KeyEvent;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the push-to-talk keycode contract: the "no key" sentinel is
 * {@link Settings#DEFAULT_PUSH_KEY} ({@code -1}), and {@code KEYCODE_UNKNOWN}
 * ({@code 0}) or any legacy stored {@code 0} value from a "Reset Key" press
 * must never be treated as a bound PTT key (ODD-07).
 */
public class SettingsPushKeyTest extends TestCase {

    private static class FakeEditor implements SharedPreferences.Editor {
        private final Map<String, Object> mValues;
        private final Map<String, Object> mTemp = new HashMap<>();
        private final Set<String> mRemoved = new java.util.HashSet<>();
        private boolean mCleared = false;

        FakeEditor(Map<String, Object> values) {
            mValues = values;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            mTemp.put(key, values);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            mTemp.remove(key);
            mRemoved.add(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            mTemp.clear();
            mCleared = true;
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            if (mCleared) {
                mValues.clear();
                mCleared = false;
            }
            for (String key : mRemoved) {
                mValues.remove(key);
            }
            mRemoved.clear();
            mValues.putAll(mTemp);
            mTemp.clear();
        }
    }

    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> mValues = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(mValues);
        }

        @Override
        public String getString(String key, String defValue) {
            Object val = mValues.get(key);
            return val instanceof String ? (String) val : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object val = mValues.get(key);
            return val instanceof Set ? (Set<String>) val : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object val = mValues.get(key);
            return val instanceof Integer ? (Integer) val : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object val = mValues.get(key);
            return val instanceof Long ? (Long) val : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object val = mValues.get(key);
            return val instanceof Float ? (Float) val : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object val = mValues.get(key);
            return val instanceof Boolean ? (Boolean) val : defValue;
        }

        @Override
        public boolean contains(String key) {
            return mValues.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor(mValues);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    }

    public void testDefaultPushKeySentinel() {
        assertEquals("No-key sentinel must stay -1", -1, Settings.DEFAULT_PUSH_KEY);
    }

    public void testGetPushToTalkKey_Unconfigured() {
        Settings settings = Settings.createForTesting(new FakeSharedPreferences());
        assertEquals("Unconfigured preference must resolve to -1", -1, settings.getPushToTalkKey());
    }

    public void testGetPushToTalkKey_BoundKeyRoundTrip() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        prefs.edit().putInt(Settings.PREF_PUSH_KEY, KeyEvent.KEYCODE_VOLUME_UP).commit();
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, settings.getPushToTalkKey());
    }

    public void testGetPushToTalkKey_LegacyZeroReset_StaysStored() {
        // A client version before ODD-07 may have persisted 0 via the old Reset
        // path. The stored value is preserved (no migration), but
        // Settings.isPttKeyBound treats it as unbound, and the dialog
        // normalizes it to -1 on load so storage converges on next edit.
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        prefs.edit().putInt(Settings.PREF_PUSH_KEY, 0).commit();
        assertEquals("Legacy stored 0 must read back as stored", 0, settings.getPushToTalkKey());
    }

    public void testResetSentinelRoundTrip() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        prefs.edit().putInt(Settings.PREF_PUSH_KEY, Settings.DEFAULT_PUSH_KEY).commit();
        assertEquals("Reset path must persist the -1 sentinel",
                Settings.DEFAULT_PUSH_KEY, settings.getPushToTalkKey());
    }

    /** Regression check for the ODD-07 activity guard semantics, via production code. */
    public void testPttGuardSemantics_AgainstLegacyZeroAndUnknown() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);

        for (int keyCode : new int[] {0 /* KEYCODE_UNKNOWN */, Settings.DEFAULT_PUSH_KEY}) {
            prefs.edit().putInt(Settings.PREF_PUSH_KEY, keyCode).commit();
            int pttKey = settings.getPushToTalkKey();
            assertFalse("KEYCODE_UNKNOWN/sentinel must never match PTT",
                    Settings.isPttKeyBound(pttKey, keyCode));
        }

        // A real key must still match, and only its exact code.
        prefs.edit().putInt(Settings.PREF_PUSH_KEY, KeyEvent.KEYCODE_VOLUME_UP).commit();
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
