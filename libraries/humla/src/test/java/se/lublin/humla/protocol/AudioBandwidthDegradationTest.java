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

package se.lublin.humla.protocol;

import junit.framework.TestCase;

/**
 * Unit tests verifying the pure bandwidth-degradation decision in
 * {@link AudioHandler} (ODD-09). The table pins the branch behavior of
 * {@code computeEffectiveConfig} so the encoder-degradation logic that drives
 * the UDP send-queue rescale cannot drift silently.
 */
public class AudioBandwidthDegradationTest extends TestCase {

    private static void assertConfig(int expectedBitrate, int expectedFpp,
                                     int bitrate, int fpp, int maxBandwidth) {
        AudioHandler.EffectiveAudioConfig config =
                AudioHandler.computeEffectiveConfig(bitrate, fpp, maxBandwidth);
        assertEquals(expectedBitrate, config.bitrate);
        assertEquals(expectedFpp, config.framesPerPacket);
    }

    /**
     * Unset server limit (-1) leaves the configuration untouched.
     */
    public void testUnsetMaxBandwidthIsNoOp() {
        assertConfig(40000, 2, 40000, 2, -1);
    }

    /**
     * Sufficient bandwidth leaves the configuration untouched.
     * 40000 bps @ 20ms costs 19600 overhead + 40000 = 59600 <= 72000.
     */
    public void testSufficientBandwidthIsNoOp() {
        assertConfig(40000, 2, 40000, 2, 72000);
    }

    /**
     * 10ms packets degrade to 20ms under a 64 kbps cap without bitrate loss.
     * 40000 bps @ 10ms costs 38400 + 40000 = 78400 > 64000; @ 20ms costs 59600.
     */
    public void testSingleFrameDegradesToTwoFrames() {
        assertConfig(40000, 2, 40000, 1, 64000);
    }

    /**
     * 20ms packets degrade to 40ms under a 48 kbps cap with bitrate reduction.
     * @ 40ms base cost is 10200 + 40000 = 50200 > 48000, so bitrate steps down
     * to 37000 (47200 <= 48000).
     */
    public void testTwoFramesDegradeToFourFrames() {
        assertConfig(37000, 4, 40000, 2, 48000);
    }

    /**
     * Severe 32 kbps cap forces 40ms packets and deep bitrate reduction:
     * 10200 + 40000 = 50200 > 32000, stepping down to 21000 (31200 <= 32000).
     */
    public void testSevereCapForcesFourFramesAndLowBitrate() {
        assertConfig(21000, 4, 40000, 2, 32000);
    }

    /**
     * Bitrate never drops below the 8000 bps floor, even when the cap cannot
     * be met (18200 > 8000 at the floor).
     */
    public void testBitrateFloorHolds() {
        assertConfig(8000, 4, 9000, 2, 8000);
    }

    /**
     * Invalid frames-per-packet input is sanitized to the default (2).
     */
    public void testInvalidFramesPerPacketSanitized() {
        assertConfig(40000, 2, 40000, 3, -1);
    }
}
