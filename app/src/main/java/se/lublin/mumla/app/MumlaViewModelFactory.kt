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

package se.lublin.mumla.app

import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import se.lublin.mumla.channel.AccessTokenViewModel
import se.lublin.mumla.channel.ChannelViewModel
import se.lublin.mumla.channel.ChatViewModel
import se.lublin.mumla.channel.ServerInfoViewModel
import se.lublin.mumla.channel.comment.ChannelDescriptionFragment
import se.lublin.mumla.channel.comment.CommentViewModel
import se.lublin.mumla.channel.comment.UserCommentFragment
import se.lublin.mumla.container

/**
 * The one [ViewModelProvider.Factory]. It reads the container from the
 * application and hands each ViewModel only the flows it collects and the
 * functions it calls, never the container or `HumlaClient` itself. Tests
 * build ViewModels directly with plain flows.
 *
 * Screens with arguments (server ids, session ids) pass their fragment
 * arguments through the second constructor; the factory hands the model
 * only the values it needs.
 */
class MumlaViewModelFactory private constructor(
    private val application: Application,
    private val args: Bundle,
    private val fragment: Fragment?,
) : ViewModelProvider.Factory {

    constructor(application: Application) : this(application, Bundle.EMPTY, null)

    constructor(fragment: Fragment) : this(
        fragment.requireActivity().application,
        fragment.arguments ?: Bundle.EMPTY,
        fragment,
    )

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val container = application.container
        val client = container.runtime.client
        val model: ViewModel = when {
            modelClass.isAssignableFrom(ServerInfoViewModel::class.java) -> ServerInfoViewModel(
                clientState = client.state,
                sessionState = container.runtime.sessionState,
            )
            modelClass.isAssignableFrom(ChannelViewModel::class.java) -> ChannelViewModel(
                clientState = client.state,
                sessionState = container.runtime.sessionState,
                talkActivity = container.runtime.talkActivity,
                settings = container.settingsRepository.snapshot,
                emitTalkKey = container::emitTalkKey,
                setVoiceTargetId = { id -> client.currentSession.value?.setVoiceTarget(id) },
                unregisterVoiceTarget = { id ->
                    client.currentSession.value?.unregisterWhisperTarget(id)
                },
            )
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(
                messages = container.chatLog.messages,
                target = parentChannelViewModel().chatTargetDisplay,
                clientState = client.state,
                sessionState = container.runtime.sessionState,
                sendText = { channels, trees, users, message ->
                    client.currentSession.value?.sendText(channels, trees, users, message)
                },
                clearLog = { container.chatLog.clear() },
            )
            modelClass.isAssignableFrom(AccessTokenViewModel::class.java) -> AccessTokenViewModel(
                serverId = args.getLong(ARG_SERVER),
                loadTokens = { id -> container.database.query { it.getAccessTokens(id) } },
                addToken = { id, token -> container.database.query { it.addAccessToken(id, token) } },
                removeToken = { id, token ->
                    container.database.query { it.removeAccessToken(id, token) }
                },
                sendTokens = { tokens -> client.currentSession.value?.sendAccessTokens(tokens) },
            )
            modelClass.isAssignableFrom(CommentViewModel::class.java) -> {
                val target = if (args.containsKey(UserCommentFragment.ARG_SESSION)) {
                    CommentViewModel.Target.User(args.getInt(UserCommentFragment.ARG_SESSION))
                } else {
                    CommentViewModel.Target.Channel(
                        args.getInt(ChannelDescriptionFragment.ARG_CHANNEL),
                    )
                }
                CommentViewModel(
                    target = target,
                    sessionState = container.runtime.sessionState,
                    requestBlob = {
                        when (target) {
                            is CommentViewModel.Target.User ->
                                client.currentSession.value?.requestComment(target.session)
                            is CommentViewModel.Target.Channel ->
                                client.currentSession.value?.requestDescription(target.channelId)
                        }
                    },
                    setComment = { comment ->
                        when (target) {
                            is CommentViewModel.Target.User ->
                                client.currentSession.value?.setComment(target.session, comment)
                            is CommentViewModel.Target.Channel -> throw UnsupportedOperationException(
                                "Channel description editing is not supported",
                            )
                        }
                    },
                )
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return model as T
    }

    /**
     * The chat screen shares the parent channel screen's chat target, so it
     * reads that screen's ViewModel from the same store instead of keeping a
     * second copy.
     */
    private fun parentChannelViewModel(): ChannelViewModel {
        val parent = fragment?.parentFragment
            ?: throw IllegalStateException(
                "ChatViewModel needs the channel screen's parent fragment",
            )
        return ViewModelProvider(parent, MumlaViewModelFactory(parent))[ChannelViewModel::class.java]
    }

    companion object {
        /** Fragment argument key for a saved server id. */
        const val ARG_SERVER = "server"
    }
}
