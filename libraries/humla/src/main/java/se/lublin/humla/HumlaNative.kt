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

package se.lublin.humla

/**
 * Single owner of `libhumlaaudio.so`.
 *
 * Every class with `external` members goes through [ensureLoaded] (or
 * [isAvailable] when it must report instead of fail), so there is exactly one
 * load attempt and one place to look when the library is missing — the
 * failure mode that killed the first step-5a attempt.
 *
 * Loading happens once, on first touch of this object; the outcome is cached.
 * [ensureLoaded] throws an [IllegalStateException] carrying the original
 * error, which callers either propagate (fail loudly) or convert into their
 * own failure type. Reporting a load failure as a `VoiceEvent.PipelineError`
 * on release builds lands with the `VoiceEngine` facade (step 5a.3), which
 * owns that event type; JVM tests will inject a fake native layer there
 * instead of catching `UnsatisfiedLinkError`.
 */
object HumlaNative {
    const val LIBRARY_NAME = "humlaaudio"

    private val loadError: Throwable? = try {
        System.loadLibrary(LIBRARY_NAME)
        null
    } catch (t: Throwable) {
        t
    }

    /** True when the library loaded and its entry points are callable. */
    @JvmStatic
    fun isAvailable(): Boolean = loadError == null

    /** Throws when the library failed to load; a no-op once loaded. */
    @JvmStatic
    fun ensureLoaded() {
        val error = loadError ?: return
        throw IllegalStateException(
            "Native audio library (lib$LIBRARY_NAME) failed to load",
            error
        )
    }
}
