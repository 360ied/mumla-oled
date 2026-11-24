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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.Message
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.mumla.PrefSnapshot

/** Message heads-up notifications; never suppressed by chat visibility. */
class MessageNotificationsFeature(
    private val events: SharedFlow<SessionEventRecord>,
    private val clientState: StateFlow<ClientState>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
    /** Test seam for a slow notification manager. */
    private val postSink: (suspend (IMessage) -> Unit)? = null,
) {
    private var view: MessageNotificationsView? = null

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        val sink = postSink ?: { message: IMessage ->
            if (settings.value.chatNotify) ensureView().show(message)
        }
        val queue = FeatureEventQueue<IMessage>(child) { sink(it) }
        child.launch {
            events.collect { record ->
                val event = record.event
                if (event is SessionEvent.TextMessageReceived) {
                    queue.offer(
                        Message(
                            event.actor ?: -1,
                            event.actorName,
                            emptyList(),
                            emptyList(),
                            emptyList(),
                            event.message,
                        ),
                    )
                }
            }
        }
        child.launch {
            clientState.collect { state ->
                when (state) {
                    is ClientState.Connecting, is ClientState.Connected -> ensureView()
                    is ClientState.Idle, is ClientState.Reconnecting, is ClientState.Failed ->
                        ensureView().dismiss()
                }
            }
        }
    }

    fun clear() {
        ensureView().dismiss()
    }

    private fun ensureView(): MessageNotificationsView =
        view ?: platform.newMessageNotifications().also { view = it }
}
