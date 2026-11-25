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

package se.lublin.mumla.channel.comment

import com.google.protobuf.ByteString
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.emptySessionState

/** Comment text from state, one-shot blob requests and saving. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture(target: CommentViewModel.Target) {
        val sessionState = MutableStateFlow(emptySessionState())
        var requests = 0
        val saved = ArrayList<String>()
        val viewModel = CommentViewModel(
            target = target,
            sessionState = sessionState,
            requestBlob = { requests++ },
            setComment = { saved.add(it) },
        )

        fun withUser(session: Int = SESSION, comment: String?, hash: ByteString?): Fixture {
            val current = sessionState.value
            val user = UserInfo(
                session = session,
                userId = 1,
                name = "alice",
                channelId = 0,
                certHash = null,
                comment = comment,
                commentHash = hash,
                texture = null,
                textureHash = null,
                muted = false,
                deafened = false,
                suppressed = false,
                selfMuted = false,
                selfDeafened = false,
                prioritySpeaker = false,
                recording = false,
                listeningChannels = persistentSetOf(),
                localMuted = false,
                localIgnored = false,
            )
            sessionState.value = current.copy(
                server = current.server.copy(users = current.server.users.put(session, user)),
            )
            return this
        }

        fun withChannel(id: Int = CHANNEL, description: String?, hash: ByteString?): Fixture {
            val current = sessionState.value
            val channel = ChannelInfo(
                id = id,
                parentId = null,
                name = "General",
                position = 0,
                temporary = false,
                description = description,
                descriptionHash = hash,
                links = persistentSetOf(),
                maxUsers = 0,
                enterRestricted = false,
                canEnter = true,
                permissions = 0,
            )
            sessionState.value = current.copy(
                server = current.server.copy(channels = current.server.channels.put(id, channel)),
            )
            return this
        }
    }

    @Test
    fun userCommentWithTextNeedsNoRequest() {
        val fixture = Fixture(CommentViewModel.Target.User(SESSION))
        fixture.withUser(comment = "<p>hi</p>", hash = ByteString.copyFromUtf8("h"))
        assertEquals("<p>hi</p>", fixture.viewModel.state.value.comment)
        assertEquals(0, fixture.requests)
    }

    @Test
    fun hashWithoutTextRequestsTheBlobOncePerHash() {
        val fixture = Fixture(CommentViewModel.Target.User(SESSION))
        fixture.withUser(comment = null, hash = ByteString.copyFromUtf8("h"))
        assertNull(fixture.viewModel.state.value.comment)
        assertEquals(1, fixture.requests)
        // Later emissions with the same hash must not re-request.
        fixture.withUser(comment = null, hash = ByteString.copyFromUtf8("h"))
        assertEquals(1, fixture.requests)
        // The arriving text replaces the loading state.
        fixture.withUser(comment = "arrived", hash = null)
        assertEquals("arrived", fixture.viewModel.state.value.comment)
    }

    @Test
    fun aCommentChangedWhileOpenIsRequestedAgain() {
        // The server announces a changed comment as a new hash and the state
        // clears the text; the open dialog must fetch it, not stay loading.
        val fixture = Fixture(CommentViewModel.Target.User(SESSION))
        fixture.withUser(comment = null, hash = ByteString.copyFromUtf8("h"))
        fixture.withUser(comment = "first", hash = null)
        fixture.withUser(comment = null, hash = ByteString.copyFromUtf8("h2"))
        assertNull(fixture.viewModel.state.value.comment)
        assertEquals(2, fixture.requests)
        fixture.withUser(comment = "second", hash = null)
        assertEquals("second", fixture.viewModel.state.value.comment)
        assertEquals(2, fixture.requests)
    }

    @Test
    fun aRefusedSaveReachesTheCaller() {
        // The factory refuses channel description edits by throwing; save
        // must not swallow that.
        val viewModel = CommentViewModel(
            target = CommentViewModel.Target.Channel(CHANNEL),
            sessionState = MutableStateFlow(emptySessionState()),
            requestBlob = {},
            setComment = { throw UnsupportedOperationException("read-only") },
        )
        try {
            viewModel.save("text")
            fail("save swallowed the refusal")
        } catch (expected: UnsupportedOperationException) {
        }
    }

    @Test
    fun noHashMeansNoCommentAndNoRequest() {
        val fixture = Fixture(CommentViewModel.Target.User(SESSION))
        fixture.withUser(comment = null, hash = null)
        assertEquals("", fixture.viewModel.state.value.comment)
        assertEquals(0, fixture.requests)
    }

    @Test
    fun unknownTargetIsLoading() {
        val fixture = Fixture(CommentViewModel.Target.User(99))
        assertEquals(null, fixture.viewModel.state.value.comment)
        assertEquals(0, fixture.requests)
    }

    @Test
    fun channelDescriptionFollowsTheSameRules() {
        val fixture = Fixture(CommentViewModel.Target.Channel(CHANNEL))
        fixture.withChannel(description = null, hash = ByteString.copyFromUtf8("h"))
        assertNull(fixture.viewModel.state.value.comment)
        assertEquals(1, fixture.requests)
        fixture.withChannel(description = "the description", hash = ByteString.copyFromUtf8("h"))
        assertEquals("the description", fixture.viewModel.state.value.comment)
    }

    @Test
    fun aTextAlreadyInStateShowsWithoutALoadingFrame() {
        // A paused Main: nothing the ViewModel launches has run yet.
        Dispatchers.setMain(StandardTestDispatcher())
        val populated = Fixture(CommentViewModel.Target.User(SESSION))
            .withUser(comment = "<p>hi</p>", hash = ByteString.copyFromUtf8("h"))
        val viewModel = CommentViewModel(
            target = CommentViewModel.Target.User(SESSION),
            sessionState = populated.sessionState,
            requestBlob = {},
            setComment = {},
        )
        assertEquals("<p>hi</p>", viewModel.state.value.comment)
    }

    @Test
    fun saveForwardsTheEditedText() {
        val fixture = Fixture(CommentViewModel.Target.User(SESSION))
        fixture.viewModel.save("<p>new</p>")
        assertEquals(listOf("<p>new</p>"), fixture.saved)
    }

    private companion object {
        const val SESSION = 3
        const val CHANNEL = 5
    }
}
