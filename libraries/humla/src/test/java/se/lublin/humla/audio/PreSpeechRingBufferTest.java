/*
 * Copyright (C) 2026 Mumla Developers
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

package se.lublin.humla.audio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PreSpeechRingBufferTest {

    @Test
    public void testPushAndFlushInChronologicalOrder() {
        PreSpeechRingBuffer buffer = new PreSpeechRingBuffer(4, 10);

        for (int frameIdx = 0; frameIdx < 3; frameIdx++) {
            short[] pcm = new short[10];
            for (int i = 0; i < 10; i++) {
                pcm[i] = (short) ((frameIdx + 1) * 100 + i);
            }
            buffer.push(pcm, 10);
        }

        assertEquals(3, buffer.getCount());

        List<short[]> flushed = new ArrayList<>();
        buffer.flush((pcm, len) -> {
            short[] copy = new short[len];
            System.arraycopy(pcm, 0, copy, 0, len);
            flushed.add(copy);
        });

        assertEquals(3, flushed.size());
        assertEquals(0, buffer.getCount());

        for (int frameIdx = 0; frameIdx < 3; frameIdx++) {
            short[] expected = new short[10];
            for (int i = 0; i < 10; i++) {
                expected[i] = (short) ((frameIdx + 1) * 100 + i);
            }
            assertArrayEquals(expected, flushed.get(frameIdx));
        }
    }

    @Test
    public void testOverflowOverwritesOldestFramesInFIFOOrder() {
        PreSpeechRingBuffer buffer = new PreSpeechRingBuffer(3, 4);

        // Push 5 frames into capacity-3 buffer (frames 1, 2, 3, 4, 5)
        for (int frameIdx = 1; frameIdx <= 5; frameIdx++) {
            short[] pcm = new short[]{(short) frameIdx, (short) frameIdx, (short) frameIdx, (short) frameIdx};
            buffer.push(pcm, 4);
        }

        assertEquals(3, buffer.getCount());

        List<short[]> flushed = new ArrayList<>();
        buffer.flush((pcm, len) -> {
            short[] copy = new short[len];
            System.arraycopy(pcm, 0, copy, 0, len);
            flushed.add(copy);
        });

        // Must contain frames 3, 4, 5 in exact FIFO order
        assertEquals(3, flushed.size());
        assertArrayEquals(new short[]{3, 3, 3, 3}, flushed.get(0));
        assertArrayEquals(new short[]{4, 4, 4, 4}, flushed.get(1));
        assertArrayEquals(new short[]{5, 5, 5, 5}, flushed.get(2));
    }

    @Test
    public void testClearResetsCount() {
        PreSpeechRingBuffer buffer = new PreSpeechRingBuffer(4, 10);
        buffer.push(new short[]{1, 2, 3}, 3);
        assertEquals(1, buffer.getCount());

        buffer.clear();
        assertEquals(0, buffer.getCount());

        List<short[]> flushed = new ArrayList<>();
        buffer.flush((pcm, len) -> flushed.add(pcm));
        assertEquals(0, flushed.size());
    }
}
