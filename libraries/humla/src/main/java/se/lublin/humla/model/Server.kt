/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.model

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Single server model: Room row, parcel, and UI model in one (no separate layers).
 *
 * Room and Parcel bind the raw `name`/`host`/`port` fields (`@JvmField`, so no
 * accessor masks them); `id`/`username`/`password` are plain properties. The
 * user-visible name fallback lives in [getName] only. SRV resolution is not
 * cached here: the transport's `SrvResolver` and the server-list pings own the
 * rule, and `updateServers` rebuilds these rows from the database on every
 * resume. Parcel bytes match the hand-written original exactly (six fields in
 * order).
 */
@Entity(tableName = "server")
@Parcelize
// Final by intent (pre-image Java was non-final, but no subclass exists
// in-tree): documents sealing; re-open if extensibility is ever required.
class Server(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    var id: Long,
    @ColumnInfo(name = "name")
    @JvmField
    var name: String?,
    @ColumnInfo(name = "host")
    @JvmField
    var host: String?,
    @ColumnInfo(name = "port")
    @JvmField
    var port: Int,
    @ColumnInfo(name = "username")
    var username: String?,
    @ColumnInfo(name = "password")
    var password: String?
) : Parcelable {
    /**
     * Returns a user-defined name for the server, or the host if the user-defined name is not set.
     * @return A user readable name for the server.
     */
    fun getName(): String? {
        return if (!name.isNullOrEmpty()) name else host
    }

    fun setName(name: String?) {
        this.name = name
    }

    fun setHost(host: String?) {
        this.host = host
    }

    fun getHost(): String? {
        return host
    }

    fun setPort(port: Int) {
        this.port = port
    }

    fun getPort(): Int {
        return port
    }

    /**
     * Returns whether or not the server is stored in a database.
     * @return true if the server's ID is in the database.
     */
    fun isSaved(): Boolean {
        return id != -1L
    }
}
