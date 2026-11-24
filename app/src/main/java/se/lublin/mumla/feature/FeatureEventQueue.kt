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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * An event collector offers into an unlimited channel and returns at once;
 * one worker in the feature's own scope drains it. A slow Android dependency
 * (TTS, database, notification manager) therefore cannot fill the client's
 * 256-event buffer and suspend the session's message coroutine.
 */
class FeatureEventQueue<T>(
    scope: CoroutineScope,
    private val handle: suspend (T) -> Unit,
) {
    private val channel = Channel<T>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in channel) {
                handle(item)
            }
        }
    }

    /** Never suspends; the container's hand-off rule covers full flows. */
    fun offer(item: T) {
        channel.trySend(item)
    }
}
