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

package se.lublin.humla.audio;

import junit.framework.TestCase;

/**
 * Unit tests verifying render-lead pacing behavior, idle rebasing, and stall
 * breakout in {@link AudioOutput.Pacer}.
 */
public class AudioOutputPacerTest extends TestCase {

    private static final int RENDER_SAMPLES = 960; // 20 ms at 48 kHz
    private static final int TRACK_FRAMES = 1920;  // 40 ms buffer

    public void testMaxLeadSamplesFloorAndTrackCapacity() {
        // Floor of 1 render quantum (960 samples) when trackFrames is smaller than 1 quantum (e.g. 480 or 0)
        AudioOutput.Pacer pacerSmall = new AudioOutput.Pacer(RENDER_SAMPLES, 480);
        assertEquals(RENDER_SAMPLES, pacerSmall.maxLeadSamples);

        AudioOutput.Pacer pacerZero = new AudioOutput.Pacer(RENDER_SAMPLES, 0);
        assertEquals(RENDER_SAMPLES, pacerZero.maxLeadSamples);

        // Clamps to 2 render quanta (1920 samples) even with larger hardware buffers
        // (e.g. Bluetooth A2DP 4800 frames) so render-lead never outruns the jitter buffer margin
        AudioOutput.Pacer pacerLarge = new AudioOutput.Pacer(RENDER_SAMPLES, 4800);
        assertEquals(RENDER_SAMPLES * 2, pacerLarge.maxLeadSamples);
    }

    public void testInitialCheckProceedsAndRebases() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        assertTrue(pacer.wasIdle);

