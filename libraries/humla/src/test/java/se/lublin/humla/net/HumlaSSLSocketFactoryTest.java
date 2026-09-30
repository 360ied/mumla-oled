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

import java.util.Arrays;

/**
 * Tests for the Phase 4 M1 TLS protocol floor helper.
 * Pure array in/out, no sockets, no Android APIs.
 */
public class HumlaSSLSocketFactoryTest extends TestCase {

    public void testModernRuntimeKeeps12And13() {
        String[] supported = {"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"};
        assertTrue(Arrays.equals(
                new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported)));
    }

    public void testPre29RuntimeKeeps12() {
        String[] supported = {"TLSv1", "TLSv1.1", "TLSv1.2"};
        assertTrue(Arrays.equals(
                new String[]{"TLSv1.2"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported)));
    }

    public void testLegacyOnlyYieldsEmpty() {
        String[] supported = {"TLSv1", "TLSv1.1"};
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(supported).length);
    }

    public void testNullAndEmptyYieldEmpty() {
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(null).length);
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(new String[0]).length);
    }

    public void testResultFollowsPreferredOrder() {
        // Supported order must not leak through; output is deterministic.
        String[] supported = {"TLSv1.3", "TLSv1.2", "TLSv1.1"};
        assertTrue(Arrays.equals(
                new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported)));
    }
}
