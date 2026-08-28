/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
