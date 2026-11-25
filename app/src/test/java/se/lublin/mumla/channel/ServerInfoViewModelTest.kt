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

package se.lublin.mumla.channel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.CryptCounts
import se.lublin.humla.client.Session
import se.lublin.humla.client.SessionStats
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.humla.net.Transport
import se.lublin.humla.state.ServerInfo
import se.lublin.humla.state.emptySessionState

/** Server-info rendering from state, without the old polling executor. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerInfoViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture {
        val clientState = MutableStateFlow<ClientState>(ClientState.Idle)
        val sessionState = MutableStateFlow(emptySessionState())
        val stats = MutableStateFlow(SessionStats.initial())
        val transport: Transport = mock(Transport::class.java).also {
            `when`(it.remoteHost).thenReturn("voice.example.org")
            `when`(it.remotePort).thenReturn(64739)
        }
        val session: Session = mock(Session::class.java).also {
            `when`(it.stats).thenReturn(stats)
            `when`(it.transport).thenReturn(transport)
        }
        val viewModel = ServerInfoViewModel(clientState, sessionState)

        fun connect() {
            clientState.value = ClientState.Connected(emptyConnectTarget(), session)
        }

        fun setInfo(info: ServerInfo) {
            sessionState.value = sessionState.value.copy(
                server = sessionState.value.server.copy(info = info),
            )
        }
    }

    /** WhileSubscribed: something must collect before the state has a value. */
    private fun TestScope.subscribe(fixture: Fixture) {
        backgroundScope.launch { fixture.viewModel.state.collect {} }
        testScheduler.runCurrent()
    }

    private fun syncedInfo(
        release: String? = null,
        os: String? = "Linux",
        osVersion: String? = "6.1",
        opus: Boolean = true,
        maxBandwidth: Int? = 72000,
        versionV2: Long? = 0x0001_0005_0000_0000L,
    ): ServerInfo = ServerInfo(
        versionV2 = versionV2,
        release = release,
        os = os,
        osVersion = osVersion,
        opus = opus,
        maxBandwidth = maxBandwidth,
        welcomeText = null,
        settings = null,
    )

    @Test
    fun noDataWithoutASession() = runTest {
        val fixture = Fixture()
        fixture.setInfo(syncedInfo())
        subscribe(fixture)
        val state = fixture.viewModel.state.value
        assertFalse(state.connected)
        assertNull(state.host)
        assertNull(state.tcpLatencyMicros)
        // The mirror keeps the last server's info across the disconnect; the
        // screen gates rendering on `connected`.
        assertEquals(Constants.formatVersion(0x0001_0005_0000_0000L), state.release)
    }

    @Test
    fun connectedSessionProvidesEndpointAndStats() = runTest {
        val fixture = Fixture()
        fixture.setInfo(syncedInfo(release = "1.4.230"))
        fixture.stats.value = SessionStats(
            tcpLatencyMicros = 12_500,
            udpLatencyMicros = 31_000,
            usingUdp = true,
            currentBandwidth = 68_000,
            localCrypt = CryptCounts.zero(),
            remoteCrypt = CryptCounts.zero(),
        )
        fixture.connect()
        subscribe(fixture)
        val state = fixture.viewModel.state.value
        assertTrue(state.connected)
        assertEquals("1.4.230", state.release)
        assertEquals("Linux", state.osName)
        assertEquals("6.1", state.osVersion)
        assertEquals(12_500L, state.tcpLatencyMicros)
        assertEquals(31_000L, state.udpLatencyMicros)
        assertEquals("voice.example.org", state.host)
        assertEquals(64739, state.port)
        assertEquals("Opus", state.codecName)
        assertEquals(72_000, state.maxBandwidth)
        assertEquals(68_000, state.currentBandwidth)
    }

    @Test
    fun releaseFallsBackToTheProtocolVersion() = runTest {
        val fixture = Fixture()
        fixture.setInfo(syncedInfo(release = null, versionV2 = 0x0001_0005_0000_0000L))
        fixture.connect()
        subscribe(fixture)
        assertEquals(
            Constants.formatVersion(0x0001_0005_0000_0000L),
            fixture.viewModel.state.value.release,
        )
    }

    @Test
    fun liveStatsUpdatesReachTheState() = runTest {
        val fixture = Fixture()
        fixture.setInfo(syncedInfo())
        fixture.connect()
        subscribe(fixture)
        assertNull(fixture.viewModel.state.value.tcpLatencyMicros)
        fixture.stats.value =
            fixture.stats.value.copy(tcpLatencyMicros = 9_000, currentBandwidth = 42_000)
        testScheduler.runCurrent()
        assertEquals(9_000L, fixture.viewModel.state.value.tcpLatencyMicros)
        assertEquals(42_000, fixture.viewModel.state.value.currentBandwidth)
    }

    @Test
    fun aDirectSessionSwapFollowsTheNewSessionOnly() = runTest {
        // Connected(A) -> Connected(B) with no Idle between: the old session's
        // stats flow never ends, so only flatMapLatest stops following it.
        val fixture = Fixture()
        fixture.setInfo(syncedInfo())
        fixture.connect()
        subscribe(fixture)
        val statsB = MutableStateFlow(SessionStats.initial().copy(tcpLatencyMicros = 2_000))
        val transportB = mock(Transport::class.java).also {
            `when`(it.remoteHost).thenReturn("second.example.org")
            `when`(it.remotePort).thenReturn(64740)
        }
        val sessionB = mock(Session::class.java).also {
            `when`(it.stats).thenReturn(statsB)
            `when`(it.transport).thenReturn(transportB)
        }
        fixture.clientState.value = ClientState.Connected(emptyConnectTarget(), sessionB)
        testScheduler.runCurrent()
        assertEquals("second.example.org", fixture.viewModel.state.value.host)
        assertEquals(2_000L, fixture.viewModel.state.value.tcpLatencyMicros)

        // A late update from session A must not overwrite B's values.
        fixture.stats.value = fixture.stats.value.copy(tcpLatencyMicros = 9_999)
        testScheduler.runCurrent()
        assertEquals(2_000L, fixture.viewModel.state.value.tcpLatencyMicros)
        assertEquals(64740, fixture.viewModel.state.value.port)
    }

    @Test
    fun disconnectDropsTheSessionFields() = runTest {
        val fixture = Fixture()
        fixture.setInfo(syncedInfo())
        fixture.connect()
        subscribe(fixture)
        assertTrue(fixture.viewModel.state.value.connected)
        fixture.clientState.value = ClientState.Idle
        testScheduler.runCurrent()
        val state = fixture.viewModel.state.value
        assertFalse(state.connected)
        assertNull(state.host)
        assertNull(state.currentBandwidth)
    }

    @Test
    fun unknownValuesStayUnknown() = runTest {
        val fixture = Fixture()
        fixture.setInfo(syncedInfo(opus = false, maxBandwidth = null, os = null, osVersion = null))
        fixture.connect()
        subscribe(fixture)
        val state = fixture.viewModel.state.value
        assertNull(state.codecName)
        assertNull(state.maxBandwidth)
        assertNull(state.osName)
        assertNull(state.osVersion)
    }
}
