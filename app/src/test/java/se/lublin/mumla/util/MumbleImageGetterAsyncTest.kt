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

package se.lublin.mumla.util

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.lublin.mumla.AppContainer
import se.lublin.mumla.app.MumlaApplication

/**
 * Pins the coroutine port of the external chat-image fetches
 * (docs/target-architecture/steps/8-clean-up.md, commit 4). One
 * [StandardTestDispatcher] drives both the getter's main dispatcher and its
 * fetch dispatcher, which makes the notification coalescing deterministic:
 * two downloads complete before the queued notification can run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = MumbleImageGetterAsyncTest.TestApplication::class, sdk = [34])
class MumbleImageGetterAsyncTest {

    /** Builds the real container over an in-memory settings store. */
    class TestApplication : MumlaApplication() {
        private val preferencesStore = InMemoryPreferencesStore()

        override fun createContainer(): AppContainer {
            val app = this
            // A getter, not an initializer: AppContainer's startup read runs
            // before a subclass property initializer would.
            return object : AppContainer(app) {
                override val settingsStore: DataStore<Preferences>
                    get() = app.preferencesStore
            }
        }
    }

    private class InMemoryPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val fetched = AtomicInteger(0)
    private val loaded = AtomicInteger(0)
    private lateinit var context: Context

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as MumlaApplication
        context = application
        // External fetches are off by default; the getter only schedules a
        // download when the user enabled them.
        application.container.settingsRepository.updateTyped { it.loadImages = true }
    }

    private fun getter(fetch: (String) -> Bitmap?): MumbleImageGetter = MumbleImageGetter(
        context,
        { loaded.incrementAndGet() },
        fetch,
        dispatcher,
        dispatcher,
    )

    private fun bitmap(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    private fun countingFetch(result: Bitmap?): (String) -> Bitmap? = {
        fetched.incrementAndGet()
        result
    }

    @Test
    fun twoCompletionsInOneTurnNotifyOnce() {
        val bitmap = bitmap()
        val getter = getter(countingFetch(bitmap))

        // Both launches queue on the test dispatcher before anything runs.
        getter.getDrawable("https://one.example/a.png")
        getter.getDrawable("https://two.example/b.png")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, fetched.get())
        assertEquals(1, loaded.get())

        // The cache write happened on the main path: a rebind is a cache hit.
        assertNotNull(getter.getDrawable("https://one.example/a.png"))
        assertEquals(2, fetched.get())
    }

    @Test
    fun failedUrlIsNotFetchedAgain() {
        val getter = getter(countingFetch(null))

        getter.getDrawable("https://down.example/a.png")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fetched.get())

        // The failed set keeps a server-controlled stream of unique URLs from
        // retrying the same one.
        getter.getDrawable("https://down.example/a.png")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fetched.get())
        assertEquals(0, loaded.get())
    }

    @Test
    fun shutdownStopsCallbacksAndNewFetches() {
        val getter = getter(countingFetch(bitmap()))

        getter.getDrawable("https://one.example/a.png")
        getter.shutdown()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, loaded.get())

        // A getDrawable after shutdown starts nothing at all.
        getter.getDrawable("https://two.example/b.png")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, fetched.get())
        assertEquals(0, loaded.get())
    }
}
