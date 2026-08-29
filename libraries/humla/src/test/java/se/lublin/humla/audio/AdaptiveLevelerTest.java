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

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdaptiveLevelerTest {

    private AdaptiveLeveler mLeveler;

    @Before
    public void setUp() {
        mLeveler = new AdaptiveLeveler();
    }

    @Test
    public void testDisabledLevelerPassesThroughUnchanged() {
        mLeveler.setEnabled(false);
        assertFalse(mLeveler.isEnabled());

        short[] buffer = new short[]{100, 500, 1000, -2000, 3000};
        short[] original = buffer.clone();

        mLeveler.process(buffer, 0, buffer.length, 0.9f);

        for (int i = 0; i < buffer.length; i++) {
            assertEquals(original[i], buffer[i]);
        }
    }

    @Test
    public void testSilenceFreezesGainAndDoesNotPump() {
        // Feed silence / ambient noise with speechProb = 0.0f
        short[] silenceBuffer = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        for (int i = 0; i < silenceBuffer.length; i++) {
            silenceBuffer[i] = (short) (i % 50); // Low amplitude noise (~29 RMS)
        }

        float initialGain = mLeveler.getCurrentGain();
        float initialSmoothedRms = mLeveler.getSmoothedRms();

        // Process 100 frames (1 second of silence)
        for (int frame = 0; frame < 100; frame++) {
            mLeveler.process(silenceBuffer, 0, silenceBuffer.length, 0.0f);
        }

        // Gain and smoothed RMS must remain strictly frozen
        assertEquals(initialGain, mLeveler.getCurrentGain(), 0.0001f);
        assertEquals(initialSmoothedRms, mLeveler.getSmoothedRms(), 0.0001f);
    }

    @Test
    public void testQuietSpeechRampsGainUpward() {
        // Create quiet speech signal (~1000 RMS, below 4125 target)
        short[] quietSpeech = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        for (int i = 0; i < quietSpeech.length; i++) {
            quietSpeech[i] = (short) (1000.0 * Math.sin(2 * Math.PI * i / 48.0));
        }

        float prevGain = mLeveler.getCurrentGain();

        // Feed 150 frames (1.5s of active speech)
        for (int frame = 0; frame < 150; frame++) {
            short[] frameData = quietSpeech.clone();
            mLeveler.process(frameData, 0, frameData.length, 0.85f);

            float currentGain = mLeveler.getCurrentGain();
            // Gain must not jump more than max slew rate per frame
            assertTrue(currentGain - prevGain <= AdaptiveLeveler.MAX_GAIN_SLEW_PER_FRAME + 1e-5f);
            assertTrue(currentGain >= prevGain); // Monotonically increasing towards boost
            prevGain = currentGain;
        }

        // After 1.5s of quiet speech, gain must have increased significantly
        assertTrue(mLeveler.getCurrentGain() > 1.2f);
        assertTrue(mLeveler.getCurrentGain() <= AdaptiveLeveler.MAX_GAIN);
        assertTrue(mLeveler.getSmoothedRms() < AdaptiveLeveler.DEFAULT_TARGET_RMS);
    }

    @Test
    public void testLoudSpeechRampsGainDownward() {
        // Create loud speech signal (~12000 RMS, above 4125 target)
        short[] loudSpeech = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        for (int i = 0; i < loudSpeech.length; i++) {
            loudSpeech[i] = (short) (12000.0 * Math.sin(2 * Math.PI * i / 48.0));
        }

        float prevGain = mLeveler.getCurrentGain();

        // Feed 150 frames (1.5s of active speech)
        for (int frame = 0; frame < 150; frame++) {
            short[] frameData = loudSpeech.clone();
            mLeveler.process(frameData, 0, frameData.length, 0.95f);

            float currentGain = mLeveler.getCurrentGain();
            // Gain must not jump more than max slew rate per frame
            assertTrue(prevGain - currentGain <= AdaptiveLeveler.MAX_GAIN_SLEW_PER_FRAME + 1e-5f);
            assertTrue(currentGain <= prevGain); // Monotonically decreasing towards attenuation
            prevGain = currentGain;
        }

        // After 1.5s of loud speech, gain must have decreased below unity
        assertTrue(mLeveler.getCurrentGain() < 0.8f);
        assertTrue(mLeveler.getCurrentGain() >= AdaptiveLeveler.MIN_GAIN);
        assertTrue(mLeveler.getSmoothedRms() > AdaptiveLeveler.DEFAULT_TARGET_RMS);
    }

    @Test
    public void testSoftLimiterSaturationProtection() {
        // Force high gain
        AdaptiveLeveler leveler = new AdaptiveLeveler(true, 10000.0f);
        short[] frame = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = 1000; // quiet
        }
        for (int f = 0; f < 300; f++) {
            leveler.process(frame.clone(), 0, frame.length, 0.9f);
        }
        assertTrue(leveler.getCurrentGain() > 2.0f);

        // Now feed large transient peaks while gain is high
        short[] peakFrame = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        peakFrame[0] = 25000;
        peakFrame[1] = -25000;
        peakFrame[2] = 32000;
        peakFrame[3] = -32000;

        leveler.process(peakFrame, 0, peakFrame.length, 0.9f);

        for (int i = 0; i < 4; i++) {
            assertTrue("Sample " + i + " exceeded positive limit", peakFrame[i] <= 32767);
            assertTrue("Sample " + i + " exceeded negative limit", peakFrame[i] >= -32768);
        }
    }

    @Test
    public void testResetRestoresInitialState() {
        short[] loudSpeech = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        for (int i = 0; i < loudSpeech.length; i++) {
            loudSpeech[i] = 15000;
        }
        for (int f = 0; f < 50; f++) {
            mLeveler.process(loudSpeech.clone(), 0, loudSpeech.length, 0.9f);
        }
        assertTrue(mLeveler.getCurrentGain() < 1.0f);

        mLeveler.reset();

        assertEquals(1.0f, mLeveler.getCurrentGain(), 0.0001f);
        assertEquals(AdaptiveLeveler.DEFAULT_TARGET_RMS, mLeveler.getSmoothedRms(), 0.0001f);
    }

    @Test
    public void testUnifiedAmplitudeBoostWithLeveler() {
        short[] frame = new short[AdaptiveLeveler.SAMPLES_PER_10MS];
        frame[0] = 5000;
        frame[1] = -5000;

        // With initial gain = 1.0f and amplitudeBoost = 1.5f, effective gain is 1.5x (7500)
        mLeveler.process(frame, 0, frame.length, 0.0f, 1.5f);

        assertEquals(7500, frame[0]);
        assertEquals(-7500, frame[1]);
    }
}
