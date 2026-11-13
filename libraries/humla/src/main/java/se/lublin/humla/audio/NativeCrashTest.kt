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

package se.lublin.humla.audio

import se.lublin.humla.HumlaNative

/**
 * Minimal JNI hook exercising the native crash capture path (REASON_CRASH_NATIVE).
 * Test-only: never invoked during normal operation — reachable only via the
 * hidden crash-test gesture plus confirmation.
 *
 * Object (no public constructor): all members are static. The @JvmStatic
 * external keeps the explicit JNI mangling
 * Java_se_lublin_humla_audio_NativeCrashTest_nativeCrash.
 */
object NativeCrashTest {
    /**
     * True when the native library loaded and the crash hook is callable.
     * Reported through [HumlaNative], the single load owner, so any caller
     * (including :app) can probe it.
     */
    @JvmStatic
    fun isAvailable(): Boolean {
        return HumlaNative.isAvailable()
    }

    @JvmStatic
    external fun nativeCrash()
}
