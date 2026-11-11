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

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.net.LegacyVoiceLink
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.route.RouteState
import se.lublin.humla.util.HumlaLogger

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControlTest {
    private fun newControl(
        scope: kotlinx.coroutines.test.TestScope,
        route: MutableStateFlow<RouteState> = MutableStateFlow(RouteState.Idle),
        policy: MutableStateFlow<VoicePolicy> = MutableStateFlow(VoicePolicy.mutedFallback(VoiceSettings.initial())),
        settings: MutableStateFlow<VoiceSettings> = MutableStateFlow(VoiceSettings.initial()),
        prefs: MutableStateFlow<se.lublin.humla.route.RoutePreferences> = MutableStateFlow(
            se.lublin.humla.route.RoutePreferences(bluetoothSco = false, handsetStream = 3),
        ),
    ): VoiceControl {
        val context = mock(Context::class.java)
        val logger = mock(HumlaLogger::class.java)
        val encode = mock(AudioHandler.AudioEncodeListener::class.java)
        val talking = mock(AudioOutput.AudioOutputListener::class.java)
        val link = mock(LegacyVoiceLink::class.java)
        // Child scope: close() cancels only this child, never the TestScope.
        val child = kotlinx.coroutines.CoroutineScope(
            scope.coroutineContext + kotlinx.coroutines.SupervisorJob(),
        )
        return VoiceControl(
            context = context,
            logger = logger,
            encodeListener = encode,
            talkingListener = talking,
            routeStates = route,
            policies = policy,
            settings = settings,
            prefs = prefs,
            voiceLink = link,
            appScope = child,
            controlDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun sessionLifecycleDoesNotCrashWithoutRoute() = runTest {
        val route = MutableStateFlow<RouteState>(RouteState.Idle)
        val control = newControl(scope = this, route = route)
        // No session: route Ready must not crash (tryCreate no-ops).
        route.value = RouteState.Ready(
            se.lublin.humla.route.AppliedRoute.VOICE_HANDSET,
            se.lublin.humla.route.StreamProfile.VOICE,
        )
        runCurrent()
        // SessionEnded without session must not crash.
        control.sessionEnded()
        runCurrent()
        control.close()
        runCurrent()
    }

    @Test
    fun closeCancelsWithoutPipeline() = runTest {
        val control = newControl(scope = this)
        control.close()
        runCurrent()
    }

    @Test
    fun settingsChangeWithoutSessionDoesNotCrash() = runTest {
        val settings = MutableStateFlow(VoiceSettings.initial())
        val control = newControl(scope = this, settings = settings)
        // No session: settings changes must no-op, never crash or build.
        settings.value = settings.value.copy(adaptiveLeveler = false)
        runCurrent()
        assertTrue(control.currentHandler() == null)
        control.close()
        runCurrent()
    }
}
