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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.lublin.humla.model.Server

/**
 * Room database replacing the hand-SQL `MumlaSQLiteDatabase` behind the
 * [MumlaDatabase] interface (migration slice 2).
 *
 * Opens the existing `mumble.db` at version 9 and migrates to 10 via
 * [MIGRATION_9_10] — destructive fallback is forbidden, the fleet has real
 * data. `Server` stays the hand-written Java model in this slice (it becomes
 * `@Entity @Parcelize` in slice 5); this class maps entities to/from it.
 *
 * Threading (interim): the interface is synchronous and callers include the
 * main thread, so every method bridges via `runBlocking(Dispatchers.IO)` —
 * Room forbids main-thread queries. Slice 4 suspend-ifies the service core;
 * until then the local DB is small enough that the block is unnoticeable.
 * Exactly one instance per process ([getInstance]) — Room forbids concurrent
 * instances on one file.
 */
@Database(
    entities = [
        ServerEntity::class,
        FavouriteEntity::class,
        TokenEntity::class,
        CommentEntity::class,
        LocalMuteEntity::class,
        LocalIgnoreEntity::class,
        CertificateEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class MumlaRoomDatabase : RoomDatabase(), MumlaDatabase {
    abstract fun serverDao(): ServerDao
    abstract fun pinnedDao(): PinnedDao
    abstract fun tokenDao(): TokenDao
    abstract fun commentDao(): CommentDao
    abstract fun localMuteDao(): LocalMuteDao
    abstract fun localIgnoreDao(): LocalIgnoreDao
    abstract fun certificateDao(): CertificateDao

    override fun open() {
        // Do nothing, mirroring the SQLite implementation: Room opens lazily.
    }

    override fun close() {
        // Intentional no-op: this is a process-lifetime singleton shared by
        // activities, services, and Hilt. Closing under one consumer breaks
        // the rest; the process owns the lifecycle (as before, in practice).
    }

    override fun getServers(): List<Server> = io {
        serverDao().getAll().map { it.toModel() }
    }

    override fun addServer(server: Server) {
        val id = io { serverDao().insert(server.toEntity()) }
        server.id = id
    }

    override fun updateServer(server: Server) {
        io { serverDao().update(server.toEntity()) }
    }

    override fun removeServer(server: Server) {
        io {
            serverDao().deleteById(server.id)
            // Server-specific cleanup, mirroring the SQLite implementation.
            pinnedDao().deleteByServer(server.id)
            tokenDao().deleteByServer(server.id)
            localMuteDao().deleteByServer(server.id)
            localIgnoreDao().deleteByServer(server.id)
        }
    }

    override fun isCommentSeen(hash: String, commentHash: ByteArray): Boolean =
        io { commentDao().count(hash, String(commentHash)) > 0 }

    override fun markCommentSeen(hash: String, commentHash: ByteArray) {
        io {
            commentDao().insert(
                CommentEntity(
                    who = hash,
                    comment = String(commentHash),
                    // Pre-existing quirk, preserved: stores the literal
                    // "datetime('now')" instead of evaluating it (filed
                    // separately; the value is never read).
                    seen = "datetime('now')",
                ),
            )
        }
    }

    override fun getPinnedChannels(serverId: Long): List<Int> =
        io { pinnedDao().getChannels(serverId).map { it.toIntOrNull() ?: 0 } }

    override fun addPinnedChannel(serverId: Long, channelId: Int) {
        io { pinnedDao().insert(FavouriteEntity(channel = channelId.toString(), serverId = serverId)) }
    }

    override fun removePinnedChannel(serverId: Long, channelId: Int) {
        io { pinnedDao().delete(serverId, channelId.toString()) }
    }

    override fun isChannelPinned(serverId: Long, channelId: Int): Boolean =
        io { pinnedDao().count(serverId, channelId.toString()) > 0 }

    override fun getAccessTokens(serverId: Long): List<String> =
        io { tokenDao().getValues(serverId) }

    override fun addAccessToken(serverId: Long, token: String) {
        io { tokenDao().insert(TokenEntity(value = token, serverId = serverId)) }
    }

    override fun removeAccessToken(serverId: Long, token: String) {
        io { tokenDao().delete(serverId, token) }
    }

    override fun getLocalMutedUsers(serverId: Long): List<Int> =
        io { localMuteDao().getUsers(serverId) }

    override fun addLocalMutedUser(serverId: Long, userId: Int) {
        io { localMuteDao().insert(LocalMuteEntity(serverId = serverId, userId = userId)) }
    }

    override fun removeLocalMutedUser(serverId: Long, userId: Int) {
        io { localMuteDao().delete(serverId, userId) }
    }

    override fun getLocalIgnoredUsers(serverId: Long): List<Int> =
        io { localIgnoreDao().getUsers(serverId) }

    override fun addLocalIgnoredUser(serverId: Long, userId: Int) {
        io { localIgnoreDao().insert(LocalIgnoreEntity(serverId = serverId, userId = userId)) }
    }

    override fun removeLocalIgnoredUser(serverId: Long, userId: Int) {
        io { localIgnoreDao().delete(serverId, userId) }
    }

    override fun addCertificate(name: String, certificate: ByteArray, password: String?): DatabaseCertificate {
        val id = io {
            certificateDao().insert(CertificateEntity(data = certificate, name = name, password = password))
        }
        return DatabaseCertificate(id, name)
    }

    override fun getCertificates(): List<DatabaseCertificate> =
        io { certificateDao().getAll().map { DatabaseCertificate(it.id, it.name) } }

    override fun getCertificateData(id: Long): ByteArray? =
        io { certificateDao().getData(id) }

    override fun getCertificatePassword(id: Long): String? =
        io { certificateDao().getPassword(id) }

    override fun removeCertificate(id: Long) {
        io { certificateDao().deleteById(id) }
    }

    companion object {
        const val DATABASE_NAME = "mumble.db"

        @Volatile
        private var instance: MumlaRoomDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): MumlaRoomDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MumlaRoomDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_9_10).build().also { instance = it }
            }

        /**
         * v9 → v10: the six identical tables migrate untouched (empty path —
         * Room copies them as-is). The other three are recreated with
         * data-preserving INSERT…SELECT:
         * - `comments`: `seen` gains TEXT affinity (was DATE) and the table a
         *   composite primary key (Room requires one).
         * - `local_mute` / `local_ignore`: the bare UNIQUE constraint becomes
         *   an explicit `PRIMARY KEY(server, user)` — identical duplicate
         *   rejection, and the shape Room validates on open.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `comments_new` " +
                        "(`who` TEXT NOT NULL, `comment` TEXT NOT NULL, `seen` TEXT NOT NULL, " +
                        "PRIMARY KEY(`who`, `comment`, `seen`))",
                )
                db.execSQL(
                    "INSERT INTO `comments_new` (`who`, `comment`, `seen`) " +
                        "SELECT `who`, `comment`, `seen` FROM `comments`",
                )
                db.execSQL("DROP TABLE `comments`")
                db.execSQL("ALTER TABLE `comments_new` RENAME TO `comments`")

                for (table in listOf("local_mute", "local_ignore")) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `" + table + "_new` " +
                            "(`server` INTEGER NOT NULL, `user` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`server`, `user`))",
                    )
                    db.execSQL(
                        "INSERT INTO `" + table + "_new` (`server`, `user`) " +
                            "SELECT `server`, `user` FROM `" + table + "`",
                    )
                    db.execSQL("DROP TABLE `" + table + "`")
                    db.execSQL("ALTER TABLE `" + table + "_new` RENAME TO `" + table + "`")
                }
            }
        }

        private fun ServerEntity.toModel(): Server =
            Server(id, name, host, port ?: 0, username, password)

        private fun Server.toEntity(): ServerEntity =
            ServerEntity(id = id, name = name, host = host, port = port, username = username, password = password)

        private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }
    }
}
