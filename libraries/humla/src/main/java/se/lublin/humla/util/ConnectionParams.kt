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

package se.lublin.humla.util

import se.lublin.humla.model.Server

/**
 * Immutable connection-parameter snapshot (config track of the HumlaService
 * decomposition): the 11 apply-time values `connect()` and
 * `onConnectionEstablished()` read. Frozen at the `apply()` boundary
 * (main-confined) and published through a single volatile field, so
 * connection threads always observe one atomic snapshot instead of eleven
 * independently mutating fields.
 *
 * `server` travels by reference — it is mutable (`setResolved` in
 * `onConnectionEstablished`) and excluded from value equality. Byte arrays
 * and lists are defensively copied at freeze time on the `apply()` path
 * (which does `copyOf()`/`toList()`); direct construction must already hold
 * frozen inputs, as the public constructor aliases caller-supplied arrays
 * and lists. `reconnectNeeded` stays
 * presence-based: any delta carrying a reconnect-relevant key returns true,
 * even for an unchanged value.
 */
data class ConnectionParams(
    val server: Server? = null,
    val certificate: ByteArray? = null,
    val certificatePassword: String? = null,
    val forceTcp: Boolean = false,
    val clientName: String? = null,
    val accessTokens: List<String>? = null,
    val trustStore: String? = null,
    val trustStorePassword: String? = null,
    val trustStoreFormat: String? = null,
    val localMuteHistory: List<Int>? = null,
    val localIgnoreHistory: List<Int>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionParams) return false
        return server === other.server &&
            (certificate === other.certificate ||
                (certificate != null && other.certificate != null &&
                    certificate.contentEquals(other.certificate))) &&
            certificatePassword == other.certificatePassword &&
            forceTcp == other.forceTcp &&
            clientName == other.clientName &&
            accessTokens == other.accessTokens &&
            trustStore == other.trustStore &&
            trustStorePassword == other.trustStorePassword &&
            trustStoreFormat == other.trustStoreFormat &&
            localMuteHistory == other.localMuteHistory &&
            localIgnoreHistory == other.localIgnoreHistory
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(server)
        result = 31 * result + (certificate?.contentHashCode() ?: 0)
        result = 31 * result + (certificatePassword?.hashCode() ?: 0)
        result = 31 * result + forceTcp.hashCode()
        result = 31 * result + (clientName?.hashCode() ?: 0)
        result = 31 * result + (accessTokens?.hashCode() ?: 0)
        result = 31 * result + (trustStore?.hashCode() ?: 0)
        result = 31 * result + (trustStorePassword?.hashCode() ?: 0)
        result = 31 * result + (trustStoreFormat?.hashCode() ?: 0)
        result = 31 * result + (localMuteHistory?.hashCode() ?: 0)
        result = 31 * result + (localIgnoreHistory?.hashCode() ?: 0)
        return result
    }

    // Redacted: certificates, passwords, and tokens must never reach logs.
    override fun toString(): String =
        "ConnectionParams(server=$server, certificate=<${certificate?.size ?: 0} bytes>, " +
            "certificatePassword=<redacted>, forceTcp=$forceTcp, clientName=$clientName, " +
            "accessTokens=<redacted>, trustStore=$trustStore, " +
            "trustStorePassword=<redacted>, trustStoreFormat=$trustStoreFormat, " +
            "localMuteHistory=$localMuteHistory, localIgnoreHistory=$localIgnoreHistory)"
}
