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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Test seam for the container's settings repository: the startup read is the
 * only blocking call, exactly as in [AppContainer]. Writes after construction
 * go through [SettingsRepository.update], so the in-memory value stays the
 * single source of truth instead of a separate DataStore write.
 */
object TestSettings {
    @JvmStatic
    fun repository(store: DataStore<Preferences>): SettingsRepository {
        val initial = runBlocking(Dispatchers.IO) { store.data.first() }
        return SettingsRepository(
            store,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            initial,
        )
    }

    @JvmStatic
    fun create(store: DataStore<Preferences>): Settings = Settings(repository(store))

    @JvmStatic
    fun <T> put(settings: Settings, key: Preferences.Key<T>, value: T) =
        settings.repository.update { it[key] = value }

    @JvmStatic
    fun <T> remove(settings: Settings, key: Preferences.Key<T>) =
        settings.repository.update { it.remove(key) }

    @JvmStatic
    fun <T> get(settings: Settings, key: Preferences.Key<T>, defaultValue: T): T =
        settings.repository.state.value.preferences[key] ?: defaultValue
}
