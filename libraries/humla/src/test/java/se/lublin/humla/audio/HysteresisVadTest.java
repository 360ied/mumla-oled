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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HysteresisVadTest {

    @Test
    public void testSilenceDoesNotTriggerSpeech() {
        HysteresisVad vad = new HysteresisVad(0.50f, 0.35f, 5);
        short[] silence = new short[480]; // all zeroes

        boolean speaking = vad.process(silence, 0, silence.length, 0.0f);
        assertFalse(speaking);
        assertFalse(vad.isSpeaking());
    }

    @Test
    public void testSpeechActivationAtVadMax() {
        HysteresisVad vad = new HysteresisVad(0.50f, 0.35f, 5);

        // Loud signal (~10000 amplitude -> high dB)
        short[] loud = new short[480];
        for (int i = 0; i < 480; i++) {
            loud[i] = 10000;
        }

        // Combined neural prob (0.80) and loud acoustic energy exceeds vadMax (0.50)
        boolean speaking = vad.process(loud, 0, loud.length, 0.80f);
        assertTrue(speaking);
        assertTrue(vad.isSpeaking());
    }

    @Test
    public void testHysteresisDeactivationBetweenMinAndMaxWithHangover() {
        HysteresisVad vad = new HysteresisVad(0.60f, 0.30f, 3);

        // 1. Activate with loud frame
        short[] loud = new short[480];
        for (int i = 0; i < 480; i++) loud[i] = 15000;
        assertTrue(vad.process(loud, 0, loud.length, 0.9f));

        // 2. Medium frame (below vadMax 0.60, but above vadMin 0.30)
        short[] medium = new short[480];
        for (int i = 0; i < 480; i++) medium[i] = 2000;
        assertTrue(vad.process(medium, 0, medium.length, 0.45f));

        // 3. Silence frame (drops below vadMin, but within holdFrames=3)
        short[] silence = new short[480];
        assertTrue("Frame 1 of hangover", vad.process(silence, 0, silence.length, 0.0f));
        assertTrue("Frame 2 of hangover", vad.process(silence, 0, silence.length, 0.0f));
        assertTrue("Frame 3 of hangover", vad.process(silence, 0, silence.length, 0.0f));

        // 4. Hangover expired
        assertFalse("Hangover expired", vad.process(silence, 0, silence.length, 0.0f));
        assertFalse(vad.isSpeaking());
    }

    @Test
    public void testNeuralProbabilityOverridesEnergy() {
        HysteresisVad vad = new HysteresisVad(0.50f, 0.35f, 5);
        short[] quiet = new short[480];
        for (int i = 0; i < 480; i++) quiet[i] = 200; // quiet consonant

        // High neural speech probability (0.95) should trigger speech even on quiet consonants
        boolean speaking = vad.process(quiet, 0, quiet.length, 0.95f);
        assertTrue(speaking);
    }

    @Test
    public void testDefaultThresholdsAndGetters() {
        HysteresisVad vad = new HysteresisVad();
        assertEquals(0.35f, vad.getVadMax(), 0.0001f);
        assertEquals(0.25f, vad.getVadMin(), 0.0001f);
        assertEquals(-65.0f, vad.getSquelchMinDb(), 0.0001f);
    }

    @Test
    public void testSoftSpeechActivationWithCalibratedThreshold() {
        HysteresisVad vad = new HysteresisVad(); // 0.35f / 0.25f
        short[] softSpeech = new short[480];
        for (int i = 0; i < 480; i++) softSpeech[i] = 400; // ~ -38 dBFS, above -65 dBFS squelch

        // Neural prob = 0.40 >= vadMax (0.35) activates directly without acoustic energy penalty
        boolean speaking = vad.process(softSpeech, 0, softSpeech.length, 0.40f);
        assertTrue(speaking);
        assertTrue(vad.isSpeaking());
    }

    @Test
    public void testHighAmbientNoiseImmunity() {
        HysteresisVad vad = new HysteresisVad(); // vadMax = 0.35
        // Very loud ambient acoustic noise (~ -7 dBFS), but non-speech (neuralSpeechProb = 0.10)
        short[] loudNoise = new short[480];
        for (int i = 0; i < 480; i++) loudNoise[i] = 15000;

        // Acoustic energy does not add to score; neural score (0.10) < vadMax (0.35)
        boolean speaking = vad.process(loudNoise, 0, loudNoise.length, 0.10f);
        assertFalse(speaking);
        assertFalse(vad.isSpeaking());
    }

    @Test
    public void testSquelchFloorSuppression() {
        HysteresisVad vad = new HysteresisVad(); // squelch = -65 dBFS
        // Sub-squelch signal (~ -76 dBFS, amplitude = 5)
        short[] subSquelch = new short[480];
        for (int i = 0; i < 480; i++) subSquelch[i] = 5;

        // Even with high neural probability (0.90), sub-squelch signal is zeroed
        boolean speaking = vad.process(subSquelch, 0, subSquelch.length, 0.90f);
        assertFalse(speaking);
        assertFalse(vad.isSpeaking());
    }

    @Test
    public void testQuietWhisperSensitivityAboveSquelch() {
        HysteresisVad vad = new HysteresisVad(); // vadMax = 0.35, squelch = -65 dBFS
        // Quiet whisper (~ -44 dBFS, amplitude = 200, well above -65 dBFS)
        short[] whisper = new short[480];
        for (int i = 0; i < 480; i++) whisper[i] = 200;

        // Neural probability (0.38 >= 0.35) cleanly activates without energy attenuation
        boolean speaking = vad.process(whisper, 0, whisper.length, 0.38f);
        assertTrue(speaking);
        assertTrue(vad.isSpeaking());
    }

    @Test
    public void testSquelchConfigurationAndGetters() {
        HysteresisVad vad = new HysteresisVad(0.35f, 0.25f, 25, -50.0f);
        assertEquals(-50.0f, vad.getSquelchMinDb(), 0.0001f);

        // Amplitude 200 (~ -44 dBFS) is above -50 dBFS squelch
        short[] signal44 = new short[480];
        for (int i = 0; i < 480; i++) signal44[i] = 200;
        assertTrue(vad.process(signal44, 0, signal44.length, 0.50f));

        vad.reset();

        // Amplitude 50 (~ -56 dBFS) is below -50 dBFS squelch
        short[] signal56 = new short[480];
        for (int i = 0; i < 480; i++) signal56[i] = 50;
        assertFalse(vad.process(signal56, 0, signal56.length, 0.50f));
    }
}
