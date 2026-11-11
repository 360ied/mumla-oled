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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