        // First check upon startup must proceed immediately and align writtenTotal
        AudioOutput.Pacer.Action action = pacer.check(0);
        assertEquals(AudioOutput.Pacer.Action.PROCEED, action);
        assertFalse(pacer.wasIdle);
        assertEquals(0L, pacer.writtenTotal);
    }

    public void testNormalPacingBlocksWhenLeadExceededAndResumesOnPlayback() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        pacer.check(0); // initial idle rebase

        // Simulate writing two 20 ms quanta (1920 samples)
        pacer.onWritten(RENDER_SAMPLES);
        pacer.onWritten(RENDER_SAMPLES);
        assertEquals(1920L, pacer.writtenTotal);

        // Head is still at 0: writtenTotal + RENDER_SAMPLES - played = 1920 + 960 - 0 = 2880 > 1920
        // Pacer must wait for AudioTrack consumption
        AudioOutput.Pacer.Action action = pacer.check(0);
        assertEquals(AudioOutput.Pacer.Action.WAIT, action);

        // Playback head advances by 960 frames (20 ms played)
        // writtenTotal + RENDER_SAMPLES - played = 1920 + 960 - 960 = 1920 <= 1920
        action = pacer.check(960);
        assertEquals(AudioOutput.Pacer.Action.PROCEED, action);
    }

    public void testIdleRebasePreventsDeadlockAfterUnderrun() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        pacer.check(0);

        // Simulate active stream of 48000 frames written
        pacer.onWritten(48000);

        // AudioTrack underruns or stops during an idle gap at head 47000 (1000 frames unrendered/flushed)
        // Without idle rebase, 48000 + 960 - 47000 = 1960 > 1920, which would permanently deadlock!
        pacer.onIdle();
        assertTrue(pacer.wasIdle);

        // When the next burst arrives, check(47000) must rebase writtenTotal to head and PROCEED
        AudioOutput.Pacer.Action action = pacer.check(47000);
        assertEquals(AudioOutput.Pacer.Action.PROCEED, action);
        assertFalse(pacer.wasIdle);
        assertEquals(47000L, pacer.writtenTotal);

        // Following quantum can now fit inside maxLeadSamples without blocking
        action = pacer.check(47000);
        assertEquals(AudioOutput.Pacer.Action.PROCEED, action);
    }

    public void testStallBreakoutAfterUnchangedPolls() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        pacer.check(0);

        // Force writtenTotal beyond lead bound
        pacer.onWritten(RENDER_SAMPLES * 3); // 2880

        // Simulate head frozen at 0 (e.g. stalled sink or un-clocked AudioTrack)
        for (int i = 0; i < AudioOutput.Pacer.MAX_STALL_POLLS - 1; i++) {
            AudioOutput.Pacer.Action action = pacer.check(0);
            assertEquals(AudioOutput.Pacer.Action.WAIT, action);
        }

        // The MAX_STALL_POLLS-th poll with frozen head must trigger STALL_BREAK and rebase writtenTotal
        AudioOutput.Pacer.Action action = pacer.check(0);
        assertEquals(AudioOutput.Pacer.Action.STALL_BREAK, action);
        assertEquals(0L, pacer.writtenTotal);

        // Next check can now proceed
        action = pacer.check(0);
        assertEquals(AudioOutput.Pacer.Action.PROCEED, action);

        // Subsequent stall: verify stallCount was reset so breakout requires full MAX_STALL_POLLS polls
        pacer.onWritten(RENDER_SAMPLES * 3);
        for (int i = 0; i < AudioOutput.Pacer.MAX_STALL_POLLS - 1; i++) {
            action = pacer.check(0);
            assertEquals("Must wait full stall timeout on subsequent stall rather than breaking immediately",
                    AudioOutput.Pacer.Action.WAIT, action);
        }
        action = pacer.check(0);
        assertEquals(AudioOutput.Pacer.Action.STALL_BREAK, action);
    }

    public void testStallBreakoutAtNonZeroHead() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        pacer.check(48000); // active head position

        // Force writtenTotal beyond lead bound
        pacer.onWritten(RENDER_SAMPLES * 3);

        // Exactly MAX_STALL_POLLS polls at head 48000 before triggering STALL_BREAK
        for (int i = 0; i < AudioOutput.Pacer.MAX_STALL_POLLS - 1; i++) {
            AudioOutput.Pacer.Action action = pacer.check(48000);
            assertEquals(AudioOutput.Pacer.Action.WAIT, action);
        }
        AudioOutput.Pacer.Action action = pacer.check(48000);
        assertEquals(AudioOutput.Pacer.Action.STALL_BREAK, action);
        assertEquals(48000L, pacer.writtenTotal);
    }

    public void testStallCounterResetsWhenHeadAdvances() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, 4800);
        pacer.check(0);
        pacer.onWritten(RENDER_SAMPLES * 3); // exceeds maxLead (1920)

        // Poll 39 times at head 0 (just below MAX_STALL_POLLS of 40)
        for (int i = 0; i < AudioOutput.Pacer.MAX_STALL_POLLS - 1; i++) {
            assertEquals(AudioOutput.Pacer.Action.WAIT, pacer.check(0));
        }
        assertEquals(AudioOutput.Pacer.MAX_STALL_POLLS - 1, pacer.stallCount);

        // Head moves slightly (100 samples) before 40th poll: resets counter to 1 while still waiting
        assertEquals(AudioOutput.Pacer.Action.WAIT, pacer.check(100));
        assertEquals(1, pacer.stallCount);
    }

    public void testPlaybackHead32BitWrap() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        pacer.check(0);

        // Set lastHead near 32-bit unsigned overflow
        pacer.check((int) 0xFFFFFFF0L);
        assertEquals(0L, pacer.playedWrap);

        // Wrap to small positive value
        pacer.check(0x00000020);
        assertEquals(1L << 32, pacer.playedWrap);
    }

    public void testSpuriousPlaybackHeadResetRebasesLead() {
        AudioOutput.Pacer pacer = new AudioOutput.Pacer(RENDER_SAMPLES, TRACK_FRAMES);
        pacer.check(0);

        // Non-wrapping backward jump (e.g. route reset: lastHead 10000 -> 100)
        pacer.check(10000);
        pacer.onWritten(10000);

        pacer.check(100);
        // Must not increment playedWrap (not genuine 32-bit overflow)
        assertEquals(0L, pacer.playedWrap);
        // writtenTotal must be rebased to 100
        assertEquals(100L, pacer.writtenTotal);
    }
}
