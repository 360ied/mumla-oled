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
 * Shallow-frozen connection-parameter snapshot (config track of the HumlaService
 * decomposition): the 11 apply-time values `connect()` and
 * `onConnectionEstablished()` read. Frozen at the `apply()` boundary
 * (main-confined) and published through a single volatile field, so
 * connection threads always observe one atomic snapshot instead of eleven
 * independently mutating fields.
 *
 * `server` travels by reference — it is mutable (`setResolved` in
 * `onConnectionEstablished`) and compared by identity, not value. Byte arrays
 * and lists are defensively copied on construction, and getters return
 * defensive copies too, so a reader holding the returned array/list can
 * never mutate the published snapshot. `reconnectNeeded` is `HumlaService.apply`'s local
 * presence-based return (any delta carrying a reconnect-relevant key
 * returns true, even for an unchanged value), not a member here.
 */
class ConnectionParams(
    val server: Server? = null,
    certificate: ByteArray? = null,
    val certificatePassword: String? = null,
    val forceTcp: Boolean = false,
    val clientName: String? = null,
    accessTokens: List<String>? = null,
    val trustStore: String? = null,
    val trustStorePassword: String? = null,
    val trustStoreFormat: String? = null,
    localMuteHistory: List<Int>? = null,
    localIgnoreHistory: List<Int>? = null,
) {
    val certificate: ByteArray? = certificate?.copyOf()
        get() = field?.clone()
    val accessTokens: List<String>? = accessTokens?.toList()
        get() = field?.toList()
    val localMuteHistory: List<Int>? = localMuteHistory?.toList()
        get() = field?.toList()
    val localIgnoreHistory: List<Int>? = localIgnoreHistory?.toList()
        get() = field?.toList()

    fun copy(
        server: Server? = this.server,
        certificate: ByteArray? = this.certificate,
        certificatePassword: String? = this.certificatePassword,
        forceTcp: Boolean = this.forceTcp,
        clientName: String? = this.clientName,
        accessTokens: List<String>? = this.accessTokens,
        trustStore: String? = this.trustStore,
        trustStorePassword: String? = this.trustStorePassword,
        trustStoreFormat: String? = this.trustStoreFormat,
        localMuteHistory: List<Int>? = this.localMuteHistory,
        localIgnoreHistory: List<Int>? = this.localIgnoreHistory,
    ): ConnectionParams = ConnectionParams(
        server = server,
        certificate = certificate?.copyOf(),
        certificatePassword = certificatePassword,
        forceTcp = forceTcp,
        clientName = clientName,
        accessTokens = accessTokens?.toList(),
        trustStore = trustStore,
        trustStorePassword = trustStorePassword,
        trustStoreFormat = trustStoreFormat,
        localMuteHistory = localMuteHistory?.toList(),
        localIgnoreHistory = localIgnoreHistory?.toList(),
    )

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
