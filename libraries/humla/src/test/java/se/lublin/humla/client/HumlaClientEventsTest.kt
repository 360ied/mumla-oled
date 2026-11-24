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

package se.lublin.humla.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord

/**
 * Pins the step-6 event stream: one application-lifetime flow whose records
 * carry the emitting session's state right after the transition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HumlaClientEventsTest {
    @Test
    fun recordsCarryTheStateThatProducedThem() = runTest {
        val harness = ClientHarness(this)
        val records = mutableListOf<SessionEventRecord>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.client.events.collect { records.add(it) }
        }

        harness.connectAndSync()
        harness.session.setSelfMuteDeaf(true, false)
        harness.step()

        val mute = records.map { it.event }
            .filterIsInstance<SessionEvent.SelfMuteStateChanged>()
            .lastOrNull()
        assertTrue("self-mute intent must emit an event", mute != null)
        assertTrue("record must carry the post-transition state",
            records.any { it.event is SessionEvent.SelfMuteStateChanged &&
                it.state.local.selfMuteDesired == true })

        collector.cancel()
        harness.client.close().join()
        harness.collector.close()
    }

    @Test
    fun aBurstOfEventsNeverStallsASlowSubscriber() = runTest {
        val harness = ClientHarness(this)
        val gate = CompletableDeferred<Unit>()
        val slow = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.client.events.collect { gate.await() }
        }
        val seen = mutableListOf<SessionEventRecord>()
        val fast = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.client.events.collect { seen.add(it) }
        }
        harness.connectAndSync()
        val baseline = seen.size
        repeat(300) { i ->
            check(
                harness.transport.inboundChannel.trySend(
                    Inbound.Message(
                        HumlaTCPMessageType.UserState,
                        userState(1000 + i, "burst$i", 0),
                    ),
                ).isSuccess,
            )
        }
        harness.step()
        // DROP_OLDEST plus tryEmit: 300 events exceed the 256-slot buffer,
        // but a fast subscriber still sees every one and emitAll never stalls.
        assertEquals(baseline + 300, seen.size)
        gate.complete(Unit)
        slow.cancel()
        fast.cancel()
        harness.finish()
    }

    @Test
    fun oneAppLifetimeStreamSeesBothSessions() = runTest {
        val harness = ClientHarness(this)
        val records = mutableListOf<SessionEventRecord>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.client.events.collect { records.add(it) }
        }

        harness.connectAndSync()
        harness.deliver(HumlaTCPMessageType.UserState, userState(7, "first-session", 0))
        val afterFirst = records.size
        assertTrue(afterFirst > 0)

        harness.client.disconnect()
        harness.step()
        harness.connectAndSync()
        harness.deliver(HumlaTCPMessageType.UserState, userState(8, "second-session", 0))

        assertTrue(records.size > afterFirst)
        assertTrue(
            "the same collector must see events from the second session",
            records.drop(afterFirst).any { it.event is SessionEvent.UserJoinedServer },
        )
        collector.cancel()
        harness.finish()
    }
}
