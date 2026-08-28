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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SoftLimiterTest {

    @Test
    public void testUnityBoostPassesThrough() {
        short sample = 15000;
        assertEquals(15000, SoftLimiter.processSample(sample, 1.0f));
    }

    @Test
    public void testLinearRegionBelowKnee() {
        // Knee is at 2/3 of 32767 = 21844
        short sample = 10000;
        // 10000 * 1.5 = 15000 (< 21844) -> passes linearly
        assertEquals(15000, SoftLimiter.processSample(sample, 1.5f));
    }

    @Test
    public void testSmoothSaturationAboveKneeWithoutSquareClipping() {
        // High boost factor of 3.0x on 20000 (scaled = 60000, > 32767)
        short sample = 20000;
        short processed = SoftLimiter.processSample(sample, 3.0f);

        // Must be smoothly saturated, strictly within [-32768, 32767]
        assertTrue(processed > 21844);
        assertTrue(processed <= 32767);

        // Negative sample test
        short negSample = -20000;
        short negProcessed = SoftLimiter.processSample(negSample, 3.0f);
        assertTrue(negProcessed < -21844);
        assertTrue(negProcessed >= -32768);
    }

    @Test
    public void testProcessBufferInPlace() {
        short[] buffer = new short[]{0, 1000, 15000, 30000};
        SoftLimiter.processBuffer(buffer, 0, buffer.length, 2.0f);

        assertEquals(0, buffer[0]);
        assertEquals(2000, buffer[1]);
        assertTrue(buffer[2] > 21844 && buffer[2] <= 32767);
        assertTrue(buffer[3] > 21844 && buffer[3] <= 32767);
    }
}
