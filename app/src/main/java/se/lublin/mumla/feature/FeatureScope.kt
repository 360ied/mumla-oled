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

package se.lublin.mumla.feature

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import se.lublin.mumla.BuildConfig

/**
 * One child scope per feature: Main.immediate (windows, toasts, SoundPool and
 * MediaSession need it), a SupervisorJob so one failing feature stops alone,
 * and the appScope exception rule. A feature that hands slow work to this
 * scope can never suspend the session's event emitter.
 */
fun CoroutineScope.featureChild(): CoroutineScope {
    val handler = CoroutineExceptionHandler { _, error ->
        if (BuildConfig.DEBUG) throw RuntimeException(error)
        Log.e("MumlaFeature", "Unhandled feature failure", error)
    }
    val parent = coroutineContext[Job]
    return CoroutineScope(SupervisorJob(parent) + Dispatchers.Main.immediate + handler)
}
