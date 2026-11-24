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

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver

/** Registers the TALK receiver while connected in push-to-talk mode. */
class TalkBroadcastFeature(
    private val clientState: StateFlow<ClientState>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
    private val setPtt: (Boolean) -> Unit,
    private val togglePtt: () -> Unit,
) {
    private var receiver: BroadcastReceiver? = null
    private var connected = false
    private var allowed = false
    private var pushToTalk = false

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            clientState.collect {
                connected = it is ClientState.Connected
                updateReceiver()
            }
        }
        child.launch {
            settings.collect {
                allowed = it.allowTalkBroadcast
                pushToTalk = it.isPushToTalk()
                updateReceiver()
            }
        }
    }

    private fun updateReceiver() {
        val wanted = connected && allowed && pushToTalk
        if (wanted && receiver == null) {
            val next = platform.newTalkReceiver { status -> onStatus(status) } ?: return
            receiver = next
            platform.registerTalkReceiver(next)
        } else if (!wanted) {
            val current = receiver ?: return
            receiver = null
            platform.unregisterTalkReceiver(current)
        }
    }

    private fun onStatus(status: String?) {
        if (!connected || !pushToTalk) return
        when (status) {
            TalkBroadcastReceiver.TALK_STATUS_ON -> setPtt(true)
            TalkBroadcastReceiver.TALK_STATUS_OFF -> setPtt(false)
            TalkBroadcastReceiver.TALK_STATUS_TOGGLE -> togglePtt()
        }
    }
}
