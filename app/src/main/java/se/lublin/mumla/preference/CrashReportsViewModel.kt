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

package se.lublin.mumla.preference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.lublin.humla.client.ClientState

/**
 * Crash-test availability from the client state. The buttons are enabled
 * while nothing is live; the old "bound and not connected" rule was a
 * fail-closed stand-in for a state that could not answer.
 */
class CrashReportsViewModel(clientState: StateFlow<ClientState>) : ViewModel() {

    /** True while the state is Idle or Failed, i.e. no live connection. */
    val crashTestsEnabled: StateFlow<Boolean> = clientState
        .map { it is ClientState.Idle || it is ClientState.Failed }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            clientState.value is ClientState.Idle || clientState.value is ClientState.Failed,
        )
}
