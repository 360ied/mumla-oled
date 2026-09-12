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

public class SettingsNotificationStyleTest extends TestCase {

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

    public static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> mValues;

        public FakeSharedPreferences(Map<String, Object> values) {
            mValues = values;
        }

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(mValues);
        }

        @Override
        public String getString(String key, String defValue) {
            Object val = mValues.get(key);
            return val instanceof String ? (String) val : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
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
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }

    private Map<String, Object> mPrefsMap;
    private Settings mSettings;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPrefsMap = new HashMap<>();
        mSettings = new Settings(new FakeSharedPreferences(mPrefsMap));
    }

    public void testDefaultNotificationStyleIsBigText() {
        assertEquals("Default notification style must be 'bigtext'",
                Settings.NOTIFICATION_STYLE_BIGTEXT,
                mSettings.getNotificationStyle());
        assertTrue("isNotificationStyleBigText must return true by default",
                mSettings.isNotificationStyleBigText());
    }

    public void testSetNotificationStyleMedia() {
        mSettings.setNotificationStyle(Settings.NOTIFICATION_STYLE_MEDIA);
        assertEquals("Notification style must be 'media' after being set",
                Settings.NOTIFICATION_STYLE_MEDIA,
                mSettings.getNotificationStyle());
        assertFalse("isNotificationStyleBigText must return false when set to 'media'",
                mSettings.isNotificationStyleBigText());
    }

    public void testSetNotificationStyleBigText() {
        mSettings.setNotificationStyle(Settings.NOTIFICATION_STYLE_MEDIA);
        mSettings.setNotificationStyle(Settings.NOTIFICATION_STYLE_BIGTEXT);
        assertEquals("Notification style must be 'bigtext' after switching back",
                Settings.NOTIFICATION_STYLE_BIGTEXT,
                mSettings.getNotificationStyle());
        assertTrue("isNotificationStyleBigText must return true when switched to 'bigtext'",
                mSettings.isNotificationStyleBigText());
    }

    public void testInvalidNotificationStyleFallback() {
        mPrefsMap.put(Settings.PREF_NOTIFICATION_STYLE, "invalid_or_corrupt_value");
        assertEquals("Invalid notification style preference must fall back to default bigtext",
                Settings.DEFAULT_NOTIFICATION_STYLE,
                mSettings.getNotificationStyle());
        assertTrue("Invalid notification style must be treated as bigtext",
                mSettings.isNotificationStyleBigText());
    }

    public void testSetInvalidNotificationStyleThrows() {
        try {
            mSettings.setNotificationStyle("non_existent_style");
            fail("setNotificationStyle should throw IllegalArgumentException for unknown style");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public void testSetNotificationStyleNullThrows() {
        try {
            mSettings.setNotificationStyle(null);
            fail("setNotificationStyle should throw IllegalArgumentException for null");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public void testEmptyNotificationStyleFallback() {
        mPrefsMap.put(Settings.PREF_NOTIFICATION_STYLE, "");
        assertEquals("Empty notification style preference must fall back to default bigtext",
                Settings.DEFAULT_NOTIFICATION_STYLE,
                mSettings.getNotificationStyle());
        assertTrue("Empty notification style must be treated as bigtext",
                mSettings.isNotificationStyleBigText());
    }

    public void testNullNotificationStyleFallback() {
        mPrefsMap.put(Settings.PREF_NOTIFICATION_STYLE, null);
        assertEquals("Null notification style preference must fall back to default bigtext",
                Settings.DEFAULT_NOTIFICATION_STYLE,
                mSettings.getNotificationStyle());
        assertTrue("Null notification style must be treated as bigtext",
                mSettings.isNotificationStyleBigText());
    }
}
