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

package se.lublin.humla.state

import junit.framework.TestCase
import com.google.protobuf.ByteString
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

private fun syncedStateWith(vararg users: UserInfo, selfSession: Int? = null): SessionState {
    var s = emptySessionState()
    s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build()).state
    s = s.copy(server = s.server.copy(phase = SyncPhase.SYNCED, selfSession = selfSession))
    if (users.isNotEmpty()) {
        var map = s.server.users
        for (u in users) map = map.putting(u.session, u)
        s = s.copy(server = s.server.copy(users = map))
    }
    return s
}

private fun userIn(session: Int, name: String, channelId: Int? = 0): UserInfo = UserInfo(
    session = session, userId = null, name = name, channelId = channelId,
    certHash = null, comment = null, commentHash = null, texture = null,
    textureHash = null, muted = false, deafened = false, suppressed = false,
    selfMuted = false, selfDeafened = false, prioritySpeaker = false,
    recording = false,
    listeningChannels = kotlinx.collections.immutable.persistentSetOf(),
    localMuted = false, localIgnored = false,
)

/** Ported from ModelHandlerSelfMuteTest and ModelHandlerUserRemoveTest. */
class UserTransitionsTest : TestCase() {
    @Test
    fun testSelfEchoNeverEmits() {
        var s = syncedStateWith(userIn(1, "self"), selfSession = 1)
        fun echo(mute: Boolean, deaf: Boolean) = Mumble.UserState.newBuilder()
            .setSession(1).setSelfMute(mute).setSelfDeaf(deaf).build()
        var r = applyUserState(s, echo(true, false))
        assertTrue(r.events.isEmpty())
        assertTrue(r.state.server.users[1]?.selfMuted == true)
        s = r.state
        r = applyUserState(s, echo(true, false))
        assertTrue(r.events.isEmpty())
        s = r.state
        r = applyUserState(s, echo(false, false))
        assertTrue(r.events.isEmpty())
        assertFalse(r.state.server.users[1]?.selfMuted == true)
    }

    @Test
    fun testOtherUserSelfMuteInSameChannelEmits() {
        var s = syncedStateWith(userIn(1, "self", 0), userIn(2, "other", 0), selfSession = 1)
        val r = applyUserState(
            s,
            Mumble.UserState.newBuilder().setSession(2).setSelfMute(true).setSelfDeaf(false).build(),
        )
        assertEquals(1, r.events.size)
        val e = r.events[0] as SessionEvent.UserSelfMuteChanged
        assertEquals(2, e.session)
        assertTrue(e.muted)
    }

