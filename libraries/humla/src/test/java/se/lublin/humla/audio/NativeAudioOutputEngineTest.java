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

package se.lublin.humla.audio;

import junit.framework.TestCase;

/**
 * Unit tests verifying NativeAudioOutputEngine Java wrapper behavior when handle is zero or closed.
 */
public class NativeAudioOutputEngineTest extends TestCase {

    public void testHasActiveVoicesReturnsFalseWhenHandleIsZero() {
        NativeAudioOutputEngine engine = new NativeAudioOutputEngine(0L);
        assertFalse(engine.hasActiveVoices());
    }

    public void testSafeNoOpOnZeroHandleOperations() {
        NativeAudioOutputEngine engine = new NativeAudioOutputEngine(0L);
        engine.queuePacket(1, new byte[]{0x01}, 1, 0, 0, false);
        engine.removeUser(1);
        engine.reset();
        engine.setJitterMarginFrames(2);
        engine.destroy();
        assertFalse(engine.hasActiveVoices());
    }
}
