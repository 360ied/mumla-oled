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

package se.lublin.humla.state

import junit.framework.TestCase
import org.junit.Test

/** Pins intent coercion, gating, whisper allocation, and volume recording. */
class IntentTransitionsTest : TestCase() {
    private fun syncedSelf(): SessionState {
        var s = emptySessionState()
        s = s.copy(server = s.server.copy(phase = SyncPhase.SYNCED, selfSession = 1))
        var users = s.server.users
        users = users.putting(
            1,
            UserInfo(
                1, null, "self", 0, null, null, null, null, null,
                false, false, false, false, false, false, false,
                kotlinx.collections.immutable.persistentSetOf(), false, false,
            ),
        )
        return s.copy(server = s.server.copy(users = users))
    }

    @Test
    fun testSelfMuteCoercionAndOptimistic() {
        val s = syncedSelf()
        // Deafen implies mute.
        var r = applySelfMuteDeafIntent(s, false, true)
        assertTrue(r.state.local.selfMuteDesired == true)
        assertTrue(r.state.local.selfDeafDesired == true)
        assertTrue(r.state.server.users[1]?.selfMuted == true)
        assertNotNull(r.userStateOutbound)
        assertEquals(1, r.events.filterIsInstance<SessionEvent.SelfMuteStateChanged>().size)
        // Unmute implies undeafen.
        r = applySelfMuteDeafIntent(r.state, false, false)
        assertFalse(r.state.local.selfMuteDesired == true)
        assertFalse(r.state.local.selfDeafDesired == true)
    }

    @Test
    fun testSelfMuteNeedsSync() {
        val s = emptySessionState()
        val r = applySelfMuteDeafIntent(s, true, false)
        assertEquals(s, r.state)
        assertTrue(r.events.isEmpty())
        assertNull(r.userStateOutbound)
    }

    @Test
    fun testWhisperAllocationUpTo30() {
        var s = syncedSelf()
        val ids = mutableListOf<Int>()
        for (i in 1..30) {
            val r = applyRegisterWhisperIntent(s, WhisperTarget(1, false, false, null))
            assertTrue(r.whisperId in 1..30)
            ids.add(r.whisperId)
            s = r.state
        }
        assertEquals((1..30).toList(), ids.sorted())
        val full = applyRegisterWhisperIntent(s, WhisperTarget(1, false, false, null))
        assertEquals(-1, full.whisperId)
        // Free one and reallocate the lowest.
        s = applyUnregisterWhisperIntent(s, 5).state
        val r = applyRegisterWhisperIntent(s, WhisperTarget(2, true, false, "g"))
        assertEquals(5, r.whisperId)
        // Outside range ignored.
        assertEquals(s, applyUnregisterWhisperIntent(s, 31).state)
    }

    @Test
    fun testListeningVolumeRecordsAndNeedsSync() {
        val s = syncedSelf()
        val r = applyListeningVolumeIntent(s, 3, 0.5f)
        assertEquals(0.5f, r.state.local.listeningVolumes[3])
        assertNotNull(r.userStateOutbound)
        val unsynced = applyListeningVolumeIntent(emptySessionState(), 3, 0.5f)
        assertEquals(emptySessionState(), unsynced.state)
    }

    @Test
    fun testVoiceTargetRange() {
        val s = syncedSelf()
        assertEquals(7, applyVoiceTargetIntent(s, 7).state.local.voiceTargetId)
        assertEquals(s, applyVoiceTargetIntent(s, 32).state)
    }
}
