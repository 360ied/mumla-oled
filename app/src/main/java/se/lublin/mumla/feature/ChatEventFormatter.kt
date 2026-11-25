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

package se.lublin.mumla.feature

import android.content.Context
import se.lublin.humla.Constants
import se.lublin.humla.net.VoiceEvent
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.SessionState
import se.lublin.humla.util.MessageFormatter
import se.lublin.mumla.R

/** One chat-log line; warnings and errors render in the warning color. */
data class ChatLine(val text: String, val warning: Boolean = false)

/**
 * Turns semantic events into the text the old ModelHandler produced. This is
 * a pure port of the step-2 adapter: the recorded goldens in :app pin every
 * string and both sides of the move and recording filters.
 */
class ChatEventFormatter(private val context: Context) {

    fun lines(record: SessionEventRecord): List<ChatLine> = lines(record.event, record.state)

    fun lines(event: SessionEvent, state: SessionState): List<ChatLine> {
        val out = ArrayList<ChatLine>(1)
        when (event) {
            is SessionEvent.UserJoinedServer -> out.add(
                ChatLine(
                    context.getString(
                        R.string.chat_notify_connected,
                        MessageFormatter.highlightString(event.name),
                    ),
                ),
            )
            is SessionEvent.UserLeft -> out.add(userLeft(event))
            is SessionEvent.UserMoved -> userMoved(event, state)?.let { out.add(it) }
            is SessionEvent.RecordingChanged -> recording(event, state)?.let { out.add(it) }
            is SessionEvent.SelfMuteStateChanged -> out.add(
                ChatLine(
                    when {
                        event.muted && event.deafened -> context.getString(R.string.chat_notify_muted_deafened)
                        event.muted -> context.getString(R.string.chat_notify_muted)
                        else -> context.getString(R.string.chat_notify_unmuted)
                    },
                ),
            )
            is SessionEvent.UserSelfMuteChanged -> out.add(
                ChatLine(
                    when {
                        event.muted && event.deafened -> context.getString(
                            R.string.chat_notify_now_muted_deafened,
                            MessageFormatter.highlightString(event.name),
                        )
                        event.muted -> context.getString(
                            R.string.chat_notify_now_muted,
                            MessageFormatter.highlightString(event.name),
                        )
                        else -> context.getString(
                            R.string.chat_notify_now_unmuted,
                            MessageFormatter.highlightString(event.name),
                        )
                    },
                ),
            )
            is SessionEvent.WelcomeText -> out.add(ChatLine(event.text))
            is SessionEvent.ConnectionWarning -> out.add(ChatLine(event.message, warning = true))
            is SessionEvent.TextMessageReceived -> Unit
            is SessionEvent.TextMessageSent -> Unit
            is SessionEvent.PermissionDenied -> Unit
        }
        return out
    }

    private fun userLeft(event: SessionEvent.UserLeft): ChatLine {
        val userName = event.name ?: "unknown"
        return if (event.actor != null) {
            val actorName = event.actorName ?: "unknown"
            ChatLine(
                context.getString(
                    if (event.ban) R.string.chat_notify_kick_ban else R.string.chat_notify_kick,
                    MessageFormatter.highlightString(actorName),
                    event.reason,
                    MessageFormatter.highlightString(userName),
                ),
                warning = true,
            )
        } else {
            ChatLine(
                context.getString(
                    R.string.chat_notify_disconnected,
                    MessageFormatter.highlightString(userName),
                ),
            )
        }
    }

