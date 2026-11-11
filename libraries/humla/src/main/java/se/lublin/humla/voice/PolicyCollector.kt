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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.lublin.humla.state.SessionState
import se.lublin.humla.state.emptySessionState

/**
 * Interim policy collector. Combines the current session's state with
 * VoiceSettings, maps each pair to a VoicePolicy, skips unchanged values,
 * and publishes a StateFlow. Runs on its own single-thread dispatcher,
 * never the voice control thread.
 *
 * Before the native path exists the Kotlin capture thread reads the flow
 * each frame and drives the engine. Step 5a replaces the reader with
 * VoiceEngine.setPolicy; the collector and voicePolicy stay.
 */
class PolicyCollector(
    sessionStates: FlowOfStates,
    settings: StateFlow<VoiceSettings>,
    appScope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    /**
     * Minimal session-state source: the current session's state flow, or
     * null when disconnected. Keeps the collector Android-free and
     * testable without HumlaClient.
     */
    fun interface FlowOfStates {
        fun states(): Flow<SessionState?>
    }

    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob() + dispatcher)

    private val _policy: MutableStateFlow<VoicePolicy> =
        MutableStateFlow(VoicePolicy.mutedFallback(settings.value))
    val policy: StateFlow<VoicePolicy> = _policy.asStateFlow()

    init {
        scope.launch {
            combine(
                sessionStates.states(),
                settings,
            ) { sessionState, voiceSettings ->
                val base = sessionState ?: emptySessionState()
                voicePolicy(base, voiceSettings)
            }.collect { next ->
                if (next != _policy.value) {
                    _policy.value = next
                }
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
