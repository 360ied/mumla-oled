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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.interop

import java.time.Clock
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ConnectTarget
import se.lublin.humla.client.HumlaClient
import se.lublin.humla.client.InMemoryLocalFactsStore
import se.lublin.humla.client.NetworkMonitor
import se.lublin.humla.client.NoopRouteLifecycle
import se.lublin.humla.client.ServerEndpoint
import se.lublin.humla.client.Session
import se.lublin.humla.client.TrustStoreRef
import se.lublin.humla.client.VoiceLifecycle
import se.lublin.humla.net.DefaultTransportFactory
import se.lublin.humla.net.LegacyVoiceLink
import se.lublin.humla.net.VoiceLink
import se.lublin.humla.util.IHumlaObserver

private const val AWAIT_TIMEOUT_NANOS = 30_000_000_000L
private const val POLL_MILLIS = 100L

/**
 * Java-friendly factory for murmur interop clients (step 3 drives HumlaClient).
 * Hides PersistentList, Function1, CoroutineScope, and Flow construction from Java.
 */
object InteropClient {
    const val TRUST_PASSWORD = "interop"
    const val TRUST_FORMAT = "JKS"
    const val CLIENT_NAME = "MumlaOLED-interop"
    @JvmStatic
    fun target(
        host: String,
        port: Int,
        username: String?,
        password: String?,
        trustStorePath: String?,
    ): ConnectTarget = target(host, port, username, password, trustStorePath, forceTcp = true)

    @JvmStatic
    fun target(
        host: String,
        port: Int,
        username: String?,
        password: String?,
        trustStorePath: String?,
        forceTcp: Boolean,
    ): ConnectTarget = ConnectTarget(
        server = ServerEndpoint(
            serverId = -1L,
            name = null,
            host = host,
            port = port,
            username = username,
            password = password,
        ),
        certificate = null,
        trustStore = if (trustStorePath != null) {
            TrustStoreRef(path = trustStorePath, password = TRUST_PASSWORD, format = TRUST_FORMAT)
        } else {
            null
        },
        forceTcp = forceTcp,
        clientName = CLIENT_NAME,
        accessTokens = persistentListOf(),
    )

    @JvmStatic
    fun create(observer: IHumlaObserver): HumlaClientHandle = create(observer, forceTcp = true)

    @JvmStatic
    fun create(observer: IHumlaObserver, forceTcp: Boolean): HumlaClientHandle =
        create(observer, forceTcp, udpEnabled = true)

