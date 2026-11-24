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

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.humla.client.ClientState
import se.lublin.mumla.Settings
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver

/** The receiver's parsing plus the feature's connected/PTT-mode guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TalkBroadcastFeatureTest {

    private class Fixture(
        allowed: Boolean = true,
        mode: String = Settings.ARRAY_INPUT_METHOD_PTT,
    ) {
        val platform = FakeFeaturePlatform()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val settings = MutableStateFlow(testSnapshot(allowTalkBroadcast = allowed, audioInputMethod = mode))
        val setPtt = ArrayList<Boolean>()
        var toggles = 0
        val feature = TalkBroadcastFeature(
            state, settings, platform,
            { setPtt.add(it) },
            { toggles++ },
        )

        fun start(scope: kotlinx.coroutines.CoroutineScope) {
            feature.start(scope)
            state.value = connectedState()
        }

        fun receive(intent: Intent?) {
            platform.registered.lastOrNull()?.onReceive(null, intent)
        }
    }

    private fun talkIntent(status: String?): Intent {
        val intent = Intent(TalkBroadcastReceiver.BROADCAST_TALK)
        if (status != null) intent.putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status)
        return intent
    }

    @Test fun unknownActionAndStatusAreIgnored() = runFeature { scope ->
        val f = Fixture()
        f.start(scope)
        f.receive(Intent("se.lublin.mumla.action.BOGUS"))
        f.receive(talkIntent("bogus"))
        assertTrue(f.setPtt.isEmpty())
        assertEquals(0, f.toggles)
    }

    @Test fun onOffAndMissingStatusToggle() = runFeature { scope ->
        val f = Fixture()
        f.start(scope)
        f.receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON))
        f.receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_OFF))
        f.receive(talkIntent(null))
        assertEquals(listOf(true, false), f.setPtt)
        assertEquals(1, f.toggles)
    }

    @Test fun toggleInvertsCurrentState() = runFeature { scope ->
        val f = Fixture()
        f.start(scope)
        f.receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_TOGGLE))
        assertEquals(1, f.toggles)
    }

    @Test fun voiceActivityAndOptOutNeverRegister() = runFeature { scope ->
        val voice = Fixture(mode = Settings.ARRAY_INPUT_METHOD_VOICE)
        voice.start(scope)
        assertTrue(voice.platform.registered.isEmpty())
        voice.receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON))
        assertTrue(voice.setPtt.isEmpty())

        val optOut = Fixture(allowed = false)
        optOut.start(scope)
        assertTrue(optOut.platform.registered.isEmpty())
    }

    @Test fun disconnectUnregistersTheReceiver() = runFeature { scope ->
        val f = Fixture()
        f.start(scope)
        assertEquals(1, f.platform.registered.size)
        f.state.value = ClientState.Idle
        assertEquals(1, f.platform.unregistered.size)
    }
}
