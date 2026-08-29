/*
 * Copyright (C) 2026 Mumla OLED Contributors
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

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SettingsOverlayTest extends TestCase {

    private static class FakeEditor implements SharedPreferences.Editor {
        private final Map<String, Object> mValues;
        private final Map<String, Object> mTemp = new HashMap<>();

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
            mValues.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            mTemp.clear();
            mValues.clear();
            return this;
        }

        @Override
        public boolean commit() {
            mValues.putAll(mTemp);
            mTemp.clear();
            return true;
        }

        @Override
        public void apply() {
            commit();
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

    public void testOverlayConstants() {
        assertEquals("overlay_shown", Settings.PREF_OVERLAY_SHOWN);
        assertFalse(Settings.DEFAULT_OVERLAY_SHOWN);
        assertEquals("overlay_hud_pos_x", Settings.PREF_OVERLAY_POS_X);
        assertEquals("overlay_hud_pos_y", Settings.PREF_OVERLAY_POS_Y);
    }

    public void testOverlayDefaultState() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        assertFalse("Overlay must be disabled by default", settings.isOverlayShown());
    }

    public void testSetOverlayShownTrue() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayShown(true);
        assertTrue("Overlay should be enabled after setOverlayShown(true)", settings.isOverlayShown());
        assertEquals(true, prefs.getBoolean(Settings.PREF_OVERLAY_SHOWN, false));
    }

    public void testSetOverlayShownFalse() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayShown(true);
        assertTrue(settings.isOverlayShown());

        settings.setOverlayShown(false);
        assertFalse("Overlay should be disabled after setOverlayShown(false)", settings.isOverlayShown());
        assertEquals(false, prefs.getBoolean(Settings.PREF_OVERLAY_SHOWN, true));
    }
}
