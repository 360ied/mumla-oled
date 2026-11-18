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
 * Service-lifetime remote and self talk levels (step 5a.3a; taken over by
 * the VoiceEngine facade in 5a.3b, which only changes the sources).
 *
 * Talk state travels as levels, never events: [users] holds every
 * currently talking remote session, and absence means passive, so a slow
 * reader sees a newer table, never a contradiction or a gap. See
 * docs/target-architecture/voice.md#outputs.
 *
 * Android-free, so the mapping and the immutability walk run in plain JVM
 * tests. Every write uses [update], because the notifier and the talking
 * callback can write at the same moment.
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
 * Production publisher for [TalkActivity]. Owned by HumlaService (one
 * instance for the service lifetime, so subscribers never sit on a dead
 * flow); fed by the native notifier (remote users) and AudioHandler's
 * Kotlin talking callback (self); cleared whenever the output engine is
 * destroyed, after the notifier's final read.
 */
class TalkActivityPublisher {
    private val _talkActivity = MutableStateFlow(TalkActivity.initial())
    val talkActivity: StateFlow<TalkActivity> = _talkActivity.asStateFlow()

    /**
     * Replaces the remote table wholesale. Called on the native notifier
     * thread, at most once per snapshot change (step 5a.3a source; 5a.4
     * routes through [updateAll] so remote and self land atomically).
     */
    fun updateRemote(users: Map<Int, TalkState>) {
        val persistent = users.toPersistentMap()
        _talkActivity.update { current ->
            if (current.users == persistent) current else current.copy(users = persistent)
        }
    }

    /** Called on the capture thread via AudioHandler's talking callback (pre-5a.4). */
    fun setSelfTalking(talking: Boolean) {
        _talkActivity.update { current ->
            if (current.selfTalking == talking) current else current.copy(selfTalking = talking)
        }
    }

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

    /**
     * Resets the whole activity when the output engine goes away (Stop,
     * and every pipeline rebuild). Called after the notifier stopped, so
     * no snapshot from the dead engine can land afterwards and re-light
     * an indicator — stale entries would stick, and after a reconnect even
     * light up a different user, since Murmur reuses session IDs. Self
     * talk resets too: the capture-thread false edge is funneled through
     * a sync-gated callback that teardown can drop while unsynced, and a
     * stuck true would survive into the next connection with no emission
     * to correct it (a later true is a no-op dedup).
     */
    fun clearRemote() {
        _talkActivity.update { current ->
            val cleared = TalkActivity.initial()
            if (current == cleared) current else cleared
        }
    }
}
