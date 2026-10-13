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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entities mirroring the v9 `mumble.db` schema column-for-column
 * (migration slice 2). Column names and affinities must stay identical to the
 * hand-SQL schema — Room validates the migrated database on open, and the
 * fleet upgrades in place (`fallbackToDestructiveMigration` is forbidden).
 *
 * Two intentional deviations, both covered by `MIGRATION_9_10`:
 * - `comments` gains a composite primary key (Room requires one) and TEXT
 *   affinity for `seen` (v9 declared DATE); values migrate untouched,
 *   including the pre-existing `datetime('now')` literal (separate bug file).
 * - `local_mute` / `local_ignore` gain an explicit `PRIMARY KEY(server, user)`
 *   replacing the bare UNIQUE constraint — identical duplicate rejection.
 * - `server` follows the nullability of the single Server model since slice 5
 *   (`MIGRATION_10_11`): `name`/`host`/`username` nullable for parcel
 *   round-trips, `port` NOT NULL. `Server` itself is the `@Entity`, so no
 *   mapping layer remains.
 */
@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,
    /** Stored as TEXT (v9 affinity); channel ids convert to/from Int in the adapter. */
    @ColumnInfo(name = "channel")
    val channel: String,
    @ColumnInfo(name = "server")
    val serverId: Long,
)

@Entity(tableName = "tokens")
data class TokenEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "server")
    val serverId: Long,
)

@Entity(
    tableName = "comments",
    primaryKeys = ["who", "comment", "seen"],
)
data class CommentEntity(
    @ColumnInfo(name = "who")
    val who: String,
    @ColumnInfo(name = "comment")
    val comment: String,
    @ColumnInfo(name = "seen")
    val seen: String,
)

@Entity(
    tableName = "local_mute",
    primaryKeys = ["server", "user"],
)
data class LocalMuteEntity(
    @ColumnInfo(name = "server")
    val serverId: Long,
    @ColumnInfo(name = "user")
    val userId: Int,
)

@Entity(
    tableName = "local_ignore",
    primaryKeys = ["server", "user"],
)
data class LocalIgnoreEntity(
    @ColumnInfo(name = "server")
    val serverId: Long,
    @ColumnInfo(name = "user")
    val userId: Int,
)

@Entity(tableName = "certificates")
data class CertificateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,
    @ColumnInfo(name = "data")
    val data: ByteArray,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "password")
    val password: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CertificateEntity) return false
        return id == other.id && data.contentEquals(other.data)
            && name == other.name && password == other.password
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (password?.hashCode() ?: 0)
        return result
    }
}
