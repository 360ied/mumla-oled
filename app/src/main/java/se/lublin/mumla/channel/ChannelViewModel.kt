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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.lublin.humla.client.ClientState
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.VoiceTargetMode
import se.lublin.humla.state.voiceTargetMode
import se.lublin.humla.state.whisperTargetName
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.Settings
import se.lublin.mumla.TalkKeyEvent

/**
 * The chat target the two children of the channel screen share. IDs, not
 * values: the display name is looked up in the latest state each render.
 */
class ChatTarget private constructor(
    val channelId: Int?,
    val session: Int?,
) {
    companion object {
        fun channel(channelId: Int): ChatTarget = ChatTarget(channelId, null)
        fun user(session: Int): ChatTarget = ChatTarget(null, session)
    }
}

/**
 * A chat target resolved against the latest state. [target] is null for the
 * self channel, today's default; [name] is null while the name is unknown.
 */
data class ChatTargetDisplay(
    val target: ChatTarget? = null,
    val name: String? = null,
)

/** Everything the channel screen renders from. */
data class ChannelUiState(
    val connected: Boolean = false,
    val selfTalking: Boolean = false,
    /** Hides the push-to-talk button, as `configureInput`'s guard did. */
    val muted: Boolean = false,
    val showPttButton: Boolean = false,
    val pttButtonHeight: Int = Settings.DEFAULT_PTT_BUTTON_HEIGHT,
    /** Name of the active whisper target; null hides the target panel. */
    val whisperTargetName: String? = null,
    /** The voice target id, for the target panel's cancel button. */
    val voiceTargetId: Int = 0,
    val voiceTargetMode: VoiceTargetMode = VoiceTargetMode.NORMAL,
    val bluetoothMenuVisible: Boolean = false,
    /** The requested headset toggle, mirrored in the overflow checkmark. */
    val bluetoothHeadset: Boolean = false,
)

/**
 * The channel screen: self mute/deafen and talk state, the voice target, the
 * push-to-talk button's settings, and the shared chat target. Talk keys go
 * into the container's hand-off flow, so the screen no longer calls the
 * service's `onTalkKey*` delegations.
 */
class ChannelViewModel(
    clientState: StateFlow<ClientState>,
    private val sessionState: StateFlow<SessionState>,
    talkActivity: StateFlow<TalkActivity>,
    settings: Flow<PrefSnapshot>,
    private val emitTalkKey: (TalkKeyEvent) -> Unit,
    private val setVoiceTargetId: (Int) -> Unit,
    private val unregisterVoiceTarget: (Int) -> Unit,
) : ViewModel() {

    private val _chatTarget = MutableStateFlow<ChatTarget?>(null)
    val chatTarget: StateFlow<ChatTarget?> = _chatTarget.asStateFlow()

    val state: StateFlow<ChannelUiState> = combine(
        clientState,
        sessionState,
        talkActivity,
        settings,
    ) { client, session, talk, snapshot ->
        val connected = client is ClientState.Connected
        val self = session.server.self
        // Mirrors configureInput: the mute guard only applies while connected.
        val muted = if (connected) {
            self == null || self.muted || self.suppressed || self.selfMuted
        } else {
            false
        }
        ChannelUiState(
            connected = connected,
            selfTalking = connected && talk.selfTalking,
            muted = muted,
            showPttButton = !muted &&
                !snapshot.hidePtt &&
                snapshot.audioInputMethod == Settings.ARRAY_INPUT_METHOD_PTT,
            pttButtonHeight = clampButtonHeight(snapshot.pttButtonHeight),
            whisperTargetName = if (session.local.voiceTargetMode() == VoiceTargetMode.WHISPER) {
                session.whisperTargetName(session.local.voiceTargetId)
            } else {
                null
            },
            voiceTargetId = session.local.voiceTargetId,
            voiceTargetMode = session.local.voiceTargetMode(),
            bluetoothMenuVisible = connected,
            bluetoothHeadset = snapshot.bluetoothHeadset,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelUiState())

    /** The chat target's display name, resolved against the latest state. */
    val chatTargetDisplay: StateFlow<ChatTargetDisplay> = combine(
        _chatTarget,
        sessionState,
    ) { target, session ->
        val name = when {
            target == null -> session.server.self
                ?.channelId
                ?.let { session.server.channels[it]?.name }
            target.channelId != null -> session.server.channels[target.channelId]?.name
            else -> session.server.users[target.session]?.name
        }
        ChatTargetDisplay(target, name)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatTargetDisplay(),
    )

    fun setChatTarget(target: ChatTarget?) {
        _chatTarget.value = target
    }

    fun talkDown() = emitTalkKey(TalkKeyEvent.Down)

    fun talkUp() = emitTalkKey(TalkKeyEvent.Up)

    fun talkCancel() = emitTalkKey(TalkKeyEvent.Cancel)

    /**
     * The target panel's cancel button: unregisters a whisper target and
     * returns to normal routing, exactly as the fragment's listener did.
     */
    fun clearVoiceTarget() {
        val current = sessionState.value
        if (current.local.voiceTargetMode() != VoiceTargetMode.WHISPER) {
            return
        }
        val id = current.local.voiceTargetId
        setVoiceTargetId(0)
        unregisterVoiceTarget(id)
    }

    private fun clampButtonHeight(height: Int): Int = when {
        height <= 0 -> Settings.DEFAULT_PTT_BUTTON_HEIGHT
        else -> height.coerceIn(Settings.MIN_PTT_BUTTON_HEIGHT, Settings.MAX_PTT_BUTTON_HEIGHT)
    }
}
