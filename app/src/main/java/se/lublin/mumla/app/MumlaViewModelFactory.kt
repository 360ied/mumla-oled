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
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import se.lublin.mumla.channel.AccessTokenViewModel
import se.lublin.mumla.channel.ChannelListActions
import se.lublin.mumla.channel.ChannelListViewModel
import se.lublin.mumla.channel.ChannelViewModel
import se.lublin.mumla.channel.ChatViewModel
import se.lublin.mumla.channel.ServerInfoViewModel
import se.lublin.mumla.channel.comment.ChannelDescriptionFragment
import se.lublin.mumla.channel.comment.CommentViewModel
import se.lublin.mumla.channel.comment.UserCommentFragment
import java.io.IOException
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.HumlaClient
import se.lublin.humla.client.Session
import se.lublin.humla.model.Server
import se.lublin.humla.state.WhisperTarget
import se.lublin.mumla.TalkKeyEvent
import se.lublin.mumla.container
import se.lublin.mumla.preference.CrashReportsViewModel
import se.lublin.mumla.util.CrashReporter
import se.lublin.mumla.util.CrashReportSeenStore
import se.lublin.mumla.util.MumlaTrustStore

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
private const val TAG = "MumlaViewModelFactory"

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
            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
                clientState = client.state,
                events = container.runtime.events,
                denyFormatter = container.denyFormatter,
                actions = mainActions(),
            )
            modelClass.isAssignableFrom(CrashReportsViewModel::class.java) -> CrashReportsViewModel(
                clientState = client.state,
            )
            modelClass.isAssignableFrom(ChannelViewModel::class.java) -> ChannelViewModel(
                clientState = client.state,
                sessionState = container.runtime.sessionState,
                talkActivity = container.runtime.talkActivity,
                settings = container.settingsRepository.snapshot,
                emitTalkKey = container::emitTalkKey,
                setVoiceTargetId = { id -> connectedSession(client)?.setVoiceTarget(id) },
                unregisterVoiceTarget = { id ->
                    connectedSession(client)?.unregisterWhisperTarget(id)
                },
            )
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(
                messages = container.chatLog.messages,
                target = parentChannelViewModel().chatTargetDisplay,
                clientState = client.state,
                sessionState = container.runtime.sessionState,
                sendText = { channels, trees, users, message ->
                    connectedSession(client)?.sendText(channels, trees, users, message)
                },
                clearLog = { container.chatLog.clear() },
            )
            modelClass.isAssignableFrom(ChannelListViewModel::class.java) -> ChannelListViewModel(
                clientState = client.state,
                sessionState = container.runtime.sessionState,
                talkActivity = container.runtime.talkActivity,
                settings = container.settingsRepository.snapshot,
                loadPinnedChannels = { serverId -> container.database.getPinnedChannels(serverId) },
                savePinnedChannel = { serverId, channelId, pinned ->
                    if (pinned) {
                        container.database.addPinnedChannel(serverId, channelId)
                    } else {
                        container.database.removePinnedChannel(serverId, channelId)
                    }
                },
                actions = sessionActions(),
            )
            modelClass.isAssignableFrom(AccessTokenViewModel::class.java) -> AccessTokenViewModel(
                serverId = args.getLong(ARG_SERVER),
                loadTokens = { id -> container.database.getAccessTokens(id) },
                addToken = { id, token -> container.database.addAccessToken(id, token) },
                removeToken = { id, token -> container.database.removeAccessToken(id, token) },
                sendTokens = { tokens -> connectedSession(client)?.sendAccessTokens(tokens) },
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
                                connectedSession(client)?.requestComment(target.session)
                            is CommentViewModel.Target.Channel ->
                                connectedSession(client)?.requestDescription(target.channelId)
                        }
                    },
                    setComment = { comment ->
                        when (target) {
                            is CommentViewModel.Target.User ->
                                connectedSession(client)?.setComment(target.session, comment)
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
     * The one session access for ViewModel actions: the current session only
     * while the client is Connected, never a session held across a state
     * change. A pre-sync session is deliberately invisible here.
     */
    private fun connectedSession(client: HumlaClient): Session? =
        (client.state.value as? ClientState.Connected)?.session

    /** The main screen's connection, notification, trust and crash actions. */
    private fun mainActions(): MainViewModel.MainActions {
        val appContext = application.applicationContext
        val container = application.container
        val client = container.runtime.client
        return object : MainViewModel.MainActions {
            override fun connect(server: Server) {
                container.connect(server)
            }

            override fun disconnect() {
                client.disconnect()
            }

            override fun cancelReconnect() {
                client.cancelReconnect()
            }

            override fun setSuppressNotifications(suppress: Boolean) {
                container.connectionNotification.setSuppressNotifications(suppress)
            }

            override fun emitTalkKey(event: TalkKeyEvent) {
                container.emitTalkKey(event)
            }

            override suspend fun saveServerPassword(server: Server) {
                if (server.isSaved()) {
                    container.database.updateServer(server)
                }
            }

            override fun pinnedCertificate(host: String): X509Certificate? {
                return try {
                    val trustStore = MumlaTrustStore.getTrustStore(appContext)
                    if (!trustStore.containsAlias(host)) return null
                    val pinned = trustStore.getCertificate(host)
                    pinned as? X509Certificate
                } catch (e: GeneralSecurityException) {
                    Log.e(TAG, "Could not read trust store", e)
                    null
                } catch (e: IOException) {
                    Log.e(TAG, "Could not read trust store", e)
                    null
                }
            }

            override fun pinCertificate(host: String, certificate: X509Certificate): Boolean {
                return try {
                    val trustStore = MumlaTrustStore.getTrustStore(appContext)
                    trustStore.setCertificateEntry(host, certificate)
                    MumlaTrustStore.saveTrustStore(appContext, trustStore)
                    true
                } catch (e: GeneralSecurityException) {
                    Log.e(TAG, "Could not write trust store", e)
                    false
                } catch (e: IOException) {
                    Log.e(TAG, "Could not write trust store", e)
                    false
                }
            }

            override suspend fun newestUnseenCrashReport(drain: Boolean): String? {
                if (drain) {
                    try {
                        CrashReporter.drainExitInfo(appContext)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Crash re-drain failed", e)
                    }
                }
                val entries = CrashReporter.store(appContext).list()
                val newest = entries.firstOrNull() ?: return null
                val lastSeen = try {
                    CrashReportSeenStore.getLastSeen(appContext)
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not read crash seen-store", e)
                    null
                }
                return if (lastSeen == newest.id) null else newest.id
            }

            override suspend fun markCrashReportSeen(id: String) {
                try {
                    CrashReportSeenStore.setLastSeen(appContext, id)
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not write crash seen-store", e)
                }
            }

            override fun clearChatNotifications() {
                container.messageNotifications.clear()
            }
        }
    }

    /** Session intents for the channel list's rows and menus. */
    private fun sessionActions(): ChannelListActions {
        val client = application.container.runtime.client
        fun current(): Session? = connectedSession(client)
        return object : ChannelListActions {
            override fun joinChannel(channelId: Int) {
                current()?.joinChannel(channelId)
            }

            override fun moveUserToChannel(session: Int, channelId: Int) {
                current()?.moveUserToChannel(session, channelId)
            }

            override fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
                current()?.kickBanUser(session, reason, ban)
            }

            override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
                current()?.setMuteDeafState(session, mute, deaf)
            }

            override fun setPrioritySpeaker(session: Int, priority: Boolean) {
                current()?.setPrioritySpeaker(session, priority)
            }

            override fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) {
                current()?.setSelfMuteDeaf(mute, deaf)
            }

            override fun setLocalMute(session: Int, muted: Boolean) {
                current()?.setLocalMute(session, muted)
            }

            override fun setLocalIgnore(session: Int, ignored: Boolean) {
                current()?.setLocalIgnore(session, ignored)
            }

            override fun setUserComment(session: Int, comment: String) {
                current()?.setComment(session, comment)
            }

            override fun registerUser(session: Int) {
                current()?.registerUser(session)
            }

            override fun linkChannels(channelId: Int, otherChannelId: Int) {
                current()?.linkChannels(channelId, otherChannelId)
            }

            override fun unlinkChannels(channelId: Int, otherChannelId: Int) {
                current()?.unlinkChannels(channelId, otherChannelId)
            }

            override fun unlinkAllChannels(channelId: Int) {
                current()?.unlinkAllChannels(channelId)
            }

            override fun setListening(channelId: Int, listening: Boolean) {
                current()?.setListening(channelId, listening)
            }

            override fun requestPermissions(channelId: Int) {
                current()?.requestPermissions(channelId)
            }

            override fun registerWhisperTarget(target: WhisperTarget): Int =
                current()?.registerWhisperTarget(target) ?: -1

            override fun unregisterWhisperTarget(id: Int) {
                current()?.unregisterWhisperTarget(id)
            }

            override fun setVoiceTarget(id: Int) {
                current()?.setVoiceTarget(id)
            }
        }
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
