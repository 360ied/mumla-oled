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

import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.humla.HumlaRuntime
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.FakeFactory
import se.lublin.humla.client.FakeTransport
import se.lublin.humla.client.testTarget
import se.lublin.humla.route.RouteState
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.voice.EngineNative
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.service.MumlaService

/**
 * Executable check of the step-6 lifetime rule: the container and its runtime
 * are built once per process, and a service created after another one was
 * destroyed gets the same, still working objects.
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

    @Test
    fun secondServiceSharesTheRuntimeAndDropsTheFirstObservers() {
        val app = RuntimeEnvironment.getApplication() as TestApplication
        val container = app.container
        val firstController = Robolectric.buildService(MumlaService::class.java).create()
        val first = firstController.get()
        val firstRuntime = runtimeOf(first)
        assertSame(container.runtime, firstRuntime)

        var firstCalls = 0
        val firstObserver = object : HumlaObserver() {
            override fun onConnecting() { firstCalls++ }
        }
        first.registerObserver(firstObserver)

        firstController.destroy()

        // The runtime's route thread is still alive after the first service
        // is gone: the process-lifetime answer is not a closed route.
        container.runtime.retrySco()
        assertTrue(container.runtime.routeState.value is RouteState)

        container.runtime.callbacks.onConnecting()
        assertTrue("observer of a destroyed service must be gone", firstCalls == 0)

        val secondController = Robolectric.buildService(MumlaService::class.java).create()
        val second = secondController.get()
        val secondRuntime = runtimeOf(second)
        assertSame("second service must hold the same runtime", firstRuntime, secondRuntime)
        assertSame("second service must hold the same client", firstRuntime.client, secondRuntime.client)
        assertSame(container.runtime.client, secondRuntime.client)

        var secondCalls = 0
        val secondObserver = object : HumlaObserver() {
            override fun onConnecting() { secondCalls++ }
        }
        second.registerObserver(secondObserver)
        container.runtime.callbacks.onConnecting()
        assertTrue("observer of the live service must receive callbacks", secondCalls > 0)
        assertNotSame(first, second)

        secondController.destroy()
    }

    @Test
    fun connectThroughSecondServiceReachesTheFakeTransport() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication() as TestApplication
        val container = app.container
        val firstController = Robolectric.buildService(MumlaService::class.java).create()
        val first = firstController.get()
        firstController.destroy()
        val secondController = Robolectric.buildService(MumlaService::class.java).create()
        val second = secondController.get()

        val factory = container.runtimeEdges.transports as FakeFactory
        val transport = FakeTransport()
        factory.transports.addLast(transport)
        container.runtime.client.connect(testTarget())

        withTimeout(5_000) {
            while (container.runtime.client.state.value !is ClientState.Connecting) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue(container.runtime.client.state.value is ClientState.Connecting)
        // A connect reaches the fake transport and the process-lifetime route
        // is still live (its own thread answers SessionStarted asynchronously).
        withTimeout(5_000) {
            while (factory.created.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue("connect must reach the fake transport factory", factory.created.isNotEmpty())
        assertTrue(container.runtime.routeState.value is RouteState)
        container.runtime.client.disconnect()
        secondController.destroy()
    }

    }

    private fun runtimeOf(service: MumlaService): HumlaRuntime {
        val field = Class.forName("se.lublin.humla.HumlaService").getDeclaredField("appRuntime")
        field.isAccessible = true
        return field.get(service) as HumlaRuntime
    }
}
