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

package se.lublin.humla.interop

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.HumlaClient
import se.lublin.humla.client.Session
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.humla.voice.InputMode
import se.lublin.humla.voice.PolicyCollector
import se.lublin.humla.voice.VoiceSettings

/**
 * Step 5a.4 intent-to-silence traversal over UDP: a live [Session] mute and
 * PTT intent flows through a real [PolicyCollector] into the production
 * [se.lublin.humla.voice.VoiceEngine.setPolicy] path (no `*ForTests` seams
 * on the sender), and the receiver measures silence and recovery.
 *
 * Complements `voiceGatingMutesDeafensAndPtt` (synthetic gate, tunnel):
 * the mapping (`voicePolicy`), the serialized facade push, and the native
 * gate enforcement are traversed in one test on the UDP socket path.
 */
@Category(MurmurInterop::class)
class MurmurVoiceGateInteropTest {
    private var server: MurmurTestServer? = null
    private val open: MutableList<HumlaClientHandle> = ArrayList()
    private val tempStores: MutableList<Path> = ArrayList()

    @Before
    @Throws(Exception::class)
    fun startServer() {
        server = MurmurTestServer.start()
    }

    @After
    fun tearDown() {
        for (h in open) {
            try {
                h.disconnectAndClose()
            } catch (ignored: Exception) {
            }
        }
        open.clear()
        server?.stop()
        server = null
        for (p in tempStores) {
            try {
                Files.deleteIfExists(p)
            } catch (ignored: Exception) {
            }
        }
        tempStores.clear()
    }

