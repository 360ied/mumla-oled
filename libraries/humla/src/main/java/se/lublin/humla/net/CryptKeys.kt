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

package se.lublin.humla.net

import com.google.protobuf.ByteString
import se.lublin.humla.client.CryptCounts

/**
 * Latest full key set from CryptSetup. Each exactly 16 bytes (AES block).
 * See docs/tech-debt-whales/target-architecture/transport.md#crypt-setup.
 */
data class CryptKeys(
    val key: ByteString,
    val clientNonce: ByteString,
    val serverNonce: ByteString,
) {
    /** True when all three are exactly one AES block. */
    fun isWellFormed(): Boolean =
        key.size() == 16 && clientNonce.size() == 16 && serverNonce.size() == 16

    override fun toString(): String = "CryptKeys(<redacted 16+16+16 bytes>)"
}

data class TransportStats(
    val tcpLatencyMicros: Long?,
    val udpLatencyMicros: Long?,
    val usingUdp: Boolean,
    val remoteCrypt: CryptCounts,
) {
    companion object {
        fun initial(usingUdp: Boolean = true): TransportStats = TransportStats(
            tcpLatencyMicros = null,
            udpLatencyMicros = null,
            usingUdp = usingUdp,
            remoteCrypt = CryptCounts.zero(),
        )
    }
}
