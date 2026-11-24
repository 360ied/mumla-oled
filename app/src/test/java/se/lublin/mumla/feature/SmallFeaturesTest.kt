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

import android.content.res.Configuration
import com.google.protobuf.ByteString
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.audio.BluetoothScoManager
import se.lublin.humla.client.ClientState
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RouteEvent
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.StreamProfile
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.TalkKeyEvent

/** Hot corner, proximity, permission guard, SCO toast, avatars, reconnect. */
class SmallFeaturesTest {

    @Test fun hotCornerGesturesGoIntoTheHandOffFlow() = runFeature { scope ->
        val platform = FakeFeaturePlatform()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val talk = MutableStateFlow(TalkActivity.initial())
        val configuration = MutableStateFlow(Configuration())
        val settings = MutableStateFlow(testSnapshot(hotCorner = Settings.ARRAY_HOT_CORNER_TOP_LEFT))
        val emitted = ArrayList<TalkKeyEvent>()
        HotCornerFeature(state, talk, configuration, settings, platform) { emitted.add(it) }
            .start(scope)
        state.value = connectedState()
        assertTrue(platform.hotCorner.shown)
        talk.value = TalkActivity(true, persistentMapOf())
        assertTrue(platform.hotCorner.talking)
        configuration.value = Configuration()
        assertTrue(platform.hotCorner.refreshes > 0)
        state.value = ClientState.Idle
        assertFalse(platform.hotCorner.shown)
    }

    @Test fun proximityLockFollowsHandsetModeAndSco() = runFeature { scope ->
        val platform = FakeFeaturePlatform()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val route = MutableStateFlow<RouteState>(RouteState.Idle)
        val settings = MutableStateFlow(testSnapshot(handsetMode = true))
        ProximityFeature(state, route, settings, platform).start(scope)
        state.value = connectedState()
        assertTrue(platform.lock.held)
        route.value = RouteState.Ready(AppliedRoute.VOICE_SCO, StreamProfile.VOICE)
        assertFalse(platform.lock.held)
    }

    @Test fun bluetoothGuardRevertsWithoutTheGrant() = runFeature { scope ->
        val platform = FakeFeaturePlatform()
        platform.grant = false
        val settings = MutableStateFlow(testSnapshot(bluetoothHeadset = false))
        BluetoothPermissionGuardFeature(settings, platform).start(scope)
        settings.value = testSnapshot(bluetoothHeadset = true)
        assertEquals(listOf(false), platform.bluetoothWrites)
        assertEquals(listOf(R.string.grant_perm_bluetooth), platform.toasts)
    }

    @Test fun scoFailureToastsTheBucketedMessage() = runFeature { scope ->
        val platform = FakeFeaturePlatform()
        val events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 8)
        ScoFailureFeature(events, platform).start(scope)
        events.emit(RouteEvent.ScoFailed(BluetoothScoManager.REASON_PERMISSION))
        assertEquals(listOf(R.string.bluetooth_sco_permission), platform.toasts)
        events.emit(RouteEvent.ScoFailed(null))
        assertEquals(
            listOf(R.string.bluetooth_sco_permission, R.string.bluetooth_sco_failed),
            platform.toasts,
        )
    }

    @Test fun avatarRequestHappensOncePerHashAndForgetsOnSessionEnd() = runFeature { scope ->
        val requested = ArrayList<Int>()
        val session = MutableStateFlow(emptySessionState())
        AvatarRequestsFeature(session) { requested.add(it) }.start(scope)
        val base = goldenState()
        val user = base.server.users.getValue(2)
        val withHash = base.copy(
            server = base.server.copy(
                users = base.server.users.put(2, user.copy(textureHash = ByteString.copyFromUtf8("h1"))),
            ),
        )
        session.value = withHash
        session.value = withHash
        assertEquals(listOf(2), requested)
        session.value = emptySessionState()
        session.value = withHash
        assertEquals(listOf(2, 2), requested)
    }

    @Test fun reconnectNoticeOnlyWarnsWhileConnected() = runFeature { scope ->
        val platform = FakeFeaturePlatform()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val settings = MutableStateFlow(testSnapshot(certificateId = 1L))
        ReconnectNoticeFeature(state, settings, platform).start(scope)
        settings.value = testSnapshot(certificateId = 2L)
        assertTrue(platform.toasts.isEmpty())
        state.value = connectedState()
        settings.value = testSnapshot(certificateId = 3L)
        assertEquals(listOf(R.string.change_requires_reconnect), platform.toasts)
    }
}
