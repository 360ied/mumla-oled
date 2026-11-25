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
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.LocalFactsDao

/**
 * Room database replacing the hand-SQL `MumlaSQLiteDatabase` behind the
 * [MumlaDatabase] interface (migration slice 2).
 *
 * Opens the existing `mumble.db` at version 9 and migrates to 11 via
 * [MIGRATION_9_10] and [MIGRATION_10_11] — destructive fallback is forbidden,
 * the fleet has real data. `Server` is the single `@Entity @Parcelize` model
 * (DB row, parcel, and UI model in one); no entity mapping remains.
 *
 * Threading: every read and write is suspend and runs on Dispatchers.IO, so
 * no screen, feature, or service can block the main thread on storage. The
 * container builds exactly one instance per process ([create]) — Room forbids
 * concurrent instances on one file.
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
abstract class MumlaRoomDatabase : RoomDatabase(), MumlaDatabase, LocalFactsDao {
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
        // No-op for the shared process singleton: activities, services, and
        // features share the container's instance, so closing under one
        // consumer breaks the rest. Directly-built instances (tests) close
        // normally.
        if (this !== instance) {
            super.close()
        }
    }

    override suspend fun getServers(): List<Server> = withContext(Dispatchers.IO) {
        serverDao().getAll()
    }

    override suspend fun addServer(server: Server) {
        // Insert via a detached copy so concurrent readers of the passed
        // instance never observe the transient id == 0 window: the caller's
        // object keeps its previous id (-1 unsaved sentinel) until the
        // single final write below. Callers must still not share a Server
        // across threads during addServer (the final id write itself races),
        // and every host/port write must route through the setters or
        // resolved-host invalidation is bypassed.
        val detached = Server(
            id = 0,
            name = server.name,
            host = server.host,
            port = server.port,
            username = server.username,
            password = server.password,
        )
        server.id = withContext(Dispatchers.IO) { serverDao().insert(detached) }
    }

    override suspend fun updateServer(server: Server) {
        withContext(Dispatchers.IO) { serverDao().update(server) }
    }

    override suspend fun removeServer(server: Server) {
        // Atomic five-table cleanup: a crash mid-way must not leave orphans
        // (no FKs backstop these tables), so the deletes run in one Room
        // transaction on the IO bridge (Room forbids main-thread queries).
        withContext(Dispatchers.IO) {
            runInTransaction {
                serverDao().deleteById(server.id)
                // Server-specific cleanup, mirroring the SQLite implementation.
                pinnedDao().deleteByServer(server.id)
                tokenDao().deleteByServer(server.id)
                localMuteDao().deleteByServer(server.id)
                localIgnoreDao().deleteByServer(server.id)
            }
        }
    }

    override suspend fun getPinnedChannels(serverId: Long): List<Int> =
        // Corrupt non-numeric rows are dropped, not coerced to channel 0
        // (0 is a valid channel; mapping garbage there would pin it).
        withContext(Dispatchers.IO) { pinnedDao().getChannels(serverId).mapNotNull { it.toIntOrNull() } }

    override suspend fun addPinnedChannel(serverId: Long, channelId: Int) {
        withContext(Dispatchers.IO) { pinnedDao().insert(FavouriteEntity(channel = channelId.toString(), serverId = serverId)) }
    }

    override suspend fun removePinnedChannel(serverId: Long, channelId: Int) {
        withContext(Dispatchers.IO) { pinnedDao().delete(serverId, channelId.toString()) }
    }

    override suspend fun isChannelPinned(serverId: Long, channelId: Int): Boolean =
        withContext(Dispatchers.IO) { pinnedDao().count(serverId, channelId.toString()) > 0 }

    override suspend fun getAccessTokens(serverId: Long): List<String> =
        withContext(Dispatchers.IO) { tokenDao().getValues(serverId) }

    override suspend fun addAccessToken(serverId: Long, token: String) {
        withContext(Dispatchers.IO) { tokenDao().insert(TokenEntity(value = token, serverId = serverId)) }
    }

    override suspend fun removeAccessToken(serverId: Long, token: String) {
        withContext(Dispatchers.IO) { tokenDao().delete(serverId, token) }
    }

    override suspend fun getLocalMutedUsers(serverId: Long): List<Int> =
        withContext(Dispatchers.IO) { localMuteDao().getUsers(serverId) }

    override suspend fun addLocalMutedUser(serverId: Long, userId: Int) {
        withContext(Dispatchers.IO) { localMuteDao().insert(LocalMuteEntity(serverId = serverId, userId = userId)) }
    }

    override suspend fun removeLocalMutedUser(serverId: Long, userId: Int) {
        withContext(Dispatchers.IO) { localMuteDao().delete(serverId, userId) }
    }

    override suspend fun getLocalIgnoredUsers(serverId: Long): List<Int> =
        withContext(Dispatchers.IO) { localIgnoreDao().getUsers(serverId) }

    override suspend fun addLocalIgnoredUser(serverId: Long, userId: Int) {
        withContext(Dispatchers.IO) { localIgnoreDao().insert(LocalIgnoreEntity(serverId = serverId, userId = userId)) }
    }

    override suspend fun removeLocalIgnoredUser(serverId: Long, userId: Int) {
        withContext(Dispatchers.IO) { localIgnoreDao().delete(serverId, userId) }
    }

    override suspend fun addCertificate(name: String, certificate: ByteArray, password: String?): DatabaseCertificate {
        val id = withContext(Dispatchers.IO) {
            certificateDao().insert(CertificateEntity(data = certificate, name = name, password = password))
        }
        return DatabaseCertificate(id, name)
    }

    override suspend fun getCertificates(): List<DatabaseCertificate> =
        withContext(Dispatchers.IO) { certificateDao().getAll().map { DatabaseCertificate(it.id, it.name) } }

    override suspend fun getCertificateData(id: Long): ByteArray? =
        withContext(Dispatchers.IO) { certificateDao().getData(id) }

    override suspend fun getCertificatePassword(id: Long): String? =
        withContext(Dispatchers.IO) { certificateDao().getPassword(id) }

    override suspend fun removeCertificate(id: Long) {
        withContext(Dispatchers.IO) { certificateDao().deleteById(id) }
    }

    override suspend fun mutedUsers(serverId: Long): List<Int> = localMutedUsers(serverId)

    override suspend fun ignoredUsers(serverId: Long): List<Int> = localIgnoredUsers(serverId)

    override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) {
        if (muted) insertLocalMutedUser(serverId, userId) else deleteLocalMutedUser(serverId, userId)
    }

    override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) {
        if (ignored) insertLocalIgnoredUser(serverId, userId) else deleteLocalIgnoredUser(serverId, userId)
    }

    suspend fun localMutedUsers(serverId: Long): List<Int> = withContext(Dispatchers.IO) {
        localMuteDao().getUsers(serverId)
    }

    suspend fun localIgnoredUsers(serverId: Long): List<Int> = withContext(Dispatchers.IO) {
        localIgnoreDao().getUsers(serverId)
    }

    suspend fun insertLocalMutedUser(serverId: Long, userId: Int) = withContext(Dispatchers.IO) {
        localMuteDao().insert(LocalMuteEntity(serverId = serverId, userId = userId))
    }

    suspend fun deleteLocalMutedUser(serverId: Long, userId: Int) = withContext(Dispatchers.IO) {
        localMuteDao().delete(serverId, userId)
    }

    suspend fun insertLocalIgnoredUser(serverId: Long, userId: Int) = withContext(Dispatchers.IO) {
        localIgnoreDao().insert(LocalIgnoreEntity(serverId = serverId, userId = userId))
    }

    suspend fun deleteLocalIgnoredUser(serverId: Long, userId: Int) = withContext(Dispatchers.IO) {
        localIgnoreDao().delete(serverId, userId)
    }

    companion object {
        const val DATABASE_NAME = "mumble.db"

        // Process singleton without extra concurrency primitives (startup
        // is single-threaded; the arch allowlist only ratchets down, so no
        // new tripwire matches may be added).
        private var instance: MumlaRoomDatabase? = null

        @JvmStatic
        fun create(context: Context): MumlaRoomDatabase {
            val existing = instance
            if (existing != null) return existing
            val created = Room.databaseBuilder(
                context.applicationContext,
                MumlaRoomDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_8_10, MIGRATION_9_10, MIGRATION_10_11).build()
            instance = created
            return created
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
            // Preserve the autoincrement sequence across DROP+RENAME: without
            // this an emptied-but-previously-used table restarts at 1, which
            // could re-animate orphan favourites/tokens/mute/ignore rows
            // referencing recycled server ids after a delete-all-then-migrate.
            // Tables without AUTOINCREMENT have no sequence row (null → skip).
            var sequence: Long? = null
            val seqCursor = db.query("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table))
            try {
                if (seqCursor.moveToFirst()) {
                    sequence = seqCursor.getLong(0)
                }
            } finally {
                seqCursor.close()
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS `" + table + "_new` " + definition)
            db.execSQL(
                "INSERT INTO `" + table + "_new` (" + columns + ") " +
                    "SELECT " + selectColumns + " FROM `" + table + "`",
            )
            db.execSQL("DROP TABLE `" + table + "`")
            db.execSQL("ALTER TABLE `" + table + "_new` RENAME TO `" + table + "`")
            if (sequence != null) {
                // INSERT OR REPLACE: after DROP+RENAME an emptied table has
                // no sequence row left, so a bare UPDATE would touch 0 rows
                // and silently drop the saved value (restarting ids at 1).
                db.execSQL(
                    "INSERT OR REPLACE INTO sqlite_sequence(name, seq) VALUES(?, ?)",
                    arrayOf<Any?>(table, sequence),
                )
            }
        }
    }
}
