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

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Slice 6 payoff: the route policy, now behind [AudioPlatform] and [ScoLink]
 * ports, is testable on the plain JVM with fakes plus a TestScope for
 * virtual time. No Robolectric, no shadows. Each case is a falsifiable
 * oracle (fake assertion plus virtual-time advance), each tracing to a past
 * fix commit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteControllerTest {

    private class FakePlatform : AudioPlatform {
        var hasManagerValue = true
        var modeValue: Int = AudioManager.MODE_NORMAL
        var throwOnMode = false
        var devices: Array<AudioDeviceInfo>? = emptyArray()
        var throwOnQuery = false
        var queryCount = 0
        var throwOnRegister = false
        var throwOnUnregister = false
        var capturedCallback: AudioDeviceCallback? = null
        var commDevice: AudioDeviceInfo? = null
        var useSPlusSystemSco = false
        var scoOnValue = false
        val events = mutableListOf<String>()
        val modeSets = mutableListOf<Int>()

        override val hasManager: Boolean get() = hasManagerValue
        override var mode: Int
            get() {
                if (throwOnMode) throw RuntimeException("mode get failed")
                return modeValue
            }
            set(value) {
                if (throwOnMode) throw RuntimeException("mode set failed")
                modeSets += value
                modeValue = value
            }
        override fun getOutputDevices(): Array<AudioDeviceInfo>? {
            queryCount++
            if (throwOnQuery) throw RuntimeException("query failed")
            return devices
        }
        override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) {
            if (throwOnRegister) throw RuntimeException("register failed")
            capturedCallback = callback
        }
        override fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
            if (throwOnUnregister) throw RuntimeException("unregister failed")
            if (capturedCallback === callback) capturedCallback = null
        }
        override fun getCommunicationDevice(): AudioDeviceInfo? = commDevice
        override var bluetoothScoOn: Boolean
            get() = scoOnValue
            set(value) {
                events += "scoOn=$value"
                scoOnValue = value
            }
        override fun clearCommunicationDevice() {
            events += "clearComm"
        }
        override fun isSystemScoOn(): Boolean =
            if (useSPlusSystemSco) commDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            else scoOnValue
        override fun clearSystemSco() {
            if (useSPlusSystemSco) {
                clearCommunicationDevice()
            } else {
                stopBluetoothSco()
                bluetoothScoOn = false
            }
        }
        override fun stopBluetoothSco() {
            events += "stopSco"
        }
    }

    private class FakeSco : ScoLink {
        var idleValue = true
        var activeValue = false
        var availableValue = true
        var startCount = 0
        var stopCount = 0
        var destroyCount = 0
        val events = mutableListOf<String>()
        override fun start() {
            // No-op while starting or active, per the ScoLink contract.
            if (!idleValue || activeValue) return
            startCount++
            events += "start"
            // Mirrors the manager: IDLE -> STARTING (busy, not yet active).
            idleValue = false
        }
        override fun stop() {
            stopCount++
            events += "stop"
            // Mirrors the manager: any state -> IDLE with the link down.
            idleValue = true
            activeValue = false
        }
        override fun destroy() {
            destroyCount++
        }
        override fun isIdle(): Boolean = idleValue
        override fun isActive(): Boolean = activeValue
        override fun isScoDeviceAvailable(): Boolean = availableValue
    }

    private class FakeHost : AudioRouteController.Host {
        var live = true
        var tokenValue = 1
        var tokenReads = 0
        var bumpTokenOnSecondRead = false
        var pipeline = true
        var recreateCount = 0
        var throwOnRecreate = false
        val recreateEvents = mutableListOf<String>()
        var pipelineErrors = mutableListOf<String?>()
        var routeChangedCount = 0
        var scoChanged = mutableListOf<Boolean>()
        var teardownCount = 0
        var failedReasons = mutableListOf<String?>()

        override val isLive: Boolean get() = live
        override val sessionToken: Int
            get() {
                tokenReads++
                return if (bumpTokenOnSecondRead && tokenReads >= 2) tokenValue + 1 else tokenValue
            }
        override fun hasLivePipeline(): Boolean = pipeline
        override fun recreatePipeline() {
            recreateCount++
            recreateEvents += "recreate"
            if (throwOnRecreate) throw se.lublin.humla.exception.AudioException("boom")
        }
        override fun onPipelineError(message: String?) {
            pipelineErrors += message
        }
        override fun onRouteChanged() {
            routeChangedCount++
        }
        override fun onScoChanged(active: Boolean) {
            scoChanged += active
        }
        override fun onScoTeardown() {
            teardownCount++
        }
        override fun onBluetoothScoFailed(reason: String?) {
            failedReasons += reason
        }
    }

    private fun deviceOf(type: Int): AudioDeviceInfo {
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.type).thenReturn(type)
        return device
    }

    private fun newFakes(): Triple<FakePlatform, FakeSco, FakeHost> {
        return Triple(FakePlatform(), FakeSco(), FakeHost())
    }

    @Test
    fun settleBudgetBoundsRechecksPerEpisode() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        val baseQueries = platform.queryCount
        // Voice-by-absence: no media sink, SCO down, live with pipeline.
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(baseQueries + 1, platform.queryCount)
        // Two bounded rechecks, then silence: exactly 3 funnel entries.
        // Advances step one millisecond past each exact delay boundary.
        advanceTimeBy(3001L)
        assertEquals(baseQueries + 2, platform.queryCount)
        advanceTimeBy(3001L)
        assertEquals(baseQueries + 3, platform.queryCount)
        advanceTimeBy(9000L)
        assertEquals(baseQueries + 3, platform.queryCount)
        // No recreate: the route never moved (voice throughout, no force).
        assertEquals(0, host.recreateCount)
        // A fresh edge resets the budget for a new episode.
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(baseQueries + 4, platform.queryCount)
        advanceTimeBy(3001L)
        assertEquals(baseQueries + 5, platform.queryCount)
    }

    @Test
    fun settleRecheckCatchesLaggingMediaSink() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // First edge sees no sink (voice); the A2DP edge lags with no callback.
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        // The lagging sink appears before the recheck fires.
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        advanceTimeBy(3001L)
        // Exactly one recreate onto the media path, with route notification.
        assertEquals(1, host.recreateCount)
        assertEquals(AppliedRoute.MEDIA, route.decision())
        assertEquals(1, host.routeChangedCount)
        // Media won: no further settle cover is armed.
        advanceTimeBy(9000L)
        assertEquals(1, host.recreateCount)
    }

    @Test
    fun debounceCoalescesBurstIntoOneRecompute() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        val callback = platform.capturedCallback!!
        val baseQueries = platform.queryCount
        val added = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        // The live query sees the attached sink (media), so the funnel does
        // not arm settle cover that would pollute the recompute count.
        platform.devices = added
        callback.onAudioDevicesAdded(added)
        callback.onAudioDevicesAdded(added)
        callback.onAudioDevicesAdded(added)
        // No recompute yet: the burst is still inside the debounce window.
        assertEquals(baseQueries, platform.queryCount)
        advanceTimeBy(301L)
        assertEquals(baseQueries + 1, platform.queryCount)
        // Exactly one recompute per burst, not one per event.
        advanceTimeBy(9000L)
        assertEquals(baseQueries + 1, platform.queryCount)
    }

    @Test
    fun modeNeverClaimedOverForeignHold() = runTest {
        val (platform, sco, host) = newFakes()
        platform.modeValue = AudioManager.MODE_IN_COMMUNICATION
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        // Externally held: claim nothing, change nothing.
        assertTrue(platform.modeSets.isEmpty())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
    }

    @Test
    fun modeNeverClaimedOverCellularCall() = runTest {
        val (platform, sco, host) = newFakes()
        platform.modeValue = AudioManager.MODE_IN_CALL
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertTrue(platform.modeSets.isEmpty())
        assertEquals(AudioManager.MODE_IN_CALL, platform.modeValue)
    }

    @Test
    fun modeRestoreGuardedByCurrentHold() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Claim over a normal baseline.
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(listOf(AudioManager.MODE_IN_COMMUNICATION), platform.modeSets)
        // A phone call steals the mode; teardown must not clobber it.
        platform.modeValue = AudioManager.MODE_IN_CALL
        platform.modeSets.clear()
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(AudioManager.MODE_IN_CALL, platform.modeValue)
        assertTrue(platform.modeSets.isEmpty())
    }

    @Test
    fun modeReleasedWhileCurrentRestoresBaseline() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        // Still ours at teardown: restore the saved baseline.
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(AudioManager.MODE_NORMAL, platform.modeValue)
        assertEquals(
            listOf(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL),
            platform.modeSets,
        )
    }

    @Test
    fun foreignScoCounterClearsBeforeMediaPipeline() = runTest {
        val (platform, sco, host) = newFakes()
        val shared = mutableListOf<String>()
        // Flip to media with an unrequested system SCO up (legacy path).
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        platform.scoOnValue = true
        // Order probe: platform stop events vs host recreate events share one timeline.
        // clearSystemSco is the seam under test: the probe replicates the
        // legacy stop-then-clear through its own overrides to keep the
        // ordering assertion on the observed calls.
        val probePlatform = object : AudioPlatform by platform {
            override fun stopBluetoothSco() {
                shared += "stop"
                platform.stopBluetoothSco()
            }
            override var bluetoothScoOn: Boolean
                get() = platform.scoOnValue
                set(value) {
                    shared += "scoOn=false"
                    platform.scoOnValue = value
                }
            override fun clearSystemSco() {
                stopBluetoothSco()
                bluetoothScoOn = false
            }
        }
        val probeRoute = AudioRouteController(probePlatform, sco, this, object : AudioRouteController.Host by host {
            override fun recreatePipeline() {
                shared += "recreate"
                host.recreatePipeline()
            }
        })
        // Voice start claims the hold; the media flip must counter first.
        platform.devices = emptyArray()
        probeRoute.onInit()
        probeRoute.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        shared.clear()
        probeRoute.onEvent(RouteEvent.SettingsChanged(false))
        assertTrue("counter must stop system SCO, got $shared", "stop" in shared)
        assertTrue("counter must clear routing flag, got $shared", "scoOn=false" in shared)
        assertTrue("media flip must recreate, got $shared", "recreate" in shared)
        assertTrue(
            "clear must precede media pipeline creation, got $shared",
            shared.indexOf("stop") < shared.indexOf("recreate"),
        )
        assertEquals(AppliedRoute.MEDIA, probeRoute.decision())
    }

    @Test
    fun foreignScoCounterClearsBeforeMediaPipelineOnSPlus() = runTest {
        val (platform, sco, host) = newFakes()
        val shared = mutableListOf<String>()
        // S+ branch: the system SCO surfaces as a communication device.
        platform.useSPlusSystemSco = true
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        platform.commDevice = deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        // Order probe: platform clear events vs host recreate events share one timeline.
        val probePlatform = object : AudioPlatform by platform {
            override fun clearSystemSco() {
                shared += "clearComm"
                platform.clearSystemSco()
            }
        }
        val probeRoute = AudioRouteController(probePlatform, sco, this, object : AudioRouteController.Host by host {
            override fun recreatePipeline() {
                shared += "recreate"
                host.recreatePipeline()
            }
        })
        // Voice start claims the hold; the media flip must counter first.
        platform.devices = emptyArray()
        probeRoute.onInit()
        probeRoute.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        shared.clear()
        probeRoute.onEvent(RouteEvent.SettingsChanged(false))
        assertTrue("counter must clear S+ system SCO, got $shared", "clearComm" in shared)
        assertTrue("media flip must recreate, got $shared", "recreate" in shared)
        assertTrue(
            "clear must precede media pipeline creation, got $shared",
            shared.indexOf("clearComm") < shared.indexOf("recreate"),
        )
        assertEquals(AppliedRoute.MEDIA, probeRoute.decision())
    }

    @Test
    fun foreignScoCounterSkippedWhenRequested() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        route.setScoRequested(true)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        platform.scoOnValue = true
        platform.events.clear()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertFalse("stopSco" in platform.events)
    }

    @Test
    fun foreignScoCounterSkippedDuringCellularCall() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        platform.scoOnValue = true
        platform.modeValue = AudioManager.MODE_IN_CALL
        platform.events.clear()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertFalse("stopSco" in platform.events)
    }

    @Test
    fun teardownRaceSuppressesStaleNotifyAfterReconfirm() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Live SCO link tears down: the false-notify posts to the scope.
        sco.activeValue = true
        sco.idleValue = false
        route.stopScoWithNotify()
        assertEquals(0, host.teardownCount)
        // A newer link confirms before the post runs: stale false suppressed.
        sco.activeValue = true
        sco.idleValue = false
        route.onEvent(RouteEvent.ScoLinkChanged(true))
        assertEquals(listOf(true), host.scoChanged)
        runCurrent()
        assertEquals(0, host.teardownCount)
    }

    @Test
    fun teardownNotifyFiresWithoutReconfirm() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        sco.activeValue = true
        sco.idleValue = false
        route.stopScoWithNotify()
        runCurrent()
        assertEquals(1, host.teardownCount)
        assertTrue(host.scoChanged.isEmpty())
    }

    @Test
    fun retryScoStaleTokenIsNoOp() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.setScoRequested(true)
        sco.idleValue = true
        host.live = true
        host.bumpTokenOnSecondRead = true
        assertFalse(route.retrySco())
        // Stray bring-up torn down; no leak across the session change.
        assertEquals(1, sco.startCount)
        assertEquals(1, sco.stopCount)
    }

    @Test
    fun retryScoHappyPathStartsWithoutStop() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.setScoRequested(true)
        sco.idleValue = true
        host.live = true
        assertTrue(route.retrySco())
        assertEquals(1, sco.startCount)
        assertEquals(0, sco.stopCount)
    }

    @Test
    fun scoFailureFansOutReasonToHost() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onScoFailed("permission")
        assertEquals(listOf("permission"), host.failedReasons)
        route.onScoFailed("no-device")
        assertEquals(listOf("permission", "no-device"), host.failedReasons)
    }

    @Test
    fun platformEdgesAreFailClosed() = runTest {
        val (platform, sco, host) = newFakes()
        platform.hasManagerValue = false
        val route = AudioRouteController(platform, sco, this, host)
        // Null manager: every edge is a safe no-op.
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(0, host.recreateCount)
        // Sync always builds the pipeline, even with no manager.
        route.onEvent(RouteEvent.SessionStarted)
        assertEquals(1, host.recreateCount)
        route.onEvent(RouteEvent.SessionEnded)
        route.onEvent(RouteEvent.Destroyed)
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        assertEquals(1, host.recreateCount)
    }

    @Test
    fun queryFailureSelectsVoiceWithoutCrashing() = runTest {
        val (platform, sco, host) = newFakes()
        platform.throwOnQuery = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
    }

    @Test
    fun listenerRegistrationFailureIsContained() = runTest {
        val (platform, sco, host) = newFakes()
        platform.throwOnRegister = true
        val route = AudioRouteController(platform, sco, this, host)
        route.registerDeviceListener(mock(Handler::class.java))
        platform.throwOnRegister = false
        // Contained: the controller survives and the funnel still works.
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        // Register once successfully so the unregister path is exercised:
        // the tail must survive a throwing platform, not a null callback.
        route.registerDeviceListener(mock(Handler::class.java))
        platform.throwOnUnregister = true
        route.unregisterDeviceListener()
        route.onEvent(RouteEvent.SettingsChanged(false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
    }

    @Test
    fun modeFailuresAreContained() = runTest {
        val (platform, sco, host) = newFakes()
        platform.throwOnMode = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Mode get/set throwing must not break the funnel or teardown.
        route.onEvent(RouteEvent.SettingsChanged(false))
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(0, host.recreateCount)
    }

    @Test
    fun unavailableDeviceNeverTriggersBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        sco.availableValue = false
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        val callback = platform.capturedCallback!!
        // A2DP-first stagger falls back to the availability probe, which
        // reports nothing usable here: no bring-up. (A SCO entry in the
        // added array is the fast path and starts regardless.)
        callback.onAudioDevicesAdded(arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        runCurrent()
        assertEquals(0, sco.startCount)
    }

    @Test
    fun deviceMatrixSelectsExpectedRoutes() = runTest {
        val mediaTypes = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        for (type in mediaTypes) {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            platform.devices = arrayOf(deviceOf(type))
            route.onEvent(RouteEvent.SettingsChanged(false))
            assertEquals("type $type must select media", AppliedRoute.MEDIA, route.decision())
        }
        // SCO attached but unconfirmed stays voice-by-handset; confirmation
        // promotes to the SCO voice triple.
        run {
            val (platform2, sco2, host2) = newFakes()
            val route2 = AudioRouteController(platform2, sco2, this, host2)
            route2.onInit()
            platform2.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            route2.onEvent(RouteEvent.SettingsChanged(false))
            assertEquals(AppliedRoute.VOICE_HANDSET, route2.decision())
            route2.onEvent(RouteEvent.ScoLinkChanged(true))
            assertEquals(AppliedRoute.VOICE_SCO, route2.decision())
        }
    }

    @Test
    fun nonTriggerDeviceNeverStartsBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        sco.availableValue = true
        val callback = platform.capturedCallback!!
        // LE Audio rides the media path by design: no trigger, no bring-up.
        callback.onAudioDevicesAdded(arrayOf(deviceOf(AudioDeviceInfo.TYPE_HEARING_AID)))
        runCurrent()
        // Hearing-aid is a media sink, not a bring-up trigger on its own;
        // the A2DP/BLE-class fallback needs an A2DP/BLE add to fire.
        assertEquals(0, sco.startCount)
    }

    @Test
    fun scoAttachStartsRequestedBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        val callback = platform.capturedCallback!!
        callback.onAudioDevicesAdded(arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        runCurrent()
        assertEquals(1, sco.startCount)
    }

    @Test
    fun pipelineFailureKeepsOldPipelineAndReports() = runTest {
        val (platform, sco, host) = newFakes()
        host.throwOnRecreate = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Sync-time creation failure: reported, old pipeline kept (no crash).
        route.onEvent(RouteEvent.SessionStarted)
        assertEquals(1, host.pipelineErrors.size)
        // Funnel-time reload failure: same containment.
        host.pipelineErrors.clear()
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.SettingsChanged(true))
        assertEquals(1, host.pipelineErrors.size)
    }
}
