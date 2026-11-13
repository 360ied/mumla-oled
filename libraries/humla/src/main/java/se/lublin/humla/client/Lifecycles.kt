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

package se.lublin.humla.client

import java.util.concurrent.atomic.AtomicInteger

/**
 * Platform lifecycle boundaries the client sequences but never implements.
 * See docs/target-architecture/client.md#humlaclient.
 *
 * Android-free: small interfaces so the client runs in plain JVM tests
 * with fakes. The app wires the real VoiceEngine/RouteControl in step 4+.
 */
interface VoiceLifecycle {
    /** Start network voice with the session's UDP address and crypt keys. */
    fun startVoice(host: String, port: Int)
    fun stopVoice()
    /** Forced-TCP targets never open a UDP socket. Defaults to tunnel-agnostic. */
    fun setForceTcp(force: Boolean) {}
}

interface RouteLifecycle {
    fun sessionStarted()
    fun sessionEnded()
}

/** No-op lifecycles for tests and for the interim HumlaService bridge. */
class NoopVoiceLifecycle : VoiceLifecycle {
    private val startsCount = AtomicInteger(0)
    private val stopsCount = AtomicInteger(0)
    val starts: Int get() = startsCount.get()
    val stops: Int get() = stopsCount.get()

    override fun startVoice(host: String, port: Int) {
        startsCount.incrementAndGet()
    }

    override fun stopVoice() {
        stopsCount.incrementAndGet()
    }
}

class NoopRouteLifecycle : RouteLifecycle {
    private val startsCount = AtomicInteger(0)
    private val endsCount = AtomicInteger(0)
    val starts: Int get() = startsCount.get()
    val ends: Int get() = endsCount.get()

    override fun sessionStarted() {
        startsCount.incrementAndGet()
    }

    override fun sessionEnded() {
        endsCount.incrementAndGet()
    }
}

/**
 * Main-thread guard. From step 3 on, every method that may block starts
 * with this (see migration.md ground rules).
 *
 * Android-free implementation: the JVM/Android main thread is named
 * "main". This deliberately avoids android.os.Looper so the client
 * package stays import-clean for plain JVM tests; the app-layer
 * route/voice threads (step 4) assert off-main the same way.
 */
fun checkNotMainThread() {
    if (Thread.currentThread().name != "main") return
    if (se.lublin.humla.BuildConfig.DEBUG) {
        throw IllegalStateException("must not block the main thread")
    }
    java.util.logging.Logger.getLogger("HumlaClient")
        .warning("blocking call on the main thread")
}
