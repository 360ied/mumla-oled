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

package se.lublin.mumla.feature

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.humla.client.ClientError
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord

/** Chat notifications, clearing, the master switch and backpressure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageNotificationsFeatureTest {

    private class Fixture(postSink: (suspend (se.lublin.humla.model.IMessage) -> Unit)? = null) {
        val platform = FakeFeaturePlatform()
        val events = MutableSharedFlow<SessionEventRecord>(extraBufferCapacity = 256)
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val settings = MutableStateFlow(testSnapshot(chatNotify = true))
        val feature = MessageNotificationsFeature(events, state, settings, platform, postSink)
    }

    private fun textRecord() = SessionEventRecord(
        SessionEvent.TextMessageReceived(
            2, "Alice", emptyList(), emptyList(), emptyList(), "hello", 0L,
        ),
        goldenState(),
    )

    @Test fun postsTextMessagesAndClearsOnConnectionEnd() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.events.emit(textRecord())
        kotlinx.coroutines.yield()
        assertEquals(1, f.platform.messages.shown.size)
        f.state.value = ClientState.Idle
        assertEquals(1, f.platform.messages.dismissed)
        f.feature.clear()
        assertEquals(2, f.platform.messages.dismissed)
    }

    @Test fun masterSwitchSuppressesPosts() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.settings.value = testSnapshot(chatNotify = false)
        f.events.emit(textRecord())
        kotlinx.coroutines.yield()
        assertEquals(0, f.platform.messages.shown.size)
    }

    @Test fun reconnectingClearsTheNotification() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        val before = f.platform.messages.dismissed
        f.state.value = ClientState.Reconnecting(
            emptyConnectTarget(), 1, 0L,
            ClientError.Connection("gone", null), null,
        )
        assertEquals(before + 1, f.platform.messages.dismissed)
    }

    @Test fun slowNotificationManagerNeverStallsTheEmitter() = runFeature { scope ->
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(postSink = { gate.await() })
        f.feature.start(scope)
        kotlinx.coroutines.runBlocking {
            withTimeout(2_000) { repeat(300) { f.events.emit(textRecord()) } }
        }
        gate.complete(Unit)
    }
}
