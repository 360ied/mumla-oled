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

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import se.lublin.humla.model.Server
import se.lublin.humla.net.SrvResolver

private const val TAG = "ServerPing"

/**
 * Pings the requested server and returns a [ServerInfoResponse], or a
 * 'dummy' response on any failure.
 *
 * The address goes through the transport's SRV rule ([SrvResolver]), and the
 * reply is checked against the address actually queried, so an off-path
 * spoof cannot poison the server list. Runs on the caller's ping dispatcher
 * ([ServerAdapter] bounds it); [SrvResolver] does its own lookup off-thread.
 *
 * @author morlunk
 */
suspend fun pingServer(server: Server, resolver: SrvResolver): ServerInfoResponse {
    try {
        // Create ping message
        val buffer = ByteBuffer.allocate(12)
        buffer.putInt(0) // Request type
        buffer.putLong(server.id) // Identifier
        // Resolve once: the reply source below is validated against this
        // actual request address (not server.host, which may differ via SRV),
        // so off-path spoofs can't poison the ping list.
        val target = resolver.resolve(
            server.host ?: throw NullPointerException("host"),
            server.port,
        )
        val requestAddress = InetAddress.getByName(target.host)
        val requestPort = target.port
        val requestPacket = DatagramPacket(
            buffer.array(), 12,
            requestAddress, requestPort,
        )

        // Send packet and wait for response
        DatagramSocket().use { socket ->
            socket.soTimeout = 1000
            socket.receiveBufferSize = 1024

            val startTime = System.nanoTime()

            socket.send(requestPacket)

            val responseBuffer = ByteArray(24)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            if (responsePacket.length < 24) {
                return ServerInfoResponse() // Spoofed/short reply: dummy
            }
            if (responsePacket.address != requestAddress || responsePacket.port != requestPort) {
                Log.d(TAG, "Ping reply source mismatch, dropping")
                return ServerInfoResponse() // Off-path reply: dummy
            }

            val latencyInMs = ((System.nanoTime() - startTime) / 1000000).toInt()

            val response = ServerInfoResponse(server, responseBuffer, latencyInMs)

            Log.d(
                TAG, "Server version: " + response.getVersionString() +
                    " Users: " + response.currentUsers + "/" + response.maximumUsers,
            )

            return response
        }
    } catch (e: Exception) {
        // Best-effort UI hint: every failure is the 'offline' row.
    }

    return ServerInfoResponse() // Return dummy in case of failure
}