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

import se.lublin.humla.Constants;
import se.lublin.humla.model.Server;

public class MumlaServiceChatLogTest extends TestCase {

    public void testGetServerKey_NullServer() {
        assertEquals("", MumlaService.getServerKey(null));
    }

    public void testGetServerKey_SavedServer() {
        Server savedServer = new Server(42, "My Server", "mumble.example.com", 64738, "alice", "secret");
        assertEquals("id:42", MumlaService.getServerKey(savedServer));
    }

    public void testGetServerKey_UnsavedServer() {
        Server unsavedServer = new Server(-1, "AdHoc Server", "voice.example.org", 64739, "bob", "");
        assertEquals("endpoint:voice.example.org:64739:bob", MumlaService.getServerKey(unsavedServer));
    }

    public void testGetServerKey_HostCaseInsensitivity() {
        Server upperServer = new Server(-1, "Upper", "MUMBLE.EXAMPLE.COM", 64738, "alice", "");
        Server lowerServer = new Server(-1, "Lower", "mumble.example.com", 64738, "alice", "");
        assertEquals(MumlaService.getServerKey(lowerServer), MumlaService.getServerKey(upperServer));
    }

    public void testGetServerKey_DefaultPortFallback() {
        Server zeroPortServer = new Server(-1, "Zero Port", "mumble.example.com", 0, "charlie", "");
        assertEquals("endpoint:mumble.example.com:" + Constants.DEFAULT_PORT + ":charlie",
                MumlaService.getServerKey(zeroPortServer));
    }

    public void testMaxChatLogSizeConstant() {
        assertEquals(500, MumlaService.MAX_CHAT_LOG_SIZE);
    }
}
