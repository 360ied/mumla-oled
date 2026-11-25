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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.mumla.Settings
import se.lublin.mumla.TalkKeyEvent

/** Talk-key guards, moved out of the service into the feature. */
class TalkKeysFeatureTest {

    private class Fixture(toggle: Boolean = false, mode: String = Settings.ARRAY_INPUT_METHOD_PTT) {
        val keys = MutableSharedFlow<TalkKeyEvent>(extraBufferCapacity = 16)
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val settings = MutableStateFlow(testSnapshot(togglePtt = toggle, audioInputMethod = mode))
        val setPtt = ArrayList<Boolean>()
        var toggles = 0
        var talking = false
        val feature = TalkKeysFeature(
            keys, state, settings,
            { pressed -> setPtt.add(pressed); talking = pressed },
            { toggles++; talking = !talking },
            { talking },
        )

        fun connected(): Fixture {
            state.value = connectedState()
            return this
        }
    }

    @Test fun holdModeDownAndUp() = runFeature { scope ->
        val f = Fixture().connected()
        f.feature.start(scope)
        f.keys.emit(TalkKeyEvent.Down)
        assertEquals(listOf(true), f.setPtt)
        f.keys.emit(TalkKeyEvent.Up)
        assertEquals(listOf(true, false), f.setPtt)
    }

    @Test fun toggleModeUpToggles() = runFeature { scope ->
        val f = Fixture(toggle = true).connected()
        f.feature.start(scope)
        f.keys.emit(TalkKeyEvent.Down)
        assertEquals(0, f.toggles)
        f.keys.emit(TalkKeyEvent.Up)
        assertEquals(1, f.toggles)
        f.keys.emit(TalkKeyEvent.Up)
        assertEquals(2, f.toggles)
    }

    @Test fun cancelEndsHeldPressButNotToggle() = runFeature { scope ->
        val hold = Fixture().connected()
        hold.feature.start(scope)
        hold.keys.emit(TalkKeyEvent.Down)
        hold.keys.emit(TalkKeyEvent.Cancel)
        assertEquals(listOf(true, false), hold.setPtt)

        val toggle = Fixture(toggle = true).connected()
        toggle.feature.start(scope)
        toggle.keys.emit(TalkKeyEvent.Cancel)
        assertEquals(0, toggle.toggles)
        assertEquals(0, toggle.setPtt.size)
    }

    @Test fun disconnectedOrVoiceActivityIgnoreKeys() = runFeature { scope ->
        val disconnected = Fixture()
        disconnected.feature.start(scope)
        disconnected.keys.emit(TalkKeyEvent.Down)
        assertEquals(0, disconnected.setPtt.size)

        val voice = Fixture(mode = Settings.ARRAY_INPUT_METHOD_VOICE).connected()
        voice.feature.start(scope)
        voice.keys.emit(TalkKeyEvent.Down)
        assertEquals(0, voice.setPtt.size)
    }
}
