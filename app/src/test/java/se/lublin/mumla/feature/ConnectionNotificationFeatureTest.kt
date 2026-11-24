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

package se.lublin.mumla.feature

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.service.MumlaService

/** Notification variants, permission re-show, actions and backpressure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionNotificationFeatureTest {

    private class Actions : NotificationActions {
        val calls = ArrayList<String>()
        override fun onMuteToggled() { calls.add("mute") }
        override fun onMediaKeyMuteToggled() { calls.add("media") }
        override fun onDeafenToggled() { calls.add("deafen") }
        override fun onOverlayToggled() { calls.add("overlay") }
        override fun onCancelReconnect() { calls.add("cancel") }
        override fun onDisconnect() { calls.add("disconnect") }
    }

    private class Fixture(refreshSink: (suspend () -> Unit)? = null) {
        val platform = FakeFeaturePlatform()
        val actions = Actions()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val session = MutableStateFlow(emptySessionState())
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 256)
        val talk = MutableStateFlow(TalkActivity.initial())
        val settings = MutableStateFlow(testSnapshot())
        val feature = ConnectionNotificationFeature(
            RuntimeEnvironment.getApplication(), state, session, events, talk, settings,
            platform, actions, { false }, refreshSink,
        )
    }

    @Test fun variantsFollowClientStateAndHideOnIdle() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        assertTrue(f.platform.notification.connecting >= 1)
        assertTrue(f.feature.visible.value)
        f.state.value = connectedState()
        assertEquals(1, f.platform.notification.connected)
        f.state.value = ClientState.Reconnecting(
            emptyConnectTarget(), 2, 0L,
            ClientError.Connection("boom", null), null,
        )
        assertEquals(1, f.platform.notification.reconnecting)
        f.state.value = ClientState.Idle
        assertFalse(f.feature.visible.value)
        assertTrue(f.platform.notification.hidden > 0)
    }

    @Test fun failedStateHidesTheConnectionNotification() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = ClientState.Connecting(emptyConnectTarget(), 0)
        assertTrue(f.feature.visible.value)
        f.state.value = ClientState.Failed(
            emptyConnectTarget(), ClientError.Connection("boom", null),
        )
        assertFalse(f.feature.visible.value)
        assertTrue(f.platform.notification.hidden > 0)
    }

    @Test fun permissionDeniedReshowsUnlessSuppressed() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = connectedState()
        val denied = SessionEventRecord(
            SessionEvent.PermissionDenied(-1, null, null, null, null, null),
            goldenState(),
        )
        f.events.emit(denied)
        kotlinx.coroutines.yield()
        assertTrue(f.platform.notification.shown > 0)
        f.feature.setSuppressNotifications(true)
        val before = f.platform.notification.shown
        f.events.emit(denied)
        kotlinx.coroutines.yield()
        assertEquals(before, f.platform.notification.shown)
    }

    @Test fun explicitActionsReachTheContainer() {
        val f = Fixture()
        f.feature.handleAction(MumlaService.ACTION_MUTE)
        f.feature.handleAction(MumlaService.ACTION_DISCONNECT)
        assertEquals(listOf("mute", "disconnect"), f.actions.calls)
    }

    @Test fun slowNotificationManagerNeverStallsTheEmitter() = runFeature { scope ->
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(refreshSink = { gate.await() })
        f.feature.start(scope)
        val denied = SessionEventRecord(
            SessionEvent.PermissionDenied(-1, null, null, null, null, null),
            goldenState(),
        )
        kotlinx.coroutines.runBlocking {
            withTimeout(2_000) { repeat(300) { f.events.emit(denied) } }
        }
        gate.complete(Unit)
    }
}
