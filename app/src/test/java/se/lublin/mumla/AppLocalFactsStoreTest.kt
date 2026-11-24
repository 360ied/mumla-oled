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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one-writer rule: each mute or ignore toggle reaches the database
 * once, an unsaved server is never written, and a self-mute intent writes
 * settings once. The session is the only decision maker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLocalFactsStoreTest {
    private class FakeDao : LocalFactsDao {
        var writes = 0
        var loads = 0
        val muted = mutableSetOf<Int>()
        val ignored = mutableSetOf<Int>()

        override suspend fun mutedUsers(serverId: Long): List<Int> {
            loads++
            return muted.toList()
        }

        override suspend fun ignoredUsers(serverId: Long): List<Int> {
            loads++
            return ignored.toList()
        }

        override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) {
            writes++
            if (muted) this.muted.add(userId) else this.muted.remove(userId)
        }

        override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) {
            writes++
            if (ignored) this.ignored.add(userId) else this.ignored.remove(userId)
        }
    }

    @Test
    fun oneDatabaseWritePerToggleAndLoadReturnsTheLists() = runTest {
        val dao = FakeDao()
        val store = AppLocalFactsStore(dao, TestSettings.create(TestDataStores.create()))

        store.setLocalMute(1, 42, true)
        store.setLocalIgnore(1, 42, true)
        store.setLocalMute(1, 42, false)
        store.setLocalIgnore(1, 42, false)

        assertEquals(4, dao.writes)
        val facts = store.load(1)
        assertFalse(facts.mutedUserIds.contains(42))
        assertFalse(facts.ignoredUserIds.contains(42))
    }

    @Test
    fun unsavedServerSkipsWritesAndLoadsEmptyLists() = runTest {
        val dao = FakeDao()
        val store = AppLocalFactsStore(dao, TestSettings.create(TestDataStores.create()))

        store.setLocalMute(-1, 42, true)
        store.setLocalIgnore(-1, 42, true)
        val facts = store.load(-1)

        assertEquals(0, dao.writes)
        assertEquals(0, dao.loads)
        assertTrue(facts.mutedUserIds.isEmpty())
        assertTrue(facts.ignoredUserIds.isEmpty())
    }

    @Test
    fun negativeUserIdsSkipWrites() = runTest {
        val dao = FakeDao()
        val store = AppLocalFactsStore(dao, TestSettings.create(TestDataStores.create()))

        store.setLocalMute(1, -1, true)
        store.setLocalIgnore(1, -1, true)
        store.setLocalMute(-1, -2, true)
        store.setLocalIgnore(-1, -2, true)

        assertEquals(0, dao.writes)
        assertTrue(dao.muted.isEmpty())
        assertTrue(dao.ignored.isEmpty())
    }

    @Test
    fun selfMuteIntentWritesSettingsOnce() = runTest(UnconfinedTestDispatcher()) {
        val settings = TestSettings.create(TestDataStores.create())
        val store = AppLocalFactsStore(FakeDao(), settings)
        var updates = 0
        val job = launch { settings.repository.state.drop(1).collect { updates++ } }

        store.setSelfMuteDeaf(true, false)

        assertEquals(1, updates)
        assertTrue(settings.isMuted)
        assertFalse(settings.isDeafened)
        job.cancel()
    }
}
