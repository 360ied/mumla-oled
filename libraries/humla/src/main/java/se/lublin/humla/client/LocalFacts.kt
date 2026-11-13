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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.client

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Device-local facts loaded once per session attempt. See
 * docs/target-architecture/client.md#connecttarget.
 *
 * Lists are keyed by registered user ID, as in today's database. When a
 * UserState arrives for a registered user, the transition sets
 * localMuted/localIgnored from these sets, so session IDs are never stored.
 */
data class LocalFacts(
    val mutedUserIds: PersistentSet<Int>,
    val ignoredUserIds: PersistentSet<Int>,
    val selfMuted: Boolean,
    val selfDeafened: Boolean,
) {
    companion object {
        fun empty(): LocalFacts = LocalFacts(
            mutedUserIds = persistentSetOf(),
            ignoredUserIds = persistentSetOf(),
            selfMuted = false,
            selfDeafened = false,
        )
    }
}

/**
 * Database/settings backing for [LocalFacts]. Writes run on the I/O
 * dispatcher in the session scope; the state update never waits for them.
 *
 * Mute/ignore lists are keyed by registered user ID per server; self
 * mute/deafen is a global persisted setting, hence no serverId.
 */
interface LocalFactsStore {
    suspend fun load(serverId: Long): LocalFacts
    suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean)
    suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean)
    suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean)
}

/** Test-only single-server store (also the interim HumlaService bridge).
 * serverId is intentionally ignored: one instance holds one server's facts. */
class InMemoryLocalFactsStore(
    initial: LocalFacts = LocalFacts.empty(),
) : LocalFactsStore {
    private val mutex = Mutex()
    private var current: LocalFacts = initial

    override suspend fun load(serverId: Long): LocalFacts = mutex.withLock { current }

    override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) {
        mutex.withLock {
            current = if (muted) {
                current.copy(mutedUserIds = current.mutedUserIds.add(userId))
            } else {
                current.copy(mutedUserIds = current.mutedUserIds.remove(userId))
            }
        }
    }

    override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) {
        mutex.withLock {
            current = if (ignored) {
                current.copy(ignoredUserIds = current.ignoredUserIds.add(userId))
            } else {
                current.copy(ignoredUserIds = current.ignoredUserIds.remove(userId))
            }
        }
    }

    override suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean) {
        mutex.withLock {
            current = current.copy(selfMuted = muted, selfDeafened = deafened)
        }
    }
}

/**
 * Platform network-availability source. The app implements it over
 * ConnectivityManager.registerDefaultNetworkCallback; the client collects
 * it only while Reconnecting.
 */
interface NetworkMonitor {
    /** Emits on each default-network availability. */
    val available: Flow<Unit>
}
