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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import se.lublin.mumla.AppDataStore

/**
 * `PreferenceDataStore` over the settings DataStore (migration slice 2,
 * dual-bus fix): the `PreferenceFragmentCompat` screens must read and write
 * the same `"settings"` file that `Settings` reads, otherwise UI edits and
 * service state diverge permanently after the one-time migration.
 *
 * Reads fall back to the caller-supplied default (the XML widget default),
 * mirroring `SharedPreferences` semantics. Blocking reads/writes on
 * DataStore's in-memory cache — same interim stance as `Settings` itself.
 */
class DataStorePreferenceDataStore(private val store: DataStore<Preferences>) : PreferenceDataStore() {

    constructor(context: Context) : this(AppDataStore.get(context))

    private fun <T> read(block: suspend (Preferences) -> T): T =
        runBlocking(Dispatchers.IO) { block(store.data.first()) }

    // NPE-exact with a message: platform callers never pass null keys, and a
    // bare `key!!` would throw message-less. Mirrors the file's idiom elsewhere.
    private fun keyOf(key: String?): String =
        key ?: throw NullPointerException("Preference key is null")

    private fun write(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking(Dispatchers.IO) { store.edit(block) }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        read { it[booleanPreferencesKey(keyOf(key))] ?: defValue }

    override fun putBoolean(key: String?, value: Boolean) =
        write { it[booleanPreferencesKey(keyOf(key))] = value }

    override fun getInt(key: String?, defValue: Int): Int =
        read { it[intPreferencesKey(keyOf(key))] ?: defValue }

    override fun putInt(key: String?, value: Int) =
        write { it[intPreferencesKey(keyOf(key))] = value }

    override fun getLong(key: String?, defValue: Long): Long =
        read { it[longPreferencesKey(keyOf(key))] ?: defValue }

    override fun putLong(key: String?, value: Long) =
        write { it[longPreferencesKey(keyOf(key))] = value }

    override fun getFloat(key: String?, defValue: Float): Float =
        read { it[floatPreferencesKey(keyOf(key))] ?: defValue }

    override fun putFloat(key: String?, value: Float) =
        write { it[floatPreferencesKey(keyOf(key))] = value }

    override fun getString(key: String?, defValue: String?): String? =
        read { it[stringPreferencesKey(keyOf(key))] ?: defValue }

    override fun putString(key: String?, value: String?) =
        write {
            if (value == null) it.remove(stringPreferencesKey(keyOf(key)))
            else it[stringPreferencesKey(keyOf(key))] = value
        }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        read { it[stringSetPreferencesKey(keyOf(key))] ?: defValues }

    override fun putStringSet(key: String?, values: Set<String>?) =
        write {
            if (values == null) it.remove(stringSetPreferencesKey(keyOf(key)))
            else it[stringSetPreferencesKey(keyOf(key))] = values
        }
}
