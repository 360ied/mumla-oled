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

package se.lublin.mumla.service;

import junit.framework.TestCase;

import android.view.KeyEvent;

import se.lublin.mumla.FakeSharedPreferences;
import se.lublin.mumla.R;

public class MumlaConnectionNotificationTest extends TestCase {

    public void testMuteActionIconResolution() {
        assertEquals("Unmuted state must resolve to ic_action_microphone",
                R.drawable.ic_action_microphone,
                MumlaConnectionNotification.getMuteActionIcon(false));

        assertEquals("Muted state must resolve to ic_action_microphone_muted",
                R.drawable.ic_action_microphone_muted,
                MumlaConnectionNotification.getMuteActionIcon(true));
    }

    public void testDeafenActionIconResolution() {
        assertEquals("Undeafened state must resolve to ic_action_audio",
                R.drawable.ic_action_audio,
                MumlaConnectionNotification.getDeafenActionIcon(false));

        assertEquals("Deafened state must resolve to ic_action_audio_muted",
                R.drawable.ic_action_audio_muted,
                MumlaConnectionNotification.getDeafenActionIcon(true));
    }

    public void testOverlayActionIconResolution() {
        assertEquals("Overlay hidden state must resolve to ic_action_overlay_off",
                R.drawable.ic_action_overlay_off,
                MumlaConnectionNotification.getOverlayActionIcon(false));

        assertEquals("Overlay shown state must resolve to ic_action_overlay_on",
                R.drawable.ic_action_overlay_on,
                MumlaConnectionNotification.getOverlayActionIcon(true));
    }

    public void testOverlayActionConstants() {
        assertEquals("Action constant must match expected intent filter action",
                "se.lublin.mumla.action.TOGGLE_OVERLAY",
                MumlaService.ACTION_TOGGLE_OVERLAY);
    }

    public void testActionConstantsUniqueness() {
        String[] actions = {
                MumlaService.ACTION_DISCONNECT,
                MumlaService.ACTION_MUTE,
                MumlaService.ACTION_DEAFEN,
                MumlaService.ACTION_TOGGLE_OVERLAY,
                MumlaService.ACTION_CANCEL_RECONNECT
        };
        for (int i = 0; i < actions.length; i++) {
            assertNotNull(actions[i]);
            for (int j = i + 1; j < actions.length; j++) {
                assertFalse("Action constants must be distinct: " + actions[i] + " vs " + actions[j],
                        actions[i].equals(actions[j]));
            }
        }
    }

    public void testNotificationStyleConstants() {
        assertEquals("Preference key must be notification_style",
                "notification_style",
                se.lublin.mumla.Settings.PREF_NOTIFICATION_STYLE);
        assertEquals("BigText style constant must match bigtext",
                "bigtext",
                se.lublin.mumla.Settings.NOTIFICATION_STYLE_BIGTEXT);
        assertEquals("Media style constant must match media",
                "media",
                se.lublin.mumla.Settings.NOTIFICATION_STYLE_MEDIA);
        assertEquals("Default style must be bigtext",
                se.lublin.mumla.Settings.NOTIFICATION_STYLE_BIGTEXT,
                se.lublin.mumla.Settings.DEFAULT_NOTIFICATION_STYLE);
        assertFalse("Style constants must be distinct",
                se.lublin.mumla.Settings.NOTIFICATION_STYLE_BIGTEXT.equals(se.lublin.mumla.Settings.NOTIFICATION_STYLE_MEDIA));
    }

    public void testCleanStatusText() {
        assertEquals("Connected", MumlaConnectionNotification.cleanStatusText("Connected."));
        assertEquals("已连接", MumlaConnectionNotification.cleanStatusText("已连接。"));
        assertEquals("Muted", MumlaConnectionNotification.cleanStatusText("Muted"));
        assertNull(MumlaConnectionNotification.cleanStatusText(null));
    }

    public void testFormatChannelName() {
        assertEquals("Lobby", MumlaConnectionNotification.formatChannelName("Lobby", "Channel"));
        assertEquals("Channel", MumlaConnectionNotification.formatChannelName("", "Channel"));
        assertEquals("Channel", MumlaConnectionNotification.formatChannelName(null, "Channel"));
    }

    public void testMuteToggleKeyCodes() {
        assertTrue("Play/pause must toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertTrue("Pause must toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertTrue("Play must toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertTrue("Headset hook must toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_HEADSETHOOK));

        assertFalse("Stop must not toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_MEDIA_STOP));
        assertFalse("Next must not toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse("Volume keys must not toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse("Unknown key must not toggle mute",
                MumlaConnectionNotification.isMuteToggleKeyCode(KeyEvent.KEYCODE_UNKNOWN));
    }

    public void testPttClaimedMediaKey() {
        assertTrue("Pause bound as PTT must be PTT-claimed",
                MumlaConnectionNotification.isPttClaimedMediaKey(
                        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertFalse("Pause must not be PTT-claimed when PTT is unbound",
                MumlaConnectionNotification.isPttClaimedMediaKey(
                        KeyEvent.KEYCODE_MEDIA_PAUSE, se.lublin.mumla.Settings.DEFAULT_PUSH_KEY));
        assertFalse("Pause must not be PTT-claimed when PTT is another key",
                MumlaConnectionNotification.isPttClaimedMediaKey(
                        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse("Non-toggle keys are never PTT-claimed media keys",
                MumlaConnectionNotification.isPttClaimedMediaKey(
                        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP));
    }

    public void testNotificationInstanceHooks() {
        se.lublin.mumla.Settings settings = se.lublin.mumla.Settings.createForTesting(
                new FakeSharedPreferences(new java.util.HashMap<>()));
        MumlaConnectionNotification notification = new MumlaConnectionNotification(null, null, settings);
        assertSame(settings, notification.getSettings());
        assertFalse(notification.isMediaSessionActive());

        MumlaConnectionNotification created = MumlaConnectionNotification.create(null, null, settings);
        assertSame(settings, created.getSettings());
        assertFalse(created.isMediaSessionActive());
    }
}
