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

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.model.Server
import se.lublin.humla.util.ConfigDelta
import se.lublin.humla.voice.VoiceSettings

/**
 * Pins the two step-6 commit-2 rules: the interim transmit-mode read follows
 * the applied VoiceSettings, and a rejected settings delta commits no part of
 * the delta (neither the pending connect target nor the settings flow).
 */
class HumlaServiceApplyTest {

    @Test
    fun transmitModeFollowsAppliedSettings() {
        val service = HumlaService()
        assertEquals(Constants.TRANSMIT_VOICE_ACTIVITY, service.getTransmitMode())

        service.apply(ConfigDelta(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK))
        assertEquals(Constants.TRANSMIT_PUSH_TO_TALK, service.getTransmitMode())

        service.apply(ConfigDelta(transmitMode = Constants.TRANSMIT_CONTINUOUS))
        assertEquals(Constants.TRANSMIT_CONTINUOUS, service.getTransmitMode())
    }

    @Test
    fun rejectedDeltaLeavesPendingTargetAndSettingsUnchanged() {
        val service = HumlaService()
        val before = settingsOf(service)
        val server = Server(1L, "name", "example.org", 64738, "user", null)

        assertThrows(IllegalArgumentException::class.java) {
            service.apply(ConfigDelta(server = server, transmitMode = 999))
        }

        assertEquals(before, settingsOf(service))
        assertNull(service.getTargetServer())
        assertNull(pendingServerOf(service))
    }

    @Suppress("UNCHECKED_CAST")
    private fun settingsOf(service: HumlaService): VoiceSettings {
        val field = HumlaService::class.java.getDeclaredField("voiceSettingsFlow")
        field.isAccessible = true
        return (field.get(service) as MutableStateFlow<VoiceSettings>).value
    }

    private fun pendingServerOf(service: HumlaService): Any? {
        val field = HumlaService::class.java.getDeclaredField("pendingConnection")
        field.isAccessible = true
        val pending = (field.get(service) as java.util.concurrent.atomic.AtomicReference<*>).get()
        val server = pending.javaClass.getDeclaredField("server")
        server.isAccessible = true
        return server.get(pending)
    }
}
