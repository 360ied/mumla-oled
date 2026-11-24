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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.state.SessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.PrefSnapshot

/** Shows who is talking in a system overlay, over the application context. */
class OverlayFeature(
    private val clientState: StateFlow<ClientState>,
    private val sessionState: StateFlow<SessionState>,
    private val talkActivity: StateFlow<TalkActivity>,
    private val configuration: StateFlow<Configuration>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
    private val overlayHost: OverlayHost,
    private val setOverlaySetting: (Boolean) -> Unit,
) {
    private var view: OverlayView? = null
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun isShown(): Boolean = _shown.value

    /** Binder delegation: ask for the permission before turning the setting on. */
    fun setShown(show: Boolean) {
        if (show && !platform.canDrawOverlays()) {
            platform.requestOverlayPermission()
            setOverlaySetting(false)
            return
        }
        if (settings.value.overlayShown != show) {
            setOverlaySetting(show)
        } else {
            refresh(clientState.value, settings.value)
        }
    }

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        child.launch {
            combine(clientState, settings, sessionState, talkActivity) { state, snap, _, _ ->
                state to snap
            }.collect { (state, snap) -> refresh(state, snap) }
        }
        child.launch {
            configuration.collect { view?.updatePosition() }
        }
        child.launch {
            settings.map { it.overlayPlacement }.distinctUntilChanged().collect {
                view?.updatePosition()
            }
        }
    }

    private fun refresh(state: ClientState, snap: PrefSnapshot) {
        val wanted = state is ClientState.Connected && snap.overlayShown && platform.canDrawOverlays()
        if (wanted) {
            val current = view ?: platform.newOverlay(overlayHost).also { view = it }
            if (!current.isShown()) current.show()
        } else {
            view?.hide()
        }
        _shown.value = view?.isShown() == true
    }
}
