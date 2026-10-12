/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.audio.inputmode

import se.lublin.humla.audio.NativeAudioInputEngine
import kotlin.math.max
import kotlin.math.min

/**
 * Modern Voice Activity Detection (VAD) Input Mode.
 *
 * Uses dual-threshold hysteresis (activation threshold vadMax, deactivation threshold vadMin)
 * with a 250ms hangover hold timer.
 */
class ActivityInputMode(detectionThreshold: Float) : IInputMode {
    companion object {
        const val DEFAULT_VAD_MAX = NativeAudioInputEngine.DEFAULT_VAD_MAX
        const val DEFAULT_VAD_MIN = NativeAudioInputEngine.DEFAULT_VAD_MIN
        const val DEFAULT_HOLD_FRAMES = NativeAudioInputEngine.DEFAULT_HOLD_FRAMES
    }

    var vadMax: Float
        private set
    var vadMin: Float
        private set

    init {
        val vadMaxValue = max(0.0f, min(detectionThreshold, 1.0f))
        vadMax = vadMaxValue
        vadMin = max(0.0f, vadMaxValue * 0.7f)
    }

    /**
     * Unused in the active pipeline. Voice activity detection and speech-gating
     * are executed natively in C++ via {@link se.lublin.humla.audio.NativeAudioInputEngine}.
     */
    override fun shouldTransmit(pcm: ShortArray, length: Int): Boolean {
        return false
    }

    override fun waitForInput() {
    }

    fun setThreshold(threshold: Float) {
        vadMax = max(0.0f, min(threshold, 1.0f))
        vadMin = max(0.0f, vadMax * 0.7f)
    }
}
