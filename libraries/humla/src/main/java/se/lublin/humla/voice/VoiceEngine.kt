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

package se.lublin.humla.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.client.VoiceLifecycle
import se.lublin.humla.net.LegacyVoiceLink
import se.lublin.humla.net.VoiceCommand
import se.lublin.humla.net.VoiceFormat
import se.lublin.humla.net.VoiceStatus

/**
 * Kotlin VoiceEngine facade (step 5a.3b item 2).
 *
 * Implements the client's [VoiceLifecycle] and the transport's `VoiceLink`
 * so neither depends on the Android-backed class. Takes over the
 * service-lifetime [TalkActivity] publisher from 5a.3a (same instance,
 * new owner).
 *
 * Interim: network voice still runs on today's Kotlin `HumlaUDP` and
 * `CryptState` via the [LegacyVoiceLink] base. Items 3-6 move the socket,
 * receive, tunnel parse, send seam, pings, and counters native; item 8
 * deletes the base with the Kotlin path it serves. `Start` already carries
 * the transport's per-session decision (UDP format, legacy version);
 * `Stop` resets the route to tunnel so each session starts tunnelled.
 *
 * Android-free: no android.* imports.
 */
class VoiceEngine @JvmOverloads constructor(
    tunnelSink: (ByteArray) -> Unit = {},
    private val talkPublisher: TalkActivityPublisher = TalkActivityPublisher(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LegacyVoiceLink(tunnelSink, scope), VoiceLifecycle {
    /** Service-lifetime talk levels; the notifier feeds remote, AudioHandler feeds self. */
    val talkActivity: StateFlow<TalkActivity> = talkPublisher.talkActivity

    override fun startVoice(host: String, port: Int, format: VoiceFormat) {
        submit(VoiceCommand.Start(host, port, format))
    }

    override fun stopVoice() {
        submit(VoiceCommand.Stop)
    }

    override fun submit(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Start -> {
                super.startVoice(command.host, command.port, command.format)
                refreshRunning(true)
            }
            is VoiceCommand.Stop -> {
                super.stopVoice()
                refreshRunning(false)
            }
            else -> super.submit(command)
        }
    }

    private fun refreshRunning(running: Boolean) {
        val current = status.value
        if (current.running == running) return
        _status.value = current.copy(running = running)
    }
}
