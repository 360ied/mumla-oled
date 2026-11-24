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

package se.lublin.mumla.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The access-token list of one saved server: loaded from the database,
 * persisted on every edit, and pushed to the live session after each change.
 * All I/O is suspend; the ViewModel never blocks the main thread.
 */
class AccessTokenViewModel(
    private val serverId: Long,
    private val loadTokens: suspend (Long) -> List<String>,
    private val addToken: suspend (Long, String) -> Unit,
    private val removeToken: suspend (Long, String) -> Unit,
    private val sendTokens: (List<String>) -> Unit,
) : ViewModel() {

    private val _tokens = MutableStateFlow<List<String>>(emptyList())
    val tokens: StateFlow<List<String>> = _tokens.asStateFlow()

    init {
        viewModelScope.launch {
            _tokens.value = try {
                loadTokens(serverId)
            } catch (e: Exception) {
                // A read failure leaves the empty list; the screen still works.
                emptyList()
            }
        }
    }

    /**
     * Adds a token and pushes the full list to the live session. Blank input
     * is ignored, as the fragment's empty-text check did.
     */
    fun add(tokenText: String) {
        val token = tokenText.trim()
        if (token.isEmpty()) {
            return
        }
        viewModelScope.launch {
            _tokens.update { it + token }
            addToken(serverId, token)
            sendTokens(_tokens.value)
        }
    }

    /** Removes one occurrence of [token] (the tapped row) and pushes the list. */
    fun remove(token: String) {
        viewModelScope.launch {
            val index = _tokens.value.indexOf(token)
            if (index < 0) {
                return@launch
            }
            _tokens.update { current ->
                current.toMutableList().also { it.removeAt(index) }
            }
            removeToken(serverId, token)
            sendTokens(_tokens.value)
        }
    }
}
