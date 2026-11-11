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

package se.lublin.humla.audio

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserHandle
import se.lublin.humla.state.applyChannelState
import se.lublin.humla.state.applyUserState
import se.lublin.humla.state.emptySessionState

/**
 * Regression test: remote talking indicators stuck on after one utterance.
 *
 * `HumlaService.getUser()` returns a fresh live `UserHandle` per call
 * (default `talkState == PASSIVE`), so `AudioOutput` must not dedup
 * against `user.talkState`: the `PASSIVE` following `TALKING` compares
 * equal on a fresh handle and would never be broadcast. Native already
 * emits transitions only (`recordTalkLocked`/`lastReportedState`).
 */
@RunWith(RobolectricTestRunner::class)
class AudioOutputTalkStateTest {

    private class RecordingListener(
        private val flow: MutableStateFlow<SessionState>,
    ) : AudioOutput.AudioOutputListener {
        val emitted = mutableListOf<TalkState>()

        // Fresh handle per call, mirroring HumlaService.getUser().
        override fun getUser(session: Int): IUser? {
            if (flow.value.server.users[session] == null) return null
            return UserHandle(session, flow)
        }

        override fun onUserTalkStateUpdated(user: IUser) {
            emitted.add(user.talkState)
        }
    }

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
    fun `passive following talking is emitted`() {
        val flow = flowWithUser()
        val listener = RecordingListener(flow)
        val output = AudioOutput(listener)

        output.onTalkStateChanged(2, NativeAudioOutputEngine.TALK_TALKING)
        output.onTalkStateChanged(2, NativeAudioOutputEngine.TALK_PASSIVE)
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(TalkState.TALKING, TalkState.PASSIVE), listener.emitted)
    }

    @Test
    fun `unknown session emits nothing`() {
        val flow = flowWithUser()
        val listener = RecordingListener(flow)
        val output = AudioOutput(listener)

        output.onTalkStateChanged(99, NativeAudioOutputEngine.TALK_TALKING)
        ShadowLooper.idleMainLooper()

        assertTrue(listener.emitted.isEmpty())
    }
}