    @JvmStatic
    fun create(observer: IHumlaObserver, forceTcp: Boolean, udpEnabled: Boolean): HumlaClientHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = LegacyVoiceLink(tunnelSink = {})
        link.setForceTcp(forceTcp)
        // udpEnabled=false emulates fully-blocked UDP with working TCP: the
        // voice layer never opens its socket, so both cumulative good
        // counters stay zero and the transport must give up UDP for the
        // tunnel ~20 s after creation. Never-working UDP is the only case
        // the giveup covers, matching upstream parity.
        val noUdp: NoUdpVoice? = if (udpEnabled) null else NoUdpVoice(link)
        val voiceLink: VoiceLink = noUdp ?: link
        val voiceLifecycle: VoiceLifecycle = noUdp ?: link
        val route = NoopRouteLifecycle()
        val network = object : NetworkMonitor {
            // The network-available path is intentionally unexercised here;
            // virtual-time client tests pin it instead.
            override val available = emptyFlow<Unit>()
        }
        val client = HumlaClient(
            parentScope = scope,
            transports = DefaultTransportFactory(),
            voiceLifecycle = voiceLifecycle,
            routeLifecycle = route,
            voiceLink = voiceLink,
            network = network,
            localFactsStore = InMemoryLocalFactsStore(),
            clock = Clock.systemUTC(),
            observer = observer,
            mainPoster = { r -> r.run() },
            denyFormatter = { "denied" },
        )
        return HumlaClientHandle(client, link, scope)
    }

    private fun remainingNanos(startNanos: Long): Long =
        (AWAIT_TIMEOUT_NANOS - (System.nanoTime() - startNanos)).coerceAtLeast(0L)

    /** Awaits a Connected session, failing with detail on Failed/timeout. */
    @JvmStatic
    fun awaitConnected(client: HumlaClient, what: String): Session {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            when (val s = client.state.value) {
                is ClientState.Connected -> return s.session
                is ClientState.Failed -> throw AssertionError(
                    "Failed while waiting for $what: ${s.error}",
                )
                else -> Thread.sleep(POLL_MILLIS)
            }
        }
        throw AssertionError("Timed out waiting for $what; state=${client.state.value}")
    }

    @JvmStatic
    fun awaitIdle(client: HumlaClient, what: String) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            if (client.state.value is ClientState.Idle) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what; state=${client.state.value}")
    }

    @JvmStatic
    fun awaitFailed(client: HumlaClient, what: String): ClientState.Failed {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            when (val s = client.state.value) {
                is ClientState.Failed -> return s
                else -> Thread.sleep(POLL_MILLIS)
            }
        }
        throw AssertionError("Timed out waiting for $what; state=${client.state.value}")
    }

    @JvmStatic
    fun awaitUser(
        session: Session,
        name: String,
        what: String,
    ) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            val users = session.state.value.server.users.values
            for (u in users) {
                if (u.name == name) return
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @JvmStatic
    fun awaitReconnecting(client: HumlaClient, what: String) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            if (client.state.value is ClientState.Reconnecting) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what; state=${client.state.value}")
    }

    @JvmStatic
    fun awaitChannel(
        session: Session,
        channelId: Int,
        what: String,
    ) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            if (session.state.value.server.channels.containsKey(channelId)) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @JvmStatic
    fun awaitUserInChannel(
        session: Session,
        name: String,
        channelId: Int,
        what: String,
    ) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            val users = session.state.value.server.users.values
            for (u in users) {
                if (u.name == name && u.channelId == channelId) return
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @JvmStatic
    fun awaitSelfMute(
        session: Session,
        name: String,
        mute: Boolean,
        deaf: Boolean,
        what: String,
    ) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            val users = session.state.value.server.users.values
            for (u in users) {
                if (u.name == name && u.selfMuted == mute && u.selfDeafened == deaf) return
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @JvmStatic
    fun awaitUsingUdp(
        session: Session,
        expected: Boolean,
        what: String,
    ) = awaitUsingUdp(session, expected, what, 30L)

    @JvmStatic
    fun awaitUsingUdp(
        session: Session,
        expected: Boolean,
        what: String,
        timeoutSeconds: Long,
    ) {
        val start = System.nanoTime()
        val budgetNanos = timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() - start < budgetNanos) {
            if (session.stats.value.usingUdp == expected) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for $what; usingUdp=${session.stats.value.usingUdp}",
        )
    }

    @JvmStatic
    fun awaitUdpLatency(
        session: Session,
        what: String,
    ) {
        val start = System.nanoTime()
        while (remainingNanos(start) > 0) {
            if (session.stats.value.udpLatencyMicros != null) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what")
    }
}

/**
 * Test-only voice that never opens UDP, emulating a network where UDP is
 * entirely blocked while TCP works. Keys, the ping sink, and teardown
 * delegate to the wrapped link; only socket creation is suppressed. The
 * transport observes identical conditions to wire-level blocking (both
 * cumulative good counters stay zero) and must fall back to the tunnel.
 */
private class NoUdpVoice(private val delegate: LegacyVoiceLink) :
    VoiceLink by delegate,
    VoiceLifecycle by delegate {
    override fun startVoice(host: String, port: Int) {
        // UDP stays closed for the session.
    }
}

class HumlaClientHandle(
    val client: HumlaClient,
    val voice: LegacyVoiceLink,
    private val scope: CoroutineScope,
) {
    fun disconnectAndClose() {
        try {
            client.disconnect()
        } catch (e: Exception) {
            java.util.logging.Logger.getLogger("InteropClient")
                .fine("Teardown disconnect dropped: $e")
        }
        // Async disconnect runs on the client dispatcher; poll for Idle
        // instead of a fixed sleep so teardown cannot race server stop.
        try {
            InteropClient.awaitIdle(client, "teardown disconnect")
        } catch (e: AssertionError) {
            // Best effort in teardown; the scope cancel below still reclaims.
            java.util.logging.Logger.getLogger("InteropClient")
                .fine("Teardown idle wait dropped: $e")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            voice.close()
        } catch (e: Exception) {
            java.util.logging.Logger.getLogger("InteropClient")
                .fine("Teardown voice close dropped: $e")
        }
        scope.cancel()
    }
}
