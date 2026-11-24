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

import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the step-6 settings contract: one in-memory value with raw keys and a
 * typed snapshot published together, read-your-writes without blocking, and
 * a single writer that converges the DataStore to the latest value.
 */
class SettingsCurrentValueTest {
    @Test
    fun updatePublishesRawAndSnapshotTogether() {
        val settings = TestSettings.create(TestDataStores.create())
        val repository = settings.repository
        assertFalse(repository.state.value.snapshot.overlayShown)

        settings.isOverlayShown = true

        assertTrue(repository.state.value.snapshot.overlayShown)
        assertEquals(
            true,
            repository.state.value.preferences[booleanPreferencesKey(Settings.PREF_OVERLAY_SHOWN)],
        )
        assertTrue(settings.isOverlayShown)
    }

    @Test
    fun writerConvergesStoreToLatestInMemoryValue() = runBlocking(Dispatchers.IO) {
        val store = TestDataStores.create()
        val settings = TestSettings.create(store)
        val key = booleanPreferencesKey(Settings.PREF_CHAT_NOTIFY)

        repeat(20) { index ->
            settings.repository.update { it[key] = index % 2 == 0 }
        }
        val expected = settings.repository.state.value.preferences

        val deadline = System.currentTimeMillis() + 5_000
        var stored = store.data.first()
        while (stored != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            stored = store.data.first()
        }
        assertEquals(expected, stored)
    }

    @Test
    fun startupReadLoadsValuesWrittenBeforeConstruction() = runBlocking(Dispatchers.IO) {
        val store = TestDataStores.create()
        TestDataStores.put(store, booleanPreferencesKey(Settings.PREF_OVERLAY_SHOWN), true)

        val settings = TestSettings.create(store)

        assertTrue(settings.isOverlayShown)
        assertEquals(store.data.first(), settings.repository.state.value.preferences)
    }
}
