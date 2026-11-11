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
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

/** Ported coverage for sync, config, version, codec, query, text, and deny paths. */
class ServerTransitionsTest : TestCase() {
    @Test
    fun testPermissionQueryFlushAndRoot() {
        var s = emptySessionState()
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(0).setName("R").build()).state
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(1).setName("A").setParent(0).build()).state
        s = applyPermissionQuery(s, Mumble.PermissionQuery.newBuilder().setChannelId(0).setPermissions(7).build()).state
        assertEquals(7, s.server.channels[0]?.permissions)
        assertEquals(7, s.server.rootPermissions)
        s = applyPermissionQuery(s, Mumble.PermissionQuery.newBuilder().setChannelId(1).setPermissions(3).build()).state
        assertEquals(3, s.server.channels[1]?.permissions)
        assertEquals(7, s.server.rootPermissions)
        s = applyPermissionQuery(s, Mumble.PermissionQuery.newBuilder().setChannelId(1).setPermissions(3).setFlush(true).build()).state
        assertEquals(0, s.server.channels[0]?.permissions)
        assertEquals(3, s.server.channels[1]?.permissions)
        // Root permissions track only explicit root queries, not the flush reset.
        assertEquals(7, s.server.rootPermissions)
    }

    @Test
    fun testServerSyncPublishesSelfAndWelcome() {
        var s = emptySessionState()
        val r = applyServerSync(
            s,
            Mumble.ServerSync.newBuilder().setSession(42).setPermissions(7)
                .setMaxBandwidth(72000).setWelcomeText("hi").build(),
        )
        assertEquals(SyncPhase.SYNCED, r.state.server.phase)
        assertEquals(42, r.state.server.selfSession)
        assertEquals(7, r.state.server.rootPermissions)
        assertEquals(72000, r.state.server.info.maxBandwidth)
        assertEquals("hi", r.state.server.info.welcomeText)
        assertEquals(1, r.events.filterIsInstance<SessionEvent.WelcomeText>().size)
    }

    @Test
    fun testServerConfigSetsSettingsAndBandwidth() {
        val s = emptySessionState()
        val r = applyServerConfig(
            s,
            Mumble.ServerConfig.newBuilder().setAllowHtml(true).setMessageLength(5000)
                .setImageMessageLength(131072).setMaxUsers(100)
                .setWelcomeText("w").setMaxBandwidth(48000).build(),
        )
        val settings = r.state.server.info.settings
        assertNotNull(settings)
        assertTrue(settings!!.allowHtml)
        assertEquals(5000, settings.messageLength)
        assertEquals("w", r.state.server.info.welcomeText)
        assertEquals(48000, r.state.server.info.maxBandwidth)
    }

    @Test
    fun testVersionV1ConvertsToV2() {
        val s = emptySessionState()
        val legacy = (1 shl 16) or (2 shl 8) or 3
        val r = applyVersion(
            s,
            Mumble.Version.newBuilder().setVersionV1(legacy)
                .setRelease("r").setOs("o").setOsVersion("ov").build(),
        )
        assertNotNull(r.state.server.info.versionV2)
        assertEquals("r", r.state.server.info.release)
        assertEquals("o", r.state.server.info.os)
        val r2 = applyVersion(s, Mumble.Version.newBuilder().setVersionV2(123L).build())
        assertEquals(123L, r2.state.server.info.versionV2)
    }

    @Test
    fun testCodecVersionSetsOpus() {
        val s = emptySessionState()
        fun codec(opus: Boolean?) = Mumble.CodecVersion.newBuilder()
            .setAlpha(1).setBeta(2).setPreferAlpha(true)
            .let { if (opus == null) it else it.setOpus(opus) }.build()
        assertFalse(applyCodecVersion(s, codec(null)).state.server.info.opus)
        assertTrue(applyCodecVersion(s, codec(true)).state.server.info.opus)
        assertFalse(applyCodecVersion(s, codec(false)).state.server.info.opus)
    }

    @Test
    fun testTextFromIgnoredSenderDrops() {
        var s = syncedStateWithIgnored(9)
        val msg = Mumble.TextMessage.newBuilder().setActor(9).setMessage("hi").build()
        assertTrue(applyTextMessage(s, msg, 1L).events.isEmpty())
        s = syncedStateWithHeard(9)
        val r = applyTextMessage(s, msg, 1L)
        assertEquals(1, r.events.filterIsInstance<SessionEvent.TextMessageReceived>().size)
    }

    @Test
    fun testTextFiltersUnknownIds() {
        var s = syncedStateWithHeard(9)
        s = applyChannelState(s, Mumble.ChannelState.newBuilder().setChannelId(0).setName("R").build()).state
        val r = applyTextMessage(
            s,
            Mumble.TextMessage.newBuilder().setActor(9).setMessage("hi")
                .addChannelId(0).addChannelId(77).addTreeId(78).addSession(9).addSession(78).build(),
            1L,
        )
        val e = r.events.filterIsInstance<SessionEvent.TextMessageReceived>()[0]
        assertEquals(listOf(0), e.channels)
        assertEquals(emptyList<Int>(), e.trees)
        assertEquals(listOf(9), e.users)
        assertEquals(9, e.actor)
        assertEquals(1L, e.receivedAtMillis)
    }

    @Test
    fun testCodecNullPreservesTrue() {
        var s = emptySessionState()
        s = applyCodecVersion(s, Mumble.CodecVersion.newBuilder().setAlpha(1).setBeta(2).setPreferAlpha(true).setOpus(true).build()).state
        assertTrue(applyCodecVersion(s, Mumble.CodecVersion.newBuilder().setAlpha(1).setBeta(2).setPreferAlpha(true).build()).state.server.info.opus)
    }

    @Test
    fun testPermissionDeniedEmitsRawFields() {
        val s = emptySessionState()
        val r = applyPermissionDenied(
            s,
            Mumble.PermissionDenied.newBuilder()
                .setType(Mumble.PermissionDenied.DenyType.ChannelFull)
                .setReason("full").setName("n").build(),
        )
        val e = r.events.filterIsInstance<SessionEvent.PermissionDenied>()[0]
        assertEquals(Mumble.PermissionDenied.DenyType.ChannelFull.number, e.type)
        assertEquals("full", e.reason)
        assertEquals("n", e.name)
        assertNull(e.channel)
        val r2 = applyPermissionDenied(
            s,
            Mumble.PermissionDenied.newBuilder()
                .setType(Mumble.PermissionDenied.DenyType.Permission)
                .setPermission(4).setChannelId(7).setSession(9).build(),
        )
        val e2 = r2.events.filterIsInstance<SessionEvent.PermissionDenied>()[0]
        assertEquals(4, e2.permission)
        assertEquals(7, e2.channel)
        assertEquals(9, e2.session)
        assertNull(e2.reason)
    }

    private fun syncedStateWithIgnored(session: Int): SessionState {
        var s = emptySessionState()
        s = s.copy(server = s.server.copy(phase = SyncPhase.SYNCED))
        var map = s.server.users
        map = map.putting(
            session,
            UserInfo(
                session, null, "ign", 0, null, null, null, null, null,
                false, false, false, false, false, false, false,
                kotlinx.collections.immutable.persistentSetOf(),
                false, true,
            ),
        )
        return s.copy(server = s.server.copy(users = map))
    }

    private fun syncedStateWithHeard(session: Int): SessionState {
        var s = emptySessionState()
        s = s.copy(server = s.server.copy(phase = SyncPhase.SYNCED))
        var map = s.server.users
        map = map.putting(
            session,
            UserInfo(
                session, null, "u", 0, null, null, null, null, null,
                false, false, false, false, false, false, false,
                kotlinx.collections.immutable.persistentSetOf(),
                false, false,
            ),
        )
        return s.copy(server = s.server.copy(users = map))
    }
}
