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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.Constants
import se.lublin.humla.client.ServerEndpoint
import se.lublin.humla.model.Server

/**
 * The main screen's "already connected to this server" test, ported from the
 * deleted activity helper with the screen's connect-target/server shapes.
 */
class ServerIdentityTest {

    /** The endpoint the current connect target would carry for [server]. */
    private fun endpointOf(server: Server): ServerEndpoint = ServerEndpoint(
        serverId = server.id,
        name = server.name,
        host = server.host ?: "",
        port = server.port,
        username = server.username,
        password = server.password,
    )

    @Test
    fun savedServersCompareByIdAndPassword() {
        val a = Server(42, "A", "mumble.example.com", 64738, "alice", "")
        val renamed = Server(42, "B", "other.example.com", 64739, "bob", "")
        assertTrue(MainViewModel.isSameServer(endpointOf(a), renamed))

        assertFalse(
            MainViewModel.isSameServer(
                endpointOf(a),
                Server(43, "B", "mumble.example.com", 64738, "alice", ""),
            ),
        )
        assertFalse(
            "a corrected password must reconnect",
            MainViewModel.isSameServer(
                endpointOf(Server(42, "A", "mumble.example.com", 64738, "alice", "old")),
                Server(42, "A", "mumble.example.com", 64738, "alice", "new"),
            ),
        )
    }

    @Test
    fun savedAndUnsavedServersAreNeverTheSame() {
        val saved = Server(42, "Saved", "mumble.example.com", 64738, "alice", "")
        val unsaved = Server(-1, "Unsaved", "mumble.example.com", 64738, "alice", "")
        assertFalse(MainViewModel.isSameServer(endpointOf(saved), unsaved))
        assertFalse(MainViewModel.isSameServer(endpointOf(unsaved), saved))
    }

    @Test
    fun unsavedServersCompareByCanonicalEndpointAndPassword() {
        val a = Server(-1, "A", "mumble.example.com", 64738, "alice", "")
        assertTrue(
            MainViewModel.isSameServer(
                endpointOf(a),
                Server(-1, "B", "mumble.example.com", 64738, "alice", ""),
            ),
        )
        assertTrue(
            "hosts compare case-insensitively",
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "Upper", "MUMBLE.EXAMPLE.COM", 64738, "alice", "")),
                Server(-1, "Lower", "mumble.example.com", 64738, "alice", ""),
            ),
        )
        assertTrue(
            "port 0 means the default port",
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "Zero", "mumble.example.com", 0, "alice", "")),
                Server(-1, "Default", "mumble.example.com", Constants.DEFAULT_PORT, "alice", ""),
            ),
        )
        assertFalse(
            MainViewModel.isSameServer(
                endpointOf(a),
                Server(-1, "B", "mumble.example.com", 64739, "alice", ""),
            ),
        )
        assertFalse(
            MainViewModel.isSameServer(
                endpointOf(a),
                Server(-1, "B", "mumble.example.com", 64738, "bob", ""),
            ),
        )
        assertFalse(
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "A", "mumble.example.com", 64738, "alice", "old")),
                Server(-1, "B", "mumble.example.com", 64738, "alice", "new"),
            ),
        )
    }

    @Test
    fun passwordsNullAndEmptyAreTheSame() {
        val endpoint = endpointOf(Server(-1, "A", "mumble.example.com", 64738, "alice", null))
        assertTrue(
            MainViewModel.isSameServer(
                endpoint,
                Server(-1, "B", "mumble.example.com", 64738, "alice", ""),
            ),
        )
        assertTrue(
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "A", "mumble.example.com", 64738, "alice", "")),
                Server(-1, "B", "mumble.example.com", 64738, "alice", null),
            ),
        )
    }

    @Test
    fun hostsAreTrimmedAndTrailingDotsIgnored() {
        val plain = Server(-1, "B", "mumble.example.com", 64738, "alice", "")
        assertTrue(
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "A", "mumble.example.com.", 64738, "alice", "")),
                plain,
            ),
        )
        assertTrue(
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "A", "  mumble.example.com  ", 64738, "alice", "")),
                plain,
            ),
        )
    }

    @Test
    fun emptyHostAndUsernameMatchNull() {
        assertTrue(
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "A", "", 64738, "alice", "")),
                Server(-1, "B", null, 64738, "alice", ""),
            ),
        )
        assertTrue(
            MainViewModel.isSameServer(
                endpointOf(Server(-1, "A", "mumble.example.com", 64738, "", "")),
                Server(-1, "B", "mumble.example.com", 64738, null, ""),
            ),
        )
    }
}
