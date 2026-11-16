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

package se.lublin.humla.audio

/**
 * Render entry into the application-scoped native voice engine (step 5a.3b).
 *
 * Implemented by the VoiceEngine facade, which owns the output engine and
 * both inbound rings across sessions. The render thread (AudioOutput's
 * AudioTrack thread) is the only caller while it runs; producers reach the
 * engine through the rings and the policy cell, never through this.
 */
interface RenderDriver {
    /**
     * Renders mixed PCM. Only the render thread calls this while it runs;
     * destroy runs only after the render thread is joined.
     */
    fun render(out: ShortArray?, offset: Int, length: Int): Int

    /**
     * Waits for work after a silent quantum. timeoutMs < 0 waits
     * indefinitely, else at most that long. True when either ring is
     * non-empty afterwards.
     */
    fun awaitWork(timeoutMs: Int): Boolean

    /** Whether the last render found active voices (idle-wait input). */
    fun lastHasActiveVoices(): Boolean

    /** Unconditional post for Stop; wakes a racing wait entry. */
    fun wakeForStop()
}
