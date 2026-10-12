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

package se.lublin.mumla.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * DAOs mirroring [MumlaDatabase] one-for-one (migration slice 2); method
 * names match the hand-SQL originals so call sites diff minimally. Conflict
 * strategies: mute/ignore use IGNORE (old `insert()` returned -1 without
 * throwing); comments use REPLACE like the old `replace()` call — which also
 * dedupes repeats under the new composite PK (old table allowed duplicates,
 * but `isCommentSeen` only queries who+comment, so results are unchanged).
 * Plain `@Insert` elsewhere is ABORT (throws on conflict); unreachable by
 * construction today (autoincrement PKs, no UNIQUE columns, `copy(id = 0)`).
 */
@Dao
interface ServerDao {
    @Query("SELECT * FROM `server`")
    fun getAll(): List<ServerEntity>

    @Insert
    fun insert(server: ServerEntity): Long

    @Update
    fun update(server: ServerEntity)

    @Query("DELETE FROM `server` WHERE `_id` = :id")
    fun deleteById(id: Long)
}

@Dao
interface PinnedDao {
    @Query("SELECT `channel` FROM `favourites` WHERE `server` = :serverId")
    fun getChannels(serverId: Long): List<String>

    @Insert
    fun insert(favourite: FavouriteEntity): Long

    @Query("DELETE FROM `favourites` WHERE `server` = :serverId AND `channel` = :channel")
    fun delete(serverId: Long, channel: String)

    @Query("SELECT COUNT(*) FROM `favourites` WHERE `server` = :serverId AND `channel` = :channel")
    fun count(serverId: Long, channel: String): Int

    @Query("DELETE FROM `favourites` WHERE `server` = :serverId")
    fun deleteByServer(serverId: Long)
}

@Dao
interface TokenDao {
    @Query("SELECT `value` FROM `tokens` WHERE `server` = :serverId")
    fun getValues(serverId: Long): List<String>

    @Insert
    fun insert(token: TokenEntity): Long

    @Query("DELETE FROM `tokens` WHERE `server` = :serverId AND `value` = :value")
    fun delete(serverId: Long, value: String)

    @Query("DELETE FROM `tokens` WHERE `server` = :serverId")
    fun deleteByServer(serverId: Long)
}

@Dao
interface CommentDao {
    @Query("SELECT COUNT(*) FROM `comments` WHERE `who` = :who AND `comment` = :comment")
    fun count(who: String, comment: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(comment: CommentEntity): Long
}

@Dao
interface LocalMuteDao {
    @Query("SELECT `user` FROM `local_mute` WHERE `server` = :serverId")
    fun getUsers(serverId: Long): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entry: LocalMuteEntity): Long

    @Query("DELETE FROM `local_mute` WHERE `server` = :serverId AND `user` = :userId")
    fun delete(serverId: Long, userId: Int)

    @Query("DELETE FROM `local_mute` WHERE `server` = :serverId")
    fun deleteByServer(serverId: Long)
}

@Dao
interface LocalIgnoreDao {
    @Query("SELECT `user` FROM `local_ignore` WHERE `server` = :serverId")
    fun getUsers(serverId: Long): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entry: LocalIgnoreEntity): Long

    @Query("DELETE FROM `local_ignore` WHERE `server` = :serverId AND `user` = :userId")
    fun delete(serverId: Long, userId: Int)

    @Query("DELETE FROM `local_ignore` WHERE `server` = :serverId")
    fun deleteByServer(serverId: Long)
}

@Dao
interface CertificateDao {
    @Query("SELECT * FROM `certificates`")
    fun getAll(): List<CertificateEntity>

    @Insert
    fun insert(certificate: CertificateEntity): Long

    @Query("SELECT `data` FROM `certificates` WHERE `_id` = :id")
    fun getData(id: Long): ByteArray?

    @Query("SELECT `password` FROM `certificates` WHERE `_id` = :id")
    fun getPassword(id: Long): String?

    @Query("DELETE FROM `certificates` WHERE `_id` = :id")
    fun deleteById(id: Long)
}
