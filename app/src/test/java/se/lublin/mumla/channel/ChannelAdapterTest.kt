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

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.protobuf.ByteString
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.textureCacheKey
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.R

/** The overlay HUD adapter: value-keyed rows and talk-state icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelAdapterTest {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelAdapter

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        recycler = RecyclerView(activity).also { activity.setContentView(it) }
        recycler.layoutManager = LinearLayoutManager(activity)
        adapter = ChannelAdapter(activity)
        recycler.adapter = adapter
    }

    private fun user(
        session: Int,
        name: String,
        muted: Boolean = false,
        selfMuted: Boolean = false,
    ): UserInfo = UserInfo(
        session = session,
        userId = session,
        name = name,
        channelId = 0,
        certHash = null,
        comment = null,
        commentHash = null,
        texture = null,
        textureHash = null,
        muted = muted,
        deafened = false,
        suppressed = false,
        selfMuted = selfMuted,
        selfDeafened = false,
        prioritySpeaker = false,
        recording = false,
        listeningChannels = persistentSetOf(),
        localMuted = false,
        localIgnored = false,
    )

    private fun submit(users: List<UserInfo>, talk: TalkActivity = TalkActivity.initial(), self: Int? = 1) {
        adapter.submit(users, talk, self)
        repeat(2) {
            recycler.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            recycler.layout(0, 0, 1080, 2000)
        }
    }

    private fun iconRes(position: Int): Int {
        val holder = recycler.findViewHolderForAdapterPosition(position)
            ?: throw AssertionError("no holder at $position")
        val icon = holder.itemView.findViewById<ImageView>(R.id.user_row_state)
        return Shadows.shadowOf(icon.drawable).createdFromResId
    }

    @Test
    fun rowsAreKeyedBySession() {
        submit(listOf(user(101, "Alice"), user(102, "Bob")))
        assertEquals(2, adapter.itemCount)
        assertEquals(101L, adapter.getItemId(0))
        assertEquals(102L, adapter.getItemId(1))
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(-1))
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(2))
        assertEquals(
            "Alice",
            recycler.findViewHolderForAdapterPosition(0)!!
                .itemView.findViewById<TextView>(R.id.user_row_name).text.toString(),
        )
    }

    @Test
    fun submitWithoutUsersClearsTheRows() {
        submit(emptyList())
        assertEquals(0, adapter.itemCount)
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(0))
    }

    @Test
    fun talkAndMuteStatesPickTheIcon() {
        submit(
            listOf(user(101, "Alice"), user(102, "Bob", muted = true)),
            talk = TalkActivity.initial().copy(
                users = persistentMapOf(101 to TalkState.TALKING),
                selfTalking = true,
            ),
        )
        assertEquals(R.drawable.outline_circle_talking_on, iconRes(0))
        assertEquals(R.drawable.outline_circle_server_muted, iconRes(1))
    }

    @Test
    fun everyIconBranchAndItsPrecedence() {
        val base = user(101, "Alice")
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
    }

    @Test
    fun theAvatarCacheKeysOnTheDerivedTextureKey() {
        val cache = AvatarCache()
        val bytes = byteArrayOf(1, 2, 3)
        val alice = user(101, "Alice").copy(texture = ByteString.copyFrom(bytes))
        val first = cache.get(alice.session, alice.textureCacheKey(), bytes)
        // Equal bytes in a new state value derive the same key: a cache hit,
        // even with no texture to decode.
        val copy = alice.copy(texture = ByteString.copyFrom(bytes.copyOf()))
        assertSame(first, cache.get(copy.session, copy.textureCacheKey(), null))
        // A changed avatar derives a new key, so the stale bitmap is dropped.
        val changed = alice.copy(texture = null, textureHash = ByteString.copyFromUtf8("new"))
        assertNull(cache.get(changed.session, changed.textureCacheKey(), null))
        assertEquals(0, cache.size())
    }

    @Test
    fun aShrinkToNoUsersRemovesEveryRow() {
        submit(listOf(user(101, "Alice"), user(102, "Bob")))
        assertEquals(2, recycler.childCount)
        submit(emptyList())
        assertEquals(0, adapter.itemCount)
        assertEquals(0, recycler.childCount)
    }

    @Test
    fun selfTalkingDrivesTheSelfRow() {
        submit(listOf(user(1, "Self")), talk = TalkActivity.initial().copy(selfTalking = true), self = 1)
        assertEquals(R.drawable.outline_circle_talking_on, iconRes(0))
        submit(listOf(user(1, "Self")), talk = TalkActivity.initial(), self = 1)
        assertEquals(R.drawable.outline_circle_talking_off, iconRes(0))
    }

    @Test
    fun talkChangesRepaintWithoutRebuildingTheRows() {
        submit(listOf(user(101, "Alice")))
        val rounds = adapter.users()
        submit(
            listOf(user(101, "Alice")),
            talk = TalkActivity.initial().copy(users = persistentMapOf(101 to TalkState.TALKING)),
        )
        assertEquals(rounds.map { it.session }, adapter.users().map { it.session })
        assertEquals(R.drawable.outline_circle_talking_on, iconRes(0))
    }

    @Test
    fun aDepartedUsersAvatarLeavesTheCache() {
        // Any texture records an entry (a bitmap, or a negative entry when it
        // does not decode); size() is in KB, so only zero is exact.
        val alice = user(101, "Alice").copy(texture = ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        submit(listOf(alice, user(102, "Bob")))
        adapter.getTalkStateDrawable(alice, TalkState.PASSIVE)
        assertTrue(adapter.getAvatarCache().size() > 0)

        submit(listOf(user(102, "Bob")))

        assertEquals(0, adapter.getAvatarCache().size())
        adapter.clearAvatarCache()
        assertEquals(0, adapter.getAvatarCache().size())
    }

    @Test
    fun nullContextAdapterIsInert() {
        val headless = ChannelAdapter(null)
        assertNull(headless.getTalkStateDrawable(user(1, "Self"), TalkState.PASSIVE))
    }
}
