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

package se.lublin.humla.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure reconnect-backoff table owned by ReconnectPolicy
 * (HumlaService.getReconnectDelayForAttempts delegates to it).
 * Delays: 0->1000, 1->3000, 2->6000, 3->12000, >=4->25000.
 */
class ReconnectPolicyTest {
    @Test
    fun staticBackoffTable() {
        assertEquals(1000, ReconnectPolicy.delayForAttempts(0))
        assertEquals(3000, ReconnectPolicy.delayForAttempts(1))
        assertEquals(6000, ReconnectPolicy.delayForAttempts(2))
        assertEquals(12000, ReconnectPolicy.delayForAttempts(3))
        assertEquals(25000, ReconnectPolicy.delayForAttempts(4))
        assertEquals(25000, ReconnectPolicy.delayForAttempts(5))
        assertEquals(25000, ReconnectPolicy.delayForAttempts(100))
    }

    @Test
    fun negativeAttemptsFailClosedToMax() {
        assertEquals(25000, ReconnectPolicy.delayForAttempts(-1))
        assertEquals(25000, ReconnectPolicy.delayForAttempts(Int.MIN_VALUE))
    }

    @Test
    fun namedConstantsMatchTable() {
        assertEquals(1000, ReconnectPolicy.RECONNECT_DELAY_INITIAL)
        assertEquals(25000, ReconnectPolicy.RECONNECT_DELAY_MAX)
    }
}
