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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Route policy tests: the policy behind [AudioPlatform] and [ScoLink] ports is
 * testable on the plain JVM with fakes plus a TestScope for virtual time.
 * No Robolectric, no shadows. Cases are falsifiable oracles (fake assertion
 * plus virtual-time advance) covering settle, debounce, mode, counter, SCO,
 * and teardown paths; see docs/humlaservice-decomposition/PROGRESS.md for context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouteControllerTest {

    private companion object {
        const val EVT_SCO_PREFIX = "scoOn="
        const val EVT_STOP_SCO = "stopSco"
        const val EVT_CLEAR = "clear"
        const val EVT_CLEAR_COMM = "clearComm"
        const val EVT_RECREATE = "recreate"
    }

    private class FakePlatform : AudioPlatform {
        var hasManagerValue = true
        var modeValue: Int = AudioManager.MODE_NORMAL
        var throwOnMode = false
        var devices: Array<AudioDeviceInfo>? = emptyArray()
        var throwOnQuery = false
        var queryCount = 0
        var throwOnRegister = false
        var throwOnUnregister = false
        var throwOnIsSystemScoOn = false
        var throwOnClearSystemSco = false
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
            if (throwOnQuery) throw RuntimeException("query failed")
            // Count successes only: recompute-success inference from deltas
            // must not count a throwing probe as a run.
            queryCount++
            return devices
        }
        var capturedHandler: Handler? = null
        override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) {
            if (throwOnRegister) throw RuntimeException("register failed")
            capturedCallback = callback
            capturedHandler = handler
        }
        override fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
            if (throwOnUnregister) throw RuntimeException("unregister failed")
            if (capturedCallback === callback) capturedCallback = null
        }
        override fun getCommunicationDevice(): AudioDeviceInfo? = commDevice
        override var bluetoothScoOn: Boolean
            get() = scoOnValue
            set(value) {
                events += "$EVT_SCO_PREFIX$value"
                scoOnValue = value
            }
        override fun clearCommunicationDevice() {
            events += EVT_CLEAR_COMM
            // Mirror production: clearing drops the system SCO routing.
            commDevice = null
        }
        override fun isSystemScoOn(): Boolean {
            if (throwOnIsSystemScoOn) throw RuntimeException("isSystemScoOn failed")
            // Fail-closed like production when the manager is absent.
            if (!hasManagerValue) return false
            return if (useSPlusSystemSco) commDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            else scoOnValue
        }
        override fun clearSystemSco() {
            if (throwOnClearSystemSco) throw RuntimeException("clear failed")
            if (useSPlusSystemSco) {
                clearCommunicationDevice()
            } else {
                stopBluetoothSco()
                bluetoothScoOn = false
            }
        }
        override fun stopBluetoothSco() {
            events += EVT_STOP_SCO
        }
    }

    private class FakeSco : ScoLink {
        var idleValue = true
        var activeValue = false
        var availableValue = true
        var throwOnAvailable = false
        // Next start() reports failure instead of STARTING, modelling the
        // manager's async fail(PERMISSION/NO_DEVICE/REFUSED/TIMEOUT) path
        // that the old fake (sync flip only) could never exercise.
        var failNextStartAs: String? = null
        var failListener: ((String?) -> Unit)? = null
        var startCount = 0
        var stopCount = 0
        var destroyCount = 0
        init {
            require(!(idleValue && activeValue)) { "impossible idle+active initial state" }
        }
        override fun start() {
            // No-op while starting or active, per the ScoLink contract.
            if (!idleValue || activeValue) return
            startCount++
            val failure = failNextStartAs
            if (failure != null) {
                failNextStartAs = null
                // Stay STARTING (busy, not active) like the manager: the
                // async failure arrives via the listener, not the start call.
                idleValue = false
                failListener?.invoke(failure)
                return
            }
            // Mirrors the manager: IDLE -> STARTING (busy, not yet active).
            idleValue = false
        }
        override fun stop() {
            stopCount++
            // Mirrors the manager: any state -> IDLE with the link down.
            idleValue = true
            activeValue = false
        }
        override fun destroy() {
            destroyCount++
        }
        override fun isIdle(): Boolean = idleValue
        override fun isActive(): Boolean = activeValue
        override fun isScoDeviceAvailable(): Boolean {
            if (throwOnAvailable) throw RuntimeException("probe failed")
            return availableValue
        }
    }

    // Field names match the Host members they stub (live=isLive,
    // pipeline=hasLivePipeline, tokenValue=sessionToken) so call sites stay
    // short; tokenReads counts sessionToken reads to prove retrySco
    // re-validates (see retryScoStaleTokenIsNoOp assert below).
    // Host fakes stay behavior-only: route decisions are asserted via
    // route.decision()/handsetStream() in the tests (the production host
    // reads the same accessors when pushing into the builder).
    private class FakeHost : AudioRouteController.Host {
        var live = true
        var tokenValue = 1
        var tokenReads = 0
        var bumpTokenOnSecondRead = false
        var pipeline = true
        var recreateCount = 0
        var throwOnRecreate = false
        var throwRuntimeOnRecreate = false
        var throwAssertOnRecreate = false
        val pipelineErrors = mutableListOf<String?>()
        var routeChangedCount = 0
        val scoChanged = mutableListOf<Boolean>()
        var teardownCount = 0
        val failedReasons = mutableListOf<String?>()

        override val isLive: Boolean get() = live
        override val sessionToken: Int
            get() {
                tokenReads++
                return if (bumpTokenOnSecondRead && tokenReads >= 2) tokenValue + 1 else tokenValue
            }
        override fun hasLivePipeline(): Boolean = pipeline
        override fun recreatePipeline() {
            recreateCount++
            if (throwOnRecreate) throw se.lublin.humla.exception.AudioException("boom")
            if (throwRuntimeOnRecreate) throw RuntimeException("boom-runtime")
            if (throwAssertOnRecreate) throw AssertionError("not connected")
            // Mirrors HumlaService.routeHost: a successful create flips the
            // pipeline to live for subsequent hasLivePipeline() reads.
            pipeline = true
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

    // Relies on mockito-inline (mockito-core 5.x inlines final Android types
    // on the JVM with returnDefaultValues); Handler mocks below are
    // capture-only because FakePlatform drops the handler (see handler test).
    private fun deviceOf(type: Int): AudioDeviceInfo {
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.type).thenReturn(type)
        return device
    }

    private data class Fakes(
        val platform: FakePlatform,
        val sco: FakeSco,
        val host: FakeHost,
    )

    private fun newFakes(): Fakes {
        return Fakes(FakePlatform(), FakeSco(), FakeHost())
    }

    @Test
    fun settleBudgetBoundsRechecksPerEpisode() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        val baseQueries = platform.queryCount
        // Voice-by-absence: no media sink, SCO down, live with pipeline.
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(baseQueries + 1, platform.queryCount)
        // Two bounded rechecks, then silence: exactly 3 funnel entries.
        // Each step advances 1ms past the settle window (referencing the
        // production constant directly, not by value).
        advanceTimeBy(AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 1)
        assertEquals(baseQueries + 2, platform.queryCount)
        advanceTimeBy(AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 1)
        assertEquals(baseQueries + 3, platform.queryCount)
        advanceTimeBy(2 * AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 3000)
        assertEquals(baseQueries + 3, platform.queryCount)
        // No recreate: the route never moved (voice throughout, no force).
        assertEquals(0, host.recreateCount)
        // A fresh edge resets the budget for a new episode.
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(baseQueries + 4, platform.queryCount)
        advanceTimeBy(AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 1)
        assertEquals(baseQueries + 5, platform.queryCount)
    }

    @Test
    fun settleRecheckCatchesLaggingMediaSink() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // First edge sees no sink (voice); the A2DP edge lags with no callback.
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        // The lagging sink appears before the recheck fires.
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        advanceTimeBy(AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 1)
        // Exactly one recreate onto the media path, with route notification.
        assertEquals(1, host.recreateCount)
        assertEquals(AppliedRoute.MEDIA, route.decision())
        assertEquals(1, host.routeChangedCount)
        // Media won: no further settle cover is armed.
        advanceTimeBy(2 * AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 3000)
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
        // 1ms past ROUTE_RECOMPUTE_DEBOUNCE_MS.
        advanceTimeBy(AudioRouteController.ROUTE_RECOMPUTE_DEBOUNCE_MS + 1)
        assertEquals(baseQueries + 1, platform.queryCount)
        // Exactly one recompute per burst, not one per event.
        advanceTimeBy(2 * AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 3000)
        assertEquals(baseQueries + 1, platform.queryCount)
        // The burst converged onto the media route with one pipeline build.
        assertEquals(AppliedRoute.MEDIA, route.decision())
        assertEquals(1, host.recreateCount)
    }

    @Test
    fun modeNeverClaimedOverForeignHold() = runTest {
        val (platform, sco, host) = newFakes()
        platform.modeValue = AudioManager.MODE_IN_COMMUNICATION
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
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
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertTrue(platform.modeSets.isEmpty())
        assertEquals(AudioManager.MODE_IN_CALL, platform.modeValue)
    }

    @Test
    fun modeRestoreGuardedByCurrentHold() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Claim over a normal baseline.
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
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
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
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
        // Order probe: explicit forwarding (no `by` delegation) so future
        // AudioPlatform/Host methods fail to compile here instead of being
        // silently auto-forwarded and unobserved.
        val probePlatform = object : AudioPlatform {
            override val hasManager: Boolean get() = platform.hasManager
            override var mode: Int
                get() = platform.mode
                set(value) { platform.mode = value }
            override fun getOutputDevices(): Array<AudioDeviceInfo>? = platform.getOutputDevices()
            override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) =
                platform.registerDeviceCallback(callback, handler)
            override fun unregisterDeviceCallback(callback: AudioDeviceCallback) =
                platform.unregisterDeviceCallback(callback)
            override fun getCommunicationDevice(): AudioDeviceInfo? = platform.getCommunicationDevice()
            override var bluetoothScoOn: Boolean
                get() = platform.bluetoothScoOn
                set(value) { platform.bluetoothScoOn = value }
            override fun clearCommunicationDevice() = platform.clearCommunicationDevice()
            override fun stopBluetoothSco() = platform.stopBluetoothSco()
            override fun isSystemScoOn(): Boolean = platform.isSystemScoOn()
            override fun clearSystemSco() {
                shared += EVT_CLEAR
                platform.clearSystemSco()
            }
        }
        val probeHost = object : AudioRouteController.Host {
            override val isLive: Boolean get() = host.isLive
            override val sessionToken: Int get() = host.sessionToken
            override fun hasLivePipeline(): Boolean = host.hasLivePipeline()
            override fun recreatePipeline() {
                shared += EVT_RECREATE
                host.recreatePipeline()
            }
            override fun onPipelineError(message: String?) = host.onPipelineError(message)
            override fun onRouteChanged() = host.onRouteChanged()
            override fun onScoChanged(active: Boolean) = host.onScoChanged(active)
            override fun onScoTeardown() = host.onScoTeardown()
            override fun onBluetoothScoFailed(reason: String?) = host.onBluetoothScoFailed(reason)
        }
        val probeRoute = AudioRouteController(probePlatform, sco, this, probeHost)
        // Voice start claims the hold; the media flip must counter first.
        platform.devices = emptyArray()
        probeRoute.onInit()
        probeRoute.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        shared.clear()
        probeRoute.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertTrue("counter must clear system SCO, got $shared + ${platform.events}", EVT_CLEAR in shared)
        assertTrue("counter must stop SCO, got ${platform.events}", EVT_STOP_SCO in platform.events)
        assertFalse(platform.scoOnValue)
        assertTrue("media flip must recreate, got $shared", EVT_RECREATE in shared)
        assertTrue(
            "clear must precede media pipeline creation, got $shared",
            shared.indexOf(EVT_CLEAR) < shared.indexOf(EVT_RECREATE),
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
        // Order probe: explicit forwarding (no `by`) — see above.
        val probePlatform2 = object : AudioPlatform {
            override val hasManager: Boolean get() = platform.hasManager
            override var mode: Int
                get() = platform.mode
                set(value) { platform.mode = value }
            override fun getOutputDevices(): Array<AudioDeviceInfo>? = platform.getOutputDevices()
            override fun registerDeviceCallback(callback: AudioDeviceCallback, handler: Handler) =
                platform.registerDeviceCallback(callback, handler)
            override fun unregisterDeviceCallback(callback: AudioDeviceCallback) =
                platform.unregisterDeviceCallback(callback)
            override fun getCommunicationDevice(): AudioDeviceInfo? = platform.getCommunicationDevice()
            override var bluetoothScoOn: Boolean
                get() = platform.bluetoothScoOn
                set(value) { platform.bluetoothScoOn = value }
            override fun clearCommunicationDevice() = platform.clearCommunicationDevice()
            override fun stopBluetoothSco() = platform.stopBluetoothSco()
            override fun isSystemScoOn(): Boolean = platform.isSystemScoOn()
            override fun clearSystemSco() {
                shared += EVT_CLEAR_COMM
                platform.clearSystemSco()
            }
        }
        val probeHost2 = object : AudioRouteController.Host {
            override val isLive: Boolean get() = host.isLive
            override val sessionToken: Int get() = host.sessionToken
            override fun hasLivePipeline(): Boolean = host.hasLivePipeline()
            override fun recreatePipeline() {
                shared += EVT_RECREATE
                host.recreatePipeline()
            }
            override fun onPipelineError(message: String?) = host.onPipelineError(message)
            override fun onRouteChanged() = host.onRouteChanged()
            override fun onScoChanged(active: Boolean) = host.onScoChanged(active)
            override fun onScoTeardown() = host.onScoTeardown()
            override fun onBluetoothScoFailed(reason: String?) = host.onBluetoothScoFailed(reason)
        }
        val probeRoute = AudioRouteController(probePlatform2, sco, this, probeHost2)
        // Voice start claims the hold; the media flip must counter first.
        platform.devices = emptyArray()
        probeRoute.onInit()
        probeRoute.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        shared.clear()
        probeRoute.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertTrue("counter must clear S+ system SCO, got $shared", EVT_CLEAR_COMM in shared)
        assertTrue("media flip must recreate, got $shared", EVT_RECREATE in shared)
        assertTrue(
            "clear must precede media pipeline creation, got $shared",
            shared.indexOf(EVT_CLEAR_COMM) < shared.indexOf(EVT_RECREATE),
        )
        assertEquals(AppliedRoute.MEDIA, probeRoute.decision())
        // Post-clear truth: the S+ routing must read cleared, hiding a
        // double-clear / stale-truth regression.
        assertFalse(platform.isSystemScoOn())
    }

    @Test
    fun foreignScoCounterSkippedWhenRequested() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        route.setScoRequested(true)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        platform.scoOnValue = true
        platform.events.clear()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertFalse(EVT_STOP_SCO in platform.events)
    }

    @Test
    fun foreignScoCounterSkippedDuringCellularCall() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        platform.scoOnValue = true
        platform.modeValue = AudioManager.MODE_IN_CALL
        platform.events.clear()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertFalse(EVT_STOP_SCO in platform.events)
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
        // Posted-notify contract: the teardown fans out via scope.launch, so
        // the synchronous 0 is part of the contract (posted, not synchronous)
        // on TestScope; production Main.immediate may run it without deferral
        // and must not be assumed to observe the transient 0.
        assertEquals(0, host.teardownCount)
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
        // Token re-validation ran (two reads: requested + live gates) and the
        // stray bring-up was torn down; no leak across the session change.
        assertTrue(host.tokenReads >= 2)
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
    fun fakeScoAsyncFailureDuringStartingIsExercised() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Wire the manager-style async failure: start() enters STARTING, the
        // failure listener drives the controller's failure path.
        sco.failListener = { reason -> route.onScoFailed(reason) }
        sco.failNextStartAs = "timeout"
        route.setScoRequested(true)
        sco.idleValue = true
        host.live = true
        route.retrySco()
        assertEquals(1, sco.startCount)
        // STARTING (not idle, not active): the async failure path ran.
        assertFalse(sco.isIdle())
        assertFalse(sco.isActive())
        assertEquals(listOf("timeout"), host.failedReasons)
    }

    @Test
    fun offMainScoLinkChangedAndFailureDropStale() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Off-main ScoLinkChanged(true) posts; stale token drops it.
        route.mainThreadOverride = false
        sco.activeValue = true
        sco.idleValue = false
        route.onScoConnected()
        host.tokenValue = 99
        route.mainThreadOverride = true
        runCurrent()
        assertTrue(host.scoChanged.isEmpty())
        // Off-main onScoFailed posts; stale token drops the Toast half too.
        route.mainThreadOverride = false
        route.onScoFailed("timeout")
        host.tokenValue = 100
        route.mainThreadOverride = true
        runCurrent()
        assertTrue(host.failedReasons.isEmpty())
        route.mainThreadOverride = null
    }

    @Test
    fun platformEdgesAreFailClosed() = runTest {
        val (platform, sco, host) = newFakes()
        platform.hasManagerValue = false
        val route = AudioRouteController(platform, sco, this, host)
        // Null manager: every edge is a safe no-op.
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
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
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
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
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        // Register once successfully so the unregister path is exercised:
        // the tail must survive a throwing platform, not a null callback.
        route.registerDeviceListener(mock(Handler::class.java))
        platform.throwOnUnregister = true
        route.unregisterDeviceListener()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
    }

    @Test
    fun modeFailuresAreContained() = runTest {
        val (platform, sco, host) = newFakes()
        platform.throwOnMode = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Mode get/set throwing must not break the funnel or teardown.
        // Exercise under a route move (media flip) so the mode write is on
        // the recreate path, not trivially skipped on an unchanged voice
        // route: the throw is contained (Log.w) and the pipeline still
        // rebuilds onto media without crashing.
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(AppliedRoute.MEDIA, route.decision())
        assertEquals(1, host.recreateCount)
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
        // Fast path: SCO in the added array bypasses availability -- even when
        // the probe reports unavailable, a confirmed SCO attach must start.
        callback.onAudioDevicesAdded(arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        runCurrent()
        assertEquals(1, sco.startCount)
    }

    @Test
    fun scoAddedBypassesThrowingProbe() = runTest {
        val (platform, sco, host) = newFakes()
        sco.throwOnAvailable = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        // Fail-closed probe must not block the SCO-added fast path: a throwing
        // probe still starts on an explicit SCO attach.
        platform.capturedCallback!!.onAudioDevicesAdded(
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)),
        )
        runCurrent()
        assertEquals(1, sco.startCount)
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
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertEquals("type $type must select media", AppliedRoute.MEDIA, route.decision())
            // Media releases the voice mode: no IN_COMM claim survives.
            assertEquals(AudioManager.MODE_NORMAL, platform.modeValue)
        }
        // SCO attached but unconfirmed stays voice-by-handset; confirmation
        // promotes to the SCO voice triple.
        run {
            val (platform2, sco2, host2) = newFakes()
            val route2 = AudioRouteController(platform2, sco2, this, host2)
            route2.onInit()
            platform2.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            route2.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertEquals(AppliedRoute.VOICE_HANDSET, route2.decision())
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform2.modeValue)
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
        // Sync-time creation failure: reported with the cause message, old
        // pipeline kept (no crash, decision unchanged).
        val decisionBefore = route.decision()
        route.onEvent(RouteEvent.SessionStarted)
        assertEquals(1, host.pipelineErrors.size)
        assertEquals("boom", host.pipelineErrors.single())
        assertEquals(decisionBefore, route.decision())
        // Funnel-time reload failure: same containment.
        host.pipelineErrors.clear()
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.SettingsChanged(reload = true))
        assertEquals(1, host.pipelineErrors.size)
        assertEquals("boom", host.pipelineErrors.single())
    }

    @Test
    fun nullQueryFallsBackToVoiceHandset() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        platform.devices = null
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
    }

    // Builds a non-null array type containing a null via reflection to simulate
    // an OEM HAL returning nulls without an unchecked heap-pollution cast.
    private fun arrayWithNullElement(media: AudioDeviceInfo): Array<AudioDeviceInfo> {
        @Suppress("UNCHECKED_CAST")
        val arr = java.lang.reflect.Array.newInstance(
            AudioDeviceInfo::class.java, 2,
        ) as Array<AudioDeviceInfo?>
        arr[0] = media
        arr[1] = null
        @Suppress("UNCHECKED_CAST")
        return arr as Array<AudioDeviceInfo>
    }

    @Test
    fun nullEntryInArrayIsSkipped() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        val media = deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        // Heap pollution by design: smuggles a null past the non-null element
        // type to simulate an OEM HAL returning a null entry.
        platform.devices = arrayWithNullElement(media)
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.MEDIA, route.decision())
    }

    @Test
    fun nullEntryInCallbackIsSkipped() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        val media = deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        // Same heap-pollution via the callback payload (not just the query
        // path): the controller must null-guard DevicesChanged.added.
        // Callback edges debounce: advance past the window to run the funnel.
        // Query truth is clean media (isolates the callback null-guard from
        // the query null-guard covered above).
        platform.devices = arrayOf(media)
        platform.capturedCallback!!.onAudioDevicesAdded(arrayWithNullElement(media))
        advanceTimeBy(AudioRouteController.ROUTE_RECOMPUTE_DEBOUNCE_MS + 1)
        runCurrent()
        // Media present (null skipped): no crash, media route selected.
        assertEquals(AppliedRoute.MEDIA, route.decision())
    }

    @Test
    fun removalCallbackTriggersDebouncedRecompute() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        val callback = platform.capturedCallback!!
        val baseQueries = platform.queryCount
        callback.onAudioDevicesRemoved(emptyArray())
        assertEquals(baseQueries, platform.queryCount)
        // 1ms past ROUTE_RECOMPUTE_DEBOUNCE_MS.
        advanceTimeBy(AudioRouteController.ROUTE_RECOMPUTE_DEBOUNCE_MS + 1)
        assertEquals(baseQueries + 1, platform.queryCount)
        // Removal reconverges without rebuilding: still handset voice.
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        assertEquals(0, host.recreateCount)
    }

    @Test
    fun a2dpWithAvailableScoStartsFallbackBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        sco.availableValue = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        val callback = platform.capturedCallback!!
        // A2DP-first stagger: HFP not yet surfaced, but the HAL reports an
        // available SCO device — the fallback must start bring-up.
        callback.onAudioDevicesAdded(arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        runCurrent()
        assertEquals(1, sco.startCount)
    }

    @Test
    fun retryScoPreconditionsAreAllRequired() = runTest {
        // requested=false must not start, even when live+idle.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.setScoRequested(false)
            sco.idleValue = true
            host.live = true
            assertFalse(route.retrySco())
            assertEquals(0, sco.startCount)
        }
        // live=false must not start.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.setScoRequested(true)
            sco.idleValue = true
            host.live = false
            assertFalse(route.retrySco())
            assertEquals(0, sco.startCount)
        }
        // null link must not start (SCO unavailable on this device).
        run {
            val (platform, _, host) = newFakes()
            val route = AudioRouteController(platform, null, this, host)
            route.onInit()
            route.setScoRequested(true)
            host.live = true
            assertFalse(route.retrySco())
        }
        // non-idle link must not restart.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.setScoRequested(true)
            sco.idleValue = false
            sco.activeValue = false
            host.live = true
            assertFalse(route.retrySco())
            assertEquals(0, sco.startCount)
        }
        // Revalidation kills a bring-up raced by a session end.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.setScoRequested(true)
            sco.idleValue = true
            host.live = true
            host.bumpTokenOnSecondRead = true
            assertFalse(route.retrySco())
            assertEquals(1, sco.startCount)
            assertEquals(1, sco.stopCount)
        }
    }

    @Test
    fun maybeStartGuardsNeedLiveIdleAndAdd() = runTest {
        // SCO attached but not requested: no bring-up.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.registerDeviceListener(mock(Handler::class.java))
            route.setScoRequested(false)
            platform.capturedCallback!!.onAudioDevicesAdded(
                arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            )
            runCurrent()
            assertEquals(0, sco.startCount)
        }
        // Requested but offline: no bring-up.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.registerDeviceListener(mock(Handler::class.java))
            route.setScoRequested(true)
            host.live = false
            platform.capturedCallback!!.onAudioDevicesAdded(
                arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            )
            runCurrent()
            assertEquals(0, sco.startCount)
        }
        // Requested but link busy: no restart.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.registerDeviceListener(mock(Handler::class.java))
            route.setScoRequested(true)
            sco.idleValue = false
            platform.capturedCallback!!.onAudioDevicesAdded(
                arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            )
            runCurrent()
            assertEquals(0, sco.startCount)
        }
        // Removal (null add payload) never starts, even when requested.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.registerDeviceListener(mock(Handler::class.java))
            route.setScoRequested(true)
            val baseStarts = sco.startCount
            platform.capturedCallback!!.onAudioDevicesRemoved(emptyArray())
            runCurrent()
            assertEquals(baseStarts, sco.startCount)
        }
    }

    @Test
    fun noLivePipelineSkipsReloadButSyncStillBuilds() = runTest {
        val (platform, sco, host) = newFakes()
        host.pipeline = false
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Live edge with no pipeline: must not resurrect one.
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.SettingsChanged(reload = true))
        assertEquals(0, host.recreateCount)
        // Sync always builds (first creation), even with no prior pipeline.
        route.onEvent(RouteEvent.SessionStarted)
        assertEquals(1, host.recreateCount)
    }

    @Test
    fun counterSkipAssertsNoPlatformTouch() = runTest {
        // Requested SCO suppresses the counter entirely: no stop, no flag.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            route.setScoRequested(true)
            platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            platform.scoOnValue = true
            platform.events.clear()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertTrue(platform.events.isEmpty())
        }
        // Foreign SCO with nothing raised: nothing to clear.
        run {
            val (platform, sco, host) = newFakes()
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            platform.scoOnValue = false
            platform.events.clear()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertTrue(platform.events.isEmpty())
        }
        // Foreign hold (mode never claimed): never implicate us.
        run {
            val (platform, sco, host) = newFakes()
            platform.modeValue = AudioManager.MODE_IN_COMMUNICATION
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            platform.scoOnValue = true
            platform.events.clear()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertTrue(platform.events.isEmpty())
        }
    }

    @Test
    fun systemScoQueryAndClearFailuresAreContained() = runTest {
        // isSystemScoOn throwing must read as absent (no clear, no crash).
        run {
            val (platform, sco, host) = newFakes()
            platform.throwOnIsSystemScoOn = true
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            platform.scoOnValue = true
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertEquals(AppliedRoute.MEDIA, route.decision())
        }
        // clearSystemSco throwing must not break the funnel or the route.
        run {
            val (platform, sco, host) = newFakes()
            platform.throwOnClearSystemSco = true
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            platform.scoOnValue = true
            route.onEvent(RouteEvent.SettingsChanged(reload = false))
            assertEquals(AppliedRoute.MEDIA, route.decision())
            assertEquals(1, host.recreateCount)
        }
    }

    @Test
    fun onInitWithMediaSinkSelectsMedia() = runTest {
        val (platform, sco, host) = newFakes()
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        assertEquals(AppliedRoute.MEDIA, route.decision())
    }

    @Test
    fun destroyedSilencesFunnelAndTimers() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        val baseQueries = platform.queryCount
        val baseRecreates = host.recreateCount
        val baseRoutes = host.routeChangedCount
        route.onEvent(RouteEvent.Destroyed)
        // Post-destroy edges are dropped, timers cancelled.
        route.onEvent(RouteEvent.SettingsChanged(reload = true))
        route.onEvent(RouteEvent.DevicesChanged(arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))))
        route.onEvent(RouteEvent.ScoLinkChanged(true))
        route.onEvent(RouteEvent.SettleRecheck)
        // Long-horizon settle/debounce silence: no timer may requery.
        val settleSilenceMs = 10000L
        advanceTimeBy(settleSilenceMs)
        runCurrent()
        assertEquals(baseQueries, platform.queryCount)
        assertEquals(baseRecreates, host.recreateCount)
        assertEquals(baseRoutes, host.routeChangedCount)
        assertTrue(host.scoChanged.isEmpty())
    }

    @Test
    fun debounceDoesNotRecomputeAfterDestroy() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        val callback = platform.capturedCallback!!
        val baseQueries = platform.queryCount
        callback.onAudioDevicesAdded(arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        // Destroy inside the debounce window: the pending recompute dies.
        route.onEvent(RouteEvent.Destroyed)
        val debounceSilenceMs = 5000L
        advanceTimeBy(debounceSilenceMs)
        runCurrent()
        assertEquals(baseQueries, platform.queryCount)
    }

    @Test
    fun scoFailureSuppressedAfterDestroy() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.Destroyed)
        route.onScoFailed("timeout")
        assertTrue(host.failedReasons.isEmpty())
        assertTrue(host.scoChanged.isEmpty())
    }

    @Test
    fun stopScoDestroyedSuppressesPostedTeardown() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        sco.activeValue = true
        sco.idleValue = false
        route.stopScoWithNotify()
        route.onEvent(RouteEvent.Destroyed)
        runCurrent()
        assertEquals(0, host.teardownCount)
    }

    @Test
    fun stopScoFlagOnlySuppressesStaleTeardown() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Live link tears down: the false-notify posts to the scope.
        sco.activeValue = true
        sco.idleValue = false
        route.stopScoWithNotify()
        assertEquals(0, host.teardownCount)
        // A newer link confirms while the physical link stays down: only the
        // flag guard is true (isActive false, scoRouteActive true) and the
        // stale false must still be suppressed.
        sco.activeValue = false
        sco.idleValue = true
        route.onEvent(RouteEvent.ScoLinkChanged(true))
        runCurrent()
        assertEquals(0, host.teardownCount)
    }

    @Test
    fun settleLeakDoesNotRequeryAfterMediaWin() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        advanceTimeBy(AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 1)
        assertEquals(1, host.recreateCount)
        val settledQueries = platform.queryCount
        // Media won: no further settle cover is armed — queries stay flat.
        advanceTimeBy(2 * AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 3000)
        runCurrent()
        assertEquals(settledQueries, platform.queryCount)
        assertEquals(1, host.recreateCount)
    }

    @Test
    fun runtimeAndAssertFailuresReportViaPipelineError() = runTest {
        run {
            val (platform, sco, host) = newFakes()
            host.throwRuntimeOnRecreate = true
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SessionStarted)
            assertEquals(1, host.pipelineErrors.size)
            assertEquals("boom-runtime", host.pipelineErrors.single())
        }
        run {
            val (platform, sco, host) = newFakes()
            host.throwAssertOnRecreate = true
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.onEvent(RouteEvent.SessionStarted)
            assertEquals(1, host.pipelineErrors.size)
            assertEquals("not connected", host.pipelineErrors.single())
        }
    }

    @Test
    fun scoActiveDropFallsBackAndNotifies() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Confirm a live SCO link: voice triple + connected notify.
        sco.activeValue = true
        sco.idleValue = false
        route.onEvent(RouteEvent.ScoLinkChanged(true))
        assertEquals(AppliedRoute.VOICE_SCO, route.decision())
        assertEquals(listOf(true), host.scoChanged)
        val recreatesAfterConnect = host.recreateCount
        // Physical drop mid-call: falls back to the media/handset route with
        // a disconnect notify (no silent stick on the SCO triple).
        sco.activeValue = false
        sco.idleValue = true
        route.onEvent(RouteEvent.ScoLinkChanged(false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
        assertEquals(listOf(true, false), host.scoChanged)
        assertEquals(recreatesAfterConnect + 1, host.recreateCount)
    }

    @Test
    fun sessionStartedWithRequestedScoBringsUp() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Reconnect re-arm path: the next sync re-starts a requested link.
        route.setScoRequested(true)
        sco.idleValue = true
        route.onEvent(RouteEvent.SessionStarted)
        assertEquals(1, sco.startCount)
        assertEquals(1, host.recreateCount)
    }

    @Test
    fun sessionEndedResetsRouteState() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.setScoRequested(true)
        sco.activeValue = true
        sco.idleValue = false
        route.onEvent(RouteEvent.ScoLinkChanged(true))
        assertEquals(AppliedRoute.VOICE_SCO, route.decision())
        // A2DP stays attached across the disconnect: teardown must refresh
        // to the live media truth, release the mode, stop the link, and
        // cancel settle cover — not strand the SCO triple.
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(AppliedRoute.MEDIA, route.decision())
        assertEquals(AudioManager.MODE_NORMAL, platform.modeValue)
        assertEquals(1, sco.stopCount)
        // Posted teardown notify: stopScoWithNotify with wasActive posts
        // onScoTeardown; the flush below proves it is not dropped.
        runCurrent()
        assertEquals(1, host.teardownCount)
        // No live call remains: advancing past both settle windows must not
        // requery or recreate.
        val settledQueries = platform.queryCount
        val settledRecreates = host.recreateCount
        advanceTimeBy(2 * AudioRouteController.ROUTE_SETTLE_RECHECK_MS + 3000)
        runCurrent()
        assertEquals(settledQueries, platform.queryCount)
        assertEquals(settledRecreates, host.recreateCount)
    }

    @Test
    fun shouldDropStaleEventPinsTokenPredicate() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Current token passes; a moved token drops (fast-reconnect race).
        assertFalse(route.shouldDropStaleEvent(1))
        host.tokenValue = 2
        assertTrue(route.shouldDropStaleEvent(1))
        assertFalse(route.shouldDropStaleEvent(2))
        // Teardown drops everything regardless of token.
        route.onEvent(RouteEvent.Destroyed)
        assertTrue(route.shouldDropStaleEvent(2))
    }

    @Test
    fun confirmedScoWinsOverMediaSinkAndSkipsCounter() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Confirmed SCO forces the voice triple even with a media sink up…
        sco.activeValue = true
        sco.idleValue = false
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.ScoLinkChanged(true))
        assertEquals(AppliedRoute.VOICE_SCO, route.decision())
        // …and the media flip path never runs the foreign-SCO counter while
        // the confirmed link owns the route.
        platform.scoOnValue = true
        platform.events.clear()
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.VOICE_SCO, route.decision())
        assertFalse(EVT_STOP_SCO in platform.events)
    }

    @Test
    fun maybeStartBleFallbackPositiveAndWiredNegative() = runTest {
        // BLE headset add with an available SCO device: fallback fires.
        run {
            val (platform, sco, host) = newFakes()
            sco.availableValue = true
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.registerDeviceListener(mock(Handler::class.java))
            route.setScoRequested(true)
            platform.capturedCallback!!.onAudioDevicesAdded(
                arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
            )
            runCurrent()
            assertEquals(1, sco.startCount)
        }
        // Wired headset add: media sink, never a bring-up trigger.
        run {
            val (platform, sco, host) = newFakes()
            sco.availableValue = true
            val route = AudioRouteController(platform, sco, this, host)
            route.onInit()
            route.registerDeviceListener(mock(Handler::class.java))
            route.setScoRequested(true)
            platform.capturedCallback!!.onAudioDevicesAdded(
                arrayOf(deviceOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
            )
            runCurrent()
            assertEquals(0, sco.startCount)
        }
    }

    @Test
    fun offMainFunnelPostsWithTokenAndDropsStale() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Simulate an off-main caller hopping to the Main funnel: the entry
        // posts, the posted body runs as main.
        route.mainThreadOverride = false
        // Off-main SessionEnded while SCO active: flip is funneled, notify posted.
        sco.activeValue = true
        sco.idleValue = false
        route.setScoRequested(true)
        route.onEvent(RouteEvent.SessionEnded)
        route.mainThreadOverride = true
        // Off-main entry posts the whole SessionEnded (stop + flip + notify).
        assertEquals(0, sco.stopCount)
        runCurrent()
        assertEquals(1, sco.stopCount)
        assertEquals(1, host.teardownCount)
        // Stale token: bump generation before the posted body runs.
        // Fresh platform per controller: sharing `platform` across route/route2
        // couples queryCount/mode/events/capturedCallback between halves.
        val (platform2, sco2, host2) = newFakes()
        val route2 = AudioRouteController(platform2, sco2, this, host2)
        route2.onInit()
        route2.mainThreadOverride = false
        sco2.activeValue = true
        sco2.idleValue = false
        route2.setScoRequested(true)
        route2.onEvent(RouteEvent.SessionEnded)
        host2.tokenValue = 99
        route2.mainThreadOverride = true
        runCurrent()
        assertEquals(0, host2.teardownCount)
        route.mainThreadOverride = null
        route2.mainThreadOverride = null
    }

    @Test
    fun handlerIdentityIsCaptured() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        val handler = mock(Handler::class.java)
        route.registerDeviceListener(handler)
        assertSame(handler, platform.capturedHandler)
        // The callback must ride along: identity alone cannot catch a
        // threading misuse where the handler is dropped (see FakePlatform).
        assertNotNull(platform.capturedCallback)
    }

    @Test
    fun retryScoDropsWhenLiveFlipsMidBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.setScoRequested(true)
        host.live = true
        // Side-effecting link: clears live mid-start to simulate disconnect racing bring-up.
        // Uses `by` delegation: ScoLink is a small stable port (7 methods); new methods
        // must be considered here (see probe comment above for the AudioPlatform rationale).
        val racingSco = object : ScoLink by sco {
            override fun start() {
                sco.start()
                host.live = false
            }
        }
        val racingRoute = AudioRouteController(platform, racingSco, this, host)
        racingRoute.onInit()
        racingRoute.setScoRequested(true)
        assertFalse(racingRoute.retrySco())
    }

    @Test
    fun retryScoDropsWhenRequestedFlipsMidBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Box avoids lateinit-capture-before-init fragility: the racing link
        // reads the box, which is assigned before any start() can run.
        val routeBox = arrayOfNulls<AudioRouteController>(1)
        val racingSco = object : ScoLink by sco {
            override fun start() {
                sco.start()
                routeBox[0]?.setScoRequested(false)
            }
        }
        val racingRoute = AudioRouteController(platform, racingSco, this, host)
        routeBox[0] = racingRoute
        racingRoute.onInit()
        racingRoute.setScoRequested(true)
        assertFalse(racingRoute.retrySco())
    }

    @Test
    fun maybeStartProbeFailureFailsClosed() = runTest {
        val (platform, sco, host) = newFakes()
        sco.throwOnAvailable = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        platform.capturedCallback!!.onAudioDevicesAdded(
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
        )
        runCurrent()
        // Throwing probe must not crash the funnel and must not start.
        assertEquals(0, sco.startCount)
    }

    @Test
    fun bringUpPreservesDebouncedRecompute() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        val baseQueries = platform.queryCount
        platform.capturedCallback!!.onAudioDevicesAdded(
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)),
        )
        runCurrent()
        assertEquals(1, sco.startCount)
        // Debounced recompute still pending: advance past it and verify it runs.
        // Name states the contract: bring-up must NOT drain (cancel) the
        // pending recompute -- both the SCO start and the query run.
        advanceTimeBy(AudioRouteController.ROUTE_RECOMPUTE_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(platform.queryCount > baseQueries)
    }

    @Test
    fun handsetStreamAndRouteAccessors() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.setHandsetStream(AudioManager.STREAM_VOICE_CALL)
        assertEquals(AudioManager.STREAM_VOICE_CALL, route.handsetStream())
        // 999 exercises warn-and-store-verbatim: the pipeline consumes it as-is
        // and anything != VOICE_CALL maps to speakerphone/media downstream
        // (see AudioHandler/AudioOutput mapping), not a crash.
        route.setHandsetStream(999)
        assertEquals(999, route.handsetStream())
        assertFalse(route.isMediaRouteActive)
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        route.onEvent(RouteEvent.SettingsChanged(reload = true))
        assertTrue(route.isMediaRouteActive)
        assertFalse(route.isScoActive())
        sco.activeValue = true
        sco.idleValue = false
        assertTrue(route.isScoActive())
        route.setScoRequested(true)
        assertTrue(route.isScoRequested)
    }

    @Test
    fun updateScoRouteIdleSkipsStopAndNullScoIsSafe() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Idle link with nothing requested: no platform round-trip.
        sco.idleValue = true
        route.setScoRequested(false)
        route.updateScoRoute()
        assertEquals(0, sco.stopCount)
        // Null-sco controller: every teardown path is a no-op, and link events
        // are dropped defensively (impossible in production: nothing emits them
        // without a manager). State must not flip and nothing fans out.
        val nullRoute = AudioRouteController(platform, null, this, host)
        nullRoute.onInit()
        val decisionBefore = nullRoute.decision()
        val scoChangedBefore = host.scoChanged.size
        nullRoute.stopScoWithNotify()
        nullRoute.updateScoRoute()
        nullRoute.onScoConnected()
        nullRoute.onScoDisconnected()
        runCurrent()
        assertEquals(decisionBefore, nullRoute.decision())
        assertEquals(scoChangedBefore, host.scoChanged.size)
    }

    @Test
    fun settingsSameRouteForceReloadRebuilds() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SessionStarted)
        val base = host.recreateCount
        // Same route, live pipeline, forced reload: must rebuild.
        route.onEvent(RouteEvent.SettingsChanged(reload = true))
        assertTrue(host.recreateCount > base)
    }

    @Test
    fun negativeMediaTypesStayVoice() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Sampled non-media set (earpiece/HDMI/dock/line/speaker/telecom):
        // full-matrix coverage lives in deviceMatrixSelectsExpectedRoutes
        // for media types; these must all stay handset voice.
        platform.devices = arrayOf(
            deviceOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
            deviceOf(AudioDeviceInfo.TYPE_HDMI),
            deviceOf(AudioDeviceInfo.TYPE_DOCK),
            deviceOf(AudioDeviceInfo.TYPE_LINE_ANALOG),
            deviceOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            deviceOf(AudioDeviceInfo.TYPE_TELEPHONY),
        )
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
    }

    @Test
    fun bleSpeakerFallbackStartsBringUp() = runTest {
        val (platform, sco, host) = newFakes()
        sco.availableValue = true
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.registerDeviceListener(mock(Handler::class.java))
        route.setScoRequested(true)
        platform.capturedCallback!!.onAudioDevicesAdded(
            arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLE_SPEAKER)),
        )
        runCurrent()
        assertEquals(1, sco.startCount)
    }

    @Test
    fun counterDoesNotClaimWhenNotLive() = runTest {
        val (platform, sco, host) = newFakes()
        host.live = false
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        platform.devices = arrayOf(deviceOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        // Arm the counter: foreign SCO up while we are offline. With live=false
        // the counter must still skip (this used to pass vacuously via
        // !isSystemScoOn early-return with scoOnValue=false).
        platform.scoOnValue = true
        route.onEvent(RouteEvent.SettingsChanged(reload = false))
        assertEquals(AudioManager.MODE_NORMAL, platform.modeValue)
    }

    @Test
    fun nonNormalBaselineRestoresOnTeardown() = runTest {
        val (platform, sco, host) = newFakes()
        // IN_CALL is never stolen (cellular); use RINGTONE as the claimable
        // non-normal baseline whose restore proves the saved-mode path.
        platform.modeValue = AudioManager.MODE_RINGTONE
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        // Voice route claims IN_COMM, saving RINGTONE; teardown restores it.
        platform.devices = emptyArray()
        route.onEvent(RouteEvent.SessionStarted)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.modeValue)
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(AudioManager.MODE_RINGTONE, platform.modeValue)
    }

    @Test
    fun doubleDestroyedIsIdempotent() = runTest {
        val (platform, sco, host) = newFakes()
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.Destroyed)
        route.onEvent(RouteEvent.Destroyed)
        runCurrent()
        // Double-destroy is idempotent: destroy() runs once, route cleared.
        assertEquals(1, sco.destroyCount)
        assertEquals(AppliedRoute.VOICE_HANDSET, route.decision())
    }

    @Test
    fun sessionEndedWhenNotLiveResetsWithoutNotify() = runTest {
        val (platform, sco, host) = newFakes()
        host.live = false
        val route = AudioRouteController(platform, sco, this, host)
        route.onInit()
        route.onEvent(RouteEvent.SessionEnded)
        assertEquals(0, host.teardownCount)
    }
}
