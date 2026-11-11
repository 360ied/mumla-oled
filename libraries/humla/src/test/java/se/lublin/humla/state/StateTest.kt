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

import com.google.protobuf.ByteString
import junit.framework.TestCase
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test

private fun userWithMute(
    session: Int,
    selfMuted: Boolean,
    selfDeafened: Boolean,
): UserInfo = UserInfo(
    session = session,
    userId = null,
    name = "self",
    channelId = 0,
    certHash = null,
    comment = null,
    commentHash = null,
    texture = null,
    textureHash = null,
    muted = false,
    deafened = false,
    suppressed = false,
    selfMuted = selfMuted,
    selfDeafened = selfDeafened,
    prioritySpeaker = false,
    recording = false,
    listeningChannels = persistentSetOf(),
    localMuted = false,
    localIgnored = false,
)

/** Pins empty state, the self-mute gate, and small derived reads. */
class StateTest : TestCase() {
    @Test
    fun testEmptyStateIsHandshaking() {
        val s = emptySessionState()
        assertEquals(SyncPhase.HANDSHAKING, s.server.phase)
        assertNull(s.server.selfSession)
        assertNull(s.server.self)
        assertTrue(s.server.channels.isEmpty())
        assertTrue(s.server.users.isEmpty())
        // No self yet: gate fails closed.
        assertTrue(effectiveSelfMute(s))
    }

    @Test
    fun testEffectiveSelfMuteDesiredWins() {
        fun state(desiredMute: Boolean?, desiredDeaf: Boolean?, echoMute: Boolean, echoDeaf: Boolean): SessionState {
            val self = userWithMute(1, echoMute, echoDeaf)
            return emptySessionState().copy(
                server = emptySessionState().server.copy(
                    selfSession = 1,
                    users = persistentMapOf(1 to self),
                ),
                local = emptySessionState().local.copy(
                    selfMuteDesired = desiredMute,
                    selfDeafDesired = desiredDeaf,
                ),
            )
        }
        // No desired: echo decides.
        assertFalse(effectiveSelfMute(state(null, null, false, false)))
        assertTrue(effectiveSelfMute(state(null, null, true, false)))
        // Desired mute wins over an unmuted echo (optimistic latch).
        assertTrue(effectiveSelfMute(state(true, null, false, false)))
        assertFalse(effectiveSelfMute(state(false, false, false, false)))
        // Fail-closed: echoed deaf closes the gate even when desired says open.
        assertTrue(effectiveSelfMute(state(false, false, false, true)))
        assertTrue(effectiveSelfMute(state(null, null, false, true)))
        // Deaf desired implies mute at the gate.
        assertTrue(effectiveSelfMute(state(null, true, false, false)))
    }

    @Test
    fun testVoiceTargetModeDerivesFromLocal() {
        val base = emptySessionState()
        assertEquals(VoiceTargetMode.NORMAL, base.local.voiceTargetMode())
        assertEquals(
            VoiceTargetMode.WHISPER,
            base.copy(local = base.local.copy(voiceTargetId = 7)).local.voiceTargetMode(),
        )
        assertEquals(
            VoiceTargetMode.SERVER_LOOPBACK,
            base.copy(local = base.local.copy(voiceTargetId = 31)).local.voiceTargetMode(),
        )
    }

    @Test
    fun testHasTexture() {
        val base = userWithMute(1, false, false)
        assertFalse(base.hasTexture())
        assertFalse(base.copy(texture = ByteString.EMPTY).hasTexture())
        assertTrue(base.copy(texture = ByteString.copyFrom(byteArrayOf(1, 2))).hasTexture())
    }

    @Test
    fun testUserIdNullWhenUnregistered() {
        val base = userWithMute(1, false, false)
        assertNull(base.userId)
        assertEquals(42, base.copy(userId = 42).userId)
    }
}
