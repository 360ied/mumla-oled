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

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Tests for the TLS protocol floor helper.
 * Pure array in/out, no sockets, no Android APIs.
 */
public class HumlaSSLSocketFactoryTest {

    @Test
    public void modernRuntimeKeeps12And13() {
        String[] supported = {"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"};
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void pre29RuntimeKeeps12() {
        String[] supported = {"TLSv1", "TLSv1.1", "TLSv1.2"};
        assertArrayEquals(new String[]{"TLSv1.2"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void onlyTls13Supported() {
        assertArrayEquals(new String[]{"TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(new String[]{"TLSv1.3"}));
    }

    @Test
    public void legacyOnlyYieldsEmpty() {
        String[] supported = {"SSLv3", "TLSv1", "TLSv1.1"};
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(supported).length);
    }

    @Test
    public void nullAndEmptyYieldEmpty() {
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(null).length);
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(new String[0]).length);
    }

    @Test
    public void duplicateSupportedEntriesAreNotRepeated() {
        String[] supported = {"TLSv1.2", "TLSv1.2", "TLSv1.3", "TLSv1.3"};
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void resultFollowsAllowListOrder() {
        // Supported order must not leak through; output is deterministic.
        String[] supported = {"TLSv1.3", "TLSv1.2", "TLSv1.1"};
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"},
                HumlaSSLSocketFactory.filterTlsProtocols(supported));
    }

    @Test
    public void matchingIsCaseSensitive() {
        assertEquals(0, HumlaSSLSocketFactory.filterTlsProtocols(new String[]{"tlsv1.2"}).length);
    }
}
