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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.route.AppliedRoute
import se.lublin.humla.route.RouteState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.PrefSnapshot

/** PTT beeps on the stream today's code picks, reloaded on route changes. */
class PttSoundsFeature(
    private val clientState: StateFlow<ClientState>,
    private val talkActivity: StateFlow<TalkActivity>,
    private val routeState: StateFlow<RouteState>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
) {
    private var bank: SoundBank? = null
    private var activated = false
    private var connected = false
    private var enabled = false
    private var pushToTalk = false
    private var lastTalking = false

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            clientState.collect { state ->
                connected = state is ClientState.Connected
                if (state !is ClientState.Idle && !activated) {
                    activated = true
                    reload()
                }
                if (state is ClientState.Idle) lastTalking = false
            }
        }
        child.launch {
            settings.collect {
                enabled = it.pttSound
                pushToTalk = it.isPushToTalk()
            }
        }
        child.launch {
            routeState.collect { route ->
                when (route) {
                    is RouteState.Ready, is RouteState.Fallback, is RouteState.Idle -> {
                        if (activated) reload()
                    }
                    is RouteState.Settling -> Unit
                }
            }
        }
        child.launch {
            talkActivity.map { it.selfTalking }.distinctUntilChanged().collect { talking ->
                if (connected && pushToTalk && enabled && talking != lastTalking) {
                    bank?.play(talking)
                }
                lastTalking = talking
            }
        }
    }

    private fun reload() {
        val media = when (val route = routeState.value) {
            is RouteState.Ready -> route.route == AppliedRoute.MEDIA
            is RouteState.Fallback -> route.route == AppliedRoute.MEDIA
            else -> false
        }
        bank?.release()
        bank = platform.newSoundBank(voiceRoute = !media)
    }
}
