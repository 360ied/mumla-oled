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

package se.lublin.humla;

import junit.framework.TestCase;

import java.lang.reflect.Field;

/**
 * Pins the reconnect-backoff truth table in {@link HumlaService}.
 * Slice 4 replaces the Handler postDelayed with delay() but keeps these
 * exact delays: 0-&gt;1000, 1-&gt;3000, 2-&gt;6000, 3-&gt;12000, &gt;=4-&gt;25000.
 *
 * <p>The static table is pinned directly (mirrors AudioRoutePolicyTest);
 * instance delegation is pinned via the mReconnectAttempts field so the
 * switch cannot drift behind the service state.
 */
public class ReconnectBackoffTest extends TestCase {

    public void testStaticBackoffTable() {
        assertEquals(1000, HumlaService.getReconnectDelayForAttempts(0));
        assertEquals(3000, HumlaService.getReconnectDelayForAttempts(1));
        assertEquals(6000, HumlaService.getReconnectDelayForAttempts(2));
        assertEquals(12000, HumlaService.getReconnectDelayForAttempts(3));
        assertEquals(25000, HumlaService.getReconnectDelayForAttempts(4));
        assertEquals(25000, HumlaService.getReconnectDelayForAttempts(5));
        assertEquals(25000, HumlaService.getReconnectDelayForAttempts(100));
    }

    public void testConstantsMatchTable() {
        assertEquals(1000, HumlaService.RECONNECT_DELAY_INITIAL);
        assertEquals(25000, HumlaService.RECONNECT_DELAY_MAX);
    }

    public void testInstanceDelegatesToStaticTable() throws Exception {
        HumlaService service = new HumlaService();
        Field attempts = HumlaService.class.getDeclaredField("mReconnectAttempts");
        attempts.setAccessible(true);
        int[] expected = {1000, 3000, 6000, 12000, 25000};
        for (int i = 0; i < expected.length; i++) {
            attempts.setInt(service, i);
            assertEquals("attempt " + i + " must map to " + expected[i],
                    expected[i], service.getReconnectDelay());
        }
        attempts.setInt(service, 42);
        assertEquals(25000, service.getReconnectDelay());
    }
}
