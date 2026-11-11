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

import com.google.protobuf.ByteString
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * Everything a connection needs, frozen when [HumlaClient.connect] is called.
 * See docs/tech-debt-whales/target-architecture/client.md#connecttarget.
 *
 * Android-free: no android.* imports. Deeply immutable: ByteString blobs,
 * persistent collections, data classes only.
 */
data class ServerEndpoint(
    val serverId: Long,
    val name: String?,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
)

/**
 * Replaces ConfigDelta.CertRef, whose ByteArray is mutable.
 */
data class ClientCertificate(
    val pkcs12: ByteString,
    val password: String?,
) {
    override fun toString(): String =
        "ClientCertificate(<${pkcs12.size()} bytes>, password=<redacted>)"
}

data class ConnectTarget(
    val server: ServerEndpoint,
    val certificate: ClientCertificate?,
    val trustStore: TrustStoreRef?,
    val forceTcp: Boolean,
    val clientName: String,
    val accessTokens: PersistentList<String>,
) {
    // Redacted: tokens are bearer secrets; certificate/trustStore carry
    // passwords (their own toStrings are redacted too).
    override fun toString(): String =
        "ConnectTarget(server=${server.host}:${server.port}, certificate=$certificate, " +
            "trustStore=$trustStore, forceTcp=$forceTcp, clientName=$clientName, " +
            "accessTokens=<${accessTokens.size} tokens>)"
}

/**
 * Today's ConfigDelta.TrustStoreRef (strings only), moved here so the
 * client package needs no dependency on the legacy ConfigDelta type.
 */
data class TrustStoreRef(
    val path: String?,
    val password: String?,
    val format: String?,
) {
    override fun toString(): String =
        "TrustStoreRef(path=$path, password=<redacted>, format=$format)"
}

/** Empty-token convenience for tests and bridges. */
fun emptyConnectTarget(
    host: String = "127.0.0.1",
    port: Int = 64738,
    username: String? = "test",
): ConnectTarget = ConnectTarget(
    server = ServerEndpoint(
        serverId = -1L,
        name = null,
        host = host,
        port = port,
        username = username,
        password = null,
    ),
    certificate = null,
    trustStore = null,
    forceTcp = false,
    clientName = "MumlaOLED-test",
    accessTokens = persistentListOf(),
)
