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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.ServerState
import se.lublin.humla.state.UserInfo
import se.lublin.humla.state.VoiceTargetMode
import se.lublin.humla.state.WhisperTarget
import se.lublin.humla.state.voiceTargetMode
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.PrefSnapshot

/**
 * Everything the channel list renders from: the channel tree and users of the
 * session-state mirror, talk activity, and the pinned-channel list in
 * pinned-only mode.
 */
data class ChannelListUiState(
    val connected: Boolean = false,
    val session: SessionState = se.lublin.humla.state.emptySessionState(),
    val talkActivity: TalkActivity = TalkActivity.initial(),
    val pinnedChannelIds: List<Int> = emptyList(),
    val showUserCount: Boolean = false,
) {
    val server: ServerState get() = session.server
}

/**
 * The session and client intents a list row or menu can invoke. The factory
 * implements it over the live session; tests record the calls.
 */
interface ChannelListActions {
    fun joinChannel(channelId: Int)
    fun moveUserToChannel(session: Int, channelId: Int)
    fun kickBanUser(session: Int, reason: String?, ban: Boolean)
    fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean)
    fun setPrioritySpeaker(session: Int, priority: Boolean)
    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean)
    fun setLocalMute(session: Int, muted: Boolean)
    fun setLocalIgnore(session: Int, ignored: Boolean)
    fun setUserComment(session: Int, comment: String)
    fun registerUser(session: Int)
    fun linkChannels(channelId: Int, otherChannelId: Int)
    fun unlinkChannels(channelId: Int, otherChannelId: Int)
    fun unlinkAllChannels(channelId: Int)
    fun setListening(channelId: Int, listening: Boolean)
    fun requestPermissions(channelId: Int)
    fun registerWhisperTarget(target: WhisperTarget): Int
    fun unregisterWhisperTarget(id: Int)
    fun setVoiceTarget(id: Int)
}

/**
 * The channel list: one state flow for the adapter, plus the panel and menu
 * actions. A row or menu is always addressed by id; values are looked up in
 * the latest state when they are rendered or acted on.
 */
