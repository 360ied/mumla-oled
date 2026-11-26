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

package se.lublin.mumla.preference

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.Session
import se.lublin.humla.client.testTarget

/**
 * Crash tests are enabled exactly while no connection is live, and native
 * availability comes from a probe that runs off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(state: ClientState) =
        CrashReportsViewModel(MutableStateFlow(state))

    @Test
    fun idleAndFailedEnableTheTests() {
        assertTrue(viewModel(ClientState.Idle).crashTestsEnabled.value)
        assertTrue(
            viewModel(ClientState.Failed(testTarget(), ClientError.Removed(null, "bye", false)))
                .crashTestsEnabled.value,
        )
    }

    @Test
    fun liveStatesDisableTheTests() {
        assertFalse(viewModel(ClientState.Connecting(testTarget(), 0)).crashTestsEnabled.value)
        assertFalse(
            viewModel(ClientState.Connected(testTarget(), mock(Session::class.java)))
                .crashTestsEnabled.value,
        )
        assertFalse(
            viewModel(
                ClientState.Reconnecting(
                    testTarget(), 1, 0L, ClientError.Connection("boom", null), null,
                ),
            ).crashTestsEnabled.value,
        )
    }

    @Test
    fun stateChangesFollowTheFlow() {
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val viewModel = CrashReportsViewModel(state, probe = { false }, probeDispatcher = dispatcher)
        assertTrue(viewModel.crashTestsEnabled.value)
        state.value = ClientState.Connecting(testTarget(), 0)
        assertFalse(viewModel.crashTestsEnabled.value)
        state.value = ClientState.Failed(testTarget(), ClientError.Rejected(1, "no"))
        assertTrue(viewModel.crashTestsEnabled.value)
    }

    @Test
    fun nativeAvailabilityStartsFalseUntilTheProbeRuns() {
        // A paused probe dispatcher is what the screen sees while the first
        // dlopen is in flight: the button keeps its unavailable summary.
        val paused = StandardTestDispatcher()
        val viewModel = CrashReportsViewModel(
            MutableStateFlow<ClientState>(ClientState.Idle),
            probe = { true },
            probeDispatcher = paused,
        )
        assertFalse(viewModel.nativeCrashAvailable.value)
        paused.scheduler.advanceUntilIdle()
        assertTrue(viewModel.nativeCrashAvailable.value)
    }

    @Test
    fun nativeAvailabilityFollowsATrueProbe() {
        val viewModel = CrashReportsViewModel(
            MutableStateFlow<ClientState>(ClientState.Idle),
            probe = { true },
            probeDispatcher = dispatcher,
        )
        assertTrue(viewModel.nativeCrashAvailable.value)
        // The probe is availability only: it never enables the tests itself.
        assertTrue(viewModel.crashTestsEnabled.value)
    }

    @Test
    fun throwingProbeReportsUnavailableWithoutEscaping() {
        // The failure must be reported as "unavailable", not raised as an
        // uncaught coroutine exception: the probe replaces a main-thread
        // native call, so it owns the failure.
        val uncaught = AtomicReference<Throwable?>(null)
        val previous = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { _, t -> uncaught.set(t) }
        val viewModel = try {
            CrashReportsViewModel(
                MutableStateFlow<ClientState>(ClientState.Idle),
                probe = { throw IllegalStateException("no libhumlaaudio") },
                probeDispatcher = dispatcher,
            )
        } finally {
            Thread.currentThread().uncaughtExceptionHandler = previous
        }
        assertFalse(viewModel.nativeCrashAvailable.value)
        assertNull("a throwing probe must not escape", uncaught.get())
    }
}
