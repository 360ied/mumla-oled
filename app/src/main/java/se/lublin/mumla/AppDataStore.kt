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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Single DataStore<Preferences> instance for the app's settings file.
 *
 * Migration slice 2: the fleet's SharedPreferences must survive the move, so
 * the default prefs file (`<package>_preferences`, all keys) migrates via
 * [SharedPreferencesMigration] on first access. Exactly one instance may exist
 * per file per process — every consumer (Hilt graph, legacy [Settings]
 * facade) goes through [get].
 */
object AppDataStore {
    const val FILE_NAME = "settings"

    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: create(appContext).also { instance = it }
        }
    }

    private fun create(appContext: Context): DataStore<Preferences> {
        val defaultPrefsName = appContext.packageName + "_preferences"
        return PreferenceDataStoreFactory.create(
            migrations = listOf(SharedPreferencesMigration(appContext, defaultPrefsName)),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { appContext.preferencesDataStoreFile(FILE_NAME) },
        )
    }
}
