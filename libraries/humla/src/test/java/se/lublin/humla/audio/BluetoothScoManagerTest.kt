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

package se.lublin.humla.audio

import android.Manifest
import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper
import se.lublin.humla.route.RouteThread

/**
 * Virtual-time tests for the SCO state machine against a deferred-routing
 * platform shadow. Every timer, listener delivery, and failure callback runs
 * on one test dispatcher, standing in for the route thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [DeferredRouteAudioManagerShadow::class])
class BluetoothScoManagerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var app: Application
    private lateinit var platform: DeferredRouteAudioManagerShadow
    private lateinit var manager: BluetoothScoManager
    private val events = mutableListOf<String>()

    private val scoDevice: AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
    private val speaker: AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()

    // Records whether the state flip preceded each callback (ScoLink contract).
    private val listener = object : BluetoothScoManager.Listener {
        override fun onScoConnected() {
            events += "connected active=${manager.isActive()}"
        }

        override fun onScoDisconnected() {
            events += "disconnected active=${manager.isActive()}"
        }

        override fun onScoFailed(reason: String?) {
            events += "failed $reason idle=${manager.isIdle()}"
        }
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val audioManager = app.getSystemService(AudioManager::class.java)
        platform = Shadow.extract(audioManager)
        platform.setAvailableCommunicationDevices(listOf(scoDevice))
        platform.setIsBluetoothScoAvailableOffCall(true)
        manager = newManager()
    }

    @After
    fun tearDown() {
        manager.destroy()
        scheduler.advanceUntilIdle()
    }

    private fun newManager(): BluetoothScoManager {
        val thread = RouteThread(
            handler = Handler(Looper.getMainLooper()),
            executor = dispatcher.asExecutor(),
            dispatcher = dispatcher,
            isCurrentCheck = { true },
        )
        return BluetoothScoManager(app, listener, thread, TIMEOUT_MS, 2)
    }

    private fun runCurrent() = scheduler.runCurrent()

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun connectEventCompletesBringUpAfterStateFlip() {
        manager.start()
        runCurrent()
        assertEquals(1, platform.setRequests)
        assertEquals(1, platform.listenerCount)
        assertFalse(manager.isActive())

        platform.connect(scoDevice)
        runCurrent()

        assertEquals(listOf("connected active=true"), events)
        assertTrue(manager.isActive())
    }

    @Test
    fun alreadyRoutedCompletesWithoutWaitingForTimeout() {
        platform.preselect(scoDevice)
        manager.start()
        runCurrent()

        assertEquals(listOf("connected active=true"), events)
        advance(TIMEOUT_MS * 3)
        assertEquals(1, platform.setRequests)
    }

    @Test
    fun timeoutRetriesOnceThenFails() {
        manager.start()
        runCurrent()

        advance(TIMEOUT_MS)
        assertEquals("one retry after the first timeout", 2, platform.setRequests)
        assertEquals(1, platform.listenerCount)
        assertTrue(events.isEmpty())

        advance(TIMEOUT_MS)
        assertEquals(listOf("failed ${BluetoothScoManager.REASON_TIMEOUT} idle=true"), events)
        assertEquals(2, platform.setRequests)
        assertEquals(0, platform.listenerCount)
        assertNull(platform.currentDevice)
    }

    @Test
    fun failureAfterStopIsDropped() {
        platform.setAvailableCommunicationDevices(emptyList())
        manager.start()
        // The no-device failure is posted, not delivered inline.
        manager.stop()
        scheduler.advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertTrue(manager.isIdle())
    }

    @Test
    fun noDeviceFailsWhenNotStopped() {
        platform.setAvailableCommunicationDevices(emptyList())
        manager.start()
        runCurrent()

        assertEquals(listOf("failed ${BluetoothScoManager.REASON_NO_DEVICE} idle=true"), events)
        assertEquals(0, platform.setRequests)
    }

    @Test
    fun stopBeforeTimeoutCancelsIt() {
        manager.start()
        runCurrent()
        advance(TIMEOUT_MS - 1)

        manager.stop()
        scheduler.advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(1, platform.setRequests)
        assertEquals(0, platform.listenerCount)
        assertNull(platform.currentDevice)
    }

    @Test
    fun timeoutFromStoppedSessionNeverActsOnNextSession() {
        manager.start()
        runCurrent()
        advance(TIMEOUT_MS - 1)

        // Session A ends and B starts just before A's timeout is due.
        manager.stop()
        manager.start()
        runCurrent()
        assertEquals(2, platform.setRequests)

        // A's deadline passes: no retry, no duplicate bring-up for B.
        advance(1)
        assertEquals(2, platform.setRequests)
        assertEquals(1, platform.listenerCount)
        assertTrue(events.isEmpty())

        // B keeps its own full budget: retry at its first deadline.
        advance(TIMEOUT_MS - 1)
        assertEquals(3, platform.setRequests)
        assertEquals(1, platform.listenerCount)
        assertTrue(events.isEmpty())

        advance(TIMEOUT_MS)
        assertEquals(listOf("failed ${BluetoothScoManager.REASON_TIMEOUT} idle=true"), events)
    }

    @Test
    fun deviceEventQueuedForStoppedSessionNeverCompletesNextSession() {
        manager.start()
        runCurrent()

        // Session A's link report is queued when A stops and B starts, so
        // it is delivered while B is starting. stop() clears the route, so
        // B has no fast path and must wait for its own event.
        platform.connect(scoDevice)
        manager.stop()
        manager.start()
        runCurrent()

        assertTrue(events.isEmpty())
        assertFalse(manager.isActive())
        assertEquals(1, platform.listenerCount)
    }

    @Test
    fun droppedLinkReportsDisconnectAfterStateFlip() {
        manager.start()
        runCurrent()
        platform.connect(scoDevice)
        runCurrent()

        platform.connect(speaker)
        runCurrent()

        assertEquals(
            listOf("connected active=true", "disconnected active=false"),
            events,
        )
        assertEquals(0, platform.listenerCount)
    }

    @Test
    fun stopNeverCallsBack() {
        manager.start()
        runCurrent()
        platform.connect(scoDevice)
        runCurrent()
        events.clear()

        manager.stop()
        scheduler.advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertTrue(manager.isIdle())
        assertNull(platform.currentDevice)
    }

    @Test
    @Config(sdk = [30])
    fun legacyBroadcastConnectsOnHandler() {
        manager.start()
        runCurrent()
        assertEquals(1, platform.legacyStarts)

        app.sendBroadcast(scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED))
        ShadowLooper.idleMainLooper()
        runCurrent()

        assertEquals(listOf("connected active=true"), events)
    }

    @Test
    @Config(sdk = [30])
    fun legacyErrorWhileStartingFailsFast() {
        manager.start()
        runCurrent()

        app.sendBroadcast(scoState(AudioManager.SCO_AUDIO_STATE_ERROR))
        ShadowLooper.idleMainLooper()
        runCurrent()

        assertEquals(listOf("failed ${BluetoothScoManager.REASON_ERROR} idle=true"), events)
        assertEquals(1, platform.legacyStops)
    }

    private fun scoState(state: Int): Intent =
        Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state)

    private companion object {
        const val TIMEOUT_MS = 8000L
    }
}
