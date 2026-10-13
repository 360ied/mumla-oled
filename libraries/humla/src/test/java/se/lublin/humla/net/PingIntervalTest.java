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
 * Pins the keepalive-interval decision in {@link HumlaConnection#getNextPingIntervalSeconds}.
 * Slice 4 replaces the ScheduledExecutorService self-chain with
 * while (isActive) { sendKeepalivePing(); delay(interval) } but keeps this
 * exact policy: elapsed &lt; 30s -&gt; 5s; else if (!forceTCP &amp;&amp; usingUDP
 * &amp;&amp; (uiRemoteGood &lt;= 3 || good &lt;= 3)) -&gt; 5s; else 10s.
 *
 * <p>Complements AdaptiveKeepaliveTest (constants + transitions) with the
 * slice-4 branch-level contract the coroutine loop depends on.
 */
public class PingIntervalTest extends TestCase {

    private static HumlaConnection freshConnection() {
        return new HumlaConnection(null);
    }

    public void testBootstrapWindowAlwaysFiveSeconds() {
        HumlaConnection connection = freshConnection();
        connection.mStartTimestamp = System.nanoTime() - 10_000_000_000L; // 10s
        connection.mCryptState.mUiGood = 10;
        connection.mCryptState.mUiRemoteGood = 10;
        assertEquals(5, connection.getNextPingIntervalSeconds());
    }

    public void testSteadyStateWhenHealthyAfterThirtySeconds() {
        HumlaConnection connection = freshConnection();
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L; // 35s
        connection.mCryptState.mUiGood = 4;
        connection.mCryptState.mUiRemoteGood = 4;
        assertEquals(10, connection.getNextPingIntervalSeconds());
    }

    public void testDegradedRemoteGoodStaysBootstrap() {
        HumlaConnection connection = freshConnection();
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L;
        connection.mCryptState.mUiGood = 10;
        connection.mCryptState.mUiRemoteGood = 3;
        assertEquals(5, connection.getNextPingIntervalSeconds());
    }

    public void testDegradedLocalGoodStaysBootstrap() {
        HumlaConnection connection = freshConnection();
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L;
        connection.mCryptState.mUiGood = 3;
        connection.mCryptState.mUiRemoteGood = 10;
        assertEquals(5, connection.getNextPingIntervalSeconds());
    }

    public void testForceTcpSkipsHealthGateAfterThirtySeconds() {
        HumlaConnection connection = freshConnection();
        connection.setForceTCP(true);
        connection.mStartTimestamp = System.nanoTime() - 35_000_000_000L;
        connection.mCryptState.mUiGood = 0;
        connection.mCryptState.mUiRemoteGood = 0;
        assertEquals(10, connection.getNextPingIntervalSeconds());
    }
}
