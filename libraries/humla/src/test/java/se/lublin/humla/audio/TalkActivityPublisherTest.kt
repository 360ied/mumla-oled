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

package se.lublin.humla.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.model.TalkState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.applyChannelState
import se.lublin.humla.state.applyUserState
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.humla.voice.TalkActivityPublisher

/**
 * Talk-level tests for the service-lifetime publisher (5a.4): the native
 * notifier is the only writer, publishing remote and self atomically via
 * [TalkActivityPublisher.updateAll]; Stop clears through the same path
 * (native clears engines/rings, notifier delivers the final empty
 * snapshot). Handles read live (absent means passive).
 *
 * The step-2 stuck-indicator regression (comparing a fresh handle's
 * talkState against a new event) is structurally impossible here: nothing
 * stamps handles anymore.
 */
class TalkActivityPublisherTest {

    private fun channelMsg(id: Int, name: String, parent: Int? = null): Mumble.ChannelState {
        val b = Mumble.ChannelState.newBuilder().setChannelId(id).setName(name)
        if (parent != null) b.parent = parent
        return b.build()
    }

    private fun userMsg(session: Int, name: String): Mumble.UserState =
        Mumble.UserState.newBuilder().setSession(session).setName(name).build()

    private fun flowWithUser(): MutableStateFlow<SessionState> {
        var s = emptySessionState()
        s = applyChannelState(s, channelMsg(0, "Root")).state
        s = applyChannelState(s, channelMsg(1, "A", 0)).state
        s = applyUserState(s, userMsg(2, "other")).state
        s = applyUserState(s, Mumble.UserState.newBuilder().setSession(2).setChannelId(1).build()).state
        return MutableStateFlow(s)
    }

    @Test
    fun `remote table replaces wholesale and absent means passive`() {
        val publisher = TalkActivityPublisher()
        val flow = flowWithUser()

        assertEquals(
            TalkState.PASSIVE,
            UserHandle(2, flow, publisher.talkActivity).talkState,
        )
        publisher.updateAll(mapOf(2 to TalkState.TALKING), false)
        assertEquals(
            TalkState.TALKING,
            UserHandle(2, flow, publisher.talkActivity).talkState,
        )
        publisher.updateAll(mapOf(2 to TalkState.SHOUTING), false)
        assertEquals(
            TalkState.SHOUTING,
            UserHandle(2, flow, publisher.talkActivity).talkState,
        )
        // A fresh table without the user reads passive, no event needed.
        publisher.updateAll(emptyMap(), false)
        assertEquals(
            TalkState.PASSIVE,
            UserHandle(2, flow, publisher.talkActivity).talkState,
        )
    }

    @Test
    fun `same table twice emits once`() = runTest {
        val publisher = TalkActivityPublisher()
        val emissions = mutableListOf<Map<Int, TalkState>>()
        val job = launch(Dispatchers.Unconfined) {
            publisher.talkActivity.collect { emissions.add(it.users.toMap()) }
        }
        publisher.updateAll(mapOf(2 to TalkState.TALKING), false)
        publisher.updateAll(mapOf(2 to TalkState.TALKING), false)
        publisher.updateAll(mapOf(2 to TalkState.WHISPERING), false)
        job.cancel()
        // Initial + TALKING + WHISPERING: the repeated table emits nothing.
        assertEquals(3, emissions.size)
        assertTrue(emissions[0].isEmpty())
        assertEquals(TalkState.TALKING, emissions[1][2])
        assertEquals(TalkState.WHISPERING, emissions[2][2])
    }

    @Test
    fun `notifier empty snapshot clears users after talking`() {
        val publisher = TalkActivityPublisher()
        val flow = flowWithUser()
        publisher.updateAll(mapOf(2 to TalkState.TALKING), true)
        assertEquals(
            TalkState.TALKING,
            UserHandle(2, flow, publisher.talkActivity).talkState,
        )
        // Stop: native clears engines/rings and the notifier delivers the
        // final empty snapshot (remote + self atomically); no stale entry
        // lights the indicator (or a different user, since Murmur reuses
        // IDs), and no Kotlin writer is involved.
        publisher.updateAll(emptyMap(), false)
        assertTrue(publisher.talkActivity.value.users.isEmpty())
        assertEquals(
            TalkState.PASSIVE,
            UserHandle(2, flow, publisher.talkActivity).talkState,
        )
    }

    @Test
    fun `self talking toggles without touching remote`() {
        val publisher = TalkActivityPublisher()
        publisher.updateAll(mapOf(2 to TalkState.TALKING), true)
        assertTrue(publisher.talkActivity.value.selfTalking)
        assertEquals(1, publisher.talkActivity.value.users.size)
        publisher.updateAll(mapOf(2 to TalkState.TALKING), true)
        publisher.updateAll(mapOf(2 to TalkState.TALKING), false)
        assertTrue(!publisher.talkActivity.value.selfTalking)
        assertEquals(1, publisher.talkActivity.value.users.size)
    }

    @Test
    fun `stop snapshot resets self talking after disconnect`() {
        val publisher = TalkActivityPublisher()
        // Speaking exactly at disconnect: the final notifier snapshot
        // carries self false atomically with the emptied remote table, so
        // true cannot survive into the next connection with no emission to
        // correct it (a later true is a no-op dedup).
        publisher.updateAll(mapOf(2 to TalkState.TALKING), true)
        publisher.updateAll(emptyMap(), false)
        assertEquals(TalkActivity.initial(), publisher.talkActivity.value)
    }
}
