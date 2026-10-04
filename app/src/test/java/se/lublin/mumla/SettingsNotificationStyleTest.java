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

import java.util.HashMap;
import java.util.Map;

public class SettingsNotificationStyleTest extends TestCase {

    private Map<String, Object> mPrefsMap;
    private Settings mSettings;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPrefsMap = new HashMap<>();
        mSettings = new Settings(new FakeSharedPreferences(mPrefsMap));
    }

    public void testMediaKeyMuteDefaultOn() {
        assertTrue("Media-key mute toggle must default to on (grayed out unless media style)",
                mSettings.isMediaKeyMuteEnabled());
    }

    public void testMediaKeyMuteTtsDefaultOn() {
        assertTrue("Media-key mute announcement must default to on",
                mSettings.isMediaKeyMuteTtsEnabled());
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
