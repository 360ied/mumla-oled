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

package se.lublin.mumla.app;

import junit.framework.TestCase;

import se.lublin.humla.Constants;
import se.lublin.humla.model.Server;

public class MumlaActivityTest extends TestCase {

    public void testIsSameServer_SavedSameId() {
        Server a = new Server(42, "A", "mumble.example.com", 64738, "alice", "");
        Server b = new Server(42, "B", "other.example.com", 64739, "bob", "");
        assertTrue(MumlaActivity.isSameServer(a, b));
    }

    public void testIsSameServer_SavedDifferentId() {
        Server a = new Server(1, "A", "mumble.example.com", 64738, "alice", "");
        Server b = new Server(2, "B", "mumble.example.com", 64738, "alice", "");
        assertFalse(MumlaActivity.isSameServer(a, b));
    }

    public void testIsSameServer_SavedVsUnsaved() {
        Server saved = new Server(42, "Saved", "mumble.example.com", 64738, "alice", "");
        Server unsaved = new Server(-1, "Unsaved", "mumble.example.com", 64738, "alice", "");
        assertFalse(MumlaActivity.isSameServer(saved, unsaved));
        assertFalse(MumlaActivity.isSameServer(unsaved, saved));
    }

    public void testIsSameServer_UnsavedSameEndpoint() {
        Server a = new Server(-1, "A", "mumble.example.com", 64738, "alice", "");
        Server b = new Server(-1, "B", "mumble.example.com", 64738, "alice", "");
        assertTrue(MumlaActivity.isSameServer(a, b));
    }

    public void testIsSameServer_UnsavedHostCaseInsensitive() {
        Server upper = new Server(-1, "Upper", "MUMBLE.EXAMPLE.COM", 64738, "alice", "");
        Server lower = new Server(-1, "Lower", "mumble.example.com", 64738, "alice", "");
        assertTrue(MumlaActivity.isSameServer(upper, lower));
    }

    public void testIsSameServer_UnsavedZeroPortMatchesDefault() {
        Server zeroPort = new Server(-1, "Zero", "mumble.example.com", 0, "alice", "");
        Server defaultPort = new Server(-1, "Default", "mumble.example.com", Constants.DEFAULT_PORT, "alice", "");
        assertTrue(MumlaActivity.isSameServer(zeroPort, defaultPort));
    }

    public void testIsSameServer_UnsavedDifferentPort() {
        Server a = new Server(-1, "A", "mumble.example.com", 64738, "alice", "");
        Server b = new Server(-1, "B", "mumble.example.com", 64739, "alice", "");
        assertFalse(MumlaActivity.isSameServer(a, b));
    }

    public void testIsSameServer_UnsavedDifferentUser() {
        Server alice = new Server(-1, "Alice", "mumble.example.com", 64738, "alice", "");
        Server bob = new Server(-1, "Bob", "mumble.example.com", 64738, "bob", "");
        assertFalse(MumlaActivity.isSameServer(alice, bob));
    }

    public void testIsSameServer_NullHandling() {
        Server server = new Server(-1, "A", "mumble.example.com", 64738, "alice", "");
        assertTrue(MumlaActivity.isSameServer(null, null));
        assertFalse(MumlaActivity.isSameServer(null, server));
        assertFalse(MumlaActivity.isSameServer(server, null));
    }
}
