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
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the legacy listener bridge (migration slice 2): key diffing, fire,
 * unregister, and duplicate-registration guard. The collector runs on the
 * TestDispatcher; store writes block, so [scheduler.advanceUntilIdle] makes
 * delivery deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsListenerTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var store: DataStore<Preferences>
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        store = TestDataStores.create()
        settings = Settings.createForTesting(store)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun changeFiresKey() {
        val fired = mutableListOf<String?>()
        val listener = Settings.OnSettingChangedListener { fired.add(it) }
        settings.registerListener(listener)
        settings.isOverlayShown = true
        settleUntil { fired.contains(Settings.PREF_OVERLAY_SHOWN) }
        assertTrue(
            "overlay change must fire $PREF_OVERLAY_SHOWN",
            fired.contains(Settings.PREF_OVERLAY_SHOWN),
        )
        settings.unregisterListener(listener)
    }

    @Test
    fun unregisterStopsFire() {
        val fired = mutableListOf<String?>()
        val listener = Settings.OnSettingChangedListener { fired.add(it) }
        settings.registerListener(listener)
        settings.unregisterListener(listener)
        settings.theme = "forceDark"
        scheduler.advanceUntilIdle()
        assertTrue("unregistered listener must stay silent", fired.isEmpty())
    }

    @Test
    fun duplicateRegistrationFiresOnce() {
        val fired = mutableListOf<String?>()
        val listener = Settings.OnSettingChangedListener { fired.add(it) }
        settings.registerListener(listener)
        settings.registerListener(listener)
        settings.theme = "forceDark"
        settleUntil { fired.isNotEmpty() }
        assertEquals("double registration must not double-fire", 1, fired.size)
        settings.unregisterListener(listener)
    }

    /** Advances the test dispatcher, yielding to real IO threads, until the
     * condition holds (bounded): DataStore internals run off-scheduler. */
    private fun settleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            scheduler.advanceUntilIdle()
            if (!condition()) {
                Thread.sleep(10)
            }
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for listener delivery"
            }
        }
    }

    companion object {
        private const val PREF_OVERLAY_SHOWN = Settings.PREF_OVERLAY_SHOWN
    }
}
