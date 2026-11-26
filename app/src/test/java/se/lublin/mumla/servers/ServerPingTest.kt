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

package se.lublin.mumla.servers

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.model.Server
import se.lublin.humla.net.SrvResolver

/**
 * Drives the real [pingServer] over loopback UDP
 * (docs/target-architecture/steps/8-clean-up.md, commit 3): the 12-byte
 * request, the 24-byte length gate, the reply-source check, and the decoded
 * reply. The adapter test fakes the ping, so without this nothing exercises
 * the moved function.
 */
class ServerPingTest {

    private val version = (1 shl 16) or (5 shl 8)

    private fun server(port: Int, id: Long = 42L) =
        Server(id, "Test", "127.0.0.1", port, "alice", null)

    /** A loopback socket the "server" side of each case reads from. */
    private fun listener(): DatagramSocket =
        DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    private fun reply(identifier: Long, users: Int = 7, maxUsers: Int = 25): ByteArray =
        ByteBuffer.allocate(24)
            .putInt(version)
            .putLong(identifier)
            .putInt(users)
            .putInt(maxUsers)
            .putInt(1_000_000)
            .array()

    /**
     * Runs [body] on a thread and hands back the thread plus a slot holding
     * anything it threw, so a failed responder cannot hang the test.
     */
    private fun responder(body: () -> Unit): Pair<Thread, AtomicReference<Throwable?>> {
        val failure = AtomicReference<Throwable?>(null)
        val worker = thread(name = "server-ping-test-responder") {
            try {
                body()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        return worker to failure
    }

    /** Reads one request on [socket] and returns it with the sender. */
    private fun receiveRequest(socket: DatagramSocket): Pair<ByteArray, DatagramPacket> {
        val buffer = ByteArray(32)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.soTimeout = 5_000
        socket.receive(packet)
        return buffer.copyOf(packet.length) to packet
    }

    private fun assertResponded(worker: Thread, failure: AtomicReference<Throwable?>) {
        worker.join(5_000)
        assertNull("responder failed: ${failure.get()}", failure.get())
    }

    @Test
    fun realPingSendsTheLegacyRequestAndDecodesTheReply() {
        listener().use { socket ->
            val (worker, failure) = responder {
                val (request, packet) = receiveRequest(socket)
                // 12-byte request: type 0, then the saved server's id.
                assertEquals(12, request.size)
                val buffer = ByteBuffer.wrap(request)
                assertEquals(0, buffer.int)
                val identifier = buffer.long
                assertEquals(42L, identifier)
                val answer = reply(identifier)
                socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
            }

            val response = runBlocking { pingServer(server(socket.localPort), SrvResolver()) }
            assertResponded(worker, failure)

            assertFalse(response.isDummy)
            assertEquals(42L, response.identifier)
            assertEquals("1.5.0", response.getVersionString())
            assertEquals(7, response.currentUsers)
            assertEquals(25, response.maximumUsers)
            assertEquals(1_000_000, response.allowedBandwidth)
            assertTrue("latency is measured", response.latency >= 0)
            assertEquals("Test", response.server?.name)
        }
    }

    @Test
    fun aShortReplyIsTreatedAsAFailure() {
        listener().use { socket ->
            val (worker, failure) = responder {
                val (_, packet) = receiveRequest(socket)
                val answer = reply(identifier = 42L).copyOf(10)
                socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
            }

            val response = runBlocking { pingServer(server(socket.localPort), SrvResolver()) }
            assertResponded(worker, failure)

            assertTrue("a 10-byte reply is a dummy", response.isDummy)
            assertNull(response.server)
        }
    }

    @Test
    fun noReplyWithinTheTimeoutIsADummy() {
        // Nothing answers: the 1 s receive timeout is the whole outcome.
        listener().use { socket ->
            val response = runBlocking { pingServer(server(socket.localPort), SrvResolver()) }
            assertTrue(response.isDummy)
            assertNull(response.server)
        }
    }

    @Test
    fun aReplyFromAnotherPortIsDropped() {
        // The reply is checked against the address and port actually queried,
        // so an off-path sender cannot poison the list. A different source
        // port is enough to make it a dummy, without waiting for the timeout.
        listener().use { queried ->
            listener().use { other ->
                val (worker, failure) = responder {
                    val (_, packet) = receiveRequest(queried)
                    val answer = reply(identifier = 42L)
                    other.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                }

                val response = runBlocking { pingServer(server(queried.localPort), SrvResolver()) }
                assertResponded(worker, failure)

                assertTrue("off-path reply is a dummy", response.isDummy)
                assertNull(response.server)
            }
        }
    }
}
