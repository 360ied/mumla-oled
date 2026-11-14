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

package se.lublin.humla.route

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import se.lublin.humla.audio.AudioPlatform
import se.lublin.humla.audio.ScoLink

@OptIn(ExperimentalCoroutinesApi::class)
class RouteControlTest {
    private class FakePlatform : AudioPlatform {
        var hasManagerValue = true
        var modeValue: Int = AudioManager.MODE_NORMAL
        var devices: Array<AudioDeviceInfo>? = emptyArray()
        var capturedCallback: AudioDeviceCallback? = null

        override val hasManager: Boolean get() = hasManagerValue
        override var mode: Int
            get() = modeValue
            set(value) {
                modeValue = value
            }
        override fun getOutputDevices(): Array<AudioDeviceInfo>? = devices
        override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) {
            capturedCallback = callback
        }
        override fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
            if (capturedCallback === callback) capturedCallback = null
        }
        override fun getCommunicationDevice(): AudioDeviceInfo? = null
        override var bluetoothScoOn: Boolean = false
        override fun clearCommunicationDevice() {}
        override fun stopBluetoothSco() {}
        override fun isSystemScoOn(): Boolean = false
        override fun clearSystemSco() {}
    }

    private class FakeSco : ScoLink {
        var idleValue = true
        var activeValue = false
        var startCount = 0
        var stopCount = 0

        override fun start() {
            if (!idleValue || activeValue) return
            startCount++
            idleValue = false
        }

        override fun stop() {
            stopCount++
            idleValue = true
            activeValue = false
        }

        override fun destroy() {}
        override fun isIdle(): Boolean = idleValue
        override fun isActive(): Boolean = activeValue
        override fun isScoDeviceAvailable(): Boolean = true
    }

    private fun newControl(
        platform: FakePlatform = FakePlatform(),
        sco: FakeSco = FakeSco(),
        prefs: MutableStateFlow<RoutePreferences> = MutableStateFlow(
            RoutePreferences(bluetoothSco = false, handsetStream = AudioManager.STREAM_MUSIC),
        ),
        scope: kotlinx.coroutines.CoroutineScope,
    ): RouteControl {
        val handler = mock(Handler::class.java)
        // Child scope: close() cancels only this child, never the TestScope.
        val child = kotlinx.coroutines.CoroutineScope(
            scope.coroutineContext + kotlinx.coroutines.SupervisorJob(),
        )
        return RouteControl(platform, sco, prefs, child, handler, null)
    }

    @Test
    fun sessionStartedPublishesReadyVoice() = runTest {
        val control = newControl(scope = this)
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        val state = control.state.value
        assertTrue(state is RouteState.Ready)
        val ready = state as RouteState.Ready
        assertEquals(AppliedRoute.VOICE_HANDSET, ready.route)
        assertEquals(StreamProfile.VOICE, ready.profile)
        control.close()
        runCurrent()
    }

    @Test
    fun backToBackStartThenEndSettlesIdle() = runTest {
        val control = newControl(scope = this)
        // No runCurrent between submits: Ended must not be epoch-dropped.
        control.submit(RouteCommand.SessionStarted)
        control.submit(RouteCommand.SessionEnded)
        runCurrent()
        assertTrue(control.state.value is RouteState.Idle)
        control.close()
        runCurrent()
    }

    @Test
    fun sessionStartedAfterCloseStaysIdle() = runTest {
        val control = newControl(scope = this)
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        assertTrue(control.state.value is RouteState.Ready)
        control.close()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        assertTrue(control.state.value is RouteState.Idle)
    }

    @Test
    fun sessionEndedReturnsToIdle() = runTest {
        val control = newControl(scope = this)
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        control.submit(RouteCommand.SessionEnded)
        runCurrent()
        assertTrue(control.state.value is RouteState.Idle)
        control.close()
        runCurrent()
    }

    @Test
    fun mediaSinkSelectsMediaRoute() = runTest {
        val platform = FakePlatform()
        platform.devices = arrayOf(mock(AudioDeviceInfo::class.java).apply {
            org.mockito.Mockito.`when`(type).thenReturn(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        })
        val control = newControl(platform = platform, scope = this)
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        val state = control.state.value
        assertTrue(state is RouteState.Ready)
        assertEquals(AppliedRoute.MEDIA, (state as RouteState.Ready).route)
        assertEquals(StreamProfile.MEDIA, state.profile)
        control.close()
        runCurrent()
    }

    @Test
    fun scoActiveSelectsScoRoute() = runTest {
        val sco = FakeSco()
        sco.activeValue = false
        sco.idleValue = false
        val control = newControl(sco = sco, scope = this)
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        // Simulate SCO link coming up.
        sco.activeValue = true
        control.onScoConnected()
        runCurrent()
        val state = control.state.value
        assertTrue(state is RouteState.Ready)
        assertEquals(AppliedRoute.VOICE_SCO, (state as RouteState.Ready).route)
        control.close()
        runCurrent()
    }

    @Test
    fun staleEventsAreDroppedAcrossSessions() = runTest {
        val control = newControl(scope = this)
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        val first = control.state.value
        assertTrue(first is RouteState.Ready)
        // Start a new session: counter moves, old callbacks must drop.
        control.submit(RouteCommand.SessionEnded)
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        // An old SCO disconnect posted before the second start must not trash the new session.
        // Directly exercise the guard: a captured value from the prior
        // session no longer matches.
        assertTrue(control.shouldDropStaleEvent(0))
        control.close()
        runCurrent()
    }

    @Test
    fun scoFailedPublishesFallback() = runTest {
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(sco = sco, prefs = prefs, scope = this)
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        control.onScoFailed("timeout")
        runCurrent()
        val state = control.state.value
        assertTrue(state is RouteState.Fallback)
        assertEquals(AppliedRoute.VOICE_HANDSET, (state as RouteState.Fallback).route)
        control.close()
        runCurrent()
    }

    @Test
    fun deviceRemovalDoesNotStartSco() = runTest {
        val platform = FakePlatform()
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(platform = platform, sco = sco, prefs = prefs, scope = this)
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        // Force the link back to idle, then deliver a removal containing an
        // SCO device: removals must re-decide only, never bring-up.
        sco.stop()
        val before = sco.startCount
        val callback = platform.capturedCallback
        assertTrue(callback != null)
        val removed = arrayOf(mock(AudioDeviceInfo::class.java).apply {
            org.mockito.Mockito.`when`(type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        })
        callback!!.onAudioDevicesRemoved(removed)
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(before, sco.startCount)
        control.close()
        runCurrent()
    }

    @Test
    fun retryScoStartsLinkWhenRequestedAndIdle() = runTest {
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(sco = sco, prefs = prefs, scope = this)
        // Load prefs (start collects the prefs flow onto the route scope).
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        // SessionStarted already brought the link up once; force it back to
        // idle and reset the counter so the retry has observable work.
        sco.stop()
        val before = sco.startCount
        control.submit(RouteCommand.RetrySco)
        runCurrent()
        assertEquals(before + 1, sco.startCount)
        assertTrue(control.state.value !is RouteState.Idle)
        control.close()
        runCurrent()
    }

    private fun device(deviceType: Int): AudioDeviceInfo = mock(AudioDeviceInfo::class.java).apply {
        org.mockito.Mockito.`when`(type).thenReturn(deviceType)
    }

    /**
     * The decision must follow the link, not a copy of it: a stop this
     * controller performs itself leaves the link silent by contract, so a
     * cached flag would strand the route on SCO for the rest of the session.
     */
    @Test
    fun routeDecisionFollowsLinkStateNotEvents() = runTest {
        val platform = FakePlatform()
        platform.devices = arrayOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(platform = platform, sco = sco, prefs = prefs, scope = this)
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        sco.idleValue = false
        sco.activeValue = true
        control.onScoConnected()
        runCurrent()
        assertEquals(AppliedRoute.VOICE_SCO, (control.state.value as RouteState.Ready).route)
        // Drop the link behind the controller's back (no callback), then
        // trigger a re-decision through an unrelated device event.
        sco.stop()
        platform.capturedCallback!!.onAudioDevicesAdded(
            arrayOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
        )
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        val state = control.state.value
        assertTrue(state is RouteState.Ready)
        assertEquals(AppliedRoute.MEDIA, (state as RouteState.Ready).route)
        assertEquals(StreamProfile.MEDIA, state.profile)
        control.close()
        runCurrent()
    }

    @Test
    fun scoPreferenceOffWhileActiveFallsBackToMedia() = runTest {
        val platform = FakePlatform()
        platform.devices = arrayOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(platform = platform, sco = sco, prefs = prefs, scope = this)
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        sco.idleValue = false
        sco.activeValue = true
        control.onScoConnected()
        runCurrent()
        assertEquals(AppliedRoute.VOICE_SCO, (control.state.value as RouteState.Ready).route)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        val stopsBefore = sco.stopCount
        prefs.value = prefs.value.copy(bluetoothSco = false)
        runCurrent()
        assertEquals(stopsBefore + 1, sco.stopCount)
        val state = control.state.value
        assertTrue(state is RouteState.Ready)
        assertEquals(AppliedRoute.MEDIA, (state as RouteState.Ready).route)
        assertEquals(StreamProfile.MEDIA, state.profile)
        assertEquals(AudioManager.MODE_NORMAL, platform.modeValue)
        control.close()
        runCurrent()
    }

    @Test
    fun scoPreferenceOffWithoutMediaSinkKeepsHandsetVoice() = runTest {
        val platform = FakePlatform()
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(platform = platform, sco = sco, prefs = prefs, scope = this)
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        sco.idleValue = false
        sco.activeValue = true
        control.onScoConnected()
        runCurrent()
        assertEquals(AppliedRoute.VOICE_SCO, (control.state.value as RouteState.Ready).route)
        prefs.value = prefs.value.copy(bluetoothSco = false)
        runCurrent()
        // Voice by absence, and not a fallback: SCO is no longer requested.
        val state = control.state.value
        assertTrue(state is RouteState.Ready)
        assertEquals(AppliedRoute.VOICE_HANDSET, (state as RouteState.Ready).route)
        assertEquals(StreamProfile.VOICE, state.profile)
        control.close()
        runCurrent()
    }

    @Test
    fun scoPreferenceOffOnOffRepublishesScoThenMedia() = runTest {
        val platform = FakePlatform()
        platform.devices = arrayOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val sco = FakeSco()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = false, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val control = newControl(platform = platform, sco = sco, prefs = prefs, scope = this)
        control.start()
        runCurrent()
        control.submit(RouteCommand.SessionStarted)
        runCurrent()
        assertEquals(AppliedRoute.MEDIA, (control.state.value as RouteState.Ready).route)

        prefs.value = prefs.value.copy(bluetoothSco = true)
        runCurrent()
        sco.activeValue = true
        control.onScoConnected()
        runCurrent()
        assertEquals(AppliedRoute.VOICE_SCO, (control.state.value as RouteState.Ready).route)

        // The reported failure: leaving SCO must publish the media route.
        prefs.value = prefs.value.copy(bluetoothSco = false)
        runCurrent()
        assertEquals(AppliedRoute.MEDIA, (control.state.value as RouteState.Ready).route)

        prefs.value = prefs.value.copy(bluetoothSco = true)
        runCurrent()
        sco.activeValue = true
        control.onScoConnected()
        runCurrent()
        assertEquals(AppliedRoute.VOICE_SCO, (control.state.value as RouteState.Ready).route)

        prefs.value = prefs.value.copy(bluetoothSco = false)
        runCurrent()
        assertEquals(AppliedRoute.MEDIA, (control.state.value as RouteState.Ready).route)
        assertEquals(2, sco.startCount)
        assertEquals(2, sco.stopCount)
        control.close()
        runCurrent()
    }
}
