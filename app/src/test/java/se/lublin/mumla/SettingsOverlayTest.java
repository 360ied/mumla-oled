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
import android.view.Gravity;

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

    public void testOverlayCoordinatesPersistence() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();

        // Defaults
        assertEquals(0, prefs.getInt(Settings.PREF_OVERLAY_POS_X, 0));
        assertEquals(0, prefs.getInt(Settings.PREF_OVERLAY_POS_Y, 0));

        // Save position
        prefs.edit()
                .putInt(Settings.PREF_OVERLAY_POS_X, 150)
                .putInt(Settings.PREF_OVERLAY_POS_Y, 300)
                .apply();

        assertEquals(150, prefs.getInt(Settings.PREF_OVERLAY_POS_X, 0));
        assertEquals(300, prefs.getInt(Settings.PREF_OVERLAY_POS_Y, 0));
    }

    public void testRepeatedOverlayToggles() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        for (int i = 0; i < 5; i++) {
            settings.setOverlayShown(true);
            assertTrue(settings.isOverlayShown());
            settings.setOverlayShown(false);
            assertFalse(settings.isOverlayShown());
        }
    }

    public void testOverlayPlacementConstants() {
        assertEquals("overlay_placement", Settings.PREF_OVERLAY_PLACEMENT);
        assertEquals("floating", Settings.DEFAULT_OVERLAY_PLACEMENT);
        assertEquals("floating", Settings.OVERLAY_PLACEMENT_FLOATING);
        assertEquals("topLeft", Settings.OVERLAY_PLACEMENT_TOP_LEFT);
        assertEquals("topRight", Settings.OVERLAY_PLACEMENT_TOP_RIGHT);
        assertEquals("bottomLeft", Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT);
        assertEquals("bottomRight", Settings.OVERLAY_PLACEMENT_BOTTOM_RIGHT);
    }

    public void testOverlayPlacementDefault() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        assertEquals(Settings.OVERLAY_PLACEMENT_FLOATING, settings.getOverlayPlacement());
        assertFalse("Overlay should not be pinned by default", settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
    }

    public void testOverlayPlacementTopLeft() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_TOP_LEFT);
        assertEquals(Settings.OVERLAY_PLACEMENT_TOP_LEFT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
        assertEquals("topLeft", prefs.getString(Settings.PREF_OVERLAY_PLACEMENT, ""));
    }

    public void testOverlayPlacementTopRight() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_TOP_RIGHT);
        assertEquals(Settings.OVERLAY_PLACEMENT_TOP_RIGHT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.RIGHT, settings.getOverlayGravity());
        assertEquals("topRight", prefs.getString(Settings.PREF_OVERLAY_PLACEMENT, ""));
    }

    public void testOverlayPlacementBottomLeft() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT);
        assertEquals(Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.BOTTOM | Gravity.LEFT, settings.getOverlayGravity());
        assertEquals("bottomLeft", prefs.getString(Settings.PREF_OVERLAY_PLACEMENT, ""));
    }

    public void testOverlayPlacementBottomRight() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_BOTTOM_RIGHT);
        assertEquals(Settings.OVERLAY_PLACEMENT_BOTTOM_RIGHT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.BOTTOM | Gravity.RIGHT, settings.getOverlayGravity());
        assertEquals("bottomRight", prefs.getString(Settings.PREF_OVERLAY_PLACEMENT, ""));
    }

    public void testOverlayPlacementResetToFloating() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_TOP_RIGHT);
        assertTrue(settings.isOverlayPinned());

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_FLOATING);
        assertEquals(Settings.OVERLAY_PLACEMENT_FLOATING, settings.getOverlayPlacement());
        assertFalse(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
        assertEquals("floating", prefs.getString(Settings.PREF_OVERLAY_PLACEMENT, ""));
    }

    public void testOverlayPlacementUnknownOrCorruptedValue() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_OVERLAY_PLACEMENT, "corruptedCorner").apply();
        assertEquals("corruptedCorner", settings.getOverlayPlacement());
        assertFalse("Unrecognized placement must not be treated as pinned", settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());

        prefs.edit().putString(Settings.PREF_OVERLAY_PLACEMENT, "").apply();
        assertFalse("Empty placement must not be treated as pinned", settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
    }

    public void testOverlayPlacementNullSafety() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_OVERLAY_PLACEMENT, null).apply();
        assertEquals(Settings.DEFAULT_OVERLAY_PLACEMENT, settings.getOverlayPlacement());
        assertFalse(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
    }

    public void testOverlayPositionPersistence() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        assertEquals(100, settings.getOverlayPosX(100));
        assertEquals(200, settings.getOverlayPosY(200));

        settings.setOverlayPosition(350, 720);
        assertEquals(350, settings.getOverlayPosX(0));
        assertEquals(720, settings.getOverlayPosY(0));
        assertEquals(350, prefs.getInt(Settings.PREF_OVERLAY_POS_X, 0));
        assertEquals(720, prefs.getInt(Settings.PREF_OVERLAY_POS_Y, 0));
    }
}
