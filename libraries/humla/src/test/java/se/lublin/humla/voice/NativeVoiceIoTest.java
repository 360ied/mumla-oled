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

package se.lublin.humla.voice;

import junit.framework.TestCase;

/**
 * Handle-guard tests for the 5a.3a native render facade: a push after
 * stopPlaying (zero handle) is dropped without touching native memory, and
 * every other entry point is a safe no-op. Uses the zero-handle
 * constructor, so no native library is loaded.
 */
public class NativeVoiceIoTest extends TestCase {

    public void testPushAfterDestroyIsDropped() {
        NativeVoiceIo voiceIo = new NativeVoiceIo(0L);
        assertFalse(voiceIo.pushInbound(1, new byte[]{0x01}, 1, 0, 0, false));
        assertFalse(voiceIo.pushTunnel(1, new byte[]{0x01}, 1, 0, 0, false));
        assertFalse(voiceIo.pushRemoveUser(1));
    }

    public void testSafeNoOpOnZeroHandleOperations() {
        NativeVoiceIo voiceIo = new NativeVoiceIo(0L);
        assertEquals(0, voiceIo.render(new short[96], 0, 96));
        assertFalse(voiceIo.lastHasActiveVoices());
        assertEquals(0L, voiceIo.lastDroppedInbound());
        assertEquals(0L, voiceIo.lastDroppedTunnel());
        assertEquals(0L, voiceIo.lastDroppedJitter());
        voiceIo.clear();
        voiceIo.destroy();
        assertFalse(voiceIo.lastHasActiveVoices());
    }

    public void testDestroyZapsHandle() {
        NativeVoiceIo voiceIo = new NativeVoiceIo(0L);
        voiceIo.destroy();
        assertFalse(voiceIo.pushInbound(1, new byte[]{0x01}, 1, 0, 0, false));
        assertEquals(0, voiceIo.render(new short[96], 0, 96));
    }
}
