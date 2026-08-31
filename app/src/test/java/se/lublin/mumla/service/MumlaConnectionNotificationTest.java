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
}
