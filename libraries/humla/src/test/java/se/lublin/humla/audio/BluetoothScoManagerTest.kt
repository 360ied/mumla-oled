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
import kotlinx.coroutines.android.asCoroutineDispatcher
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The SCO state machine on a route thread built the way production builds
 * it: handler, executor, and looper dispatcher all on one looper, with the
 * real thread check. The looper is Robolectric's paused main looper, which
 * the test thread runs, so the test's own calls are on the route thread and
 * its virtual clock drives the connect timeout. The platform is the stock
 * audio shadow with deferred routing (see [DeferredRouteAudioManagerShadow]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [DeferredRouteAudioManagerShadow::class])
class BluetoothScoManagerTest {
    private lateinit var app: Application
    private lateinit var audioManager: AudioManager
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
        audioManager = app.getSystemService(AudioManager::class.java)
        platform = Shadow.extract(audioManager)
        platform.setAvailableCommunicationDevices(listOf(scoDevice))
        platform.setIsBluetoothScoAvailableOffCall(true)
        manager = BluetoothScoManager(app, listener, routeThread(), TIMEOUT_MS, 2)
    }

    @After
    fun tearDown() {
        manager.destroy()
        idle()
    }

    // Production's RouteControl.startThread shapes, on the test's looper.
    private fun routeThread(): RouteThread {
        val looper = Looper.getMainLooper()
        val handler = Handler(looper)
        return RouteThread(
            handler = handler,
            executor = Executor { r -> handler.post(r) },
            dispatcher = handler.asCoroutineDispatcher("route"),
            isCurrentCheck = { Looper.myLooper() == looper },
        )
    }

    /** Runs everything posted to the route looper that is due now. */
    private fun idle() = ShadowLooper.idleMainLooper()

    /** Advances the virtual clock, running what falls due on the way. */
    private fun advance(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    @Test
    fun connectEventCompletesBringUpAfterStateFlip() {
        manager.start()
        idle()
        assertEquals(1, platform.requests)
        assertEquals(1, platform.listeners)
        assertFalse(manager.isActive())

        platform.connect(scoDevice)
        idle()

        assertEquals(listOf("connected active=true"), events)
        assertTrue(manager.isActive())
    }

    @Test
    fun alreadyRoutedCompletesWithoutWaitingForTimeout() {
        platform.preselect(scoDevice)
        manager.start()
        idle()

        assertEquals(listOf("connected active=true"), events)
        advance(TIMEOUT_MS * 3)
        assertEquals("the armed timeout was cancelled", 1, platform.requests)
    }

    @Test
    fun timeoutRetriesOnceThenFails() {
        manager.start()
        idle()

        advance(TIMEOUT_MS - 1)
        assertEquals("no retry before the deadline", 1, platform.requests)
        advance(1)
        assertEquals("one retry at the first deadline", 2, platform.requests)
        assertEquals(1, platform.listeners)
        assertTrue(events.isEmpty())

        advance(TIMEOUT_MS)
        assertEquals(listOf("failed ${BluetoothScoManager.REASON_TIMEOUT} idle=true"), events)
        assertEquals(2, platform.requests)
        assertEquals(0, platform.listeners)
        assertNull("route released", audioManager.communicationDevice)
    }

    @Test
    fun failureAfterStopIsDropped() {
        platform.setAvailableCommunicationDevices(emptyList())
        manager.start()
        // The no-device failure is posted, not delivered inside start().
        manager.stop()
        advance(TIMEOUT_MS * 3)

        assertTrue(events.isEmpty())
        assertTrue(manager.isIdle())
    }

    @Test
    fun noDeviceFailsWhenNotStopped() {
        platform.setAvailableCommunicationDevices(emptyList())
        manager.start()
        assertTrue("posted, not inside start()", events.isEmpty())
        idle()

        assertEquals(listOf("failed ${BluetoothScoManager.REASON_NO_DEVICE} idle=true"), events)
        assertEquals(0, platform.requests)
    }

    @Test
    fun stopBeforeTimeoutCancelsIt() {
        manager.start()
        idle()
        advance(TIMEOUT_MS - 1)

        manager.stop()
        advance(TIMEOUT_MS * 3)

        assertTrue(events.isEmpty())
        assertEquals(1, platform.requests)
        assertEquals(0, platform.listeners)
        assertNull(audioManager.communicationDevice)
    }

    @Test
    fun timeoutFromStoppedSessionNeverActsOnNextSession() {
        manager.start()
        idle()
        advance(TIMEOUT_MS - 1)

        // Session A ends and B starts just before A's timeout is due.
        manager.stop()
        manager.start()
        idle()
        assertEquals(2, platform.requests)

        // A's deadline passes: no retry, no second bring-up for B.
        advance(1)
        assertEquals(2, platform.requests)
        assertEquals(1, platform.listeners)
        assertTrue(events.isEmpty())

        // B keeps its own full budget: retry at its first deadline.
        advance(TIMEOUT_MS - 2)
        assertEquals(2, platform.requests)
        advance(1)
        assertEquals(3, platform.requests)
        assertEquals(1, platform.listeners)
        assertTrue(events.isEmpty())

        advance(TIMEOUT_MS)
        assertEquals(listOf("failed ${BluetoothScoManager.REASON_TIMEOUT} idle=true"), events)
    }

    @Test
    fun deviceEventQueuedForStoppedSessionNeverCompletesNextSession() {
        manager.start()
        idle()

        // Session A's link report is queued when A stops and B starts, so
        // it is delivered while B is starting. stop() clears the route, so
        // B has no fast path and must wait for its own event.
        platform.connect(scoDevice)
        manager.stop()
        manager.start()
        idle()

        assertTrue(events.isEmpty())
        assertFalse(manager.isActive())
        assertEquals(1, platform.listeners)
    }

    @Test
    fun droppedLinkReportsDisconnectAfterStateFlip() {
        manager.start()
        idle()
        platform.connect(scoDevice)
        idle()

        platform.connect(speaker)
        idle()

        assertEquals(
            listOf("connected active=true", "disconnected active=false"),
            events,
        )
        assertEquals(0, platform.listeners)
    }

    @Test
    fun stopNeverCallsBack() {
        manager.start()
        idle()
        platform.connect(scoDevice)
        idle()
        events.clear()

        manager.stop()
        advance(TIMEOUT_MS * 3)

        assertTrue(events.isEmpty())
        assertTrue(manager.isIdle())
        assertNull(audioManager.communicationDevice)
    }

    @Test
    fun entryPointsOffRouteThreadThrowInDebug() {
        val calls = listOf<Pair<String, () -> Unit>>(
            "start" to { manager.start() },
            "stop" to { manager.stop() },
            "destroy" to { manager.destroy() },
            "isIdle" to { manager.isIdle() },
            "isActive" to { manager.isActive() },
            "isScoDeviceAvailable" to { manager.isScoDeviceAvailable() },
        )
        for ((name, call) in calls) {
            var thrown: Throwable? = null
            val other = Thread {
                try {
                    call()
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            other.start()
            other.join()
            assertTrue("$name off the route thread must throw", thrown is IllegalStateException)
        }
        assertTrue("nothing changed", manager.isIdle())
    }

    @Test
    @Config(sdk = [30])
    fun legacyBroadcastConnectsOnRouteHandler() {
        manager.start()
        idle()
        assertTrue("SCO requested", audioManager.isBluetoothScoOn)
        assertEquals(1, shadowOf(app).registeredReceivers.size)

        app.sendBroadcast(scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED))
        assertTrue("delivered through the handler, not inline", events.isEmpty())
        idle()

        assertEquals(listOf("connected active=true"), events)
    }

    @Test
    @Config(sdk = [30])
    fun legacyErrorWhileStartingFailsFastAndReleases() {
        manager.start()
        idle()

        app.sendBroadcast(scoState(AudioManager.SCO_AUDIO_STATE_ERROR))
        idle()

        assertEquals(listOf("failed ${BluetoothScoManager.REASON_ERROR} idle=true"), events)
        assertFalse("SCO request released", audioManager.isBluetoothScoOn)
        assertEquals(0, shadowOf(app).registeredReceivers.size)
    }

    private fun scoState(state: Int): Intent =
        Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state)

    private companion object {
        const val TIMEOUT_MS = 8000L
    }
}
