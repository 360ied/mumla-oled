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
 * Pins the keepalive-interval decision in {@link TransportImpl#nextPingDelaySeconds}.
 * Ported from HumlaConnection: elapsed &lt; 30s -&gt; 5s; else if (!forceTCP &amp;&amp; usingUDP
 * &amp;&amp; (remoteGood &lt;= 3 || localGood &lt;= 3)) -&gt; 5s; else 10s.
 */
public class PingIntervalTest extends TestCase {

    public void testBootstrapWindowAlwaysFiveSeconds() {
        assertEquals(5, TransportImpl.nextPingDelaySeconds(10_000_000L, 10, 10, true, false));
    }

    public void testSteadyStateWhenHealthyAfterThirtySeconds() {
        assertEquals(10, TransportImpl.nextPingDelaySeconds(35_000_000L, 4, 4, true, false));
    }

    public void testDegradedRemoteGoodStaysBootstrap() {
        assertEquals(5, TransportImpl.nextPingDelaySeconds(35_000_000L, 3, 10, true, false));
    }

    public void testDegradedLocalGoodStaysBootstrap() {
        assertEquals(5, TransportImpl.nextPingDelaySeconds(35_000_000L, 10, 3, true, false));
    }

    public void testForceTcpSkipsHealthGateAfterThirtySeconds() {
        assertEquals(10, TransportImpl.nextPingDelaySeconds(35_000_000L, 0, 0, true, true));
    }
}
