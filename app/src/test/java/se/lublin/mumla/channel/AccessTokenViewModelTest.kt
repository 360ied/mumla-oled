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

package se.lublin.mumla.channel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Access-token loading, persistence and live push. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessTokenViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture(initial: List<String> = listOf("alpha")) {
        val stored = HashMap<Long, MutableList<String>>().apply {
            put(SERVER_ID, initial.toMutableList())
        }
        val sent = ArrayList<List<String>>()
        val viewModel = AccessTokenViewModel(
            serverId = SERVER_ID,
            loadTokens = { id -> stored[id].orEmpty().toList() },
            addToken = { id, token -> stored.getOrPut(id) { ArrayList() }.add(token) },
            // The DAO deletes every row with the value.
            removeToken = { id, token -> stored[id]?.removeAll { it == token } },
            sendTokens = { tokens -> sent.add(tokens) },
        )
    }

    @Test
    fun loadsTheStoredTokens() = runTest {
        val fixture = Fixture(initial = listOf("alpha", "beta"))
        assertEquals(listOf("alpha", "beta"), fixture.viewModel.tokens.value)
    }

    @Test
    fun aFailedLoadLeavesAnEmptyList() = runTest {
        val viewModel = AccessTokenViewModel(
            serverId = SERVER_ID,
            loadTokens = { throw IllegalStateException("db closed") },
            addToken = { _, _ -> },
            removeToken = { _, _ -> },
            sendTokens = {},
        )
        assertTrue(viewModel.tokens.value.isEmpty())
    }

    @Test
    fun addTrimsPersistsAndSendsTheFullList() = runTest {
        val fixture = Fixture()
        fixture.viewModel.add("  beta  ")
        assertEquals(listOf("alpha", "beta"), fixture.viewModel.tokens.value)
        assertEquals(listOf("alpha", "beta"), fixture.stored[SERVER_ID])
        assertEquals(listOf(listOf("alpha", "beta")), fixture.sent)
    }

    @Test
    fun anEditMadeWhileLoadingIsNotLost() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stored = mutableListOf("alpha")
        val sent = ArrayList<List<String>>()
        val viewModel = AccessTokenViewModel(
            serverId = SERVER_ID,
            loadTokens = { gate.await(); stored.toList() },
            addToken = { _, token -> stored.add(token) },
            removeToken = { _, token -> stored.removeAll { it == token } },
            sendTokens = { tokens -> sent.add(tokens) },
        )
        // The load is suspended; the edit waits for it instead of racing it.
        viewModel.add("beta")
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("alpha", "beta"), viewModel.tokens.value)
        assertEquals(listOf(listOf("alpha", "beta")), sent)
    }

    @Test
    fun blankInputIsIgnored() = runTest {
        val fixture = Fixture()
        fixture.viewModel.add("   ")
        assertEquals(listOf("alpha"), fixture.viewModel.tokens.value)
        assertTrue(fixture.sent.isEmpty())
        assertEquals(listOf("alpha"), fixture.stored[SERVER_ID])
    }

    @Test
    fun removeDeletesEveryOccurrenceAsTheStoreDoes() = runTest {
        val fixture = Fixture(initial = listOf("alpha", "beta", "alpha"))
        fixture.viewModel.remove("alpha")
        assertEquals(listOf("beta"), fixture.viewModel.tokens.value)
        assertEquals(fixture.stored[SERVER_ID], fixture.viewModel.tokens.value)
        assertEquals(listOf(listOf("beta")), fixture.sent)
    }

    @Test
    fun removingAnUnknownTokenChangesNothing() = runTest {
        val fixture = Fixture()
        fixture.viewModel.remove("missing")
        assertEquals(listOf("alpha"), fixture.viewModel.tokens.value)
        assertTrue(fixture.sent.isEmpty())
    }

    private companion object {
        const val SERVER_ID = 7L
    }
}
