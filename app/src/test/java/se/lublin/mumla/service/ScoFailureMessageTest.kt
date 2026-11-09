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

package se.lublin.mumla.service

import org.junit.Assert.assertEquals
import org.junit.Test
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.mumla.R

/**
 * Pins the SCO-failure Toast bucketing: only the permission denial maps to
 * the permission string; every other REASON_* and null share the generic
 * one, so a future reason is never silently mis-bucketed.
 */
class ScoFailureMessageTest {

    @Test
    fun permissionReasonMapsToPermissionString() {
        assertEquals(
            R.string.bluetooth_sco_permission,
            MumlaService.scoFailureMessage(BluetoothScoManager.REASON_PERMISSION),
        )
    }

    @Test
    fun otherReasonsMapToGenericString() {
        assertEquals(
            R.string.bluetooth_sco_failed,
            MumlaService.scoFailureMessage(BluetoothScoManager.REASON_NO_DEVICE),
        )
        assertEquals(
            R.string.bluetooth_sco_failed,
            MumlaService.scoFailureMessage(BluetoothScoManager.REASON_TIMEOUT),
        )
        assertEquals(
            R.string.bluetooth_sco_failed,
            MumlaService.scoFailureMessage(BluetoothScoManager.REASON_REFUSED),
        )
        assertEquals(
            R.string.bluetooth_sco_failed,
            MumlaService.scoFailureMessage(BluetoothScoManager.REASON_ERROR),
        )
    }

    @Test
    fun nullReasonMapsToGenericString() {
        assertEquals(
            R.string.bluetooth_sco_failed,
            MumlaService.scoFailureMessage(null),
        )
    }
}
