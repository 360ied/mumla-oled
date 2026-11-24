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
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.TalkKeyEvent

/**
 * Talks keys from the screens, the on-screen PTT button, and the hot corner.
 * The media session is not a source: it swallows a media key bound as the
 * PTT key so the activity's key handling gets it.
 */
class TalkKeysFeature(
    private val talkKeys: SharedFlow<TalkKeyEvent>,
    private val clientState: StateFlow<ClientState>,
    private val settings: StateFlow<PrefSnapshot>,
    private val setPtt: (Boolean) -> Unit,
    private val togglePtt: () -> Unit,
    private val isTalking: () -> Boolean,
) {
    private var connected = false
    private var pushToTalk = false
    private var toggleMode = false

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            clientState.collect { connected = it is ClientState.Connected }
        }
        child.launch {
            settings.collect {
                pushToTalk = it.isPushToTalk()
                toggleMode = it.togglePtt
            }
        }
        child.launch {
            talkKeys.collect { onTalkKey(it) }
        }
    }

    private fun onTalkKey(event: TalkKeyEvent) {
        if (!connected || !pushToTalk) return
        when (event) {
            TalkKeyEvent.Down -> if (!toggleMode && !isTalking()) setPtt(true)
            TalkKeyEvent.Up -> if (toggleMode) togglePtt() else if (isTalking()) setPtt(false)
            // A cancel always ends a held press (leaving or rotating the
            // screen mid-press), but changes nothing in toggle mode.
            TalkKeyEvent.Cancel -> if (!toggleMode && isTalking()) setPtt(false)
        }
    }
}
