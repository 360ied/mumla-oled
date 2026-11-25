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

package se.lublin.humla.voice

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import se.lublin.humla.model.TalkState

/**
 * Service-lifetime remote and self talk levels (step 5a.4: the native
 * notifier is the only writer, publishing remote and self atomically).
 *
 * Talk state travels as levels, never events: [users] holds every
 * currently talking remote session, and absence means passive, so a slow
 * reader sees a newer table, never a contradiction or a gap. See
 * docs/target-architecture/voice.md#outputs.
 *
 * Android-free, so the mapping and the immutability walk run in plain JVM
 * tests.
 */
data class TalkActivity(
    val selfTalking: Boolean,
    val users: PersistentMap<Int, TalkState>,
) {
    companion object {
        fun initial(): TalkActivity = TalkActivity(
            selfTalking = false,
            users = persistentMapOf(),
        )
    }
}

/**
 * Production publisher for [TalkActivity]. Owned by the runtime (one
 * instance for the service lifetime, so subscribers never sit on a dead
 * flow); fed only by the native notifier via [updateAll] (remote levels
 * plus self-talking in one atomic write). Session Stop clears through the
 * same path: native clears the engines/rings and the notifier delivers the
 * final empty snapshot — no Kotlin writer touches this flow.
 */
class TalkActivityPublisher {
    private val _talkActivity = MutableStateFlow(TalkActivity.initial())
    val talkActivity: StateFlow<TalkActivity> = _talkActivity.asStateFlow()

    /**
     * Single-writer update from 5a.4: the notifier is the only writer of
     * talkActivity, publishing remote levels and self-talking together so
     * collectors never observe a half-applied snapshot.
     */
    fun updateAll(users: Map<Int, TalkState>, selfTalking: Boolean) {
        val persistent = users.toPersistentMap()
        _talkActivity.update { current ->
            if (current.users == persistent && current.selfTalking == selfTalking) current
            else current.copy(users = persistent, selfTalking = selfTalking)
        }
    }
}
