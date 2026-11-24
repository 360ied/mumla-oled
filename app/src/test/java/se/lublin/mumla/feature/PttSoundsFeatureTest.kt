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

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.client.ClientState
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RouteState
import se.lublin.humla.route.StreamProfile
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.Settings

/** PTT cues, route reloads and the input-method guard. */
class PttSoundsFeatureTest {

    private class Fixture(pttSound: Boolean = true, mode: String = Settings.ARRAY_INPUT_METHOD_PTT) {
        val platform = FakeFeaturePlatform()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val talk = MutableStateFlow(TalkActivity.initial())
        val route = MutableStateFlow<RouteState>(RouteState.Idle)
        val settings = MutableStateFlow(testSnapshot(pttSound = pttSound, audioInputMethod = mode))
        val feature = PttSoundsFeature(state, talk, route, settings, platform)

        fun connect() {
            state.value = connectedState()
        }

        fun setTalking(value: Boolean) {
            talk.value = TalkActivity(value, kotlinx.collections.immutable.persistentMapOf())
        }
    }

    @Test fun activationAndDeactivationCues() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.connect()
        f.setTalking(true)
        f.setTalking(false)
        assertEquals(listOf(true, false), f.platform.soundBanks.last().plays)
    }

    @Test fun duplicatesAndDisabledSettingPlayNothing() = runFeature { scope ->
        val f = Fixture(pttSound = false)
        f.feature.start(scope)
        f.connect()
        f.setTalking(true)
        f.setTalking(true)
        assertTrue(f.platform.soundBanks.last().plays.isEmpty())
    }

    @Test fun voiceActivityModePlaysNothing() = runFeature { scope ->
        val f = Fixture(mode = Settings.ARRAY_INPUT_METHOD_VOICE)
        f.feature.start(scope)
        f.connect()
        f.setTalking(true)
        assertTrue(f.platform.soundBanks.last().plays.isEmpty())
    }

    @Test fun idleResetsTheLastTalkingLatch() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        val latch = PttSoundsFeature::class.java.getDeclaredField("lastTalking")
        latch.isAccessible = true
        f.connect()
        f.setTalking(true)
        assertTrue(latch.getBoolean(f.feature))
        f.state.value = ClientState.Idle
        assertFalse(latch.getBoolean(f.feature))
    }

    @Test fun routeChangeReloadsTheSoundBank() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.connect()
        val before = f.platform.soundBanks.size
        f.route.value = RouteState.Ready(AppliedRoute.MEDIA, StreamProfile.MEDIA)
        assertTrue(f.platform.soundBanks.size > before)
        assertTrue(f.platform.soundBanks[before - 1].released)
    }
}