    private fun userMoved(event: SessionEvent.UserMoved, state: SessionState): ChatLine? {
        val selfSession = state.server.selfSession ?: return null
        if (event.session == selfSession) return null
        val from = event.from ?: return null
        val selfChannel = state.server.self?.channelId ?: return null
        val users = state.server.users
        val moverName = users[event.session]?.name
        val toName = state.server.channels[event.to]?.name
        if (selfChannel != event.to && selfChannel == from) {
            val mover = MessageFormatter.highlightString(moverName)
            val channel = MessageFormatter.highlightString(toName)
            return if (event.actor != null && event.actor == event.session) {
                ChatLine(context.getString(R.string.chat_notify_user_left_channel, mover, channel))
            } else {
                ChatLine(
                    context.getString(
                        R.string.chat_notify_user_left_channel_by, mover, channel,
                        actorString(event.actor, state),
                    ),
                )
            }
        } else if (selfChannel == event.to) {
            val mover = MessageFormatter.highlightString(moverName)
            return if (event.actor != null && event.actor == event.session) {
                ChatLine(context.getString(R.string.chat_notify_user_joined_channel, mover))
            } else {
                val oldName = state.server.channels[from]?.name
                ChatLine(
                    context.getString(
                        R.string.chat_notify_user_joined_channel_by,
                        mover,
                        MessageFormatter.highlightString(oldName),
                        actorString(event.actor, state),
                    ),
                )
            }
        }
        return null
    }

    private fun actorString(actor: Int?, state: SessionState): String {
        if (actor == null) return context.getString(R.string.the_server)
        val name = state.server.users[actor]?.name ?: return context.getString(R.string.the_server)
        return MessageFormatter.highlightString(name)
    }

    private fun recording(event: SessionEvent.RecordingChanged, state: SessionState): ChatLine? {
        val selfSession = state.server.selfSession
        if (event.session == selfSession) {
            return ChatLine(
                context.getString(
                    if (event.recording) R.string.chat_notify_self_recording_started
                    else R.string.chat_notify_self_recording_stopped,
                ),
            )
        }
        val selfChannel = state.server.self?.channelId ?: return null
        val userChannel = state.server.users[event.session]?.channelId ?: return null
        if (!state.server.tree.allLinks(selfChannel).contains(userChannel)) return null
        val name = MessageFormatter.highlightString(state.server.users[event.session]?.name)
        return ChatLine(
            context.getString(
                if (event.recording) R.string.chat_notify_user_recording_started
                else R.string.chat_notify_user_recording_stopped,
                name,
            ),
        )
    }

    /** Formats the permission-denied dialog that MainViewModel shows. */
    fun denyReason(event: SessionEvent.PermissionDenied): String {
        return when (event.type) {
            Mumble.PermissionDenied.DenyType.ChannelName.number ->
                context.getString(R.string.deny_reason_channel_name)
            Mumble.PermissionDenied.DenyType.TextTooLong.number ->
                context.getString(R.string.deny_reason_text_too_long)
            Mumble.PermissionDenied.DenyType.TemporaryChannel.number ->
                context.getString(R.string.deny_reason_no_operation_temp)
            Mumble.PermissionDenied.DenyType.MissingCertificate.number ->
                context.getString(R.string.deny_reason_no_certificate)
            Mumble.PermissionDenied.DenyType.UserName.number ->
                context.getString(R.string.deny_reason_invalid_username)
            Mumble.PermissionDenied.DenyType.ChannelFull.number ->
                context.getString(R.string.deny_reason_channel_full)
            Mumble.PermissionDenied.DenyType.NestingLimit.number ->
                context.getString(R.string.deny_reason_channel_nesting)
            else -> if (event.reason != null) {
                context.getString(R.string.deny_reason_other, event.reason)
            } else {
                context.getString(R.string.perm_denied)
            }
        }
    }

    /** EncoderLimited and PipelineError have no StateEventLogger counterpart. */
    fun voiceLine(event: VoiceEvent): ChatLine? = when (event) {
        is VoiceEvent.EncoderLimited -> ChatLine(
            context.getString(
                R.string.audio_max_bandwidth,
                event.maxBandwidth / 1000,
                event.bitrate / 1000,
                event.framesPerPacket * Constants.FRAME_DURATION_MS,
            ),
            warning = true,
        )
        is VoiceEvent.PipelineError -> ChatLine(
            context.getString(R.string.voice_pipeline_error, event.message),
            warning = true,
        )
        else -> null
    }
}
