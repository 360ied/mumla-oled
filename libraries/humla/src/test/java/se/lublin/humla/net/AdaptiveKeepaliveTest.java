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

package se.lublin.humla.net;

import junit.framework.TestCase;

/**
 * Unit tests verifying adaptive keepalive ping intervals and state transitions.
 */
public class AdaptiveKeepaliveTest extends TestCase {

    public void testKeepaliveConstants() {
        assertEquals(5, HumlaConnection.BOOTSTRAP_PING_INTERVAL_SECONDS);
        assertEquals(10, HumlaConnection.STEADY_STATE_PING_INTERVAL_SECONDS);
        assertEquals(30_000_000L, HumlaConnection.BOOTSTRAP_DURATION_MICROS);
    }

    public void testInitialBootstrapIntervalIsFiveSeconds() {
        HumlaConnection connection = new HumlaConnection(null);
        connection.mStartTimestamp = System.nanoTime();
        // At start (elapsed = 0, good = 0), interval must be bootstrap (5s)
        assertEquals(HumlaConnection.BOOTSTRAP_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());
    }

    public void testBootstrapIntervalDuringFirstThirtySecondsEvenWithGoodPackets() {
        HumlaConnection connection = new HumlaConnection(null);
        connection.mStartTimestamp = System.nanoTime() - 10_000_000_000L; // 10s elapsed (< 30s)
        connection.mCryptState.mUiGood = 10;
        connection.mCryptState.mUiRemoteGood = 10;
        assertEquals(HumlaConnection.BOOTSTRAP_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());
    }

    public void testBootstrapIntervalAfterThirtySecondsIfPacketsNotConfirmed() {
        HumlaConnection connection = new HumlaConnection(null);
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L; // 35s elapsed (> 30s)

        // Remote good not confirmed
        connection.mCryptState.mUiGood = 10;
        connection.mCryptState.mUiRemoteGood = 2;
        assertEquals(HumlaConnection.BOOTSTRAP_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());

        // Local good not confirmed
        connection.mCryptState.mUiGood = 2;
        connection.mCryptState.mUiRemoteGood = 10;
        assertEquals(HumlaConnection.BOOTSTRAP_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());
    }

    public void testSteadyStateTransitionAfterBootstrapAndConfirmation() {
        HumlaConnection connection = new HumlaConnection(null);
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L; // 35s elapsed (> 30s)
        connection.mCryptState.mUiGood = 4;
        connection.mCryptState.mUiRemoteGood = 4;
        assertEquals(HumlaConnection.STEADY_STATE_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());
    }

    public void testForceTcpModeTransitionsToSteadyStateAfterThirtySeconds() {
        HumlaConnection connection = new HumlaConnection(null);
        connection.setForceTCP(true);

        // Before 30 seconds: bootstrap 5s
        connection.mStartTimestamp = System.nanoTime() - 10_000_000_000L;
        assertEquals(HumlaConnection.BOOTSTRAP_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());

        // After 30 seconds: even with 0 good crypt packets (UDP is disabled), transition to steady state 10s
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L;
        assertEquals(HumlaConnection.STEADY_STATE_PING_INTERVAL_SECONDS, connection.getNextPingIntervalSeconds());
    }
}
