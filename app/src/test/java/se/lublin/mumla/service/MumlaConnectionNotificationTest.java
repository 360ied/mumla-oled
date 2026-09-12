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

package se.lublin.mumla.service;

import junit.framework.TestCase;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

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
        assertEquals("standard", Settings.NOTIFICATION_STYLE_STANDARD);
        assertEquals("media", Settings.NOTIFICATION_STYLE_MEDIA);
        assertEquals("notification_style", Settings.PREF_NOTIFICATION_STYLE);
        assertEquals(Settings.NOTIFICATION_STYLE_STANDARD, Settings.DEFAULT_NOTIFICATION_STYLE);
    }
}
