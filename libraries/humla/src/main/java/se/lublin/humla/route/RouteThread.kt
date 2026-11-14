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

package se.lublin.humla.route

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.Executor

/**
 * The route thread, in the three shapes its users need: a [Handler] for
 * platform registrations that take one, an [Executor] for those that take
 * an executor, and a [CoroutineDispatcher] for coroutine scopes. All three
 * run on the same thread, so everything handed one of them is confined to
 * it (see docs/target-architecture/route.md, Thread).
 *
 * Started by [RouteControl.startThread] (the controller is the boundary
 * file that owns the thread) before the controller and the SCO link are
 * built, so both can share it. [RouteControl] owns its lifetime: its
 * `close()` quits the thread after the final teardown has run on it.
 */
internal class RouteThread(
    val handler: Handler,
    val executor: Executor,
    val dispatcher: CoroutineDispatcher,
    private val isCurrentCheck: () -> Boolean,
    private val ownedThread: HandlerThread? = null,
) {
    /** True when the caller is running on this thread. */
    fun isCurrent(): Boolean = isCurrentCheck()

    /** Quits the owned looper once queued work has drained; no-op for test threads. */
    fun quitSafely() {
        ownedThread?.quitSafely()
    }
}
