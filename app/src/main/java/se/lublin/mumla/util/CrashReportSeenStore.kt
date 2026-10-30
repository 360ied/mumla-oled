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

package se.lublin.mumla.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.crashSeenStore by preferencesDataStore(name = "crash_seen")

/**
 * DataStore-backed last-viewed crash report id (single-shot unseen tracking).
 * Read/written on the normal path only — never from the crash handler.
 */
object CrashReportSeenStore {
    private val KEY_LAST_SEEN = stringPreferencesKey("last_seen_report_id")

    suspend fun getLastSeen(context: Context): String? {
        return try {
            context.crashSeenStore.data.map { it[KEY_LAST_SEEN] }.first()
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun setLastSeen(context: Context, id: String) {
        try {
            context.crashSeenStore.edit { it[KEY_LAST_SEEN] = id }
        } catch (_: Throwable) {
        }
    }
}
