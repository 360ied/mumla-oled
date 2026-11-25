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

package se.lublin.mumla.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.model.Server

/**
 * Covers the Room database behind [MumlaDatabase]: suspend CRUD round-trips
 * against an in-memory database plus the v9 → v11 file migrations over
 * hand-built old databases (frozen DDL — must not drift with future schema
 * versions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MumlaDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: MumlaRoomDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MumlaRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testServerCrud(): Unit = runBlocking {
        val server = Server(-1, "name", "host", 64738, "user", "pass")
        db.addServer(server)
        assertTrue("insert must assign a row id", server.id > 0)

        val servers = db.getServers()
        assertEquals(1, servers.size)
        assertEquals("name", servers[0].name)
        assertEquals("host", servers[0].host)
        assertEquals(64738, servers[0].port)
        assertEquals("user", servers[0].username)
        assertEquals("pass", servers[0].password)

        server.name = "renamed"
        db.updateServer(server)
        assertEquals("renamed", db.getServers()[0].name)

        db.removeServer(server)
        assertTrue(db.getServers().isEmpty())
    }

    @Test
    fun testServerNullableColumns(): Unit = runBlocking {
        val server = Server(-1, "n", "h", 0, "u", null)
        db.addServer(server)
        val read = db.getServers()[0]
        assertEquals(0, read.port)
        assertNull(read.password)
    }

    @Test
    fun testPinnedChannels(): Unit = runBlocking {
        db.addPinnedChannel(7L, 3)
        db.addPinnedChannel(7L, 5)
        assertEquals(2, db.getPinnedChannels(7L).size)
        assertTrue(db.isChannelPinned(7L, 3))
        assertFalse(db.isChannelPinned(7L, 9))
        db.removePinnedChannel(7L, 3)
        assertFalse(db.isChannelPinned(7L, 3))
    }

    @Test
    fun testAccessTokens(): Unit = runBlocking {
        db.addAccessToken(7L, "tok1")
        db.addAccessToken(7L, "tok2")
        assertEquals(2, db.getAccessTokens(7L).size)
        db.removeAccessToken(7L, "tok1")
        val tokens = db.getAccessTokens(7L)
        assertEquals(1, tokens.size)
        assertEquals("tok2", tokens[0])
    }

    @Test
    fun testLocalMuteIgnore(): Unit = runBlocking {
        db.addLocalMutedUser(7L, 11)
        db.addLocalMutedUser(7L, 13)
        assertEquals(2, db.getLocalMutedUsers(7L).size)
        db.removeLocalMutedUser(7L, 11)
        assertEquals(1, db.getLocalMutedUsers(7L).size)

        db.addLocalIgnoredUser(7L, 21)
        assertEquals(1, db.getLocalIgnoredUsers(7L).size)
        db.removeLocalIgnoredUser(7L, 21)
        assertTrue(db.getLocalIgnoredUsers(7L).isEmpty())
    }

    @Test
    fun testDuplicateMuteIgnored(): Unit = runBlocking {
        db.addLocalMutedUser(7L, 11)
        // Mirrors the old insert(): conflict returns without throwing.
        db.addLocalMutedUser(7L, 11)
        assertEquals(1, db.getLocalMutedUsers(7L).size)
    }

    @Test
    fun testAddCertificateWithPassword(): Unit = runBlocking {
        val data = byteArrayOf(1, 2, 3, 4)
        val cert = db.addCertificate("test.p12", data, "secret")

        assertTrue(cert.id > 0)
        assertEquals("test.p12", cert.name)
        assertArrayEquals(data, db.getCertificateData(cert.id))
        assertEquals("secret", db.getCertificatePassword(cert.id))
    }

    @Test
    fun testAddCertificateWithoutPasswordDefault(): Unit = runBlocking {
        val data = byteArrayOf(5, 6, 7, 8)
        val cert = db.addCertificate("unencrypted.p12", data)

        assertTrue(cert.id > 0)
        assertEquals("unencrypted.p12", cert.name)
        assertArrayEquals(data, db.getCertificateData(cert.id))
        assertNull(db.getCertificatePassword(cert.id))

        db.removeCertificate(cert.id)
        assertTrue(db.getCertificates().isEmpty())
    }

    @Test
    fun testRemoveServerCleansRelatedRows(): Unit = runBlocking {
        val server = Server(-1, "n", "h", 64738, "u", null)
        db.addServer(server)
        val id = server.id
        db.addPinnedChannel(id, 3)
        db.addAccessToken(id, "t")
        db.addLocalMutedUser(id, 9)
        db.addLocalIgnoredUser(id, 10)

        db.removeServer(server)

        assertTrue(db.getPinnedChannels(id).isEmpty())
        assertTrue(db.getAccessTokens(id).isEmpty())
        assertTrue(db.getLocalMutedUsers(id).isEmpty())
        assertTrue(db.getLocalIgnoredUsers(id).isEmpty())
    }

    @Test
    fun testMigration9To10PreservesData(): Unit = runBlocking {
        val dbFile = context.getDatabasePath("migrate-test.db")
        deleteDbFiles(dbFile)
        assertTrue(dbFile.parentFile.mkdirs() || dbFile.parentFile.isDirectory)
        val v9 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v9.execSQL(V9_SERVER)
        v9.execSQL(V9_FAVOURITES)
        v9.execSQL(V9_TOKENS)
        v9.execSQL(V9_COMMENTS)
        v9.execSQL(V9_LOCAL_MUTE)
        v9.execSQL(V9_LOCAL_IGNORE)
        v9.execSQL(V9_CERTIFICATES)
        v9.execSQL(
            "INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n','h',64738,'u','p');",
        )
        v9.execSQL("INSERT INTO `favourites` (`channel`,`server`) VALUES ('3',1);")
        v9.execSQL("INSERT INTO `tokens` (`value`,`server`) VALUES ('tok',1);")
        v9.execSQL(
            "INSERT INTO `comments` (`who`,`comment`,`seen`)" +
                " VALUES ('w','c','2026-01-01');",
        )
        v9.execSQL("INSERT INTO `local_mute` (`server`,`user`) VALUES (1,11);")
        v9.execSQL("INSERT INTO `local_ignore` (`server`,`user`) VALUES (1,21);")
        v9.execSQL(
            "INSERT INTO `certificates` (`data`,`name`,`password`)" +
                " VALUES (x'0102','c.p12','s');",
        )
        v9.version = 9
        v9.close()

        val migrated = Room.databaseBuilder(context, MumlaRoomDatabase::class.java, "migrate-test.db")
            .addMigrations(
                MumlaRoomDatabase.MIGRATION_9_10,
                MumlaRoomDatabase.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val servers = migrated.getServers()
            assertEquals(1, servers.size)
            assertEquals("h", servers[0].host)
            assertEquals(64738, servers[0].port)
            assertEquals(1, migrated.getPinnedChannels(1L).size)
            assertEquals(3, migrated.getPinnedChannels(1L)[0])
            assertEquals("tok", migrated.getAccessTokens(1L)[0])
            assertEquals(1, migrated.getLocalMutedUsers(1L).size)
            assertEquals(11, migrated.getLocalMutedUsers(1L)[0])
            assertEquals(1, migrated.getLocalIgnoredUsers(1L).size)
            assertEquals("c.p12", migrated.getCertificates()[0].name)

            // v10 shapes enforced: duplicate mute is ignored (IGNORE mirrors
            // the old insert()), and the PK now guards the table.
            migrated.addLocalMutedUser(1L, 11)
            assertEquals(1, migrated.getLocalMutedUsers(1L).size)

            // AUTOINCREMENT continuity across the rename: the migrated row kept
            // id 1, so the next insert takes 2.
            val extra = Server(-1, "m", "h2", 64738, "u", null)
            migrated.addServer(extra)
            assertEquals(2L, extra.id)
        } finally {
            migrated.close()
            deleteDbFiles(dbFile)
        }
    }

    @Test
    fun testMigration8To10PreservesData(): Unit = runBlocking {
        val dbFile = context.getDatabasePath("migrate-test-8.db")
        deleteDbFiles(dbFile)
        assertTrue(dbFile.parentFile.mkdirs() || dbFile.parentFile.isDirectory)
        val v8 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v8.execSQL(V9_SERVER)
        v8.execSQL(V9_FAVOURITES)
        v8.execSQL(V9_TOKENS)
        v8.execSQL(V9_COMMENTS)
        v8.execSQL(V9_LOCAL_MUTE)
        v8.execSQL(V9_LOCAL_IGNORE)
        v8.execSQL(V8_CERTIFICATES)
        v8.execSQL(
            "INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n','h',64738,'u','p');",
        )
        v8.execSQL("INSERT INTO `certificates` (`data`,`name`) VALUES (x'0102','c.p12');")
        v8.version = 8
        v8.close()

        val migrated = Room.databaseBuilder(context, MumlaRoomDatabase::class.java, "migrate-test-8.db")
            .addMigrations(
                MumlaRoomDatabase.MIGRATION_8_10,
                MumlaRoomDatabase.MIGRATION_9_10,
                MumlaRoomDatabase.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("h", migrated.getServers()[0].host)
            val certificate = migrated.getCertificates()[0]
            assertEquals("c.p12", certificate.name)
            assertArrayEquals(byteArrayOf(1, 2), migrated.getCertificateData(certificate.id))
            assertNull(
                "v8 certificates had no password column",
                migrated.getCertificatePassword(certificate.id),
            )
        } finally {
            migrated.close()
            deleteDbFiles(dbFile)
        }
    }

    @Test
    fun testMigration10To11ServerNullability(): Unit = runBlocking {
        val dbFile = context.getDatabasePath("migrate-test-10.db")
        deleteDbFiles(dbFile)
        assertTrue(dbFile.parentFile.mkdirs() || dbFile.parentFile.isDirectory)
        // v10 shapes (see MIGRATION_9_10): all tables present, server with
        // name/host/username NOT NULL and port nullable.
        val v10 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v10.execSQL(
            "CREATE TABLE `server` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`name` TEXT NOT NULL,`host` TEXT NOT NULL,`port` INTEGER," +
                "`username` TEXT NOT NULL,`password` TEXT);",
        )
        v10.execSQL(
            "CREATE TABLE `favourites` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`channel` TEXT NOT NULL,`server` INTEGER NOT NULL);",
        )
        v10.execSQL(
            "CREATE TABLE `tokens` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`value` TEXT NOT NULL,`server` INTEGER NOT NULL);",
        )
        v10.execSQL(
            "CREATE TABLE `comments` (`who` TEXT NOT NULL,`comment` TEXT NOT NULL," +
                "`seen` TEXT NOT NULL,PRIMARY KEY(`who`,`comment`,`seen`));",
        )
        v10.execSQL(
            "CREATE TABLE `local_mute` (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
                "PRIMARY KEY(`server`,`user`));",
        )
        v10.execSQL(
            "CREATE TABLE `local_ignore` (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
                "PRIMARY KEY(`server`,`user`));",
        )
        v10.execSQL(
            "CREATE TABLE `certificates` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`data` BLOB NOT NULL,`name` TEXT NOT NULL,`password` TEXT);",
        )
        v10.execSQL(
            "INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n','h',64738,'u','p');",
        )
        v10.execSQL(
            "INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n2','h2',NULL,'u2',NULL);",
        )
        v10.version = 10
        v10.close()

        val migrated = Room.databaseBuilder(context, MumlaRoomDatabase::class.java, "migrate-test-10.db")
            .addMigrations(MumlaRoomDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()
        try {
            val servers = migrated.getServers()
            assertEquals(2, servers.size)
            assertEquals(64738, servers[0].port)
            // NULL ports coalesce to 0; opening at all proves the v11 shape
            // validates exactly against the entity.
            assertEquals(0, servers[1].port)
            assertEquals("h2", servers[1].host)
        } finally {
            migrated.close()
            deleteDbFiles(dbFile)
        }
    }

    private fun deleteDbFiles(dbFile: File) {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
        dbFile.delete()
    }

    private companion object {
        // Frozen v9 DDL for the migration fixture: byte-identical to the deleted
        // hand-SQL schema. Do NOT update with future schema versions.
        const val V9_SERVER =
            "CREATE TABLE `server` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
                "`name` TEXT NOT NULL,`host` TEXT NOT NULL,`port` INTEGER," +
                "`username` TEXT NOT NULL,`password` TEXT);"
        const val V9_FAVOURITES =
            "CREATE TABLE `favourites` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
                "`channel` TEXT NOT NULL,`server` INTEGER NOT NULL);"
        const val V9_TOKENS =
            "CREATE TABLE `tokens` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
                "`value` TEXT NOT NULL,`server` INTEGER NOT NULL);"
        const val V9_COMMENTS =
            "CREATE TABLE `comments` (`who` TEXT NOT NULL,`comment` TEXT NOT NULL," +
                "`seen` DATE NOT NULL);"
        const val V9_LOCAL_MUTE =
            "CREATE TABLE local_mute (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
                "CONSTRAINT server_user UNIQUE(server,user));"
        const val V9_LOCAL_IGNORE =
            "CREATE TABLE local_ignore (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
                "CONSTRAINT server_user UNIQUE(server,user));"
        const val V9_CERTIFICATES =
            "CREATE TABLE certificates (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
                "`data` BLOB NOT NULL,`name` TEXT NOT NULL,`password` TEXT);"
        const val V8_CERTIFICATES =
            "CREATE TABLE certificates (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
                "`data` BLOB NOT NULL,`name` TEXT NOT NULL);"
    }
}
