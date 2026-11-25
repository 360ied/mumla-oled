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

package se.lublin.mumla

import android.content.Context
import android.content.Intent
import android.os.Looper
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import se.lublin.humla.HumlaRuntime
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.FakeFactory
import se.lublin.humla.client.FakeTransport
import se.lublin.humla.client.reject
import se.lublin.humla.client.syncSequence
import se.lublin.humla.client.testTarget
import se.lublin.humla.client.userState
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.Inbound
import se.lublin.humla.route.RouteState
import se.lublin.humla.voice.EngineNative
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.service.ConnectionService

/**
 * Executable check of the step-7 service rule: the container and its runtime
 * are built once per process, the UI's connect path starts ConnectionService
 * at the first visible attempt, and the service lives exactly while the
 * connection does. Nothing binds to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = AppContainerLifecycleTest.TestApplication::class, sdk = [34])
class AppContainerLifecycleTest {

    class TestApplication : MumlaApplication() {
        override fun createContainer(): AppContainer =
            AppContainer(
                this,
                HumlaRuntime.Edges(
                    engineFactory = { FakeEngineNative() },
                    transports = FakeFactory(),
                ),
            )
    }

    /** No native library under Robolectric; every entry is a no-op. */
    private class FakeEngineNative : EngineNative {
        override fun setFormat(udpFormat: Int, legacyVersion: Int) = Unit
        override fun setPolicy(
            muted: IntArray?, known: IntArray?, transmitAllowed: Boolean,
            pttPressed: Boolean, voiceTarget: Int, bitrate: Int, framesPerPacket: Int,
        ) = Unit
        override fun setSettings(
            inputMode: Int, vadMax: Float, vadMin: Float, amplitudeBoost: Float,
            adaptiveLeveler: Boolean, halfDuplex: Boolean,
        ) = Unit
        override fun setVoiceRoute(udp: Boolean) = Unit
        override fun setRnnoiseModel(model: ByteArray?): Boolean = true
        override fun setKeys(key: ByteArray?, eiv: ByteArray?, div: ByteArray?): Boolean = true
        override fun setDecryptIv(div: ByteArray?): Boolean = true
        override fun getEncryptIv(): ByteArray? = null
        override fun start(host: String, port: Int): Boolean = true
        override fun stop() = Unit
        override fun reset() = Unit
        override fun enqueuePing(timestampMicros: Long): Boolean = true
        override fun captureFrame(pcm: ShortArray?, offset: Int, length: Int) = Unit
        override fun queueTunnelled(packet: ByteArray?): Boolean = true
        override fun pushRemoveUser(session: Int): Boolean = true
        override fun render(out: ShortArray?, offset: Int, length: Int): Int = 0
        override fun openStreams(inputPreset: Int, usage: Int): LongArray? = longArrayOf(1L, 1L)
        override fun startStreams(): Boolean = true
        override fun stopStreams() = Unit
        override fun closeStreams() = Unit
        override fun lastDroppedInbound(): Long = 0
        override fun lastDroppedTunnel(): Long = 0
        override fun lastDroppedJitter(): Long = 0
        override fun lastDroppedOutbound(): Long = 0
        override fun getStats(): IntArray? = null
        override fun destroy() = Unit
    }

    private fun app(): TestApplication = RuntimeEnvironment.getApplication() as TestApplication

    private fun container(): AppContainer = app().container

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun factory(): FakeFactory = container().runtimeEdges.transports as FakeFactory

    private fun newTransport(): FakeTransport = FakeTransport().also {
        factory().transports.addLast(it)
    }

    /** Waits for the connect path's started-service intent without consuming it. */
    private fun awaitServiceIntent(): Intent = runBlocking {
        withTimeout(5_000) {
            while (Shadows.shadowOf(app()).peekNextStartedService() == null) {
                idle()
                delay(10)
            }
            Shadows.shadowOf(app()).nextStartedService
                ?: throw AssertionError("no started service")
        }
    }

    private fun <T : ClientState> awaitState(kind: KClass<T>): T = runBlocking {
        withTimeout(5_000) {
            var value = container().runtime.client.state.value
            while (!kind.isInstance(value)) {
                idle()
                delay(10)
                value = container().runtime.client.state.value
            }
            @Suppress("UNCHECKED_CAST")
            value as T
        }
    }

    /** Starts ConnectionService the way the foreground start would. */
    private fun startService(intent: Intent): ServiceController<ConnectionService> =
        Robolectric.buildService(ConnectionService::class.java, intent).create().startCommand(0, 0)

    /** Connect through the container path and start the service it asked for. */
    private fun connectAndStartService(transport: FakeTransport): ServiceController<ConnectionService> {
        idle()
        container().connect(testTarget())
        awaitState(ClientState.Connecting::class)
        val intent = awaitServiceIntent()
        assertEquals(ConnectionService::class.java.name, intent.component?.className)
        val controller = startService(intent)
        idle()
        return controller
    }

    @After fun closeRuntime() {
        // Every Robolectric container registers one process network callback;
        // release it so tests do not accumulate callbacks.
        container().runtime.close()
    }

    @Test fun runtimeKeepsAndReleasesItsNetworkCallback() {
        val runtime = container().runtime
        assertTrue(runtime.networkCallbackRegistered)
        runtime.close()
        assertFalse(runtime.networkCallbackRegistered)
        runtime.close()
        assertFalse(runtime.networkCallbackRegistered)
    }

    @Test fun processSingletonsAreShared() {
        val app = app()
        val companion = AppContainer.Companion
        val create = AppContainer.Companion::class.java.getDeclaredMethod(
            "createSettingsStore", Context::class.java,
        )
        create.isAccessible = true
        val first = create.invoke(companion, app)
        assertSame(first, create.invoke(companion, app))
        assertSame(first, app.container.settingsStore)
        assertSame(MumlaRoomDatabase.create(app), MumlaRoomDatabase.create(app))
    }

    @Test fun rejectedVoiceSettingsKeepThePreviousValueAndTheFlowAlive(): Unit = runBlocking {
        val container = container()
        val before = container.voiceSettings.value
        container.settingsRepository.updateTyped { it.inputBitrate = 0 }
        withTimeout(5_000) {
            while (container.settingsRepository.state.value.snapshot.inputBitrate != 0) {
                delay(10)
            }
        }
        delay(50)
        assertEquals(before, container.voiceSettings.value)
        // A rejected snapshot must not kill the flow: a later valid write
        // still reaches voiceSettings without another container rebuild.
        container.settingsRepository.updateTyped { it.inputBitrate = 48000 }
        withTimeout(5_000) {
            while (container.voiceSettings.value.targetBitrate != 48000) {
                delay(10)
            }
        }
        assertEquals(48000, container.voiceSettings.value.targetBitrate)
    }

    @Test
    fun connectStartsTheServiceAndTheSessionConnectsWithoutABinding(): Unit = runBlocking {
        val transport = newTransport()
        val controller = connectAndStartService(transport)
        val shadow = Shadows.shadowOf(controller.get())
        assertTrue(
            "the service must post the connection notification at once",
            shadow.isLastForegroundNotificationAttached(),
        )

        for (message in syncSequence()) {
            transport.inboundChannel.trySend(message)
        }
        awaitState(ClientState.Connected::class)
        idle()
        assertTrue("the service stays in the foreground", shadow.isLastForegroundNotificationAttached())
        assertTrue(
            "nothing binds a service",
            Shadows.shadowOf(app()).boundServiceConnections.isEmpty(),
        )

        // A semantic event reaches the process-lifetime features without any
        // service instance being bound.
        transport.inboundChannel.trySend(
            Inbound.Message(HumlaTCPMessageType.UserState, userState(3, "carol", 0)),
        )
        withTimeout(5_000) {
            while (container().chatLog.currentMessages().isEmpty()) {
                idle()
                delay(10)
            }
        }
        assertTrue(
            container().chatLog.currentMessages().any { it.body?.contains("carol") == true },
        )

        container().runtime.client.disconnect()
        awaitState(ClientState.Idle::class)
        idle()
        controller.destroy()
    }

    @Test
    fun idleStopsTheServiceAndRemovesTheNotification(): Unit = runBlocking {
        val transport = newTransport()
        val controller = connectAndStartService(transport)
        for (message in syncSequence()) {
            transport.inboundChannel.trySend(message)
        }
        awaitState(ClientState.Connected::class)
        idle()

        container().runtime.client.disconnect()
        awaitState(ClientState.Idle::class)
        idle()
        val shadow = Shadows.shadowOf(controller.get())
        assertTrue("the notification goes", shadow.isForegroundStopped())
        assertTrue("the service stops itself", shadow.isStoppedBySelf())
        controller.destroy()
    }

    @Test
    fun failedStopsTheServiceAndRemovesTheNotification(): Unit = runBlocking {
        val transport = newTransport()
        val controller = connectAndStartService(transport)
        transport.inboundChannel.trySend(Inbound.Message(HumlaTCPMessageType.Reject, reject()))
        awaitState(ClientState.Failed::class)
        idle()
        val shadow = Shadows.shadowOf(controller.get())
        assertTrue("the notification goes", shadow.isForegroundStopped())
        assertTrue("the service stops itself", shadow.isStoppedBySelf())
        controller.destroy()
    }

    @Test
    fun aConnectThatFailsBeforeConnectingLeavesNoRunningService(): Unit = runBlocking {
        // A trust failure arrives without a session; the waiter must not leave
        // a running service behind. Depending on scheduling it either never
        // starts one or starts one that immediately stops at Failed.
        factory().throwOnCreate = se.lublin.humla.net.TransportException(
            se.lublin.humla.client.trustError(),
        )
        idle()
        container().connect(testTarget())
        awaitState(ClientState.Failed::class)
        idle()
        val started = Shadows.shadowOf(app()).nextStartedService
        if (started != null) {
            val controller = startService(started)
            idle()
            val shadow = Shadows.shadowOf(controller.get())
            assertTrue("a started service must have stopped itself", shadow.isStoppedBySelf())
            assertTrue(shadow.isForegroundStopped())
            controller.destroy()
        }
    }

    @Test
    fun destroyingTheServiceWhileConnectedDisconnectsTheClient(): Unit = runBlocking {
        val transport = newTransport()
        val controller = connectAndStartService(transport)
        for (message in syncSequence()) {
            transport.inboundChannel.trySend(message)
        }
        awaitState(ClientState.Connected::class)
        idle()

        controller.destroy()
        awaitState(ClientState.Idle::class)
    }

    @Test
    fun aSecondConnectSharesTheRuntimeAndTheRouteStillAnswers(): Unit = runBlocking {
        val first = newTransport()
        val firstController = connectAndStartService(first)
        for (message in syncSequence()) {
            first.inboundChannel.trySend(message)
        }
        awaitState(ClientState.Connected::class)
        withTimeout(5_000) {
            while (container().runtime.routeState.value is RouteState.Idle) {
                idle()
                delay(10)
            }
        }
        container().runtime.client.disconnect()
        awaitState(ClientState.Idle::class)
        idle()
        firstController.destroy()

        // The process-lifetime route thread is still alive after the service
        // is gone: the process-lifetime answer is not a closed route.
        container().runtime.retrySco()
        assertTrue(container().runtime.routeState.value is RouteState)

        val runtime = container().runtime
        val client = runtime.client
        val second = newTransport()
        val secondController = connectAndStartService(second)
        assertSame("a second connect gets the same runtime", runtime, container().runtime)
        assertSame("a second connect gets the same client", client, container().runtime.client)
        for (message in syncSequence()) {
            second.inboundChannel.trySend(message)
        }
        awaitState(ClientState.Connected::class)
        withTimeout(5_000) {
            while (container().runtime.routeState.value is RouteState.Idle) {
                idle()
                delay(10)
            }
        }
        container().runtime.client.disconnect()
        awaitState(ClientState.Idle::class)
        withTimeout(5_000) {
            while (container().runtime.routeState.value !is RouteState.Idle) {
                idle()
                delay(10)
            }
        }
        idle()
        secondController.destroy()
    }

    @Test
    fun anIdleServiceStopsItself(): Unit = runBlocking {
        // The service posts first (Android's five-second rule) and the state
        // collector stops it at once when nothing is connecting.
        val intent = Intent(app(), ConnectionService::class.java)
        val controller = startService(intent)
        idle()
        val shadow = Shadows.shadowOf(controller.get())
        assertTrue(shadow.isStoppedBySelf())
        assertTrue(shadow.isForegroundStopped())
        controller.destroy()
    }
}
