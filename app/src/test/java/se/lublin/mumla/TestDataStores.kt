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

package se.lublin.mumla

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Test double replacing `FakeSharedPreferences` (migration slice 2): a real
 * Preferences DataStore per test, backed by a temp file that is never
 * migrated (no SharedPreferencesMigration — tests start empty).
 *
 * All entry points block on `Dispatchers.IO`, so plain-JVM JUnit tests can
 * use them directly with no test dispatcher.
 *
 * Lifetime: one temp dir + SupervisorJob scope per test. Temp dirs are
 * removed by a JVM shutdown hook (deleteOnExit cannot remove non-empty
 * dirs, and the DataStore writes its file inside); scopes are never
 * cancelled mid-suite. Fine for a unit suite; do not reuse this pattern for
 * long-lived processes.
 */
object TestDataStores {
    @JvmStatic
    fun create(): DataStore<Preferences> {
        val dir = Files.createTempDirectory("datastore-test").toFile()
        // Recursive delete at JVM exit: deleteOnExit() alone never removes a
        // non-empty directory, so suites would accumulate /tmp/datastore-test*.
        Runtime.getRuntime().addShutdownHook(Thread({ dir.deleteRecursively() }, "datastore-test-cleanup"))
        val file = File(dir, "settings.preferences_pb")
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file },
        )
    }

    @JvmStatic
    fun <T> put(store: DataStore<Preferences>, key: Preferences.Key<T>, value: T) =
        runBlocking(Dispatchers.IO) {
            store.edit { prefs -> prefs[key] = value }
        }

    @JvmStatic
    fun <T> remove(store: DataStore<Preferences>, key: Preferences.Key<T>) =
        runBlocking(Dispatchers.IO) {
            store.edit { prefs -> prefs.remove(key) }
        }

    @JvmStatic
    fun <T> get(store: DataStore<Preferences>, key: Preferences.Key<T>, defaultValue: T): T =
        runBlocking(Dispatchers.IO) {
            store.data.first()[key] ?: defaultValue
        }

    // String-keyed helpers so Java tests need no key-factory imports.
    @JvmStatic
    fun booleanKey(name: String): Preferences.Key<Boolean> = booleanPreferencesKey(name)

    @JvmStatic
    fun intKey(name: String): Preferences.Key<Int> = intPreferencesKey(name)

    @JvmStatic
    fun longKey(name: String): Preferences.Key<Long> = longPreferencesKey(name)

    @JvmStatic
    fun stringKey(name: String): Preferences.Key<String> = stringPreferencesKey(name)

    @JvmStatic
    fun stringSetKey(name: String): Preferences.Key<Set<String>> = stringSetPreferencesKey(name)
}
