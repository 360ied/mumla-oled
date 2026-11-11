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
 * Connection statistics. Kept out of SessionState because latency changes
 * every ping. See
 * docs/tech-debt-whales/target-architecture/client.md#statistics.
 *
 * Deeply immutable: data classes of primitives only.
 */
data class CryptCounts(
    val good: Int,
    val late: Int,
    val lost: Int,
    val resync: Int,
) {
    companion object {
        fun zero(): CryptCounts = CryptCounts(good = 0, late = 0, lost = 0, resync = 0)
    }
}

data class SessionStats(
    val tcpLatencyMicros: Long?,
    val udpLatencyMicros: Long?,
    val usingUdp: Boolean,
    val currentBandwidth: Int?,
    val localCrypt: CryptCounts,
    val remoteCrypt: CryptCounts,
) {
    companion object {
        fun initial(): SessionStats = SessionStats(
            tcpLatencyMicros = null,
            udpLatencyMicros = null,
            usingUdp = true,
            currentBandwidth = null,
            localCrypt = CryptCounts.zero(),
            remoteCrypt = CryptCounts.zero(),
        )
    }
}
