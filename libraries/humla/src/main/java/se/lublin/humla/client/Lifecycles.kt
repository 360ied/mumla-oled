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

import kotlinx.coroutines.flow.Flow

/**
 * Platform lifecycle boundaries the client sequences but never implements.
 * See docs/tech-debt-whales/target-architecture/client.md#humlaclient.
 *
 * Android-free: small interfaces so the client runs in plain JVM tests
 * with fakes. The app wires the real VoiceEngine/RouteControl in step 4+.
 */
interface VoiceLifecycle {
    /** Start network voice with the session's UDP address and crypt keys. */
    fun startVoice(host: String, port: Int)
    fun stopVoice()
}

interface RouteLifecycle {
    fun sessionStarted()
    fun sessionEnded()
}

/** No-op lifecycles for tests and for the interim HumlaService bridge. */
class NoopVoiceLifecycle : VoiceLifecycle {
    var starts: Int = 0
        private set
    var stops: Int = 0
        private set

    override fun startVoice(host: String, port: Int) {
        starts += 1
    }

    override fun stopVoice() {
        stops += 1
    }
}

class NoopRouteLifecycle : RouteLifecycle {
    var starts: Int = 0
        private set
    var ends: Int = 0
        private set

    override fun sessionStarted() {
        starts += 1
    }

    override fun sessionEnded() {
        ends += 1
    }
}

/**
 * Main-thread guard. From step 3 on, every method that may block starts
 * with this (see migration.md ground rules): throws in debug builds, logs
 * in release builds.
 *
 * Android-free implementation: the JVM/Android main thread is named
 * "main". This deliberately avoids android.os.Looper so the client
 * package stays import-clean for plain JVM tests; the app-layer
 * route/voice threads (step 4) assert off-main the same way.
 */
fun checkNotMainThread() {
    val name = Thread.currentThread().name
    if (name == "main") {
        throw IllegalStateException("must not block the main thread (called on $name)")
    }
}
