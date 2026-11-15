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
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.SyncPhase
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.emptySessionState

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyCollectorTest {
    @Test
    fun publishesMutedFallbackWhenDisconnected() = runTest {
        val states = MutableStateFlow<se.lublin.humla.state.SessionState?>(null)
        val settings = MutableStateFlow(VoiceSettings.initial())
        // Child scope: close() cancels only this child, never the TestScope.
        val child = kotlinx.coroutines.CoroutineScope(
            coroutineContext + kotlinx.coroutines.SupervisorJob(),
        )
        val collector = PolicyCollector(
            sessionStates = PolicyCollector.FlowOfStates { states },
            settings = settings,
            appScope = child,
            dispatcher = testScheduler.let {
                @OptIn(ExperimentalCoroutinesApi::class)
                kotlinx.coroutines.test.StandardTestDispatcher(it)
            },
        )
        runCurrent()
        assertFalse(collector.policy.value.transmitAllowed)
        collector.close()
        runCurrent()
    }

    @Test
    fun updatesWhenSessionConnects() = runTest {
        val states = MutableStateFlow<se.lublin.humla.state.SessionState?>(null)
        val settings = MutableStateFlow(VoiceSettings.initial())
        // Child scope: close() cancels only this child, never the TestScope.
        val child = kotlinx.coroutines.CoroutineScope(
            coroutineContext + kotlinx.coroutines.SupervisorJob(),
        )
        val collector = PolicyCollector(
            sessionStates = PolicyCollector.FlowOfStates { states },
            settings = settings,
            appScope = child,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertFalse(collector.policy.value.transmitAllowed)
        states.value = emptySessionState()
        runCurrent()
        // HANDSHAKING without self still fails closed.
        assertFalse(collector.policy.value.transmitAllowed)
        collector.close()
        runCurrent()
    }

    @Test
    fun publishesTransmittingPolicyWhenSynced() = runTest {
        val states = MutableStateFlow<se.lublin.humla.state.SessionState?>(null)
        val settings = MutableStateFlow(VoiceSettings.initial())
        // Child scope: close() cancels only this child, never the TestScope.
        val child = kotlinx.coroutines.CoroutineScope(
            coroutineContext + kotlinx.coroutines.SupervisorJob(),
        )
        val collector = PolicyCollector(
            sessionStates = PolicyCollector.FlowOfStates { states },
            settings = settings,
            appScope = child,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertFalse(collector.policy.value.transmitAllowed)
        val self = UserInfo(
            session = 1,
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
            selfMuted = false,
            selfDeafened = false,
            prioritySpeaker = false,
            recording = false,
            listeningChannels = persistentSetOf(),
            localMuted = false,
            localIgnored = false,
        )
        states.value = emptySessionState().copy(
            server = emptySessionState().server.copy(
                phase = SyncPhase.SYNCED,
                selfSession = self.session,
                users = persistentMapOf(self.session to self),
            ),
        )
        runCurrent()
        assertTrue(collector.policy.value.transmitAllowed)
        collector.close()
        runCurrent()
    }

    private fun syncedState(self: UserInfo, extraUsers: Map<Int, UserInfo> = emptyMap()): SessionState =
        emptySessionState().copy(
            server = emptySessionState().server.copy(
                phase = SyncPhase.SYNCED,
                selfSession = self.session,
                users = persistentMapOf(self.session to self).puttingAll(extraUsers),
            ),
        )

    private fun selfUser(
        session: Int = 1,
        muted: Boolean = false,
        localMuted: Boolean = false,
    ): UserInfo = UserInfo(
        session = session,
        userId = null,
        name = if (session == 1) "self" else "other",
        channelId = 0,
        certHash = null,
        comment = null,
        commentHash = null,
        texture = null,
        textureHash = null,
        muted = muted,
        deafened = false,
        suppressed = false,
        selfMuted = false,
        selfDeafened = false,
        prioritySpeaker = false,
        recording = false,
        listeningChannels = persistentSetOf(),
        localMuted = localMuted,
        localIgnored = false,
    )

    private fun TestScope.collectorOver(
        states: MutableStateFlow<SessionState?>,
    ): PolicyCollector {
        val settings = MutableStateFlow(VoiceSettings.initial())
        val child = kotlinx.coroutines.CoroutineScope(
            coroutineContext + kotlinx.coroutines.SupervisorJob(),
        )
        return PolicyCollector(
            sessionStates = PolicyCollector.FlowOfStates { states },
            settings = settings,
            appScope = child,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
    }

    @Test
    fun selfMuteWhileSyncedClosesGate() = runTest {
        val states = MutableStateFlow<SessionState?>(null)
        val collector = collectorOver(states)
        runCurrent()
        states.value = syncedState(selfUser())
        runCurrent()
        assertTrue(collector.policy.value.transmitAllowed)
        states.value = syncedState(selfUser(muted = true))
        runCurrent()
        assertFalse(collector.policy.value.transmitAllowed)
        collector.close()
        runCurrent()
    }

    @Test
    fun disconnectReturnsToFallback() = runTest {
        val states = MutableStateFlow<SessionState?>(null)
        val collector = collectorOver(states)
        runCurrent()
        states.value = syncedState(selfUser())
        runCurrent()
        assertTrue(collector.policy.value.transmitAllowed)
        states.value = null
        runCurrent()
        assertFalse(collector.policy.value.transmitAllowed)
        assertEquals(VoicePolicy.mutedFallback(VoiceSettings.initial()), collector.policy.value)
        collector.close()
        runCurrent()
    }

    @Test
    fun locallyMutedFollowsUserRemoval() = runTest {
        val states = MutableStateFlow<SessionState?>(null)
        val collector = collectorOver(states)
        runCurrent()
        val other = selfUser(session = 2, localMuted = true)
        states.value = syncedState(selfUser(), mapOf(2 to other))
        runCurrent()
        assertTrue(collector.policy.value.locallyMuted.contains(2))
        states.value = syncedState(selfUser())
        runCurrent()
        assertFalse(collector.policy.value.locallyMuted.contains(2))
        assertTrue(collector.policy.value.transmitAllowed)
        collector.close()
        runCurrent()
    }
}
