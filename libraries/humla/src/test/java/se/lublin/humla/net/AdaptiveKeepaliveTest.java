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

package se.lublin.humla.net;

import junit.framework.TestCase;

/**
 * Unit tests verifying adaptive keepalive ping intervals and state transitions.
 * Ported from HumlaConnection to TransportImpl (same constants and policy).
 */
public class AdaptiveKeepaliveTest extends TestCase {

    public void testKeepaliveConstants() {
        assertEquals(5, TransportImpl.BOOTSTRAP_PING_SECONDS);
        assertEquals(10, TransportImpl.STEADY_PING_SECONDS);
        assertEquals(30_000_000L, TransportImpl.BOOTSTRAP_DURATION_MICROS);
    }

    public void testInitialBootstrapIntervalIsFiveSeconds() {
        assertEquals(TransportImpl.BOOTSTRAP_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(0L, 0, 0, true, false));
    }

    public void testBootstrapIntervalDuringFirstThirtySecondsEvenWithGoodPackets() {
        assertEquals(TransportImpl.BOOTSTRAP_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(10_000_000L, 10, 10, true, false));
    }

    public void testBootstrapIntervalAfterThirtySecondsIfPacketsNotConfirmed() {
        assertEquals(TransportImpl.BOOTSTRAP_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(35_000_000L, 2, 10, true, false));
        assertEquals(TransportImpl.BOOTSTRAP_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(35_000_000L, 10, 2, true, false));
    }

    public void testSteadyStateTransitionAfterBootstrapAndConfirmation() {
        assertEquals(TransportImpl.STEADY_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(35_000_000L, 4, 4, true, false));
    }

    public void testForceTcpModeTransitionsToSteadyStateAfterThirtySeconds() {
        assertEquals(TransportImpl.BOOTSTRAP_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(10_000_000L, 10, 10, true, true));
        assertEquals(TransportImpl.STEADY_PING_SECONDS,
                TransportImpl.nextPingDelaySeconds(35_000_000L, 0, 0, true, true));
    }
}
