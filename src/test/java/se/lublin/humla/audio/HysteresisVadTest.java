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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HysteresisVadTest {

    @Test
    public void testSilenceDoesNotTriggerSpeech() {
        HysteresisVad vad = new HysteresisVad(0.50f, 0.35f, 5);
        short[] silence = new short[480]; // all zeroes

        boolean speaking = vad.process(silence, 0, silence.length, -1.0f);
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

        boolean speaking = vad.process(loud, 0, loud.length, -1.0f);
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
}
