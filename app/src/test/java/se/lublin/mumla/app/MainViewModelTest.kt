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

package se.lublin.mumla.app

import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Assert.assertEquals
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
import se.lublin.humla.model.Server
import se.lublin.humla.net.HandshakeFailure
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.emptySessionState
import se.lublin.mumla.TalkKeyEvent

/** The main screen's connection, dialogs, talk keys and crash prompt. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class RecordingActions : MainViewModel.MainActions {
        val calls = ArrayList<String>()
        val savedPasswords = ArrayList<Server>()
        var pinResult = true
        var newestCrash: String? = null
        val seenCrash = ArrayList<String>()
        val pinned = HashMap<String, X509Certificate>()
        val pins = ArrayList<String>()

        override fun connect(server: Server) { calls.add("connect:${server.host}:${server.password}") }
        override fun disconnect() { calls.add("disconnect") }
        override fun cancelReconnect() { calls.add("cancelReconnect") }
        override fun setSuppressNotifications(suppress: Boolean) { calls.add("suppress:$suppress") }
        override fun emitTalkKey(event: TalkKeyEvent) { calls.add("talk:$event") }
        override suspend fun saveServerPassword(server: Server) {
            savedPasswords.add(server)
            calls.add("save:${server.password}")
        }
        override fun pinnedCertificate(host: String): X509Certificate? = pinned[host]
        override fun pinCertificate(host: String, certificate: X509Certificate): Boolean {
            if (!pinResult) return false
            pins.add(host)
            pinned[host] = certificate
            return true
        }
        override suspend fun newestUnseenCrashReport(drain: Boolean): String? = newestCrash
        override suspend fun markCrashReportSeen(id: String) { seenCrash.add(id) }
        override fun clearChatNotifications() { calls.add("clearChat") }
    }

    private inner class Fixture {
        val clientState = MutableStateFlow<ClientState>(ClientState.Idle)
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 16)
        val actions = RecordingActions()
        val viewModel = MainViewModel(
            clientState = clientState,
            events = events,
            denyFormatter = { event -> "denied: ${event.reason}" },
            actions = actions,
            ioDispatcher = dispatcher,
        )
    }

    private fun server(
        host: String = "mumble.example.org",
        id: Long = -1L,
        password: String? = null,
    ): Server = Server(id, "My Server", host, 64738, "alice", password)

    @Test
    fun connectFromIdleStartsTheAttempt() = runTest {
        val f = Fixture()
        assertEquals(ConnectRequest.STARTED, f.viewModel.connect(server()))
        assertEquals(listOf("connect:mumble.example.org:null"), f.actions.calls)
    }

    @Test
    fun connectToTheCurrentServerIsANoOp() = runTest {
        val f = Fixture()
        val target = testTarget()
        f.clientState.value = ClientState.Connected(target, mock(Session::class.java))
        val current = MainViewModel.serverOf(target)
        assertEquals(ConnectRequest.ALREADY_CONNECTED, f.viewModel.connect(current))
        assertTrue(f.actions.calls.isEmpty())
    }

    @Test
    fun connectToAnotherServerAsksForConfirmation() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Connected(
            testTarget().copy(server = testTarget().server.copy(host = "other.example.org")),
            mock(Session::class.java),
        )
        assertEquals(ConnectRequest.SWITCH_CONFIRM, f.viewModel.connect(server()))
        assertTrue(f.actions.calls.isEmpty())
    }

    @Test
    fun whileConnectingTheSameAndOtherServersAreDistinct() = runTest {
        val f = Fixture()
        val target = testTarget()
        f.clientState.value = ClientState.Connecting(target, 0)
        assertEquals(
            ConnectRequest.CONNECTING_SAME,
            f.viewModel.connect(MainViewModel.serverOf(target)),
        )
        assertEquals(
            ConnectRequest.CONNECTING_OTHER,
            f.viewModel.connect(server(host = "other.example.org")),
        )
        assertTrue(f.actions.calls.isEmpty())
    }

    @Test
    fun aConfirmedSwitchConnectsOnlyAtIdleWhileStarted() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Connected(
            testTarget().copy(server = testTarget().server.copy(host = "other.example.org")),
            mock(Session::class.java),
        )
        val target = server(host = "new.example.org")
        f.viewModel.onActivityStarted()
        f.viewModel.confirmSwitch(target)
        assertEquals(listOf("disconnect"), f.actions.calls)
        assertTrue(f.viewModel.pendingSwitch.value)

        f.clientState.value = ClientState.Idle
        assertEquals(listOf("disconnect", "connect:new.example.org:null"), f.actions.calls)
        assertFalse(f.viewModel.pendingSwitch.value)
    }

    @Test
    fun aPendingSwitchWaitsForTheActivityToStart() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Connected(
            testTarget().copy(server = testTarget().server.copy(host = "other.example.org")),
            mock(Session::class.java),
        )
        f.viewModel.onActivityStopped()
        f.viewModel.confirmSwitch(server(host = "new.example.org"))
        f.clientState.value = ClientState.Idle
        assertEquals(listOf("disconnect"), f.actions.calls)
        assertTrue(f.viewModel.pendingSwitch.value)

        f.viewModel.onActivityStarted()
        assertEquals(listOf("disconnect", "connect:new.example.org:null"), f.actions.calls)
    }

    @Test
    fun disconnectClearsAPendingSwitch() = runTest {
        val f = Fixture()
        f.viewModel.confirmSwitch(server(host = "new.example.org"))
        f.viewModel.disconnect()
        assertFalse(f.viewModel.pendingSwitch.value)
        f.clientState.value = ClientState.Idle
        assertEquals(listOf("disconnect", "disconnect"), f.actions.calls)
    }

    @Test
    fun wrongPasswordRetrySavesThenConnects() = runTest {
        val f = Fixture()
        val target = testTarget()
        f.clientState.value = ClientState.Connected(target, mock(Session::class.java))
        val corrected = MainViewModel.serverOf(target).also { it.password = "new-pass" }
        f.viewModel.retryWithPassword(corrected)
        assertEquals(listOf("save:new-pass", "connect:${target.server.host}:new-pass"), f.actions.calls)
    }

    @Test
    fun wrongPasswordRetrySerializesBehindAnAttempt() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Connecting(testTarget(), 0)
        val corrected = MainViewModel.serverOf(testTarget()).also { it.password = "new-pass" }
        f.viewModel.retryWithPassword(corrected)
        assertEquals(listOf("save:new-pass", "disconnect"), f.actions.calls)
        assertTrue(f.viewModel.pendingSwitch.value)
    }

    @Test
    fun trustPromptKindsFollowTheFailureAndThePin() = runTest {
        val f = Fixture()
        val certificate = mock(X509Certificate::class.java)
        val chain = kotlinx.collections.immutable.persistentListOf(certificate)
        val target = testTarget()

        f.clientState.value = ClientState.Failed(
            target,
            ClientError.Trust(chain, HandshakeFailure.HOSTNAME_MISMATCH, "wrong.example.org"),
        )
        var prompt = f.viewModel.trustPrompt.value!!
        assertEquals(TrustPrompt.Kind.HOSTNAME_MISMATCH, prompt.kind)
        assertEquals("wrong.example.org", prompt.host)
        assertFalse(prompt.canPin)

        f.clientState.value = ClientState.Failed(
            target,
            ClientError.Trust(chain, HandshakeFailure.UNTRUSTED_ISSUER, null),
        )
        prompt = f.viewModel.trustPrompt.value!!
        assertEquals(TrustPrompt.Kind.FIRST_TRUST, prompt.kind)
        assertEquals(target.server.host, prompt.host)
        assertTrue(prompt.canPin)

        f.actions.pinned[target.server.host] = certificate
        // A reconnect attempt in between: the same Failed value would not
        // re-emit on the StateFlow.
        f.clientState.value = ClientState.Connecting(target, 1)
        f.clientState.value = ClientState.Failed(
            target,
            ClientError.Trust(chain, HandshakeFailure.UNTRUSTED_ISSUER, null),
        )
        prompt = f.viewModel.trustPrompt.value!!
        assertEquals(TrustPrompt.Kind.CHANGED_PIN, prompt.kind)
        assertEquals(certificate, prompt.pinned)
    }

    @Test
    fun pinningAndReconnecting() = runTest {
        val f = Fixture()
        val certificate = mock(X509Certificate::class.java)
        val prompt = TrustPrompt(
            TrustPrompt.Kind.FIRST_TRUST,
            "mumble.example.org",
            certificate,
            server(host = "mumble.example.org"),
            null,
        )
        assertTrue(f.viewModel.pinAndConnect(prompt))
        assertEquals(listOf("mumble.example.org"), f.actions.pins)
        assertEquals(listOf("connect:mumble.example.org:null"), f.actions.calls)

        f.actions.calls.clear()
        f.actions.pinResult = false
        assertFalse(f.viewModel.pinAndConnect(prompt))
        assertTrue(f.actions.calls.isEmpty())
    }

    @Test
    fun mismatchPromptsCannotPin() = runTest {
        val f = Fixture()
        val prompt = TrustPrompt(
            TrustPrompt.Kind.HOSTNAME_MISMATCH,
            "wrong.example.org",
            mock(X509Certificate::class.java),
            null,
            null,
        )
        assertFalse(f.viewModel.pinAndConnect(prompt))
        assertTrue(f.actions.calls.isEmpty())
    }

    @Test
    fun theErrorDialogIsShownOnceAndResetByANewAttempt() = runTest {
        val f = Fixture()
        val error = ClientError.Connection("boom", null)
        f.clientState.value = ClientState.Reconnecting(testTarget(), 2, 5_000L, error, null)
        assertEquals(error, f.viewModel.connectionError.value)
        f.viewModel.dismissError()
        assertNull(f.viewModel.connectionError.value)

        // A retry clears the dismissal so a second failure shows again.
        f.clientState.value = ClientState.Connecting(testTarget(), 3)
        assertNull(f.viewModel.connectionError.value)
        f.clientState.value = ClientState.Reconnecting(testTarget(), 4, 9_000L, error, null)
        assertEquals(error, f.viewModel.connectionError.value)
    }

    @Test
    fun trustFailuresNeverUseTheErrorDialog() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Failed(
            testTarget(),
            ClientError.Trust(
                kotlinx.collections.immutable.persistentListOf(mock(X509Certificate::class.java)),
                HandshakeFailure.UNTRUSTED_ISSUER,
                null,
            ),
        )
        assertNull(f.viewModel.connectionError.value)
    }

    @Test
    fun reconnectCountdownComesFromTheState() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Reconnecting(
            testTarget(), 3, 12_000L, ClientError.Connection("boom", null), null,
        )
        val connection = f.viewModel.connection.value as ConnectionUi.Reconnecting
        assertEquals(12_000L, connection.retryAtMillis)
        assertEquals(3, connection.attempt)
    }

    @Test
    fun permissionDeniedIsFormattedAndDismissible() = runTest {
        val f = Fixture()
        kotlinx.coroutines.runBlocking {
            f.events.emit(
                SessionEventRecord(
                    SessionEvent.PermissionDenied(-1, null, null, null, "not allowed", null),
                    emptySessionState(),
                ),
            )
        }
        assertEquals("denied: not allowed", f.viewModel.permissionDenied.value)
        f.viewModel.dismissPermissionDenied()
        assertNull(f.viewModel.permissionDenied.value)
    }

    @Test
    fun talkKeysAndSuppressionReachTheContainer() = runTest {
        val f = Fixture()
        f.viewModel.talkKeyDown()
        f.viewModel.talkKeyUp()
        f.viewModel.talkKeyCancel()
        f.viewModel.setSuppressNotifications(true)
        f.viewModel.clearChatNotifications()
        assertEquals(
            listOf("talk:Down", "talk:Up", "talk:Cancel", "suppress:true", "clearChat"),
            f.actions.calls,
        )
    }

    @Test
    fun crashPromptPublishesAndIsMarkedSeen() = runTest {
        val f = Fixture()
        f.actions.newestCrash = "report-1"
        f.viewModel.startCrashChecks()
        assertEquals("report-1", f.viewModel.crashReportId.value)
        f.viewModel.markCrashReportSeen("report-1")
        assertNull(f.viewModel.crashReportId.value)
        assertEquals(listOf("report-1"), f.actions.seenCrash)

        // A later recheck must not republish a report already seen.
        f.viewModel.recheckCrashReports()
        advanceTimeBy(MainViewModel.CRASH_RECHECK_DELAY_MS + 1_000)
        assertNull(f.viewModel.crashReportId.value)
    }

    @Test
    fun crashPromptIsNotRecheckedWhileShowing() = runTest {
        val f = Fixture()
        f.actions.newestCrash = "report-2"
        f.viewModel.recheckCrashReports()
        assertEquals("report-2", f.viewModel.crashReportId.value)
        f.actions.newestCrash = "report-3"
        f.viewModel.recheckCrashReports()
        assertEquals("report-2", f.viewModel.crashReportId.value)
        f.viewModel.dismissCrashPrompt()
        assertNull(f.viewModel.crashReportId.value)
    }

    @Test
    fun connectionUiNamesTheServerOrFallsBackToTheHost() = runTest {
        val f = Fixture()
        f.clientState.value = ClientState.Connected(testTarget(), mock(Session::class.java))
        val connected = f.viewModel.connection.value as ConnectionUi.Connected
        assertEquals(testTarget().server.host, connected.host)
        assertEquals(testTarget().server.name ?: testTarget().server.host, connected.serverName)
    }

    @Test
    fun sameServerComparesSavedIdsAndPasswords() {
        val saved = server(id = 7L, password = "pw")
        val endpoint = testTarget().server.copy(serverId = 7L, password = "pw")
        assertTrue(MainViewModel.isSameServer(endpoint, saved))
        assertFalse(MainViewModel.isSameServer(endpoint.copy(password = "other"), saved))
        assertFalse(MainViewModel.isSameServer(endpoint.copy(serverId = 8L), saved))
    }

    @Test
    fun wrongPasswordRejectTypesAreRecognised() {
        val f = Fixture()
        val error = ClientError.Rejected(Mumble.Reject.RejectType.WrongServerPW.number, "bad pw")
        f.clientState.value = ClientState.Failed(testTarget(), error)
        val failed = f.viewModel.connection.value as ConnectionUi.Failed
        assertEquals(error, failed.error)
        assertEquals(testTarget().server.serverId, failed.target.server.serverId)
    }
}
