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
    // Private backing fields: internal reads use these directly to avoid
    // allocating a defensive copy per equals/hashCode/toString/copy call.
    // Public getters below still return copies so holders can never mutate
    // the published snapshot.
    private val certificateBacking: ByteArray? = certificate?.copyOf()
    private val accessTokensBacking: List<String>? = accessTokens?.toList()
    private val localMuteHistoryBacking: List<Int>? = localMuteHistory?.toList()
    private val localIgnoreHistoryBacking: List<Int>? = localIgnoreHistory?.toList()
    val certificate: ByteArray?
        get() = certificateBacking?.copyOf()
    val accessTokens: List<String>?
        get() = accessTokensBacking?.toList()
    val localMuteHistory: List<Int>?
        get() = localMuteHistoryBacking?.toList()
    val localIgnoreHistory: List<Int>?
        get() = localIgnoreHistoryBacking?.toList()

    // Defaults read the backing fields directly (no defensive copy here): the
    // constructor copies once, so copy() costs a single copy per field even
    // for server-only deltas. Getters still return copies to outside holders.
    fun copy(
        server: Server? = this.server,
        certificate: ByteArray? = this.certificateBacking,
        certificatePassword: String? = this.certificatePassword,
        forceTcp: Boolean = this.forceTcp,
        clientName: String? = this.clientName,
        accessTokens: List<String>? = this.accessTokensBacking,
        trustStore: String? = this.trustStore,
        trustStorePassword: String? = this.trustStorePassword,
        trustStoreFormat: String? = this.trustStoreFormat,
        localMuteHistory: List<Int>? = this.localMuteHistoryBacking,
        localIgnoreHistory: List<Int>? = this.localIgnoreHistoryBacking,
    ): ConnectionParams = ConnectionParams(
        server = server,
        certificate = certificate,
        certificatePassword = certificatePassword,
        forceTcp = forceTcp,
        clientName = clientName,
        accessTokens = accessTokens,
        trustStore = trustStore,
        trustStorePassword = trustStorePassword,
        trustStoreFormat = trustStoreFormat,
        localMuteHistory = localMuteHistory,
        localIgnoreHistory = localIgnoreHistory,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionParams) return false
        return server === other.server &&
            (certificateBacking === other.certificateBacking ||
                (certificateBacking != null && other.certificateBacking != null &&
                    certificateBacking.contentEquals(other.certificateBacking))) &&
            certificatePassword == other.certificatePassword &&
            forceTcp == other.forceTcp &&
            clientName == other.clientName &&
            accessTokensBacking == other.accessTokensBacking &&
            trustStore == other.trustStore &&
            trustStorePassword == other.trustStorePassword &&
            trustStoreFormat == other.trustStoreFormat &&
            localMuteHistoryBacking == other.localMuteHistoryBacking &&
            localIgnoreHistoryBacking == other.localIgnoreHistoryBacking
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(server)
        result = 31 * result + (certificateBacking?.contentHashCode() ?: 0)
        result = 31 * result + (certificatePassword?.hashCode() ?: 0)
        result = 31 * result + forceTcp.hashCode()
        result = 31 * result + (clientName?.hashCode() ?: 0)
        result = 31 * result + (accessTokensBacking?.hashCode() ?: 0)
        result = 31 * result + (trustStore?.hashCode() ?: 0)
        result = 31 * result + (trustStorePassword?.hashCode() ?: 0)
        result = 31 * result + (trustStoreFormat?.hashCode() ?: 0)
        result = 31 * result + (localMuteHistoryBacking?.hashCode() ?: 0)
        result = 31 * result + (localIgnoreHistoryBacking?.hashCode() ?: 0)
        return result
    }

    // Redacted: certificates, passwords, and tokens must never reach logs.
    // server prints as host:port (non-secret routing info); identity hash is useless.
    override fun toString(): String =
        "ConnectionParams(server=${server?.host}:${server?.port}, certificate=<${certificateBacking?.size ?: 0} bytes>, " +
            "certificatePassword=<redacted>, forceTcp=$forceTcp, clientName=$clientName, " +
            "accessTokens=<redacted>, trustStore=$trustStore, " +
            "trustStorePassword=<redacted>, trustStoreFormat=$trustStoreFormat, " +
            "localMuteHistory=$localMuteHistoryBacking, localIgnoreHistory=$localIgnoreHistoryBacking)"
}
