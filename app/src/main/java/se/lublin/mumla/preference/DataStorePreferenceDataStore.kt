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

    // Key cache: Preferences.Key equality is name-based, so a fresh key
    // object per get/put is correct but wasteful under rapid-tap
    // SeekBar/KeySelect writes; cache per (type, name) like
    // SettingsRepository.Keys.
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, Preferences.Key<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun booleanKey(name: String): Preferences.Key<Boolean> =
        keyCache.getOrPut("b/$name") { booleanPreferencesKey(name) } as Preferences.Key<Boolean>

    @Suppress("UNCHECKED_CAST")
    private fun intKey(name: String): Preferences.Key<Int> =
        keyCache.getOrPut("i/$name") { intPreferencesKey(name) } as Preferences.Key<Int>

    @Suppress("UNCHECKED_CAST")
    private fun longKey(name: String): Preferences.Key<Long> =
        keyCache.getOrPut("l/$name") { longPreferencesKey(name) } as Preferences.Key<Long>

    @Suppress("UNCHECKED_CAST")
    private fun floatKey(name: String): Preferences.Key<Float> =
        keyCache.getOrPut("f/$name") { floatPreferencesKey(name) } as Preferences.Key<Float>

    @Suppress("UNCHECKED_CAST")
    private fun stringKey(name: String): Preferences.Key<String> =
        keyCache.getOrPut("s/$name") { stringPreferencesKey(name) } as Preferences.Key<String>

    @Suppress("UNCHECKED_CAST")
    private fun stringSetKey(name: String): Preferences.Key<Set<String>> =
        keyCache.getOrPut("ss/$name") { stringSetPreferencesKey(name) } as Preferences.Key<Set<String>>

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
        read { it[booleanKey(keyOf(key))] ?: defValue }

    override fun putBoolean(key: String?, value: Boolean) =
        write { it[booleanKey(keyOf(key))] = value }

    override fun getInt(key: String?, defValue: Int): Int =
        read { it[intKey(keyOf(key))] ?: defValue }

    override fun putInt(key: String?, value: Int) =
        write { it[intKey(keyOf(key))] = value }

    override fun getLong(key: String?, defValue: Long): Long =
        read { it[longKey(keyOf(key))] ?: defValue }

    override fun putLong(key: String?, value: Long) =
        write { it[longKey(keyOf(key))] = value }

    override fun getFloat(key: String?, defValue: Float): Float =
        read { it[floatKey(keyOf(key))] ?: defValue }

    override fun putFloat(key: String?, value: Float) =
        write { it[floatKey(keyOf(key))] = value }

    override fun getString(key: String?, defValue: String?): String? =
        read { it[stringKey(keyOf(key))] ?: defValue }

    override fun putString(key: String?, value: String?) =
        write {
            if (value == null) it.remove(stringKey(keyOf(key)))
            else it[stringKey(keyOf(key))] = value
        }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        read { it[stringSetKey(keyOf(key))] ?: defValues }

    override fun putStringSet(key: String?, values: Set<String>?) =
        write {
            if (values == null) it.remove(stringSetKey(keyOf(key)))
            else it[stringSetKey(keyOf(key))] = values
        }
}
