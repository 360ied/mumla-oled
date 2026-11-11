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

package se.lublin.humla.voice

import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.emptySessionState
import kotlinx.collections.immutable.persistentSetOf

private fun selfUser(
    session: Int = 1,
    muted: Boolean = false,
    deafened: Boolean = false,
    suppressed: Boolean = false,
    selfMuted: Boolean = false,
    selfDeafened: Boolean = false,
    localMuted: Boolean = false,
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
    muted = muted,
    deafened = deafened,
    suppressed = suppressed,
    selfMuted = selfMuted,
    selfDeafened = selfDeafened,
    prioritySpeaker = false,
    recording = false,
    listeningChannels = persistentSetOf(),
    localMuted = localMuted,
    localIgnored = false,
)

class VoicePolicyTest {
    private fun syncedSelf(
        self: UserInfo = selfUser(),
        maxBandwidth: Int? = null,
        pttPressed: Boolean = false,
        voiceTargetId: Int = 0,
        extraUsers: Map<Int, UserInfo> = emptyMap(),
    ) = emptySessionState().copy(
        server = emptySessionState().server.copy(
            phase = SyncPhase.SYNCED,
            selfSession = self.session,
            users = persistentMapOf(self.session to self).puttingAll(extraUsers),
            info = emptySessionState().server.info.copy(maxBandwidth = maxBandwidth),
        ),
        local = emptySessionState().local.copy(
            pttPressed = pttPressed,
            voiceTargetId = voiceTargetId,
        ),
    )

    @Test
    fun transmitAllowedWhenSyncedAndUnmuted() {
        val policy = voicePolicy(syncedSelf(), VoiceSettings.initial())
        assertTrue(policy.transmitAllowed)
        assertEquals(40000, policy.bitrate)
        assertEquals(2, policy.framesPerPacket)
    }

    @Test
    fun transmitBlockedBeforeSync() {
        val s = emptySessionState()
        val policy = voicePolicy(s, VoiceSettings.initial())
        assertFalse(policy.transmitAllowed)
    }

    @Test
    fun transmitBlockedByDesiredMuteAndServerMute() {
        val desiredMuted = syncedSelf().copy(
            local = syncedSelf().local.copy(selfMuteDesired = true, selfDeafDesired = false),
        )
        assertFalse(voicePolicy(desiredMuted, VoiceSettings.initial()).transmitAllowed)

        val serverMuted = syncedSelf(selfUser(muted = true))
        assertFalse(voicePolicy(serverMuted, VoiceSettings.initial()).transmitAllowed)

        val suppressed = syncedSelf(selfUser(suppressed = true))
        assertFalse(voicePolicy(suppressed, VoiceSettings.initial()).transmitAllowed)

        val deafened = syncedSelf(selfUser(deafened = true))
        assertFalse(voicePolicy(deafened, VoiceSettings.initial()).transmitAllowed)
    }

    @Test
    fun failClosedDeafEchoClosesGate() {
        val s = syncedSelf(selfUser(selfMuted = false, selfDeafened = true))
        assertFalse(voicePolicy(s, VoiceSettings.initial()).transmitAllowed)
    }

    @Test
    fun bandwidthCapLowersEncoder() {
        val s = syncedSelf(maxBandwidth = 20000)
        val policy = voicePolicy(s, VoiceSettings.initial())
        assertTrue(policy.bitrate < 40000 || policy.framesPerPacket != 2)
    }

    @Test
    fun locallyMutedSessionsCollected() {
        val other = selfUser(session = 2, localMuted = true).copy(name = "other")
        val s = syncedSelf(extraUsers = mapOf(2 to other))
        val policy = voicePolicy(s, VoiceSettings.initial())
        assertTrue(policy.locallyMuted.contains(2))
        assertFalse(policy.locallyMuted.contains(1))
    }

    @Test
    fun pttAndVoiceTargetPassThrough() {
        val s = syncedSelf(pttPressed = true, voiceTargetId = 7)
        val policy = voicePolicy(s, VoiceSettings.initial())
        assertTrue(policy.pttPressed)
        assertEquals(7, policy.voiceTarget)
    }
}
