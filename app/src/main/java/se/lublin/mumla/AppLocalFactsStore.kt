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

package se.lublin.mumla

import kotlinx.collections.immutable.toPersistentSet
import se.lublin.humla.client.LocalFacts
import se.lublin.humla.client.LocalFactsStore

/** Suspend DAO surface for the one writer of device-local facts. */
interface LocalFactsDao {
    suspend fun mutedUsers(serverId: Long): List<Int>
    suspend fun ignoredUsers(serverId: Long): List<Int>
    suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean)
    suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean)
}

/**
 * The app's real [LocalFactsStore]: mute and ignore lists live in the Room
 * database per saved server, self mute/deafen in settings. The session is
 * the only decision maker and this store is its only writer.
 *
 * An unsaved server (id -1) has no rows to key lists to, so both loads and
 * writes skip it, as the channel adapter's `Server.isSaved()` guard did.
 */
class AppLocalFactsStore(
    private val dao: LocalFactsDao,
    private val settings: Settings,
) : LocalFactsStore {

    override suspend fun load(serverId: Long): LocalFacts {
        val muted = if (serverId >= 0) dao.mutedUsers(serverId) else emptyList()
        val ignored = if (serverId >= 0) dao.ignoredUsers(serverId) else emptyList()
        return LocalFacts(
            mutedUserIds = muted.toPersistentSet(),
            ignoredUserIds = ignored.toPersistentSet(),
            selfMuted = settings.isMuted,
            selfDeafened = settings.isDeafened,
        )
    }

    override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) {
        if (serverId < 0) return
        dao.setLocalMute(serverId, userId, muted)
    }

    override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) {
        if (serverId < 0) return
        dao.setLocalIgnore(serverId, userId, ignored)
    }

    override suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean) {
        settings.setMutedAndDeafened(muted, deafened)
    }
}
