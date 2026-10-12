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

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;

import junit.framework.TestCase;

public class SettingsOverlayTest extends TestCase {

    public void testOverlayConstants() {
        assertEquals("overlay_shown", Settings.PREF_OVERLAY_SHOWN);
        assertFalse(Settings.DEFAULT_OVERLAY_SHOWN);
        assertEquals("overlay_hud_pos_x", Settings.PREF_OVERLAY_POS_X);
        assertEquals("overlay_hud_pos_y", Settings.PREF_OVERLAY_POS_Y);
    }

    public void testOverlayDefaultState() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        assertFalse("Overlay must be disabled by default", settings.isOverlayShown());
    }

    public void testSetOverlayShownTrue() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayShown(true);
        assertTrue("Overlay should be enabled after setOverlayShown(true)", settings.isOverlayShown());
        assertEquals(true, (boolean) TestDataStores.get(store, TestDataStores.booleanKey(Settings.PREF_OVERLAY_SHOWN), false));
    }

    public void testSetOverlayShownFalse() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayShown(true);
        assertTrue(settings.isOverlayShown());

        settings.setOverlayShown(false);
        assertFalse("Overlay should be disabled after setOverlayShown(false)", settings.isOverlayShown());
        assertEquals(false, (boolean) TestDataStores.get(store, TestDataStores.booleanKey(Settings.PREF_OVERLAY_SHOWN), true));
    }

    public void testOverlayCoordinatesPersistence() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        // Defaults pass through
        assertEquals(7, settings.getOverlayPosX(7));
        assertEquals(9, settings.getOverlayPosY(9));

        // Save position
        settings.setOverlayPosition(150, 300);

        assertEquals(150, settings.getOverlayPosX(0));
        assertEquals(300, settings.getOverlayPosY(0));
    }

    public void testRepeatedOverlayToggles() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

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
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        assertEquals(Settings.OVERLAY_PLACEMENT_FLOATING, settings.getOverlayPlacement());
        assertFalse("Overlay should not be pinned by default", settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
    }

    public void testOverlayPlacementTopLeft() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_TOP_LEFT);
        assertEquals(Settings.OVERLAY_PLACEMENT_TOP_LEFT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
        assertEquals("topLeft", TestDataStores.get(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), ""));
    }

    public void testOverlayPlacementTopRight() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_TOP_RIGHT);
        assertEquals(Settings.OVERLAY_PLACEMENT_TOP_RIGHT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.RIGHT, settings.getOverlayGravity());
        assertEquals("topRight", TestDataStores.get(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), ""));
    }

    public void testOverlayPlacementBottomLeft() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT);
        assertEquals(Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.BOTTOM | Gravity.LEFT, settings.getOverlayGravity());
        assertEquals("bottomLeft", TestDataStores.get(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), ""));
    }

    public void testOverlayPlacementBottomRight() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_BOTTOM_RIGHT);
        assertEquals(Settings.OVERLAY_PLACEMENT_BOTTOM_RIGHT, settings.getOverlayPlacement());
        assertTrue(settings.isOverlayPinned());
        assertEquals(Gravity.BOTTOM | Gravity.RIGHT, settings.getOverlayGravity());
        assertEquals("bottomRight", TestDataStores.get(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), ""));
    }

    public void testOverlayPlacementResetToFloating() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_TOP_RIGHT);
        assertTrue(settings.isOverlayPinned());

        settings.setOverlayPlacement(Settings.OVERLAY_PLACEMENT_FLOATING);
        assertEquals(Settings.OVERLAY_PLACEMENT_FLOATING, settings.getOverlayPlacement());
        assertFalse(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
        assertEquals("floating", TestDataStores.get(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), ""));
    }

    public void testOverlayPlacementUnknownOrCorruptedValue() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), "corruptedCorner");
        assertEquals("corruptedCorner", settings.getOverlayPlacement());
        assertFalse("Unrecognized placement must not be treated as pinned", settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT), "");
        assertFalse("Empty placement must not be treated as pinned", settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
    }

    public void testOverlayPlacementNullSafety() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.remove(store, TestDataStores.stringKey(Settings.PREF_OVERLAY_PLACEMENT));
        assertEquals(Settings.DEFAULT_OVERLAY_PLACEMENT, settings.getOverlayPlacement());
        assertFalse(settings.isOverlayPinned());
        assertEquals(Gravity.TOP | Gravity.LEFT, settings.getOverlayGravity());
    }

    public void testOverlayPositionPersistence() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        assertEquals(100, settings.getOverlayPosX(100));
        assertEquals(200, settings.getOverlayPosY(200));

        settings.setOverlayPosition(350, 720);
        assertEquals(350, settings.getOverlayPosX(0));
        assertEquals(720, settings.getOverlayPosY(0));
        assertEquals(350, (int) TestDataStores.get(store, TestDataStores.intKey(Settings.PREF_OVERLAY_POS_X), 0));
        assertEquals(720, (int) TestDataStores.get(store, TestDataStores.intKey(Settings.PREF_OVERLAY_POS_Y), 0));
    }

    public void testHotCornerGravityDefault() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        assertFalse("Hot corner must be disabled by default", settings.isHotCornerEnabled());
        assertEquals(0, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityTopLeft() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_HOT_CORNER_KEY), Settings.ARRAY_HOT_CORNER_TOP_LEFT);
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.LEFT | Gravity.TOP, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityTopRight() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_HOT_CORNER_KEY), Settings.ARRAY_HOT_CORNER_TOP_RIGHT);
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.RIGHT | Gravity.TOP, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityBottomLeft() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_HOT_CORNER_KEY), Settings.ARRAY_HOT_CORNER_BOTTOM_LEFT);
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.LEFT | Gravity.BOTTOM, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityBottomRight() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_HOT_CORNER_KEY), Settings.ARRAY_HOT_CORNER_BOTTOM_RIGHT);
        assertTrue(settings.isHotCornerEnabled());
        assertEquals(Gravity.RIGHT | Gravity.BOTTOM, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityCorruptedValue() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.put(store, TestDataStores.stringKey(Settings.PREF_HOT_CORNER_KEY), "sideways");
        assertFalse("Corrupted hot-corner value must not enable the hot corner",
                settings.isHotCornerEnabled());
        assertEquals(0, settings.getHotCornerGravity());
    }

    public void testHotCornerGravityNullSafety() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        TestDataStores.remove(store, TestDataStores.stringKey(Settings.PREF_HOT_CORNER_KEY));
        assertFalse(settings.isHotCornerEnabled());
        assertEquals(0, settings.getHotCornerGravity());
    }
}
