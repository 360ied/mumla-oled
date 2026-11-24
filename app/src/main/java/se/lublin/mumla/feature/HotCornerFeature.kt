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

import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.TalkKeyEvent

/** The PTT hot corner: gestures go into the talkKeys hand-off flow. */
class HotCornerFeature(
    private val clientState: StateFlow<ClientState>,
    private val talkActivity: StateFlow<TalkActivity>,
    private val configuration: StateFlow<Configuration>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
    private val emitTalkKey: (TalkKeyEvent) -> Unit,
) {
    private var view: HotCornerView? = null
    private var gravity = 0

    private val listener = object : HotCornerListener {
        override fun onDown() = emitTalkKey(TalkKeyEvent.Down)
        override fun onUp() = emitTalkKey(TalkKeyEvent.Up)
        override fun onCancel() = emitTalkKey(TalkKeyEvent.Cancel)
    }

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            combine(clientState, settings, talkActivity) { state, snap, activity ->
                Triple(state, snap, activity.selfTalking)
            }.collect { (state, snap, talking) ->
                val shouldShow = state is ClientState.Connected &&
                    snap.isHotCornerEnabled() && snap.isPushToTalk()
                val nextGravity = snap.hotCornerGravity()
                if (shouldShow) {
                    val current = view ?: platform.newHotCorner(nextGravity, listener).also {
                        view = it
                        gravity = nextGravity
                    }
                    if (gravity != nextGravity) {
                        gravity = nextGravity
                        current.setGravity(nextGravity)
                    }
                    if (!current.isShown()) {
                        current.setShown(true)
                        // Seed from the current level so a corner created
                        // mid-talk highlights without waiting for a hint.
                        current.updateTalkState(talking)
                    } else {
                        current.updateTalkState(talking)
                    }
                } else {
                    view?.setShown(false)
                }
            }
        }
        child.launch {
            configuration.collect { view?.refreshGestureExclusionDeferred() }
        }
        // Gravity is already updated in the combined collector above; a
        // second collector here only duplicated the same setGravity call.
    }
}
