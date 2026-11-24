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

package se.lublin.humla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.lublin.humla.model.Server
import se.lublin.humla.util.ConfigDelta

/**
 * Step 6 leaves only the connect-target half in `HumlaService.apply`; voice
 * and route settings come from the container, and local facts from the
 * session's store. These tests pin what remains.
 */
class HumlaServiceApplyTest {

    @Test
    fun connectTargetMergesWithoutTouchingOtherOwners() {
        val service = HumlaService()
        val server = Server(1L, "name", "example.org", 64738, "user", null)

        val reconnectNeeded = service.apply(ConfigDelta(server = server, clientName = "Mumla OLED"))

        assertEquals(true, reconnectNeeded)
        assertEquals("example.org", service.getTargetServer()?.host)
        assertEquals("example.org", pendingServer(service)?.host)
    }

    @Test
    fun emptyDeltaCommitsNothing() {
        val service = HumlaService()

        assertEquals(false, service.apply(ConfigDelta()))
        assertNull(service.getTargetServer())
    }

    private fun pendingServer(service: HumlaService): Server? {
        val field = HumlaService::class.java.getDeclaredField("pendingConnection")
        field.isAccessible = true
        val pending = (field.get(service) as java.util.concurrent.atomic.AtomicReference<*>).get()
        val server = pending.javaClass.getDeclaredField("server")
        server.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return server.get(pending) as Server?
    }
}
