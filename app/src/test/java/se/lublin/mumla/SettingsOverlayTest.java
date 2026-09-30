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

import android.view.Gravity;

import junit.framework.TestCase;

public class SettingsOverlayTest extends TestCase {

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

    public void testHotCornerGravityDefault() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        assertFalse("Hot corner must be disabled by default", settings.isHotCornerEnabled());
        assertEquals(0, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityTopLeft() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_TOP_LEFT).apply();
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.LEFT | Gravity.TOP, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityTopRight() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_TOP_RIGHT).apply();
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.RIGHT | Gravity.TOP, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityBottomLeft() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_BOTTOM_LEFT).apply();
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.LEFT | Gravity.BOTTOM, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityBottomRight() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_BOTTOM_RIGHT).apply();
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.RIGHT | Gravity.BOTTOM, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityCorruptedValue() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_HOT_CORNER_KEY, "sideways").apply();
        assertFalse("Corrupted hot-corner value must not enable the hot corner",
                settings.isHotCornerEnabled());
        assertEquals(0, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityNullSafety() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = new Settings(prefs);

        prefs.edit().putString(Settings.PREF_HOT_CORNER_KEY, null).apply();
        assertFalse(settings.isHotCornerEnabled());
        assertEquals(0, settings.getHotCornerGravity());
    }
}
