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

package se.lublin.mumla.servers

import android.content.Context
import android.database.DataSetObserver
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.model.Server
import se.lublin.humla.net.SrvResolver
import se.lublin.mumla.R

/**
 * Pins the coroutine port of the server-list pings
 * (docs/target-architecture/steps/8-clean-up.md, commit 3): the progress row
 * before the result, the version/users/latency row after it, the offline row
 * for a failed ping, and nothing after `shutdown()`. One
 * [StandardTestDispatcher] drives both the adapter's main dispatcher and its
 * ping dispatcher, so the ordering is the test's to advance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAdapterTest {

    private val dispatcher = StandardTestDispatcher()
    private val changes = AtomicInteger(0)
    private lateinit var context: Context

    @Before
    fun setUp() {
        // The row uses theme attributes (?selectableItemBackground, ?cardStyle);
        // inflate under the app theme, as the real grid does.
        context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_Mumla_Oled)
    }

    private val menuStub = object : FavouriteServerAdapter.FavouriteServerAdapterMenuListener {
        override fun editServer(server: Server) = Unit
        override fun shareServer(server: Server) = Unit
        override fun deleteServer(server: Server) = Unit
    }

    private fun server() = Server(-1, "Test", "chat.example.org", 0, "alice", null)

    private fun adapter(
        server: Server,
        ping: suspend (Server, SrvResolver) -> ServerInfoResponse,
    ): FavouriteServerAdapter = FavouriteServerAdapter(
        context,
        listOf(server),
        menuStub,
        ping,
        dispatcher,
        dispatcher,
    ).also {
        it.registerDataSetObserver(object : DataSetObserver() {
            override fun onChanged() {
                changes.incrementAndGet()
            }
        })
    }

    private fun bindRow(adapter: ServerAdapter<Server>): View {
        val parent = FrameLayout(context)
        return adapter.getView(0, null, parent as ViewGroup)
    }

    private fun text(row: View, id: Int): TextView = row.findViewById<TextView>(id)

    private fun progress(row: View): ProgressBar = row.findViewById(R.id.server_row_ping_progress)

    /** A wire-shaped reply: version 1.5.0, 7 of 25 users, one megabit. */
    private val reply: ByteArray = ByteBuffer.allocate(24)
        .putInt((1 shl 16) or (5 shl 8))
        .putLong(7L)
        .putInt(7)
        .putInt(25)
        .putInt(1_000_000)
        .array()

    @Test
    fun progressRowShowsBeforeTheResult() {
        val server = server()
        val gate = CompletableDeferred<ServerInfoResponse>()
        val adapter = adapter(server) { _, _ -> gate.await() }
        val row = bindRow(adapter)
        // Progress and blank status while the ping is in flight.
        assertEquals(View.VISIBLE, progress(row).visibility)
        assertEquals(View.INVISIBLE, text(row, R.id.server_row_version_status).visibility)
        assertEquals(0, changes.get())

        gate.complete(ServerInfoResponse(server, reply, 42))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, changes.get())

        // The repaint after the result carries the ping's facts.
        val bound = bindRow(adapter)
        assertEquals(View.INVISIBLE, progress(bound).visibility)
        assertEquals(View.VISIBLE, text(bound, R.id.server_row_version_status).visibility)
        assertEquals(
            context.getString(R.string.online) + " (1.5.0)",
            text(bound, R.id.server_row_version_status).text.toString(),
        )
        assertEquals("7/25", text(bound, R.id.server_row_usercount).text.toString())
        assertEquals("42ms", text(bound, R.id.server_row_latency).text.toString())
        // The stored response is the one the fake returned, not a re-ping.
        assertEquals(1, changes.get())
    }

    @Test
    fun failedPingShowsTheOfflineRow() {
        val server = server()
        val adapter = adapter(server) { _, _ -> ServerInfoResponse() }
        val row = bindRow(adapter)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, changes.get())

        val bound = bindRow(adapter)
        assertEquals(
            context.getString(R.string.offline),
            text(bound, R.id.server_row_version_status).text.toString(),
        )
        assertEquals("", text(bound, R.id.server_row_usercount).text.toString())
        assertEquals("", text(bound, R.id.server_row_latency).text.toString())
        assertSame(View.VISIBLE, text(bound, R.id.server_row_version_status).visibility)
    }

    @Test
    fun shutdownDropsAnInFlightPing() {
        val server = server()
        val gate = CompletableDeferred<ServerInfoResponse>()
        val adapter = adapter(server) { _, _ -> gate.await() }
        bindRow(adapter)
        adapter.shutdown()
        gate.complete(ServerInfoResponse(server, reply, 5))
        dispatcher.scheduler.advanceUntilIdle()

        // No store, no notify: the cancelled ping never reached the main path.
        assertEquals(0, changes.get())
        assertTrue(adapter.count == 1)
    }
}
