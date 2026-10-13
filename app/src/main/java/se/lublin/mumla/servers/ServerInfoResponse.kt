/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.servers

import java.nio.ByteBuffer
import se.lublin.humla.model.Server

/**
 * Response from server pings.
 * @see http://mumble.sourceforge.net/Protocol
 * @author morlunk
 */
class ServerInfoResponse {

    val identifier: Long
    val version: Int
    val currentUsers: Int
    val maximumUsers: Int
    val allowedBandwidth: Int
    val latency: Int
    val server: Server?

    /**
     * Whether or not this server info response represents a failure to retrieve a response. Used to efficiently denote failed responses.
     */
    val isDummy: Boolean

    /**
     * Creates a ServerInfoResponse object with the bytes obtained from the server.
     * @param response The response to the UDP pings sent by the server.
     * @throws IllegalArgumentException if response is null or shorter than 24 bytes.
     * @see http://mumble.sourceforge.net/Protocol
     */
    constructor(server: Server?, response: ByteArray?, latency: Int) {
        if (response == null || response.size < 24) {
            throw IllegalArgumentException(
                "UDP ping response too short: " +
                    (if (response == null) 0 else response.size) + " bytes"
            )
        }
        val buffer = ByteBuffer.wrap(response)
        version = buffer.int
        identifier = buffer.long
        currentUsers = buffer.int
        maximumUsers = buffer.int
        allowedBandwidth = buffer.int
        this.latency = latency
        this.server = server
        this.isDummy = false
    }

    /**
     * Instantiating a ServerInfoResponse with no data will cause it to be considered a 'dummy' response by its handler.
     */
    constructor() {
        identifier = 0
        version = 0
        currentUsers = 0
        maximumUsers = 0
        allowedBandwidth = 0
        latency = 0
        server = null
        isDummy = true
    }

    fun getVersionString(): String {
        val versionBytes = ByteBuffer.allocate(4).putInt(version).array()
        return String.format("%d.%d.%d", versionBytes[1], versionBytes[2], versionBytes[3])
    }
}