class ChannelListViewModel(
    private val clientState: StateFlow<ClientState>,
    private val sessionState: StateFlow<SessionState>,
    talkActivity: StateFlow<TalkActivity>,
    settings: Flow<PrefSnapshot>,
    private val loadPinnedChannels: suspend (Long) -> List<Int>,
    private val savePinnedChannel: suspend (Long, Int, Boolean) -> Unit,
    private val actions: ChannelListActions,
) : ViewModel() {

    private val pinnedChannels = MutableStateFlow<List<Int>>(emptyList())

    /** Only the newest pinned read may publish; a pin toggle outruns a load. */
    private var pinnedLoadGeneration = 0

    /** Pin saves run in tap order, so rapid toggles persist the last one. */
    private var pinnedSaveTail: Job? = null

    val state: StateFlow<ChannelListUiState> = combine(
        clientState,
        sessionState,
        talkActivity,
        settings,
        pinnedChannels,
    ) { client, session, talk, snapshot, pinned ->
        ChannelListUiState(
            connected = client is ClientState.Connected,
            session = session,
            talkActivity = talk,
            pinnedChannelIds = pinned,
            showUserCount = snapshot.showUserCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelListUiState())

    init {
        // The pinned list is loaded for both modes: pinned-only mode uses it
        // as the roots, and the channel menu's checkmark else.
        viewModelScope.launch {
            clientState
                .map { (it as? ClientState.Connected)?.target?.server?.serverId }
                .distinctUntilChanged()
                .collect { serverId ->
                    if (serverId == null) {
                        pinnedLoadGeneration++
                        pinnedChannels.value = emptyList()
                    } else {
                        reloadPinnedChannels(serverId)
                    }
                }
        }
    }

    // Reads: the latest value each time, never a stored copy.

    fun selfSession(): Int? = sessionState.value.server.selfSession

    fun selfChannelId(): Int? = sessionState.value.server.self?.channelId

    fun rootPermissions(): Int = sessionState.value.server.rootPermissions

    fun channel(channelId: Int): ChannelInfo? = sessionState.value.server.channels[channelId]

    fun user(session: Int): UserInfo? = sessionState.value.server.users[session]

    fun isListening(channelId: Int): Boolean {
        val self = sessionState.value.server.self ?: return false
        return self.listeningChannels.contains(channelId)
    }

    /** All channels reachable from the root, in today's move-dialog order. */
    fun channelsInTreeOrder(): List<ChannelInfo> {
        val server = sessionState.value.server
        val out = ArrayList<ChannelInfo>()
        fun walk(channelId: Int) {
            val channel = server.channels[channelId] ?: return
            out.add(channel)
            for (child in server.tree.children(channelId)) walk(child.id)
        }
        if (server.channels.containsKey(0)) walk(0)
        return out
    }

    /**
     * The live permissions of one channel: the root's own permission word for
     * channel 0, the channel's field otherwise. Replaces the popup menu's
     * observer.
     */
    fun permissionsFor(channelId: Int): StateFlow<Int> = sessionState
        .map { session ->
            if (channelId == 0) {
                session.server.rootPermissions
            } else {
                session.server.channels[channelId]?.permissions ?: 0
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun isChannelPinned(channelId: Int): Boolean = pinnedChannels.value.contains(channelId)

    // Actions.

    fun joinChannel(channelId: Int) = actions.joinChannel(channelId)

    fun setChannelPinned(channelId: Int, pinned: Boolean) {
        val serverId = currentServerId() ?: return
        val previous = pinnedSaveTail
        pinnedSaveTail = viewModelScope.launch {
            previous?.join()
            savePinnedChannel(serverId, channelId, pinned)
            // Re-read: the menu's checkmark and pinned-only roots come from here.
            reloadPinnedChannels(serverId)
        }
    }

    fun toggleSelfMute() {
        val self = sessionState.value.server.self ?: return
        val muted = !self.selfMuted
        actions.setSelfMuteDeaf(muted, self.selfDeafened && muted)
    }

    fun toggleSelfDeafen() {
        val self = sessionState.value.server.self ?: return
        val deafened = !self.selfDeafened
        actions.setSelfMuteDeaf(deafened, deafened)
    }

    fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) =
        actions.setMuteDeafState(session, mute, deaf)

    fun setPrioritySpeaker(session: Int, priority: Boolean) =
        actions.setPrioritySpeaker(session, priority)

    fun setLocalMute(session: Int, muted: Boolean) = actions.setLocalMute(session, muted)

    fun setLocalIgnore(session: Int, ignored: Boolean) = actions.setLocalIgnore(session, ignored)

    fun setUserComment(session: Int, comment: String) = actions.setUserComment(session, comment)

    fun registerUser(session: Int) = actions.registerUser(session)

    fun moveUserToChannel(session: Int, channelId: Int) =
        actions.moveUserToChannel(session, channelId)

    fun kickBanUser(session: Int, reason: String?, ban: Boolean) =
        actions.kickBanUser(session, reason, ban)

    fun linkChannels(channelId: Int, otherChannelId: Int) =
        actions.linkChannels(channelId, otherChannelId)

    fun unlinkChannels(channelId: Int, otherChannelId: Int) =
        actions.unlinkChannels(channelId, otherChannelId)

    fun unlinkAllChannels(channelId: Int) = actions.unlinkAllChannels(channelId)

    fun setListening(channelId: Int, listening: Boolean) =
        actions.setListening(channelId, listening)

    fun requestPermissions(channelId: Int) = actions.requestPermissions(channelId)

    /**
     * Sets up a channel shout target: today's sequence, replacing any live
     * whisper target first. False means every slot was full or the register
     * failed, so the menu can show its toast.
     */
    fun shout(target: WhisperTarget): Boolean {
        val local = sessionState.value.local
        if (local.voiceTargetMode() == VoiceTargetMode.WHISPER) {
            actions.unregisterWhisperTarget(local.voiceTargetId)
        }
        val id = actions.registerWhisperTarget(target)
        if (id <= 0) {
            return false
        }
        actions.setVoiceTarget(id)
        return true
    }

    /**
     * Starts a pinned-list read and publishes it only if no newer read
     * started in the meantime.
     */
    private fun reloadPinnedChannels(serverId: Long) {
        val generation = ++pinnedLoadGeneration
        viewModelScope.launch {
            val pinned = loadPinnedChannels(serverId)
            if (generation == pinnedLoadGeneration) {
                pinnedChannels.value = pinned
            }
        }
    }

    /**
     * The connect target's database id, from client.state. The unsaved
     * sentinel -1 is kept: ad-hoc servers pin their channels under -1, as
     * they always have.
     */
    private fun currentServerId(): Long? =
        (clientState.value as? ClientState.Connected)?.target?.server?.serverId
}
