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

/**
 * Pure reconnect-backoff truth table, unchanged from today's HumlaService.
 * See docs/tech-debt-whales/target-architecture/client.md#reconnect-policy.
 *
 * Android-free and side-effect-free so the JVM suite pins it directly.
 */
object ReconnectPolicy {
    const val RECONNECT_DELAY_INITIAL = 1000
    const val RECONNECT_DELAY_MAX = 25000

    /**
     * Delay by attempt number: 1 s, 3 s, 6 s, 12 s, then 25 s forever.
     * Negative attempts are unreachable (counter resets to 0 and only
     * increments); fail closed to MAX like any large value.
     */
    @JvmStatic
    fun delayForAttempts(attempts: Int): Int {
        if (attempts < 0) return RECONNECT_DELAY_MAX
        return when (attempts) {
            0 -> RECONNECT_DELAY_INITIAL
            1 -> 3000
            2 -> 6000
            3 -> 12000
            else -> RECONNECT_DELAY_MAX
        }
    }
}
