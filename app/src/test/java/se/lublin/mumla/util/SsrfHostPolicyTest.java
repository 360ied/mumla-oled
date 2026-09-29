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

package se.lublin.mumla.util;

import junit.framework.TestCase;

import java.net.InetAddress;
import java.net.URL;

public class SsrfHostPolicyTest extends TestCase {

    public void testPrivateIPv4Blocked() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("172.16.5.4")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("172.31.255.255")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("192.168.1.1")));
    }

    public void testPrivateRangeBoundaries() throws Exception {
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("172.15.255.255")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("172.32.0.1")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("9.255.255.255")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("11.0.0.1")));
    }

    public void testLoopbackLinkLocalMulticastBlocked() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("169.254.10.20")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("0.0.0.0")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("224.0.0.1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("::1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("fe80::1")));
    }

    public void testUniqueLocalAndCgnatBlocked() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("fc00::1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("fd12:3456::1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("100.127.255.255")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("100.128.0.1")));
    }

    public void testPublicAddressesAllowed() throws Exception {
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("1.1.1.1")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }

    public void testAnyAddressBlockedFailsClosed() throws Exception {
        assertTrue(SsrfHostPolicy.isAnyAddressBlocked(null));
        assertTrue(SsrfHostPolicy.isAnyAddressBlocked(new InetAddress[0]));
        assertTrue(SsrfHostPolicy.isAnyAddressBlocked(new InetAddress[]{null}));
        assertTrue(SsrfHostPolicy.isAnyAddressBlocked(new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1")}));
        assertFalse(SsrfHostPolicy.isAnyAddressBlocked(new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1")}));
    }

    public void testLiteralForms() {
        assertTrue(SsrfHostPolicy.isLiteralBlocked("10.0.0.1"));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("10.0.0.1."));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[::1]"));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[FE80::1]"));
        assertFalse(SsrfHostPolicy.isLiteralBlocked("8.8.8.8"));
        assertFalse(SsrfHostPolicy.isLiteralBlocked("example.com"));
        assertFalse(SsrfHostPolicy.isLiteralBlocked("EXAMPLE.COM"));
        assertTrue(SsrfHostPolicy.isLiteralBlocked(""));
        assertTrue(SsrfHostPolicy.isLiteralBlocked(null));
    }

    public void testNormalizeHost() {
        assertEquals("::1", SsrfHostPolicy.normalizeHost("[::1]"));
        assertEquals("example.com", SsrfHostPolicy.normalizeHost("Example.COM."));
        assertEquals("10.0.0.1", SsrfHostPolicy.normalizeHost("10.0.0.1."));
        assertNull(SsrfHostPolicy.normalizeHost(null));
    }

    public void testUserinfoRejected() throws Exception {
        assertTrue(SsrfHostPolicy.hasUserinfo(new URL("http://user@example.com/img.png")));
        assertTrue(SsrfHostPolicy.hasUserinfo(new URL("http://user:pass@example.com/img.png")));
        assertFalse(SsrfHostPolicy.hasUserinfo(new URL("http://example.com/img.png")));
        assertFalse(SsrfHostPolicy.hasUserinfo(null));
    }

    public void testRedirectOverflowStopped() {
        assertTrue(SsrfHostPolicy.shouldFollowRedirect(0, 302, "http://example.com/next"));
        assertTrue(SsrfHostPolicy.shouldFollowRedirect(4, 301, "http://example.com/next"));
        assertFalse(SsrfHostPolicy.shouldFollowRedirect(5, 302, "http://example.com/next"));
        assertFalse(SsrfHostPolicy.shouldFollowRedirect(6, 302, "http://example.com/next"));
        assertFalse(SsrfHostPolicy.shouldFollowRedirect(0, 200, "http://example.com/next"));
        assertFalse(SsrfHostPolicy.shouldFollowRedirect(0, 302, null));
        assertFalse(SsrfHostPolicy.shouldFollowRedirect(0, 302, ""));
        assertFalse(SsrfHostPolicy.shouldFollowRedirect(0, 404, "http://example.com/next"));
    }

    public void testSiteLocalBlocked() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("fec0::1")));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[fec0::1]"));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[FEC0::1]"));
    }

    public void testNat64EmbeddedPrivateBlocked() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("64:ff9b::a00:1")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("64:ff9b::7f00:1")));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[64:ff9b::a00:1]"));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("64:ff9b::808:808")));
    }
}