    @Test
    fun testOtherUserSelfMuteInOtherChannelSilent() {
        var s = syncedStateWith(selfSession = 1)
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(1).setName("A").setParent(0).build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(2).setName("B").setParent(0).build()).state
        var map = s.server.users
        map = map.putting(1, userIn(1, "self", 1))
        map = map.putting(2, userIn(2, "other", 2))
        s = s.copy(server = s.server.copy(users = map))
        val r = applyUserState(
            s,
            Mumble.UserState.newBuilder().setSession(2).setSelfMute(true).build(),
        )
        assertTrue(r.events.isEmpty())
        assertTrue(r.state.server.users[2]?.selfMuted == true)
    }

    @Test
    fun testDeafOnlyPacketCoheres() {
        var s = syncedStateWith(userIn(1, "self", 0), userIn(2, "other", 0), selfSession = 1)
        val r = applyUserState(s, Mumble.UserState.newBuilder().setSession(2).setSelfDeaf(true).build())
        assertTrue(r.state.server.users[2]?.selfMuted == true)
        assertTrue(r.state.server.users[2]?.selfDeafened == true)
        assertEquals(1, r.events.size)
    }

    @Test
    fun testJoinEmitsOnlyAfterSynced() {
        val syncing = emptySessionState()
        val r1 = applyUserState(syncing, Mumble.UserState.newBuilder().setSession(5).setName("n").build())
        assertTrue(r1.events.isEmpty())
        assertNotNull(r1.state.server.users[5])

        val synced = syncedStateWith()
        val r2 = applyUserState(synced, Mumble.UserState.newBuilder().setSession(5).setName("n").build())
        assertEquals(1, r2.events.size)
        assertTrue(r2.events[0] is SessionEvent.UserJoinedServer)
    }

    @Test
    fun testUserRemoveEmitsLeftAndEvicts() {
        var s = syncedStateWith(userIn(42, "Alice", 0), selfSession = 1)
        // Need self present for selfSession 1.
        var map = s.server.users
        map = map.putting(1, userIn(1, "self", 0))
        s = s.copy(server = s.server.copy(users = map))
        val r = applyUserRemove(
            s,
            Mumble.UserRemove.newBuilder().setSession(42).setReason("Left").build(),
        )
        assertNull(r.state.server.users[42])
        assertEquals(1, r.events.size)
        val e = r.events[0] as SessionEvent.UserLeft
        assertEquals("Alice", e.name)
        assertEquals("Left", e.reason)
        assertNull(e.actor)
    }

    @Test
    fun testKickEmitsWithActorInEveryPhase() {
        val syncing = emptySessionState()
        val r = applyUserRemove(
            syncing,
            Mumble.UserRemove.newBuilder().setSession(2).setActor(1).setReason("x").setBan(false).build(),
            userName = "t", actorName = "a",
        )
        assertEquals(1, r.events.size)
        val e = r.events[0] as SessionEvent.UserLeft
        assertEquals(1, e.actor)
        assertEquals("a", e.actorName)
    }

    @Test
    fun testRemoveUnknownUserIsGraceful() {
        val s = syncedStateWith()
        val r = applyUserRemove(s, Mumble.UserRemove.newBuilder().setSession(999).setReason("p").build())
        // Quiet leave still emits after SYNCED with unknown name.
        assertEquals(1, r.events.size)
        assertEquals(s.server.users, r.state.server.users)
    }

    @Test
    fun testSelfRemoveSetsFlag() {
        val s = syncedStateWith(userIn(1, "self", 0), selfSession = 1)
        val r = applyUserRemove(s, Mumble.UserRemove.newBuilder().setSession(1).build())
        assertTrue(r.selfRemoved)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun testMoveEmitsWithFromTo() {
        var s = syncedStateWith(selfSession = 1)
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(1).setName("A").setParent(0).build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(2).setName("B").setParent(0).build()).state
        var map = s.server.users
        map = map.putting(1, userIn(1, "self", 1))
        map = map.putting(2, userIn(2, "u", 1))
        s = s.copy(server = s.server.copy(users = map))
        val r = applyUserState(s, Mumble.UserState.newBuilder().setSession(2).setChannelId(2).build())
        assertEquals(1, r.events.filterIsInstance<SessionEvent.UserMoved>().size)
        val e = r.events.filterIsInstance<SessionEvent.UserMoved>()[0]
        assertEquals(1, e.from)
        assertEquals(2, e.to)
    }

    @Test
    fun testRecordingEmitsOnChangeOnly() {
        var s = syncedStateWith(userIn(1, "self", 0), userIn(2, "u", 0), selfSession = 1)
        var r = applyUserState(s, Mumble.UserState.newBuilder().setSession(2).setRecording(true).build())
        assertEquals(1, r.events.filterIsInstance<SessionEvent.RecordingChanged>().size)
        s = r.state
        r = applyUserState(s, Mumble.UserState.newBuilder().setSession(2).setRecording(true).build())
        assertTrue(r.events.filterIsInstance<SessionEvent.RecordingChanged>().isEmpty())
    }

    @Test
    fun testListeningAddRemoveRoundTrip() {
        var s = syncedStateWith(userIn(1, "self", 0), selfSession = 1)
        var r = applyUserState(
            s,
            Mumble.UserState.newBuilder().setSession(1).addListeningChannelAdd(3).addListeningChannelAdd(4).build(),
        )
        assertEquals(setOf(3, 4), r.state.server.users[1]?.listeningChannels)
        s = r.state
        r = applyUserState(
            s,
            Mumble.UserState.newBuilder().setSession(1).addListeningChannelRemove(3).build(),
        )
        assertEquals(setOf(4), r.state.server.users[1]?.listeningChannels)
    }

    @Test
    fun testUnknownChannelFallsBackToRoot() {
        var s = syncedStateWith(userIn(1, "self", 0), selfSession = 1)
        // User moved to an unknown channel falls back to root; already
        // there, so no move event.
        var r = applyUserState(s, Mumble.UserState.newBuilder().setSession(1).setChannelId(99).build())
        assertEquals(0, r.state.server.users[1]?.channelId)
        assertTrue(r.events.filterIsInstance<SessionEvent.UserMoved>().isEmpty())
        // From another channel the fallback is a real move to root.
        s = applyChannelState(r.state, Mumble.ChannelState.newBuilder().setChannelId(1).setName("A").setParent(0).build()).state
        s = applyUserState(s, Mumble.UserState.newBuilder().setSession(1).setChannelId(1).build()).state
        r = applyUserState(s, Mumble.UserState.newBuilder().setSession(1).setChannelId(99).build())
        assertEquals(0, r.state.server.users[1]?.channelId)
        val moves = r.events.filterIsInstance<SessionEvent.UserMoved>()
        assertEquals(1, moves.size)
        assertEquals(1, moves[0].from)
        assertEquals(0, moves[0].to)
    }

    @Test
    fun testLocalMuteRestoredFromHistory() {
        val s = syncedStateWith()
        val r = applyUserState(
            s,
            Mumble.UserState.newBuilder().setSession(9).setName("reg").setUserId(77).build(),
            mutedUserIds = setOf(77),
            ignoredUserIds = setOf(),
        )
        assertTrue(r.state.server.users[9]?.localMuted == true)
        assertFalse(r.state.server.users[9]?.localIgnored == true)
    }

    // Hash-driven clearing: a *_hash replaces the cached content with null,
    // and content replaces the hash. Server sends one or the other, never
    // both; when both appear the later content branch wins (pinned below).

    @Test
    fun testCommentHashClearsComment() {
        val before = syncedStateWith(userIn(1, "u").copy(comment = "hello", commentHash = null))
        val bytes = ByteString.copyFromUtf8("hash")
        val after = applyUserState(
            before,
            Mumble.UserState.newBuilder().setSession(1).setCommentHash(bytes).build(),
        ).state.server.users[1]
        assertEquals(null, after?.comment)
        assertEquals(bytes, after?.commentHash)
    }

    @Test
    fun testCommentClearsCommentHash() {
        val hash = ByteString.copyFromUtf8("hash")
        val before = syncedStateWith(userIn(1, "u").copy(comment = null, commentHash = hash))
        val after = applyUserState(
            before,
            Mumble.UserState.newBuilder().setSession(1).setComment("hello").build(),
        ).state.server.users[1]
        assertEquals("hello", after?.comment)
        assertEquals(null, after?.commentHash)
    }

    @Test
    fun testTextureHashClearsTexture() {
        val before = syncedStateWith(
            userIn(1, "u").copy(texture = ByteString.copyFromUtf8("png"), textureHash = null),
        )
        val bytes = ByteString.copyFromUtf8("hash")
        val after = applyUserState(
            before,
            Mumble.UserState.newBuilder().setSession(1).setTextureHash(bytes).build(),
        ).state.server.users[1]
        assertEquals(null, after?.texture)
        assertEquals(bytes, after?.textureHash)
    }

    @Test
    fun testTextureClearsTextureHash() {
        val hash = ByteString.copyFromUtf8("hash")
        val before = syncedStateWith(userIn(1, "u").copy(texture = null, textureHash = hash))
        val png = ByteString.copyFromUtf8("png")
        val after = applyUserState(
            before,
            Mumble.UserState.newBuilder().setSession(1).setTexture(png).build(),
        ).state.server.users[1]
        assertEquals(png, after?.texture)
        assertEquals(null, after?.textureHash)
    }

    @Test
    fun testUserHashAndContentInOneMessageKeepsContent() {
        val before = syncedStateWith(userIn(1, "u"))
        val png = ByteString.copyFromUtf8("png")
        val after = applyUserState(
            before,
            Mumble.UserState.newBuilder().setSession(1)
                .setTextureHash(ByteString.copyFromUtf8("hash"))
                .setTexture(png)
                .build(),
        ).state.server.users[1]
        assertEquals(png, after?.texture)
        assertEquals(null, after?.textureHash)
    }
}
