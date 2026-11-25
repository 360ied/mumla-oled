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

import android.app.Notification
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.lublin.humla.Constants
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ReconnectPolicy
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.SessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.service.NotificationActionReceiver
import se.lublin.mumla.R
import se.lublin.mumla.service.MumlaService

/** Builds the foreground notification and owns the media session's actions. */
class ConnectionNotificationFeature(
    private val context: Context,
    private val clientState: StateFlow<ClientState>,
    private val sessionState: StateFlow<SessionState>,
    private val events: SharedFlow<SessionEventRecord>,
    private val talkActivity: StateFlow<TalkActivity>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
    private val actions: NotificationActions,
    private val overlayShown: () -> Boolean,
    /** Test seam for a slow notification manager. */
    private val refreshSink: (suspend () -> Unit)? = null,
) {
    private enum class Refresh { REBUILD, RESHOW }

    private val view: ConnectionNotificationView by lazy {
        platform.newConnectionNotification(actions)
    }

    private val _notification = MutableStateFlow(initialNotification())
    val notification: StateFlow<Notification> = _notification.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var suppress = false

    fun setSuppressNotifications(value: Boolean) {
        suppress = value
    }

    /** Explicit PendingIntents from the notification reach the container here. */
    fun handleAction(action: String?) {
        when (action) {
            NotificationActionReceiver.ACTION_MUTE -> actions.onMuteToggled()
            NotificationActionReceiver.ACTION_DEAFEN -> actions.onDeafenToggled()
            NotificationActionReceiver.ACTION_TOGGLE_OVERLAY -> actions.onOverlayToggled()
            NotificationActionReceiver.ACTION_DISCONNECT -> actions.onDisconnect()
            NotificationActionReceiver.ACTION_CANCEL_RECONNECT -> actions.onCancelReconnect()
        }
    }

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        val refresh = refreshSink ?: { rebuild() }
        val queue = FeatureEventQueue<Refresh>(child) { kind ->
            refresh()
            if (kind == Refresh.RESHOW) reshow()
        }
        child.launch {
            combine(clientState, sessionState, settings) { state, _, _ -> state }
                .collect { queue.offer(Refresh.REBUILD) }
        }
        child.launch {
            // Media-session metadata follows talk activity from step 7; the
            // notification itself is state-only, so no slow work here.
            talkActivity.collect { }
        }
        child.launch {
            events.collect { record ->
                if (record.event is SessionEvent.PermissionDenied && !suppress) {
                    queue.offer(Refresh.RESHOW)
                }
            }
        }
    }

    private fun initialNotification(): Notification =
        view.connecting(context.getString(R.string.app_name), "", 0)

    private fun reshow() {
        _notification.value = view.show()
        _visible.value = true
    }

    private fun rebuild() {
        when (val state = clientState.value) {
            is ClientState.Idle, is ClientState.Failed -> {
                view.hide()
                _visible.value = false
            }
            is ClientState.Connecting -> {
                val endpoint = state.target.server
                _notification.value = view.connecting(
                    serverName(endpoint.name, endpoint.host), endpoint.host, port(endpoint.port),
                )
                _visible.value = true
            }
            is ClientState.Connected -> {
                val endpoint = state.target.server
                val snapshot = sessionState.value
                val self = snapshot.server.self
                val channelName = self?.channelId?.let { snapshot.server.channels[it]?.name }
                _notification.value = view.connected(
                    serverName(endpoint.name, endpoint.host),
                    channelName,
                    self?.selfMuted ?: false,
                    self?.selfDeafened ?: false,
                    overlayShown(),
                    hostInfo(endpoint.host, endpoint.port),
                )
                _visible.value = true
            }
            is ClientState.Reconnecting -> {
                val endpoint = state.target.server
                _notification.value = view.reconnecting(
                    serverName(endpoint.name, endpoint.host),
                    state.lastError.displayMessage(),
                    state.attempt,
                    ReconnectPolicy.delayForAttempts(state.attempt) / 1000,
                    hostInfo(endpoint.host, endpoint.port),
                )
                _visible.value = true
            }
        }
    }

    private fun serverName(name: String?, host: String): String =
        if (!name.isNullOrEmpty()) name else host

    private fun port(value: Int): Int = if (value > 0) value else Constants.DEFAULT_PORT

    private fun hostInfo(host: String, value: Int): String = "$host:${port(value)}"
}
