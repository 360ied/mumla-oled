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
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import se.lublin.humla.client.ClientState
import se.lublin.humla.state.SessionEvent
import se.lublin.humla.state.SessionEventRecord
import se.lublin.humla.state.SessionState
import se.lublin.mumla.AppLogLine
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.R
import se.lublin.mumla.SpeechLine
import se.lublin.mumla.util.HtmlUtils
import se.lublin.mumla.util.NotificationSanitizer

/** Speaks messages, connection announcements, and hand-off speech lines. */
class TextToSpeechFeature(
    private val context: Context,
    private val events: SharedFlow<SessionEventRecord>,
    private val clientState: StateFlow<ClientState>,
    private val sessionState: StateFlow<SessionState>,
    private val speech: SharedFlow<SpeechLine>,
    private val settings: StateFlow<PrefSnapshot>,
    private val platform: FeaturePlatform,
    private val emitAppLog: (AppLogLine) -> Unit,
    /** Test seam for a slow TTS engine. */
    private val speakSink: (suspend (String) -> Unit)? = null,
) {
    private var tts: TtsSpeaker? = null
    private var engine: String? = null
    private var enabled = false
    private var shortMessages = false
    private var deafened = true
    private var activated = false
    private var wasReconnecting = false

    fun start(scope: CoroutineScope) {
        val child = scope.featureChild()
        val sink = speakSink ?: { text: String -> speak(text) }
        val queue = FeatureEventQueue<String>(child) { sink(it) }
        child.launch {
            events.collect { record ->
                val event = record.event
                if (event is SessionEvent.TextMessageReceived) {
                    format(event)?.let { queue.offer(it) }
                }
            }
        }
        child.launch {
            speech.collect { queue.offer(it.text) }
        }
        child.launch {
            sessionState.collect {
                val self = it.server.self
                deafened = self == null || self.selfDeafened
            }
        }
        child.launch {
            settings.collect { snap ->
                val nextEnabled = snap.useTts
                val nextEngine = snap.ttsEngine
                shortMessages = snap.shortTtsMessages
                val changed = nextEnabled != enabled || nextEngine != engine
                enabled = nextEnabled
                engine = nextEngine
                if (changed && activated) initEngine()
            }
        }
        child.launch {
            clientState.collect { state -> onClientState(state, queue) }
        }
    }

    private fun onClientState(state: ClientState, queue: FeatureEventQueue<String>) {
        when (state) {
            is ClientState.Idle -> wasReconnecting = false
            is ClientState.Connecting -> {
                if (!activated) {
                    activated = true
                    if (enabled) initEngine()
                }
            }
            is ClientState.Connected -> {
                if (!activated) {
                    activated = true
                    if (enabled) initEngine()
                }
                if (wasReconnecting) {
                    queue.offer(ConnectionLines.reconnected(context))
                }
                wasReconnecting = false
            }
            is ClientState.Reconnecting -> {
                if (!activated) {
                    activated = true
                    if (enabled) initEngine()
                }
                if (!wasReconnecting) {
                    queue.offer(
                        ConnectionLines.disconnected(context, state.lastError.displayMessage()),
                    )
                }
                wasReconnecting = true
            }
            is ClientState.Failed -> {
                val cause = state.error.displayMessage()
                if (cause != null) queue.offer(ConnectionLines.disconnected(context, cause))
                wasReconnecting = false
            }
        }
    }

    private fun initEngine() {
        tts?.shutdown()
        tts = null
        if (!enabled) return
        val requested = engine
        tts = platform.newTts(requested) { status ->
            if (status == TextToSpeech.ERROR) {
                emitAppLog(
                    AppLogLine(
                        if (requested != null) {
                            context.getString(R.string.tts_failed_engine, requested)
                        } else {
                            context.getString(R.string.tts_failed)
                        },
                        warning = true,
                    ),
                )
            }
        }
    }

    private fun speak(text: String) {
        if (enabled && tts != null && text.isNotEmpty()) tts?.speak(text)
    }

    /** Today's HTML stripping, short links, sanitizing and 250-character cap. */
    private fun format(event: SessionEvent.TextMessageReceived): String? {
        val parsed = Jsoup.parseBodyFragment(event.message)
        val ttsMessage = if (shortMessages) {
            for (anchor: Element in parsed.getElementsByTag("A")) {
                val href = anchor.attr("href")
                if (href.isNotEmpty() && href == anchor.text()) {
                    val host = HtmlUtils.getHostnameFromLink(href)
                    if (host != null) {
                        anchor.text(context.getString(R.string.chat_message_tts_short_link, host))
                    }
                }
            }
            parsed.text()
        } else {
            parsed.text()
        }
        val formatted = context.getString(
            R.string.notification_message,
            NotificationSanitizer.sanitizeActor(event.actorName),
            NotificationSanitizer.sanitizeBody(ttsMessage),
        )
        return if (formatted.length <= TTS_THRESHOLD && !deafened) formatted else null
    }

    companion object {
        const val TTS_THRESHOLD = 250
    }
}
