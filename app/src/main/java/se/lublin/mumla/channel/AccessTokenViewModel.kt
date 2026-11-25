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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /**
     * FIFO chain for storage work: the initial load always finishes before
     * the first edit, so a token added while the read is in flight cannot be
     * overwritten by that read's snapshot.
     */
    private var storageTail: Job = viewModelScope.launch {
        _tokens.value = try {
            loadTokens(serverId)
        } catch (e: Exception) {
            // A read failure leaves the empty list; the screen still works.
            emptyList()
        }
    }

    /**
     * A failed store write is logged and changes nothing: each edit writes
     * the store before it updates the list or the session, so the screen
     * never shows a token the store does not hold.
     */
    private fun enqueueStorage(block: suspend () -> Unit) {
        val previous = storageTail
        storageTail = viewModelScope.launch {
            previous.join()
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Access token write failed", e)
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
        enqueueStorage {
            addToken(serverId, token)
            _tokens.update { it + token }
            sendTokens(_tokens.value)
        }
    }

    /**
     * Removes every occurrence of [token] and pushes the list. The store
     * deletes by value, so dropping only the tapped row would leave the list
     * and the store disagreeing after the next load.
     */
    fun remove(token: String) {
        enqueueStorage {
            if (token in _tokens.value) {
                removeToken(serverId, token)
                _tokens.update { current -> current.filter { it != token } }
                sendTokens(_tokens.value)
            }
        }
    }

    private companion object {
        const val TAG = "AccessTokenViewModel"
    }
}
