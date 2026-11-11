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

package se.lublin.humla.protocol

import android.content.Context
import se.lublin.humla.R
import se.lublin.humla.state.ChannelHandle
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.UserHandle
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.MessageFormatter

/**
 * Temporary step-2 adapter: turns semantic [SessionEvent]s into today's
 * [HumlaLogger] strings so the chat log keeps working. Moves to the app
 * chat-log feature in step 6.
 */
class StateEventLogger(
    private val context: Context,
    private val logger: HumlaLogger,
) {
    fun log(events: List<SessionEvent>, state: SessionState) {
        for (event in events) {
            when (event) {
                is SessionEvent.UserJoinedServer ->
                    logger.logInfo(
                        context.getString(
                            R.string.chat_notify_connected,
                            MessageFormatter.highlightString(event.name),
                        ),
                    )
                is SessionEvent.UserLeft -> logUserLeft(event)
                is SessionEvent.UserMoved -> logUserMoved(event, state)
                is SessionEvent.RecordingChanged -> logRecording(event, state)
                is SessionEvent.SelfMuteStateChanged -> logSelfMute(event.muted, event.deafened)
                is SessionEvent.UserSelfMuteChanged ->
                    logger.logInfo(
                        if (event.muted && event.deafened) {
                            context.getString(
                                R.string.chat_notify_now_muted_deafened,
                                MessageFormatter.highlightString(event.name),
                            )
                        } else if (event.muted) {
                            context.getString(
                                R.string.chat_notify_now_muted,
                                MessageFormatter.highlightString(event.name),
                            )
                        } else {
                            context.getString(
                                R.string.chat_notify_now_unmuted,
                                MessageFormatter.highlightString(event.name),
                            )
                        },
                    )
                is SessionEvent.WelcomeText -> logger.logInfo(event.text)
                is SessionEvent.ConnectionWarning -> logger.logWarning(event.message)
                is SessionEvent.TextMessageReceived -> Unit
                is SessionEvent.TextMessageSent -> Unit
                is SessionEvent.PermissionDenied -> Unit
            }
        }
    }

    private fun logUserLeft(event: SessionEvent.UserLeft) {
        val userName = event.name ?: "unknown"
        if (event.actor != null) {
            val actorName = event.actorName ?: "unknown"
            val reason = event.reason
            logger.logWarning(
                context.getString(
                    if (event.ban) R.string.chat_notify_kick_ban else R.string.chat_notify_kick,
                    MessageFormatter.highlightString(actorName),
                    reason,
                    MessageFormatter.highlightString(userName),
                ),
            )
        } else {
            logger.logInfo(
                context.getString(
                    R.string.chat_notify_disconnected,
                    MessageFormatter.highlightString(userName),
                ),
            )
        }
    }

    private fun logUserMoved(event: SessionEvent.UserMoved, state: SessionState) {
        val selfSession = state.server.selfSession ?: return
        if (event.session == selfSession) return
        val from = event.from ?: return
        val selfChannel = state.server.self?.channelId ?: return
        val users = state.server.users
        val moverName = users[event.session]?.name
        val toName = state.server.channels[event.to]?.name
        if (selfChannel != event.to && selfChannel == from) {
            val actorString = actorString(event.actor, state)
            val mover = MessageFormatter.highlightString(moverName)
            val channel = MessageFormatter.highlightString(toName)
            if (event.actor != null && event.actor == event.session) {
                logger.logInfo(
                    context.getString(R.string.chat_notify_user_left_channel, mover, channel),
                )
            } else {
                logger.logInfo(
                    context.getString(
                        R.string.chat_notify_user_left_channel_by, mover, channel, actorString,
                    ),
                )
            }
        } else if (selfChannel == event.to) {
            val actorString = actorString(event.actor, state)
            val mover = MessageFormatter.highlightString(moverName)
            if (event.actor != null && event.actor == event.session) {
                logger.logInfo(
                    context.getString(R.string.chat_notify_user_joined_channel, mover),
                )
            } else {
                val oldName = state.server.channels[from]?.name
                logger.logInfo(
                    context.getString(
                        R.string.chat_notify_user_joined_channel_by,
                        mover,
                        MessageFormatter.highlightString(oldName),
                        actorString,
                    ),
                )
            }
        }
    }

    private fun actorString(actor: Int?, state: SessionState): String {
        if (actor == null) return context.getString(R.string.the_server)
        val name = state.server.users[actor]?.name ?: return context.getString(R.string.the_server)
        return MessageFormatter.highlightString(name)
    }

    private fun logRecording(event: SessionEvent.RecordingChanged, state: SessionState) {
        val selfSession = state.server.selfSession
        if (event.session == selfSession) {
            logger.logInfo(
                if (event.recording) {
                    context.getString(R.string.chat_notify_self_recording_started)
                } else {
                    context.getString(R.string.chat_notify_self_recording_stopped)
                },
            )
            return
        }
        val selfChannel = state.server.self?.channelId ?: return
        val userChannel = state.server.users[event.session]?.channelId ?: return
        if (!state.server.tree.allLinks(selfChannel).contains(userChannel)) return
        val name = MessageFormatter.highlightString(state.server.users[event.session]?.name)
        logger.logInfo(
            if (event.recording) {
                context.getString(R.string.chat_notify_user_recording_started, name)
            } else {
                context.getString(R.string.chat_notify_user_recording_stopped, name)
            },
        )
    }

    private fun logSelfMute(muted: Boolean, deafened: Boolean) {
        logger.logInfo(
            if (muted && deafened) {
                context.getString(R.string.chat_notify_muted_deafened)
            } else if (muted) {
                context.getString(R.string.chat_notify_muted)
            } else {
                context.getString(R.string.chat_notify_unmuted)
            },
        )
    }

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
            else -> {
                if (event.reason != null) {
                    context.getString(R.string.deny_reason_other, event.reason)
                } else {
                    context.getString(R.string.perm_denied)
                }
            }
        }
    }

    fun toMessage(
        event: SessionEvent.TextMessageReceived,
        stateFlow: StateFlow<SessionState>,
    ): IMessage {
        val channels = ArrayList<IChannel>(event.channels.size)
        for (id in event.channels) {
            if (stateFlow.value.server.channels.containsKey(id)) {
                channels.add(ChannelHandle(id, stateFlow))
            }
        }
        val trees = ArrayList<IChannel>(event.trees.size)
        for (id in event.trees) {
            if (stateFlow.value.server.channels.containsKey(id)) {
                trees.add(ChannelHandle(id, stateFlow))
            }
        }
        val users = ArrayList<IUser>(event.users.size)
        for (session in event.users) {
            if (stateFlow.value.server.users.containsKey(session)) {
                users.add(UserHandle(session, stateFlow))
            }
        }
        return Message(
            event.actor ?: -1,
            event.actorName,
            channels,
            trees,
            users,
            event.message,
        )
    }
}
