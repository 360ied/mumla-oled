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
 * Opens the existing `mumble.db` at version 9 and migrates to 11 via
 * [MIGRATION_9_10] and [MIGRATION_10_11] — destructive fallback is forbidden,
 * the fleet has real data. `Server` is the single `@Entity @Parcelize` model
 * (DB row, parcel, and UI model in one); no entity mapping remains.
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
        Server::class,
        FavouriteEntity::class,
        TokenEntity::class,
        CommentEntity::class,
        LocalMuteEntity::class,
        LocalIgnoreEntity::class,
        CertificateEntity::class,
    ],
    version = 11,
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
        // No-op for the shared singleton only: activities, services, and Hilt
        // share the process-lifetime instance, so closing under one consumer
        // breaks the rest. Directly-built instances (tests) close normally.
        if (this !== instance) {
            super.close()
        }
    }

    override fun getServers(): List<Server> = io {
        serverDao().getAll()
    }

    override fun addServer(server: Server) {
        // The passed id (usually the -1 unsaved sentinel) is ignored, mirroring
        // the old insert without _id: SQLite assigns a fresh rowid. Server is a
        // plain class with no copy(), so the id is zeroed transiently for the
        // autogen insert and restored if it fails.
        val previous = server.id
        server.id = 0
        try {
            server.id = io { serverDao().insert(server) }
        } catch (e: Exception) {
            server.id = previous
            throw e
        }
    }

    override fun updateServer(server: Server) {
        io { serverDao().update(server) }
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
        io { commentDao().count(hash, String(commentHash, Charsets.UTF_8)) > 0 }

    override fun markCommentSeen(hash: String, commentHash: ByteArray) {
        io {
            commentDao().insert(
                CommentEntity(
                    who = hash,
                    comment = String(commentHash, Charsets.UTF_8),
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
                ).addMigrations(MIGRATION_8_10, MIGRATION_9_10, MIGRATION_10_11).build().also { instance = it }
            }

        /**
         * v9 → v10: every table is recreated with data-preserving
         * INSERT...SELECT so the result validates exactly against the entity
         * schema. Three tables change shape (see class docs); the other four
         * gain only the explicit `NOT NULL` on their `_id` primary keys,
         * which Room's validator requires (SQLite reports bare
         * `INTEGER PRIMARY KEY` as nullable in PRAGMA table_info).
         */
        @JvmStatic
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateFrom(db, hasCertPassword = true)
        }

        /**
         * v10 → v11: `server` follows the nullability of the single Server
         * model — `name`/`host`/`username` go nullable (parcel round-trips
         * carry nulls), `port` goes NOT NULL with COALESCE (writers always
         * bound a primitive). Data-preserving INSERT...SELECT; no fleet row
         * can hold a null port (every writer bound one), so the COALESCE
         * only guards hand-edited databases.
         */
        @JvmStatic
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreate(
                    db, "server",
                    "(`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT, `host` TEXT, `port` INTEGER NOT NULL, " +
                        "`username` TEXT, `password` TEXT)",
                    "`_id`, `name`, `host`, `port`, `username`, `password`",
                    selectColumns = "`_id`, `name`, `host`, COALESCE(`port`, 0), " +
                        "`username`, `password`",
                )
            }
        }

        /**
         * v8 → v10 for stragglers predating the certificate-password column
         * (DB v9 shipped 2026-09-13): identical to 9 → 10 except `password`
         * migrates as NULL. Pre-v8 databases (2020-era) are unsupported.
         */
        @JvmStatic
        val MIGRATION_8_10 = object : Migration(8, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateFrom(db, hasCertPassword = false)
        }

        private fun migrateFrom(db: SupportSQLiteDatabase, hasCertPassword: Boolean) {
            recreate(
                db, "server",
                "(`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER, " +
                    "`username` TEXT NOT NULL, `password` TEXT)",
                "`_id`, `name`, `host`, `port`, `username`, `password`",
            )
            recreate(
                db, "favourites",
                "(`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`channel` TEXT NOT NULL, `server` INTEGER NOT NULL)",
                "`_id`, `channel`, `server`",
            )
            recreate(
                db, "tokens",
                "(`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`value` TEXT NOT NULL, `server` INTEGER NOT NULL)",
                "`_id`, `value`, `server`",
            )
            recreate(
                db, "comments",
                "(`who` TEXT NOT NULL, `comment` TEXT NOT NULL, `seen` TEXT NOT NULL, " +
                    "PRIMARY KEY(`who`, `comment`, `seen`))",
                "`who`, `comment`, `seen`",
            )
            recreate(
                db, "local_mute",
                "(`server` INTEGER NOT NULL, `user` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`server`, `user`))",
                "`server`, `user`",
            )
            recreate(
                db, "local_ignore",
                "(`server` INTEGER NOT NULL, `user` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`server`, `user`))",
                "`server`, `user`",
            )
            recreate(
                db, "certificates",
                "(`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`data` BLOB NOT NULL, `name` TEXT NOT NULL, `password` TEXT)",
                "`_id`, `data`, `name`, `password`",
                // v8 has no password column: migrate it as NULL.
                selectColumns = if (hasCertPassword) {
                    "`_id`, `data`, `name`, `password`"
                } else {
                    "`_id`, `data`, `name`, NULL"
                },
            )
        }

        private fun recreate(
            db: SupportSQLiteDatabase,
            table: String,
            definition: String,
            columns: String,
            selectColumns: String = columns,
        ) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `" + table + "_new` " + definition)
            db.execSQL(
                "INSERT INTO `" + table + "_new` (" + columns + ") " +
                    "SELECT " + selectColumns + " FROM `" + table + "`",
            )
            db.execSQL("DROP TABLE `" + table + "`")
            db.execSQL("ALTER TABLE `" + table + "_new` RENAME TO `" + table + "`")
        }

        private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }
    }
}
