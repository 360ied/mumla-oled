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

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.minidns.record.SRV
import se.lublin.humla.Constants

/**
 * Pins the one SRV rule the transport's connect path and the server-list
 * pings share (docs/target-architecture/steps/8-clean-up.md, commit 1).
 * Every case drives a fake blocking lookup; the real MiniDNS call is the
 * one line of [SrvResolver]'s default, checked on device only.
 */
class SrvResolverTest {

    private val lookups = AtomicInteger(0)

    private fun record(priority: Int, weight: Int, port: Int, target: String) =
        SRV(priority, weight, port, target)

    /** A resolver whose lookup records calls and returns [records]. */
    private fun resolver(
        records: List<SRV> = emptyList(),
        timeoutMs: Long = SrvResolver.DEFAULT_TIMEOUT_MS,
    ) = SrvResolver(timeoutMs) {
        lookups.incrementAndGet()
        records
    }

    @Test
    fun explicitPortSkipsTheLookup() = runTest {
        val target = resolver(listOf(record(0, 0, 64738, "srv.example.org."))).resolve(
            "chat.example.org",
            1234,
        )
        assertEquals(SrvTarget("chat.example.org", 1234), target)
        assertEquals(0, lookups.get())
    }

    @Test
    fun ipLiteralSkipsTheLookup() = runTest {
        val target = resolver().resolve("192.0.2.10", 0)
        assertEquals(SrvTarget("192.0.2.10", Constants.DEFAULT_PORT), target)
        assertEquals(0, lookups.get())
    }

    @Test
    fun ipv6LiteralSkipsTheLookup() = runTest {
        val target = resolver().resolve("2001:db8::1", 0)
        assertEquals(SrvTarget("2001:db8::1", Constants.DEFAULT_PORT), target)
        assertEquals(0, lookups.get())
    }

    @Test
    fun onionHostSkipsTheLookup() = runTest {
        val target = resolver().resolve("chat.example.onion", 0)
        assertEquals(SrvTarget("chat.example.onion", Constants.DEFAULT_PORT), target)
        assertEquals(0, lookups.get())
    }

    // The lookup runs on a real IO thread, so the tests that expect a
    // record use real time: under runTest's virtual clock the 800 ms bound
    // would elapse before the dispatcher ever ran the fake.
    @Test
    fun firstSortedRecordWinsWithoutItsTrailingDot() = runBlocking {
        // Returned deliberately unordered: sorting is the resolver's rule.
        val target = resolver(
            listOf(
                record(20, 0, 10002, "later.example.org."),
                record(10, 0, 10001, "first.example.org."),
            ),
        ).resolve("chat.example.org", 0)
        assertEquals(SrvTarget("first.example.org", 10001), target)
        assertEquals(1, lookups.get())
    }

    @Test
    fun emptyAnswerFallsBackToTheTypedHost() = runBlocking {
        val target = resolver(emptyList()).resolve("chat.example.org", 0)
        assertEquals(SrvTarget("chat.example.org", Constants.DEFAULT_PORT), target)
        assertEquals(1, lookups.get())
    }

    @Test
    fun failedLookupFallsBackToTheTypedHost() = runBlocking {
        val resolver = SrvResolver { throw IOException("no dns today") }
        val target = resolver.resolve("chat.example.org", 0)
        assertEquals(SrvTarget("chat.example.org", Constants.DEFAULT_PORT), target)
    }

    @Test
    fun timeoutFallsBackEvenWhenTheLookupIgnoresInterruption() = runTest {
        // Ignores interruption for a bounded real-time window, like a
        // blocking resolver that only notices cancellation at the socket
        // timeout: the caller must not wait for it. The answer it eventually
        // produces is a valid record, so a resolver whose timeout does not
        // hold would return that port instead of the default.
        val started = java.util.concurrent.CountDownLatch(1)
        val resolver = SrvResolver(timeoutMs = 20) {
            started.countDown()
            val deadline = System.nanoTime() + 200_000_000L
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5)
                } catch (ignored: InterruptedException) {
                    // Deliberately swallowed: keep blocking until the deadline.
                }
            }
            listOf(record(0, 0, 12345, "late.example.org."))
        }
        val target = withTimeout(2_000) { resolver.resolve("chat.example.org", 0) }
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(SrvTarget("chat.example.org", Constants.DEFAULT_PORT), target)
        // Virtual time is the only clock the fake cannot outrun, so the
        // scheduler's time is the injected bound: a resolver that waited
        // longer (or not at all) would return the late record or overshoot.
        assertTrue(
            "timeout fires at the injected bound, not later",
            testScheduler.currentTime <= 20,
        )
    }

    @Test
    fun callerCancellationPropagates() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val thrown = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val resolver = SrvResolver(timeoutMs = 10_000) {
            started.countDown()
            Thread.sleep(5_000)
            emptyList()
        }
        val caller = launch(Dispatchers.Default) {
            try {
                resolver.resolve("chat.example.org", 0)
            } catch (e: Throwable) {
                thrown.set(e)
            }
        }
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        caller.cancelAndJoin()
        // The caller's cancellation surfaces from resolve instead of being
        // reported as a resolution failure.
        assertTrue(thrown.get() is CancellationException)
    }
}
