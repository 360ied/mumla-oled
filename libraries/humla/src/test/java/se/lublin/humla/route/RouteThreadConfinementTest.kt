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
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper
import se.lublin.humla.audio.AudioPlatform
import se.lublin.humla.audio.ScoLink
import java.time.Duration

/**
 * Detector for the SCO link's route-thread confinement (issue 01): the
 * controller and its link built by the production factory, on a real route
 * `HandlerThread`, driven through every public entry point and platform
 * callback that reaches the link, from a thread other than the route
 * thread. Every link call must land on the route thread. A path that calls
 * the link inline from a public method fails here, because the test thread
 * is not the route thread, and so does a link given a thread other than
 * the controller's, because the fake checks the thread the factory handed
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteThreadConfinementTest {
    private class RecordingSco(val thread: RouteThread, val listener: ScoLink.Listener) : ScoLink {
        val calls = mutableListOf<String>()
        val offThread = mutableListOf<String>()
        @Volatile
        var idle = true
        @Volatile
        var active = false

        private fun record(name: String) {
            synchronized(calls) {
                calls += name
                if (!thread.isCurrent()) offThread += name
            }
        }

        override fun start() {
            record("start")
            idle = false
        }

        override fun stop() {
            record("stop")
            idle = true
            active = false
        }

        override fun destroy() = record("destroy")

        override fun isIdle(): Boolean {
            record("isIdle")
            return idle
        }

        override fun isActive(): Boolean {
            record("isActive")
            return active
        }

        override fun isScoDeviceAvailable(): Boolean {
            record("isScoDeviceAvailable")
            return true
        }
    }

    // Delivers device callbacks through the handler it was given, as the
    // platform does.
    private class PostingPlatform : AudioPlatform {
        var callback: AudioDeviceCallback? = null
        var handler: Handler? = null

        override val hasManager: Boolean = true
        override var mode: Int = AudioManager.MODE_NORMAL
        override var bluetoothScoOn: Boolean = false
        override fun getOutputDevices(): Array<AudioDeviceInfo> = emptyArray()
        override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) {
            this.callback = callback
            this.handler = handler
        }

        override fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
            if (this.callback === callback) this.callback = null
        }

        override fun getCommunicationDevice(): AudioDeviceInfo? = null
        override fun clearCommunicationDevice() {}
        override fun stopBluetoothSco() {}
        override fun isSystemScoOn(): Boolean = false
        override fun clearSystemSco() {}

        fun deviceAdded(device: AudioDeviceInfo) {
            val cb = callback ?: return
            handler!!.post { cb.onAudioDevicesAdded(arrayOf(device)) }
        }
    }

    @Test
    fun everyLinkCallLandsOnTheRouteThread() {
        val platform = PostingPlatform()
        val prefs = MutableStateFlow(
            RoutePreferences(bluetoothSco = true, handsetStream = AudioManager.STREAM_MUSIC),
        )
        val appScope = CoroutineScope(SupervisorJob())
        var built: RecordingSco? = null
        val control = RouteControl.create(platform, { thread, listener -> RecordingSco(thread, listener).also { built = it } }, prefs, appScope)
        val sco = built!!
        assertTrue("test thread is not the route thread", !sco.thread.isCurrent())

        // Drives every background looper, not just the link's, so the
        // controller's work runs wherever it is scheduled and an off-thread
        // call shows up as one. Loopers the controller has quit are skipped.
        fun settle() {
            for (l in ShadowLooper.getAllLoopers()) {
                if (l == Looper.getMainLooper()) continue
                try {
                    shadowOf(l).idleFor(Duration.ofSeconds(10))
                } catch (quitting: IllegalStateException) {
                }
            }
        }

        control.onInit()
        control.start()
        control.submit(RouteCommand.SessionStarted)
        settle()
        control.onScoConnected()
        control.onScoDisconnected()
        control.onScoFailed("timeout")
        control.retrySco()
        settle()
        // A failed bring-up leaves the link idle; an A2DP arrival then
        // probes SCO availability before starting it again.
        sco.idle = true
        platform.deviceAdded(
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build(),
        )
        settle()
        // The link reports itself up through the listener create() gave it,
        // on the route thread as the real link does; the controller acts on it.
        sco.active = true
        sco.thread.handler.post { sco.listener.onScoConnected() }
        settle()
        val ready = control.state.value
        assertTrue("link-up reached the controller: $ready",
            ready is RouteState.Ready && ready.route == AppliedRoute.VOICE_SCO)
        control.setScoRequested(false)
        control.setScoRequested(true)
        prefs.value = prefs.value.copy(bluetoothSco = false)
        settle()
        control.submit(RouteCommand.SessionEnded)
        settle()
        // close() runs the teardown on the route thread, then quits its
        // looper; settle() skips the looper once it is quitting.
        val teardown = control.close()
        settle()
        assertTrue("teardown ran", teardown.isCompleted)
        appScope.cancel()

        synchronized(sco.calls) {
            for (name in listOf("start", "stop", "destroy", "isIdle", "isActive", "isScoDeviceAvailable")) {
                assertTrue("$name was exercised: ${sco.calls}", name in sco.calls)
            }
            assertEquals("link calls off the route thread", emptyList<String>(), sco.offThread)
        }
    }
}
