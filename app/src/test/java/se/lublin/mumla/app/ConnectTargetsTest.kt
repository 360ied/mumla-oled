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

package se.lublin.mumla.app

import android.content.Context
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.humla.model.Server
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.TestDataStores
import se.lublin.mumla.TestSettings
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.util.MumlaTrustStore

/**
 * The connect target freeze: saved-server re-read, server snapshot,
 * certificate and token mapping, force-TCP, client name, and trust-store
 * path validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectTargetsTest {

    private lateinit var context: Context
    private lateinit var database: MumlaRoomDatabase
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, MumlaRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = TestSettings.create(TestDataStores.create())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun build(server: Server) = runBlocking {
        ConnectTargets.build(context, database, settings, server)
    }

    @Test
    fun savedServerIsReReadFromTheDatabase() {
        val stored = Server(-1L, "Saved", "saved.example.org", 64738, "alice", null)
        database.addServer(stored)
        // The handed-in row is stale; the database is the source of truth.
        val stale = Server(stored.id, "Stale", "stale.example.org", 64739, "bob", null)

        val target = build(stale)!!

        assertEquals(stored.id, target.server.serverId)
        assertEquals("Saved", target.server.name)
        assertEquals("saved.example.org", target.server.host)
        assertEquals(64738, target.server.port)
        assertEquals("alice", target.server.username)
    }

    @Test
    fun unsavedServerIsUsedAsGiven() {
        val server = Server(-1L, "Ad hoc", "adhoc.example.org", 64738, "bob", "pw")

        val target = build(server)!!

        assertEquals(-1L, target.server.serverId)
        assertEquals("adhoc.example.org", target.server.host)
        assertEquals("pw", target.server.password)
    }

    @Test
    fun aMissingServerOrHostYieldsNoTarget() {
        assertNull(build(Server(999L, "Gone", "gone.example.org", 64738, "alice", null)))
        assertNull(build(Server(-1L, "No host", null, 64738, "alice", null)))
    }

    @Test
    fun accessTokensAreFrozenIntoTheTarget() {
        val server = Server(-1L, "Ad hoc", "adhoc.example.org", 64738, "alice", null)
        database.addServer(server)
        database.addAccessToken(server.id, "token-one")
        database.addAccessToken(server.id, "token-two")

        val target = build(server)!!

        assertEquals(listOf("token-one", "token-two"), target.accessTokens)
    }

    @Test
    fun certificateIsMappedWhenTheSettingUsesOne() {
        val server = Server(-1L, "Ad hoc", "adhoc.example.org", 64738, "alice", null)
        database.addServer(server)
        val certificate = database.addCertificate("client", byteArrayOf(1, 2, 3), "secret")
        settings.defaultCertificateId = certificate.id

        val target = build(server)!!

        assertNotNull(target.certificate)
        assertEquals(3, target.certificate?.pkcs12?.size())
        assertEquals("secret", target.certificate?.password)
        assertTrue(target.certificate.toString().contains("redacted"))

        settings.disableCertificate()
        assertNull(build(server)?.certificate)
    }

    @Test
    fun forceTcpAndClientNameComeFromTheSettings() {
        val server = Server(-1L, "Ad hoc", "adhoc.example.org", 64738, "alice", null)
        database.addServer(server)
        settings.repository.updateTyped { it.forceTcp = true }

        val target = build(server)!!

        assertTrue(target.forceTcp)
        assertEquals(
            context.getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
            target.clientName,
        )
    }

    @Test
    fun trustStoreIsUsedOnlyWhenInitialized() {
        val server = Server(-1L, "Ad hoc", "adhoc.example.org", 64738, "alice", null)
        database.addServer(server)
        assertNull("no store file yet", build(server)?.trustStore)

        File(context.filesDir, "mumla-store.bks").writeBytes(ByteArray(0))
        val target = build(server)!!
        assertNotNull(target.trustStore)
        assertEquals(
            MumlaTrustStore.getTrustStorePassword(),
            target.trustStore?.password,
        )
        assertEquals(MumlaTrustStore.getTrustStoreFormat(), target.trustStore?.format)
    }

    @Test
    fun trustStorePathValidation() {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
        assertTrue("null is allowed", ConnectTargets.isTrustStorePathAllowed(filesDir, null))
        assertTrue(
            ConnectTargets.isTrustStorePathAllowed(filesDir, File(filesDir, "store.bks").path),
        )
        assertTrue(
            ConnectTargets.isTrustStorePathAllowed(
                filesDir,
                File(filesDir, "nested/upper.BKS").path,
            ),
        )
        assertFalse(
            "only .bks files are allowed",
            ConnectTargets.isTrustStorePathAllowed(filesDir, File(filesDir, "store.p12").path),
        )
        assertFalse(
            "paths outside the files directory are rejected",
            ConnectTargets.isTrustStorePathAllowed(filesDir, "/data/local/tmp/store.bks"),
        )
        assertFalse(
            "a prefix-sharing sibling is not under the directory",
            ConnectTargets.isTrustStorePathAllowed(filesDir, filesDir.path + "-evil/store.bks"),
        )
        assertFalse(
            "no files directory means no allowed path",
            ConnectTargets.isTrustStorePathAllowed(null, "store.bks"),
        )
    }
}
