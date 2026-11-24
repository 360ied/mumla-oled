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

package se.lublin.mumla.channel.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.lublin.humla.state.SessionState

/** One render of a comment dialog. */
data class CommentUiState(
    /**
     * The comment's text; empty when the target has none, null while the
     * target is unknown or a requested blob is still on its way.
     */
    val comment: String? = null,
)

/**
 * A user's comment or a channel's description, read from the session-state
 * mirror. When state carries a hash but no text the blob is requested once;
 * the text then arrives through state, replacing the fragments' one-shot
 * observers.
 */
class CommentViewModel(
    private val target: Target,
    sessionState: StateFlow<SessionState>,
    private val requestBlob: () -> Unit,
    private val setComment: (String) -> Unit,
) : ViewModel() {

    sealed interface Target {
        data class User(val session: Int) : Target
        data class Channel(val channelId: Int) : Target
    }

    private data class Lookup(val text: String?, val hasHash: Boolean)

    private var requested = false

    val state: StateFlow<CommentUiState> = sessionState
        .map(::lookup)
        .distinctUntilChanged()
        .map { found ->
            when {
                found == null -> CommentUiState()
                found.text != null -> CommentUiState(found.text)
                found.hasHash -> CommentUiState()
                else -> CommentUiState("")
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CommentUiState())

    init {
        // One request per dialog: the server answers over TCP and the text
        // then arrives through state. Runs on the main thread like every
        // other ViewModel action.
        viewModelScope.launch {
            sessionState.collect { current ->
                if (requested) {
                    return@collect
                }
                val found = lookup(current) ?: return@collect
                if (found.text == null && found.hasHash) {
                    requested = true
                    requestBlob()
                }
            }
        }
    }

    /** Persists the edited comment (user comments only in practice). */
    fun save(comment: String) = setComment(comment)

    private fun lookup(state: SessionState): Lookup? = when (target) {
        is Target.User -> state.server.users[target.session]?.let {
            Lookup(it.comment, it.commentHash != null)
        }
        is Target.Channel -> state.server.channels[target.channelId]?.let {
            Lookup(it.description, it.descriptionHash != null)
        }
    }
}
