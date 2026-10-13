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

package se.lublin.mumla.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import se.lublin.humla.model.Server;

/**
 * Covers the Room database behind {@link MumlaDatabase} (migration slice 2):
 * CRUD round-trips against an in-memory database plus the v9 → v10 file
 * migration over a hand-built v9 database (frozen DDL — must not drift with
 * future schema versions).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MumlaDatabaseTest {

    private Context context;
    private MumlaRoomDatabase db;
    private MumlaDatabase database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        db = Room.inMemoryDatabaseBuilder(context, MumlaRoomDatabase.class)
                .allowMainThreadQueries()
                .build();
        database = db;
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void testServerCrud() {
        Server server = new Server(-1, "name", "host", 64738, "user", "pass");
        database.addServer(server);
        assertTrue("insert must assign a row id", server.getId() > 0);

        List<Server> servers = database.getServers();
        assertEquals(1, servers.size());
        assertEquals("name", servers.get(0).getName());
        assertEquals("host", servers.get(0).getHost());
        assertEquals(64738, servers.get(0).getPort());
        assertEquals("user", servers.get(0).getUsername());
        assertEquals("pass", servers.get(0).getPassword());

        server.setName("renamed");
        database.updateServer(server);
        assertEquals("renamed", database.getServers().get(0).getName());

        database.removeServer(server);
        assertTrue(database.getServers().isEmpty());
    }

    @Test
    public void testServerNullableColumns() {
        Server server = new Server(-1, "n", "h", 0, "u", null);
        database.addServer(server);
        Server read = database.getServers().get(0);
        assertEquals(0, read.getPort());
        assertNull(read.getPassword());
    }

    @Test
    public void testPinnedChannels() {
        database.addPinnedChannel(7L, 3);
        database.addPinnedChannel(7L, 5);
        assertEquals(2, database.getPinnedChannels(7L).size());
        assertTrue(database.isChannelPinned(7L, 3));
        assertFalse(database.isChannelPinned(7L, 9));
        database.removePinnedChannel(7L, 3);
        assertFalse(database.isChannelPinned(7L, 3));
    }

    @Test
    public void testAccessTokens() {
        database.addAccessToken(7L, "tok1");
        database.addAccessToken(7L, "tok2");
        assertEquals(2, database.getAccessTokens(7L).size());
        database.removeAccessToken(7L, "tok1");
        List<String> tokens = database.getAccessTokens(7L);
        assertEquals(1, tokens.size());
        assertEquals("tok2", tokens.get(0));
    }

    @Test
    public void testLocalMuteIgnore() {
        database.addLocalMutedUser(7L, 11);
        database.addLocalMutedUser(7L, 13);
        assertEquals(2, database.getLocalMutedUsers(7L).size());
        database.removeLocalMutedUser(7L, 11);
        assertEquals(1, database.getLocalMutedUsers(7L).size());

        database.addLocalIgnoredUser(7L, 21);
        assertEquals(1, database.getLocalIgnoredUsers(7L).size());
        database.removeLocalIgnoredUser(7L, 21);
        assertTrue(database.getLocalIgnoredUsers(7L).isEmpty());
    }

    @Test
    public void testDuplicateMuteIgnored() {
        database.addLocalMutedUser(7L, 11);
        // Mirrors the old insert(): conflict returns without throwing.
        database.addLocalMutedUser(7L, 11);
        assertEquals(1, database.getLocalMutedUsers(7L).size());
    }

    @Test
    public void testCommentsSeen() {
        assertFalse(database.isCommentSeen("hash", "comment".getBytes()));
        database.markCommentSeen("hash", "comment".getBytes());
        assertTrue(database.isCommentSeen("hash", "comment".getBytes()));
    }

    @Test
    public void testAddCertificateWithPassword() {
        byte[] data = new byte[]{1, 2, 3, 4};
        DatabaseCertificate cert = database.addCertificate("test.p12", data, "secret");

        assertTrue(cert.getId() > 0);
        assertEquals("test.p12", cert.getName());
        assertTrue(Arrays.equals(data, database.getCertificateData(cert.getId())));
        assertEquals("secret", database.getCertificatePassword(cert.getId()));
    }

    @Test
    public void testAddCertificateWithoutPasswordDefault() {
        byte[] data = new byte[]{5, 6, 7, 8};
        DatabaseCertificate cert = database.addCertificate("unencrypted.p12", data);

        assertTrue(cert.getId() > 0);
        assertEquals("unencrypted.p12", cert.getName());
        assertTrue(Arrays.equals(data, database.getCertificateData(cert.getId())));
        assertNull(database.getCertificatePassword(cert.getId()));

        database.removeCertificate(cert.getId());
        assertTrue(database.getCertificates().isEmpty());
    }

    @Test
    public void testRemoveServerCleansRelatedRows() {
        Server server = new Server(-1, "n", "h", 64738, "u", null);
        database.addServer(server);
        long id = server.getId();
        database.addPinnedChannel(id, 3);
        database.addAccessToken(id, "t");
        database.addLocalMutedUser(id, 9);
        database.addLocalIgnoredUser(id, 10);

        database.removeServer(server);

        assertTrue(database.getPinnedChannels(id).isEmpty());
        assertTrue(database.getAccessTokens(id).isEmpty());
        assertTrue(database.getLocalMutedUsers(id).isEmpty());
        assertTrue(database.getLocalIgnoredUsers(id).isEmpty());
    }

    // Frozen v9 DDL for the migration fixture: byte-identical to the deleted
    // hand-SQL schema. Do NOT update with future schema versions.
    private static final String V9_SERVER =
            "CREATE TABLE `server` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`name` TEXT NOT NULL,`host` TEXT NOT NULL,`port` INTEGER," +
            "`username` TEXT NOT NULL,`password` TEXT);";
    private static final String V9_FAVOURITES =
            "CREATE TABLE `favourites` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`channel` TEXT NOT NULL,`server` INTEGER NOT NULL);";
    private static final String V9_TOKENS =
            "CREATE TABLE `tokens` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`value` TEXT NOT NULL,`server` INTEGER NOT NULL);";
    private static final String V9_COMMENTS =
            "CREATE TABLE `comments` (`who` TEXT NOT NULL,`comment` TEXT NOT NULL," +
            "`seen` DATE NOT NULL);";
    private static final String V9_LOCAL_MUTE =
            "CREATE TABLE local_mute (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
            "CONSTRAINT server_user UNIQUE(server,user));";
    private static final String V9_LOCAL_IGNORE =
            "CREATE TABLE local_ignore (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
            "CONSTRAINT server_user UNIQUE(server,user));";
    private static final String V9_CERTIFICATES =
            "CREATE TABLE certificates (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`data` BLOB NOT NULL,`name` TEXT NOT NULL,`password` TEXT);";

    @Test
    public void testMigration9To10PreservesData() {
        File dbFile = context.getDatabasePath("migrate-test.db");
        deleteDbFiles(dbFile);
        assertTrue(dbFile.getParentFile().mkdirs() || dbFile.getParentFile().isDirectory());
        SQLiteDatabase v9 = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        v9.execSQL(V9_SERVER);
        v9.execSQL(V9_FAVOURITES);
        v9.execSQL(V9_TOKENS);
        v9.execSQL(V9_COMMENTS);
        v9.execSQL(V9_LOCAL_MUTE);
        v9.execSQL(V9_LOCAL_IGNORE);
        v9.execSQL(V9_CERTIFICATES);
        v9.execSQL("INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n','h',64738,'u','p');");
        v9.execSQL("INSERT INTO `favourites` (`channel`,`server`) VALUES ('3',1);");
        v9.execSQL("INSERT INTO `tokens` (`value`,`server`) VALUES ('tok',1);");
        v9.execSQL("INSERT INTO `comments` (`who`,`comment`,`seen`)" +
                " VALUES ('w','c','2026-01-01');");
        v9.execSQL("INSERT INTO `local_mute` (`server`,`user`) VALUES (1,11);");
        v9.execSQL("INSERT INTO `local_ignore` (`server`,`user`) VALUES (1,21);");
        v9.execSQL("INSERT INTO `certificates` (`data`,`name`,`password`)" +
                " VALUES (x'0102','c.p12','s');");
        v9.setVersion(9);
        v9.close();

        MumlaRoomDatabase migrated = androidx.room.Room.databaseBuilder(
                context, MumlaRoomDatabase.class, "migrate-test.db")
                .addMigrations(MumlaRoomDatabase.getMIGRATION_9_10(),
                        MumlaRoomDatabase.getMIGRATION_10_11())
                .allowMainThreadQueries()
                .build();
        try {
            MumlaDatabase mdb = migrated;
            List<Server> servers = mdb.getServers();
            assertEquals(1, servers.size());
            assertEquals("h", servers.get(0).getHost());
            assertEquals(64738, servers.get(0).getPort());
            assertEquals(1, mdb.getPinnedChannels(1L).size());
            assertEquals(3, (int) mdb.getPinnedChannels(1L).get(0));
            assertEquals("tok", mdb.getAccessTokens(1L).get(0));
            assertTrue(mdb.isCommentSeen("w", "c".getBytes()));
            assertEquals(1, mdb.getLocalMutedUsers(1L).size());
            assertEquals(11, (int) mdb.getLocalMutedUsers(1L).get(0));
            assertEquals(1, mdb.getLocalIgnoredUsers(1L).size());
            assertEquals("c.p12", mdb.getCertificates().get(0).getName());

            // v10 shapes enforced: duplicate mute is ignored (IGNORE mirrors
            // the old insert()), and the PK now guards the table.
            mdb.addLocalMutedUser(1L, 11);
            assertEquals(1, mdb.getLocalMutedUsers(1L).size());

            // AUTOINCREMENT continuity across the rename: the migrated row kept
            // id 1, so the next insert takes 2.
            Server extra = new Server(-1, "m", "h2", 64738, "u", null);
            mdb.addServer(extra);
            assertEquals(2L, extra.getId());
        } finally {
            migrated.close();
            deleteDbFiles(dbFile);
        }
    }

    private static final String V8_CERTIFICATES =
            "CREATE TABLE certificates (`_id` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`data` BLOB NOT NULL,`name` TEXT NOT NULL);";

    @Test
    public void testMigration8To10PreservesData() {
        File dbFile = context.getDatabasePath("migrate-test-8.db");
        deleteDbFiles(dbFile);
        assertTrue(dbFile.getParentFile().mkdirs() || dbFile.getParentFile().isDirectory());
        SQLiteDatabase v8 = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        v8.execSQL(V9_SERVER);
        v8.execSQL(V9_FAVOURITES);
        v8.execSQL(V9_TOKENS);
        v8.execSQL(V9_COMMENTS);
        v8.execSQL(V9_LOCAL_MUTE);
        v8.execSQL(V9_LOCAL_IGNORE);
        v8.execSQL(V8_CERTIFICATES);
        v8.execSQL("INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n','h',64738,'u','p');");
        v8.execSQL("INSERT INTO `certificates` (`data`,`name`) VALUES (x'0102','c.p12');");
        v8.setVersion(8);
        v8.close();

        MumlaRoomDatabase migrated = androidx.room.Room.databaseBuilder(
                context, MumlaRoomDatabase.class, "migrate-test-8.db")
                .addMigrations(MumlaRoomDatabase.getMIGRATION_8_10(),
                        MumlaRoomDatabase.getMIGRATION_9_10(),
                        MumlaRoomDatabase.getMIGRATION_10_11())
                .allowMainThreadQueries()
                .build();
        try {
            MumlaDatabase mdb = migrated;
            assertEquals("h", mdb.getServers().get(0).getHost());
            assertEquals("c.p12", mdb.getCertificates().get(0).getName());
            assertTrue(Arrays.equals(new byte[]{1, 2},
                    mdb.getCertificateData(mdb.getCertificates().get(0).getId())));
            assertNull("v8 certificates had no password column",
                    mdb.getCertificatePassword(mdb.getCertificates().get(0).getId()));
        } finally {
            migrated.close();
            deleteDbFiles(dbFile);
        }
    }

    @Test
    public void testMigration10To11ServerNullability() {
        File dbFile = context.getDatabasePath("migrate-test-10.db");
        deleteDbFiles(dbFile);
        assertTrue(dbFile.getParentFile().mkdirs() || dbFile.getParentFile().isDirectory());
        // v10 shapes (see MIGRATION_9_10): all tables present, server with
        // name/host/username NOT NULL and port nullable.
        SQLiteDatabase v10 = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        v10.execSQL("CREATE TABLE `server` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`name` TEXT NOT NULL,`host` TEXT NOT NULL,`port` INTEGER," +
                "`username` TEXT NOT NULL,`password` TEXT);");
        v10.execSQL("CREATE TABLE `favourites` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`channel` TEXT NOT NULL,`server` INTEGER NOT NULL);");
        v10.execSQL("CREATE TABLE `tokens` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`value` TEXT NOT NULL,`server` INTEGER NOT NULL);");
        v10.execSQL("CREATE TABLE `comments` (`who` TEXT NOT NULL,`comment` TEXT NOT NULL," +
                "`seen` TEXT NOT NULL,PRIMARY KEY(`who`,`comment`,`seen`));");
        v10.execSQL("CREATE TABLE `local_mute` (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
                "PRIMARY KEY(`server`,`user`));");
        v10.execSQL("CREATE TABLE `local_ignore` (`server` INTEGER NOT NULL,`user` INTEGER NOT NULL," +
                "PRIMARY KEY(`server`,`user`));");
        v10.execSQL("CREATE TABLE `certificates` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`data` BLOB NOT NULL,`name` TEXT NOT NULL,`password` TEXT);");
        v10.execSQL("INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n','h',64738,'u','p');");
        v10.execSQL("INSERT INTO `server` (`name`,`host`,`port`,`username`,`password`)" +
                " VALUES ('n2','h2',NULL,'u2',NULL);");
        v10.setVersion(10);
        v10.close();

        MumlaRoomDatabase migrated = androidx.room.Room.databaseBuilder(
                context, MumlaRoomDatabase.class, "migrate-test-10.db")
                .addMigrations(MumlaRoomDatabase.getMIGRATION_10_11())
                .allowMainThreadQueries()
                .build();
        try {
            MumlaDatabase mdb = migrated;
            List<Server> servers = mdb.getServers();
            assertEquals(2, servers.size());
            assertEquals(64738, servers.get(0).getPort());
            // NULL ports coalesce to 0; opening at all proves the v11 shape
            // validates exactly against the entity.
            assertEquals(0, servers.get(1).getPort());
            assertEquals("h2", servers.get(1).getHost());
        } finally {
            migrated.close();
            deleteDbFiles(dbFile);
        }
    }

    private static void deleteDbFiles(File dbFile) {
        new File(dbFile.getPath() + "-wal").delete();
        new File(dbFile.getPath() + "-shm").delete();
        new File(dbFile.getPath() + "-journal").delete();
        dbFile.delete();
    }
}
