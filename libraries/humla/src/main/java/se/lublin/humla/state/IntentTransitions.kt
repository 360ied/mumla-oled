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

import se.lublin.humla.protobuf.Mumble

/**
 * User intent transitions. Pure state updates for [Session]-owned writes;
 * the caller sends the returned outbound message. Intents that need a
 * connection are no-ops when the session is not yet SYNCED.
 */
data class IntentResult(
    val state: SessionState,
    val events: List<SessionEvent> = emptyList(),
    val userStateOutbound: Mumble.UserState? = null,
    val voiceTargetOutbound: Mumble.VoiceTarget? = null,
    val whisperId: Int = 0,
)

private fun coerceSelfMuteDeaf(mute: Boolean, deaf: Boolean): Pair<Boolean, Boolean> {
    var m = mute
    var d = deaf
    if (d) m = true
    if (!m) d = false
    return m to d
}

/** setSelfMuteDeaf: desired truth plus optimistic self echo. Emits SelfMuteStateChanged. */
fun applySelfMuteDeafIntent(state: SessionState, mute: Boolean, deaf: Boolean): IntentResult {
    if (state.server.phase != SyncPhase.SYNCED) return IntentResult(state)
    val (m, d) = coerceSelfMuteDeaf(mute, deaf)
    var server = state.server
    val selfSession = server.selfSession ?: return IntentResult(state)
    var user = server.users[selfSession] ?: return IntentResult(state)
    user = user.copy(selfMuted = m, selfDeafened = d)
    server = server.copy(
        users = server.users.putting(selfSession, user),
    )
    val local = state.local.copy(selfMuteDesired = m, selfDeafDesired = d)
    val outbound = Mumble.UserState.newBuilder().setSelfMute(m).setSelfDeaf(d).build()
    val events = listOf(SessionEvent.SelfMuteStateChanged(m, d))
    return IntentResult(state.copy(server = server, local = local), events, userStateOutbound = outbound)
}

/** setPtt: local press state, no outbound. */
fun applyPttIntent(state: SessionState, pressed: Boolean): IntentResult {
    if (state.local.pttPressed == pressed) return IntentResult(state)
    return IntentResult(state.copy(local = state.local.copy(pttPressed = pressed)))
}

/** setLocalMute / setLocalIgnore: device-local, persisted by caller for registered users. */
fun applyLocalMuteIntent(state: SessionState, session: Int, muted: Boolean): IntentResult {
    val user = state.server.users[session] ?: return IntentResult(state)
    if (user.localMuted == muted) return IntentResult(state)
    return IntentResult(
        state.copy(
            server = state.server.copy(
                users = state.server.users.putting(session, user.copy(localMuted = muted)),
            ),
        ),
    )
}

fun applyLocalIgnoreIntent(state: SessionState, session: Int, ignored: Boolean): IntentResult {
    val user = state.server.users[session] ?: return IntentResult(state)
    if (user.localIgnored == ignored) return IntentResult(state)
    return IntentResult(
        state.copy(
            server = state.server.copy(
                users = state.server.users.putting(session, user.copy(localIgnored = ignored)),
            ),
        ),
    )
}

/** setVoiceTarget: 0..31, no outbound. No-op outside 0..31; local-only and valid pre-SYNCED. */
fun applyVoiceTargetIntent(state: SessionState, id: Int): IntentResult {
    if (id !in 0..31) return IntentResult(state)
    if (state.local.voiceTargetId == id) return IntentResult(state)
    return IntentResult(state.copy(local = state.local.copy(voiceTargetId = id)))
}

/** registerWhisperTarget: lowest free id in 1..30, or -1 when full / not SYNCED. */
fun applyRegisterWhisperIntent(state: SessionState, target: WhisperTarget): IntentResult {
    if (state.server.phase != SyncPhase.SYNCED) return IntentResult(state, whisperId = -1)
    val taken = state.local.whisperTargets.keys
    var freeId = -1
    for (id in 1..30) {
        if (!taken.contains(id)) {
            freeId = id
            break
        }
    }
    if (freeId == -1) return IntentResult(state, whisperId = -1)
    val updated = state.local.whisperTargets.putting(freeId, target)
    val outbound = Mumble.VoiceTarget.newBuilder().setId(freeId)
        .addTargets(
            Mumble.VoiceTarget.Target.newBuilder()
                .setChannelId(target.channelId)
                .setLinks(target.includeLinked)
                .setChildren(target.includeSubchannels)
                .let { if (target.group != null) it.setGroup(target.group) else it }
                .build(),
        ).build()
    return IntentResult(
        state.copy(local = state.local.copy(whisperTargets = updated)),
        voiceTargetOutbound = outbound,
        whisperId = freeId,
    )
}

/** unregisterWhisperTarget: frees the id, ignores ids outside 1..30. */
fun applyUnregisterWhisperIntent(state: SessionState, id: Int): IntentResult {
    if (id !in 1..30) return IntentResult(state)
    if (!state.local.whisperTargets.containsKey(id)) return IntentResult(state)
    return IntentResult(
        state.copy(local = state.local.copy(whisperTargets = state.local.whisperTargets.removing(id))),
    )
}

/** setListeningVolume: records last-sent volume; caller sends the UserState. */
fun applyListeningVolumeIntent(state: SessionState, channel: Int, volume: Float): IntentResult {
    if (state.server.phase != SyncPhase.SYNCED) return IntentResult(state)
    val outbound = Mumble.UserState.newBuilder().addListeningVolumeAdjustment(
        Mumble.UserState.VolumeAdjustment.newBuilder()
            .setListeningChannel(channel).setVolumeAdjustment(volume).build(),
    ).build()
    val local = state.local.copy(
        listeningVolumes = state.local.listeningVolumes.putting(channel, volume),
    )
    return IntentResult(state.copy(local = local), userStateOutbound = outbound)
}

/** setListening add/remove: no state until the echo; caller sends the UserState. */
fun listeningAddOutbound(channel: Int): Mumble.UserState =
    Mumble.UserState.newBuilder().addListeningChannelAdd(channel).build()

fun listeningRemoveOutbound(channel: Int): Mumble.UserState =
    Mumble.UserState.newBuilder().addListeningChannelRemove(channel).build()