    @Test
    fun voiceMuteAndPttViaSessionTraverseToSilenceOnUdp() {
        val receiverHandle = connectHandle("InteropAlice", null, InteropClient.Voice.UDP)
        val receiverSession = InteropClient.awaitConnected(receiverHandle.client, "receiver sync")
        val senderHandle = connectHandle("InteropBob", null, InteropClient.Voice.UDP)
        val senderSession = InteropClient.awaitConnected(senderHandle.client, "sender sync")
        val senderId = senderSession.getSessionId()
        InteropClient.awaitUser(receiverSession, "InteropBob", "receiver sees sender")
        InteropClient.awaitUser(senderSession, "InteropAlice", "sender sees receiver")
        InteropClient.awaitUsingUdp(receiverSession, true, "receiver on UDP", 15L)
        InteropClient.awaitUsingUdp(senderSession, true, "sender on UDP", 15L)

        val receiverVoice = receiverHandle.voice
        val senderVoice = senderHandle.voice
        // Receiver side keeps the test filter (this test pins the sender
        // gate; receiver filtering is pinned by the sender-filter suite).
        receiverVoice.setFilterForTests(intArrayOf(), intArrayOf(senderId))

        // Production sender path: real PolicyCollector over the live session
        // state into the production setPolicy (serialized on the voice
        // control thread, status refreshed). Continuous mode keeps the VAD
        // out of the picture so the gate alone decides transmission.
        val settingsFlow = MutableStateFlow(
            VoiceSettings(
                inputMode = InputMode.CONTINUOUS,
                vadMax = 1.0f,
                vadMin = 0.7f,
                amplitudeBoost = 1.0f,
                targetBitrate = 40000,
                targetFramesPerPacket = 2,
                halfDuplex = false,
                adaptiveLeveler = false,
            )
        )
        val collectorScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val collector = PolicyCollector(
            PolicyCollector.FlowOfStates { senderSession.state },
            settingsFlow,
            collectorScope,
        )
        val fanout = collectorScope.launch {
            try {
                collector.policy.collect { senderVoice.setPolicy(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        try {
            assertGate(senderVoice, true, "collector opens gate for synced unmuted session")
            // Baseline over UDP through the real collector-fed gate.
            captureSine(senderVoice, 30)
            assertPeak(receiverVoice, true, "open gate transmits over UDP")

            // Mute via session intent: desired mute closes transmitAllowed
            // without waiting for the server echo.
            senderSession.setSelfMuteDeaf(true, false)
            assertGate(senderVoice, false, "session mute closes gate")
            drain(receiverVoice)
            // Skip the first second: mute-edge terminator + PLC tail must
            // drain before silence is pinned.
            skip(receiverVoice, 1)
            assertPeak(receiverVoice, false, "muted gate stays silent on UDP")

            // Recovery through the same intent path.
            senderSession.setSelfMuteDeaf(false, false)
            assertGate(senderVoice, true, "unmute reopens gate")
            captureSine(senderVoice, 30)
            assertPeak(receiverVoice, true, "unmuted gate transmits again")

            // PTT via session intent: switch to PTT (idle = silent).
            settingsFlow.value =
                settingsFlow.value.copy(inputMode = InputMode.PUSH_TO_TALK)
            // The settings hop flows through the same collector + facade.
            assertGatePtt(senderVoice, false, "PTT idle closes transmission")
            drain(receiverVoice)
            skip(receiverVoice, 1)
            assertPeak(receiverVoice, false, "PTT idle stays silent")
            senderSession.setPtt(true)
            assertGatePtt(senderVoice, true, "PTT pressed opens transmission")
            captureSine(senderVoice, 30)
            assertPeak(receiverVoice, true, "PTT pressed transmits")
            senderSession.setPtt(false)
        } finally {
            try {
                fanout.cancel()
            } catch (ignored: Exception) {
            }
            try {
                collectorScope.cancel()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun assertGate(voice: se.lublin.humla.voice.VoiceEngine, open: Boolean, what: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val latest = voice.latestPolicy.value
            if (latest != null && latest.transmitAllowed == open) return
            Thread.sleep(50)
        }
        throw AssertionError("gate did not ${if (open) "open" else "close"}: $what")
    }

    private fun assertGatePtt(voice: se.lublin.humla.voice.VoiceEngine, talking: Boolean, what: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val latest = voice.latestPolicy.value
            if (latest != null && latest.transmitAllowed && latest.pttPressed == talking) return
            Thread.sleep(50)
        }
        throw AssertionError("PTT gate did not settle (talking=$talking): $what")
    }

    private fun captureSine(voice: se.lublin.humla.voice.VoiceEngine, frames: Int) {
        val sine = ShortArray(480)
        for (i in sine.indices) {
            sine[i] = (10000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0)).toInt().toShort()
        }
        for (i in 0 until frames) {
            voice.captureFrame(sine, 0, sine.size)
        }
    }

    private fun drain(voice: se.lublin.humla.voice.VoiceEngine) {
        val pcm = ShortArray(960)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            voice.render(pcm, 0, pcm.size)
            Thread.sleep(20)
        }
    }

    private fun skip(voice: se.lublin.humla.voice.VoiceEngine, seconds: Long) {
        val pcm = ShortArray(960)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            voice.render(pcm, 0, pcm.size)
            Thread.sleep(20)
        }
    }

    private fun assertPeak(voice: se.lublin.humla.voice.VoiceEngine, audible: Boolean, what: String) {
        val pcm = ShortArray(960)
        var peak = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (audible) 15 else 2)
        if (audible) {
            while (peak <= 100 && System.nanoTime() < deadline) {
                val rendered = voice.render(pcm, 0, pcm.size)
                for (s in 0 until rendered) {
                    val v = Math.abs(pcm[s].toInt())
                    if (v > peak) peak = v
                }
                if (rendered == 0) Thread.sleep(20)
            }
            assertTrue("$what, peak=$peak", peak > 100)
        } else {
            var mutePeak = 0
            while (System.nanoTime() < deadline) {
                val rendered = voice.render(pcm, 0, pcm.size)
                for (s in 0 until rendered) {
                    val v = Math.abs(pcm[s].toInt())
                    if (v > mutePeak) mutePeak = v
                }
                if (rendered == 0) Thread.sleep(20)
            }
            assertTrue("$what, peak=$mutePeak", mutePeak <= 100)
        }
    }

    @Throws(Exception::class)
    private fun connectHandle(        username: String,
        password: String?,
        voice: InteropClient.Voice,
    ): HumlaClientHandle {
        val trustStore = trustStoreForTestCert()
        val target = InteropClient.target(
            server!!.host(), server!!.port(), username, password, trustStore,
            voice == InteropClient.Voice.FORCED_TCP,
        )
        val handle = InteropClient.create(noopObserver(), voice)
        open.add(handle)
        handle.client.connect(target)
        return handle
    }

    @Throws(Exception::class)
    private fun trustStoreForTestCert(): String {
        val cf = CertificateFactory.getInstance("X.509")
        val cert: java.security.cert.Certificate
        javaClass.getResourceAsStream("/murmur/test-cert.pem").use { ins ->
            if (ins == null) throw IllegalStateException("test resource missing: /murmur/test-cert.pem")
            cert = cf.generateCertificate(ins)
        }
        val ks = KeyStore.getInstance(InteropClient.TRUST_FORMAT)
        ks.load(null, null)
        ks.setCertificateEntry("127.0.0.1", cert)
        val store = Files.createTempFile("murmur-interop-trust-", ".jks")
        tempStores.add(store)
        val pw = InteropClient.TRUST_PASSWORD.toCharArray()
        try {
            Files.newOutputStream(store).use { out -> ks.store(out, pw) }
        } finally {
            Arrays.fill(pw, '\u0000')
        }
        return store.toString()
    }

    private fun noopObserver(): IHumlaObserver {
        return object : IHumlaObserver {
            override fun onConnected() {}
            override fun onConnecting() {}
            override fun onDisconnected(e: HumlaException?) {}
            override fun onTLSHandshakeFailed(
                chain: Array<java.security.cert.X509Certificate>?,
                failure: se.lublin.humla.net.HandshakeFailure?,
                verifiedHost: String?,
            ) {
            }
            override fun onChannelAdded(channel: se.lublin.humla.model.IChannel?) {}
            override fun onChannelStateUpdated(channel: se.lublin.humla.model.IChannel?) {}
            override fun onChannelRemoved(channel: se.lublin.humla.model.IChannel?) {}
            override fun onChannelPermissionsUpdated(channel: se.lublin.humla.model.IChannel?) {}
            override fun onUserConnected(user: se.lublin.humla.model.IUser?) {}
            override fun onUserStateUpdated(user: se.lublin.humla.model.IUser?) {}
            override fun onUserTalkStateUpdated(user: se.lublin.humla.model.IUser?) {}
            override fun onUserJoinedChannel(
                user: se.lublin.humla.model.IUser?,
                newChannel: se.lublin.humla.model.IChannel?,
                oldChannel: se.lublin.humla.model.IChannel?,
            ) {
            }
            override fun onUserRemoved(user: se.lublin.humla.model.IUser?, reason: String?) {}
            override fun onPermissionDenied(reason: String?) {}
            override fun onMessageLogged(message: se.lublin.humla.model.IMessage?) {}
            override fun onVoiceTargetChanged(mode: VoiceTargetMode?) {}
            override fun onBluetoothScoChanged(active: Boolean) {}
            override fun onLogInfo(message: String?) {}
            override fun onLogWarning(message: String?) {}
            override fun onLogError(message: String?) {}
        }
    }
}
