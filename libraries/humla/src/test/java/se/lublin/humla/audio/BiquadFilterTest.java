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

public class BiquadFilterTest {

    @Test
    public void testDcBlockAttenuatesConstantOffset() {
        BiquadFilter filter = new BiquadFilter();
        short[] dcSignal = new short[4800]; // 100ms of DC offset
        for (int i = 0; i < dcSignal.length; i++) {
            dcSignal[i] = 10000;
        }

        filter.process(dcSignal, 0, dcSignal.length);

        // After 100ms, the constant DC offset must be decayed close to 0
        int tailSample = Math.abs(dcSignal[dcSignal.length - 1]);
        assertTrue("DC offset should decay towards 0, got: " + tailSample, tailSample < 100);
    }

    @Test
    public void testInfrasonicRumbleAttenuatedAt30Hz() {
        BiquadFilter filter = new BiquadFilter();
        int sampleRate = 48000;
        int freq = 30; // 30 Hz infrasonic rumble
        int count = sampleRate; // 1 second
        short[] rumble = new short[count];
        for (int i = 0; i < count; i++) {
            rumble[i] = (short) (10000.0 * Math.sin(2.0 * Math.PI * freq * i / sampleRate));
        }

        filter.process(rumble, 0, count);

        // Compute RMS of the steady-state tail (last 500ms)
        double sumIn = 0.0;
        double sumOut = 0.0;
        for (int i = count / 2; i < count; i++) {
            double orig = 10000.0 * Math.sin(2.0 * Math.PI * freq * i / sampleRate);
            sumIn += orig * orig;
            sumOut += (double) rumble[i] * rumble[i];
        }
        double rmsIn = Math.sqrt(sumIn / (count / 2.0));
        double rmsOut = Math.sqrt(sumOut / (count / 2.0));
        double attenuationDb = 20.0 * Math.log10(rmsOut / rmsIn);

        // At 30 Hz with a 90 Hz 2nd-order Butterworth HPF, attenuation should be ~ -18 dB
        assertTrue("30Hz attenuation should be < -15 dB, got: " + attenuationDb, attenuationDb < -15.0);
    }

    @Test
    public void testVoiceBandPassedWithUnityGainAt1kHz() {
        BiquadFilter filter = new BiquadFilter();
        int sampleRate = 48000;
        int freq = 1000; // 1 kHz voice band
        int count = sampleRate; // 1 second
        short[] voice = new short[count];
        for (int i = 0; i < count; i++) {
            voice[i] = (short) (10000.0 * Math.sin(2.0 * Math.PI * freq * i / sampleRate));
        }

        filter.process(voice, 0, count);

        // Compute RMS of steady-state tail
        double sumIn = 0.0;
        double sumOut = 0.0;
        for (int i = count / 2; i < count; i++) {
            double orig = 10000.0 * Math.sin(2.0 * Math.PI * freq * i / sampleRate);
            sumIn += orig * orig;
            sumOut += (double) voice[i] * voice[i];
        }
        double rmsIn = Math.sqrt(sumIn / (count / 2.0));
        double rmsOut = Math.sqrt(sumOut / (count / 2.0));
        double gainDb = 20.0 * Math.log10(rmsOut / rmsIn);

        // At 1 kHz, filter should be flat unity gain within +/- 0.1 dB
        assertTrue("1kHz gain should be ~0 dB, got: " + gainDb, Math.abs(gainDb) < 0.1);
    }

    @Test
    public void testResetClearsInternalState() {
        BiquadFilter filter = new BiquadFilter();
        short[] signal = new short[480];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = 15000;
        }
        filter.process(signal, 0, signal.length);

        filter.reset();

        short[] zeroSignal = new short[480];
        filter.process(zeroSignal, 0, zeroSignal.length);

        for (short s : zeroSignal) {
            assertEquals(0, s);
        }
    }
}
