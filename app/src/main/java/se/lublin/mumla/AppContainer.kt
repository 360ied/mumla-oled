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

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import se.lublin.humla.client.LocalFactsStore
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.db.MumlaRoomDatabase

/**
 * Composition root: one instance of every application-scoped object, built
 * by hand in [MumlaApplication.onCreate] and never closed. Services, screens,
 * and features borrow these objects; a second service instance in the same
 * process gets the same ones.
 *
 * The single blocking settings read at process start also runs the legacy
 * SharedPreferences migration, so theme and locale are final before the
 * first activity draws. After that, no settings read or write blocks.
 */
open class AppContainer(private val context: Context) {
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        // Fail loudly in debug; report and keep the process alive in release.
        if (BuildConfig.DEBUG) throw RuntimeException(error)
        Log.e(TAG, "Unhandled app-scope failure", error)
    }

    open val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    open val settingsStore: DataStore<Preferences> = createSettingsStore(context)

    private val startupPreferences: Preferences = runBlocking { settingsStore.data.first() }

    open val settingsRepository = SettingsRepository(settingsStore, appScope, startupPreferences)

    open val settings = Settings(settingsRepository)

    open val database: MumlaRoomDatabase = MumlaRoomDatabase.create(context)

    open val localFactsStore: LocalFactsStore = AppLocalFactsStore(database, settings)

    private val _configuration = MutableStateFlow(Configuration(context.resources.configuration))
    open val configuration: StateFlow<Configuration> = _configuration.asStateFlow()

    open fun updateConfiguration(next: Configuration) {
        _configuration.value = Configuration(next)
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val SETTINGS_FILE = "settings"

        private fun createSettingsStore(context: Context): DataStore<Preferences> {
            val appContext = context.applicationContext
            val defaultPrefsName = appContext.packageName + "_preferences"
            return PreferenceDataStoreFactory.create(
                migrations = listOf(SharedPreferencesMigration(appContext, defaultPrefsName)),
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { appContext.preferencesDataStoreFile(SETTINGS_FILE) },
            )
        }
    }
}

/** Every component reaches the same composition root through its context. */
val Context.container: AppContainer
    get() = (applicationContext as MumlaApplication).container
