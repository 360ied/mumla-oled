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

package se.lublin.mumla.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import se.lublin.mumla.TestDataStores

/**
 * Covers the DataStore-backed `PreferenceDataStore` bridge: typed round-trips
 * plus null-removal, over a real Preferences DataStore (pedantic-review P2).
 */
class DataStorePreferenceDataStoreTest {
    private lateinit var store: DataStore<Preferences>
    private lateinit var dataStore: DataStorePreferenceDataStore

    @Before
    fun setUp() {
        store = TestDataStores.create()
        dataStore = DataStorePreferenceDataStore(store)
    }

    @Test
    fun booleanRoundTrip() {
        dataStore.putBoolean("flag", true)
        assertEquals(true, dataStore.getBoolean("flag", false))
    }

    @Test
    fun intFallsBackToDefault() {
        assertEquals(7, dataStore.getInt("missing", 7))
        dataStore.putInt("count", 3)
        assertEquals(3, dataStore.getInt("count", 7))
    }

    @Test
    fun nullStringRemovesKey() {
        dataStore.putString("name", "x")
        assertEquals("x", dataStore.getString("name", null))
        dataStore.putString("name", null)
        assertNull(dataStore.getString("name", null))
    }

    @Test(expected = NullPointerException::class)
    fun nullKeyThrowsNpeWithMessage() {
        dataStore.getBoolean(null, false)
    }
}
