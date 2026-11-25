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

import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.protobuf.ByteString
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import se.lublin.humla.model.TalkState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.applyChannelState
import se.lublin.humla.state.applyServerSync
import se.lublin.humla.state.applyUserRemove
import se.lublin.humla.state.applyUserState
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.R

/**
 * The screen-level spike the migration asks for: the real adapter bound in a
 * Robolectric view hierarchy, driven through the pure state transitions, with
 * the bound rows asserted after each change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelListAdapterTest {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelListAdapter
    private var state: SessionState = emptySessionState()
    private var talk: TalkActivity = TalkActivity.initial()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        recycler = RecyclerView(activity).also { activity.setContentView(it) }
        recycler.layoutManager = LinearLayoutManager(activity)
        adapter = newAdapter(pinnedOnly = false)
        recycler.adapter = adapter
    }

    private fun newAdapter(pinnedOnly: Boolean): ChannelListAdapter = ChannelListAdapter(
        context = recycler.context,
        showPinnedOnly = pinnedOnly,
        onChannelClick = {},
        onUserClick = {},
        onChannelJoin = {},
        onChannelMenu = { _, _ -> },
        onUserMenu = { _, _ -> },
    )

    /** Two passes: the second binds the holders the first pass created. */
    private fun layout() {
        repeat(2) {
            recycler.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY),
            )
            recycler.layout(0, 0, 1080, 4000)
        }
    }

    // Scripted steps over the real transitions.

    private fun addChannel(id: Int, name: String) {
        var builder = Mumble.ChannelState.newBuilder().setChannelId(id).setName(name)
        if (id != 0) {
            builder = builder.setParent(0)
        }
        state = applyChannelState(state, builder.build()).state
    }

    private fun sync(selfSession: Int = 1) {
        state = applyServerSync(
            state,
            Mumble.ServerSync.newBuilder().setSession(selfSession).build(),
        ).state
    }

    private fun join(session: Int, name: String, channelId: Int = 0) {
        state = applyUserState(
            state,
            Mumble.UserState.newBuilder()
                .setSession(session)
                .setName(name)
                .setChannelId(channelId)
                .build(),
        ).state
    }

    private fun move(session: Int, channelId: Int) {
        state = applyUserState(
            state,
            Mumble.UserState.newBuilder().setSession(session).setChannelId(channelId).build(),
        ).state
    }

    private fun setMute(session: Int, muted: Boolean) {
        state = applyUserState(
            state,
            Mumble.UserState.newBuilder().setSession(session).setMute(muted).build(),
        ).state
    }

    private fun listen(channelId: Int) {
        state = applyUserState(
            state,
            Mumble.UserState.newBuilder().setSession(1).addListeningChannelAdd(channelId).build(),
        ).state
    }

    private fun leave(session: Int) {
        state = applyUserState(
            state,
            Mumble.UserState.newBuilder().setSession(session).setName("").build(),
        ).state
        state = applyUserRemove(
            state,
            Mumble.UserRemove.newBuilder().setSession(session).build(),
        ).state
    }

    private fun submit() {
        adapter.submit(
            ChannelListUiState(
                connected = true,
                session = state,
                talkActivity = talk,
                pinnedChannelIds = emptyList(),
                showUserCount = false,
            ),
        )
        layout()
    }

    private fun row(position: Int): View {
        val holder = recycler.findViewHolderForAdapterPosition(position)
            ?: throw AssertionError("no bound holder at $position; rows=${adapter.rows()}")
        return holder.itemView
    }

    private fun channelName(position: Int): String =
        row(position).findViewById<TextView>(R.id.channel_row_name).text.toString()

    private fun userName(position: Int): String =
        row(position).findViewById<TextView>(R.id.user_row_name).text.toString()

    private fun talkIconRes(position: Int): Int {
        val drawable = row(position).findViewById<ImageView>(R.id.user_row_talk_highlight).drawable
        return Shadows.shadowOf(drawable).createdFromResId
    }

    private fun channelNameTypeface(position: Int): Typeface =
        row(position).findViewById<TextView>(R.id.channel_row_name).typeface
            ?: throw AssertionError("no typeface")

    @Test
    fun scriptedChannelAndUserChangesUpdateTheBoundRows() {
        addChannel(0, "Root")
        addChannel(5, "General")
        addChannel(6, "Away")
        sync(selfSession = 1)
        submit()
        // Empty subchannels stay collapsed; only the root row is shown.
        assertEquals(1, adapter.itemCount)
        assertEquals("Root", channelName(0))

        // Expand the root: its two empty subchannels appear, name-ordered
        // because both carry position 0.
        row(0).findViewById<View>(R.id.channel_row_expand).performClick()
        submit()
        assertEquals(3, adapter.itemCount)
        assertEquals(listOf("Root", "Away", "General"), (0..2).map(::channelName))

        // Alice joins the root: the user row sits under it.
        join(2, "Alice")
        submit()
        assertEquals(listOf("Root", "Alice", "Away", "General"), (0..3).map(::channelNameOrUser))
        assertEquals("Alice", userName(1))

        // Self joins and is bold, as the handle adapter rendered it.
        join(1, "Self")
        submit()
        assertEquals(
            Typeface.BOLD,
            channelNameTypeface(0).style and Typeface.BOLD,
        )

        // Alice moves into General: the row moves with her.
        move(2, 5)
        submit()
        assertEquals(listOf("Root", "Self", "Away", "General", "Alice"), (0..4).map(::channelNameOrUser))
        assertEquals("Alice", userName(4))

        // A server mute repaints the row.
        setMute(2, true)
        submit()
        assertEquals(R.drawable.outline_circle_server_muted, talkIconRes(4))
        setMute(2, false)
        submit()
        assertEquals(R.drawable.outline_circle_talking_off, talkIconRes(4))

        // Talking turns the indicator on, and stopping turns it off again.
        talk = TalkActivity.initial().copy(users = persistentMapOf(2 to TalkState.TALKING))
        submit()
        assertEquals(R.drawable.outline_circle_talking_on, talkIconRes(4))
        talk = TalkActivity.initial()
        submit()
        assertEquals(R.drawable.outline_circle_talking_off, talkIconRes(4))

        // The self row follows selfTalking, the f6244c86 regression at screen level.
        talk = TalkActivity.initial().copy(selfTalking = true)
        submit()
        assertEquals(R.drawable.outline_circle_talking_on, talkIconRes(1))
        talk = TalkActivity.initial()
        submit()
        assertEquals(R.drawable.outline_circle_talking_off, talkIconRes(1))

        // Listening marks the channel row.
        listen(6)
        submit()
        assertEquals(
            View.VISIBLE,
            row(2).findViewById<View>(R.id.channel_row_listening).visibility,
        )

        // Leaving removes the row.
        leave(2)
        submit()
        assertEquals(listOf("Root", "Self", "Away", "General"), (0..3).map(::channelNameOrUser))
    }

    @Test
    fun talkChangesRepaintWithoutRebuildingTheRows() {
        addChannel(0, "Root")
        sync(selfSession = 1)
        join(2, "Alice")
        submit()
        val before = adapter.rows()
        talk = TalkActivity.initial().copy(users = persistentMapOf(2 to TalkState.TALKING))
        submit()
        assertEquals("row identity is stable", before.map { it.stableId }, adapter.rows().map { it.stableId })
        assertEquals(TalkState.TALKING, (adapter.rows()[1] as ChannelListAdapter.Row.UserRow).talkState)
    }

    @Test
    fun valueChangesRebindTheSameHolderWithoutAChangeAnimation() {
        addChannel(0, "Root")
        sync(selfSession = 1)
        join(2, "Alice")
        submit()
        val holder = recycler.findViewHolderForAdapterPosition(1)
        talk = TalkActivity.initial().copy(users = persistentMapOf(2 to TalkState.TALKING))
        submit()
        // A payload-less change makes DefaultItemAnimator cross-fade a second holder.
        assertSame("talk change rebinds in place", holder, recycler.findViewHolderForAdapterPosition(1))
        assertEquals(R.drawable.outline_circle_talking_on, talkIconRes(1))
        assertFalse("no change animation", recycler.itemAnimator!!.isRunning)
    }

    @Test
    fun everyIconBranchAndItsPrecedence() {
        addChannel(0, "Root")
        join(5, "Eve")
        val base = state.server.users.getValue(5)
        val cases = listOf(
            // Precedence: self-deafened beats everything, then server deafen,
            // self mute, server mute, suppression, then the talk level.
            base.copy(selfDeafened = true, muted = true) to TalkState.TALKING to R.drawable.outline_circle_deafened,
            base.copy(deafened = true, selfMuted = true) to TalkState.TALKING to R.drawable.outline_circle_server_deafened,
            base.copy(selfMuted = true, muted = true) to TalkState.TALKING to R.drawable.outline_circle_muted,
            base.copy(muted = true, suppressed = true) to TalkState.TALKING to R.drawable.outline_circle_server_muted,
            base.copy(suppressed = true) to TalkState.TALKING to R.drawable.outline_circle_suppressed,
            base to TalkState.TALKING to R.drawable.outline_circle_talking_on,
            base to TalkState.SHOUTING to R.drawable.outline_circle_talking_on,
            base to TalkState.WHISPERING to R.drawable.outline_circle_talking_on,
            base to TalkState.PASSIVE to R.drawable.outline_circle_talking_off,
        )
        for ((input, expected) in cases) {
            val (user, talkState) = input
            val drawable = adapter.getTalkStateDrawable(user, talkState)!!
            assertEquals("$talkState $user", expected, Shadows.shadowOf(drawable).createdFromResId)
        }
        assertEquals(
            R.drawable.outline_circle_talking_off,
            Shadows.shadowOf(adapter.getTalkStateDrawable(null, TalkState.TALKING)!!).createdFromResId,
        )
    }

    @Test
    fun pinnedOnlyModeUsesThePinnedRoots() {
        addChannel(0, "Root")
        addChannel(5, "General")
        addChannel(6, "Away")
        join(2, "Alice", 6)
        adapter = newAdapter(pinnedOnly = true)
        recycler.adapter = adapter
        adapter.submit(
            ChannelListUiState(
                connected = true,
                session = state,
                talkActivity = talk,
                pinnedChannelIds = listOf(6),
                showUserCount = false,
            ),
        )
        layout()
        assertEquals(listOf(ChannelListAdapter.CHANNEL_ID_MASK or 6L, ChannelListAdapter.USER_ID_MASK or 2L),
            adapter.rows().map { it.stableId })
        assertEquals("Away", channelName(0))
        assertEquals("Alice", userName(1))
    }

    private fun channelNameOrUser(position: Int): String {
        val view = row(position)
        return view.findViewById<TextView>(R.id.channel_row_name)?.text?.toString()
            ?: view.findViewById<TextView>(R.id.user_row_name)?.text?.toString()
            ?: ""
    }

    @Test
    fun userCountColumnFollowsTheSetting() {
        addChannel(0, "Root")
        addChannel(5, "General")
        sync(selfSession = 1)
        join(2, "Alice", 5)
        fun submitWithCount(show: Boolean) {
            adapter.submit(
                ChannelListUiState(
                    connected = true,
                    session = state,
                    talkActivity = talk,
                    pinnedChannelIds = emptyList(),
                    showUserCount = show,
                ),
            )
            layout()
        }
        submitWithCount(true)
        var rootCount = row(0).findViewById<TextView>(R.id.channel_row_count)
        assertEquals(View.VISIBLE, rootCount.visibility)
        assertEquals("1", rootCount.text.toString())

        // Rows are unchanged; only the state flag differs, so the adapter
        // must still repaint the column.
        submitWithCount(false)
        rootCount = row(0).findViewById<TextView>(R.id.channel_row_count)
        assertEquals(View.GONE, rootCount.visibility)
    }
}
