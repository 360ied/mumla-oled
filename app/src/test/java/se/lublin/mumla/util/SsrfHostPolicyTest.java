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

    public void testTeredoEmbeddedPrivateBlocked() throws Exception {
        // Server 8.8.8.8, client F5FFFFFA ^ FFFFFFFF = 0A000005 = 10.0.0.5.
        assertTrue(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("2001:0:808:808:8000:fb2d:f5ff:fffa")));
        // Server 10.0.0.1, client 10.0.0.5: the server half is checked too.
        assertTrue(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("2001:0:a00:1:8000:fb2d:f5ff:fffa")));
        // Client F7F7F7F7 ^ FFFFFFFF = 08080808 = 8.8.8.8: no overblock.
        assertFalse(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("2001:0:808:808:8000:fb2d:f7f7:f7f7")));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[2001:0:808:808:8000:fb2d:f5ff:fffa]"));
    }

    public void testIsatapEmbeddedPrivateBlocked() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("::0:5efe:a00:5")));
        // u/l bit set variant must match the IID mask, not an exact IID.
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("::200:5efe:a00:5")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("::0:5efe:808:808")));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[::0:5efe:a00:5]"));
    }

    public void testNat64LocalUseEmbeddedPrivateBlocked() throws Exception {
        // RFC 6052 /48 row: v4 = bytes 6,7,9,10 -> 0A 00 00 05 = 10.0.0.5.
        assertTrue(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("64:ff9b:1:a00:0:500:0:0")));
        assertFalse(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("64:ff9b:1:808:0:808:0:0")));
        // Nonzero u octet is not a valid section-2.2 address: no extraction.
        assertFalse(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("64:ff9b:1:a00:100:500:0:0")));
        assertTrue(SsrfHostPolicy.isLiteralBlocked("[64:ff9b:1:a00:0:500:0:0]"));
    }

    public void testCoincidentTransitionEmbeddingsOrCombined() throws Exception {
        // NAT64-local embedding 10.0.0.94 with an ISATAP-coincident IID
        // trailing 8.8.8.8: the private embedding must win.
        assertTrue(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("64:ff9b:1:a00:0:5efe:808:808")));
        // Teredo server 10.0.0.1 with an ISATAP-coincident IID trailing 8.8.8.8.
        assertTrue(SsrfHostPolicy.isBlockedAddress(
                InetAddress.getByName("2001:0:a00:1:0:5efe:808:808")));
    }

    private static byte[] v4(int b0, int b1, int b2, int b3) {
        return new byte[]{(byte) b0, (byte) b1, (byte) b2, (byte) b3};
    }

    private static byte[] rawOf(String literal) throws Exception {
        return InetAddress.getByName(literal).getAddress();
    }

    public void testBlockedIPv4BranchesDirectly() {
        // Every explicit isBlockedIPv4 branch, bypassing the generic JDK gate.
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(0, 0, 0, 0)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(0, 255, 255, 255)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(127, 0, 0, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(169, 254, 10, 20)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(224, 0, 0, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(239, 255, 255, 255)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(10, 0, 0, 5)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(172, 16, 0, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(172, 31, 255, 255)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(192, 168, 1, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(100, 64, 0, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(100, 127, 255, 255)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(240, 0, 0, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(255, 255, 255, 255)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(192, 0, 2, 33)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(198, 51, 100, 7)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(203, 0, 113, 9)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(192, 88, 99, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(198, 18, 0, 1)));
        assertTrue(SsrfHostPolicy.isBlockedIPv4(v4(198, 19, 255, 255)));
        // Boundaries just outside the blocked ranges stay allowed.
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(1, 0, 0, 1)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(172, 15, 255, 255)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(172, 32, 0, 1)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(100, 63, 255, 255)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(100, 128, 0, 1)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(223, 255, 255, 255)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(198, 17, 255, 255)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(198, 20, 0, 1)));
        assertFalse(SsrfHostPolicy.isBlockedIPv4(v4(8, 8, 8, 8)));
    }

    public void testBlockedIPv6BranchesDirectly() throws Exception {
        assertTrue(SsrfHostPolicy.isBlockedIPv6(rawOf("fec0::1")));
        assertTrue(SsrfHostPolicy.isBlockedIPv6(rawOf("fc00::1")));
        assertTrue(SsrfHostPolicy.isBlockedIPv6(rawOf("fd00::1")));
        assertFalse(SsrfHostPolicy.isBlockedIPv6(rawOf("fe00::1")));
        assertTrue(SsrfHostPolicy.isBlockedIPv6(rawOf("2001:db8::1")));
        assertFalse(SsrfHostPolicy.isBlockedIPv6(rawOf("2001:4860:4860::8888")));
    }

    public void testUnwrapPathReachesShadowedBranches() throws Exception {
        // NAT64 unwrap reaches the shadowed 169.254/16 and 224/4 branches.
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("64:ff9b::a9fe:a14")));
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("64:ff9b::e000:1")));
        // 6to4 unwrap reaches the shadowed 10/8 branch (10.0.1.0).
        assertTrue(SsrfHostPolicy.isBlockedAddress(InetAddress.getByName("2002:a00:1::")));
    }

    public void testBuildPinnedUrlPreservesPathAndQuery() throws Exception {
        URL original = new URL("http://example.com/a/b?q=1");
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        assertEquals("http://93.184.216.34/a/b?q=1",
                SsrfHostPolicy.buildPinnedUrl(original, addr).toString());
    }

    public void testBuildPinnedUrlKeepsNonDefaultPort() throws Exception {
        URL original = new URL("https://example.com:8443/x");
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        assertEquals("https://93.184.216.34:8443/x",
                SsrfHostPolicy.buildPinnedUrl(original, addr).toString());
    }

    public void testBuildPinnedUrlBracketsIpv6() throws Exception {
        URL original = new URL("http://example.com/");
        InetAddress addr = InetAddress.getByName("::1");
        // Expected is built from getHostAddress() itself so the test does not
        // depend on the JDK's IPv6 formatting (expanded vs ::-compressed).
        assertEquals("http://[" + addr.getHostAddress() + "]/",
                SsrfHostPolicy.buildPinnedUrl(original, addr).toString());
    }

    public void testBuildPinnedUrlBareHost() throws Exception {
        URL original = new URL("http://example.com");
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        assertEquals("http://93.184.216.34",
                SsrfHostPolicy.buildPinnedUrl(original, addr).toString());
    }

    public void testBuildPinnedUrlKeepsExplicitDefaultPort() throws Exception {
        URL original = new URL("http://example.com:80/x");
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        assertEquals("http://93.184.216.34:80/x",
                SsrfHostPolicy.buildPinnedUrl(original, addr).toString());
    }

    public void testHostHeaderValueStripsDefaultPorts() throws Exception {
        assertEquals("example.com",
                SsrfHostPolicy.hostHeaderValue(new URL("http://example.com/a")));
        assertEquals("example.com",
                SsrfHostPolicy.hostHeaderValue(new URL("https://example.com:443/a")));
        assertEquals("example.com:8443",
                SsrfHostPolicy.hostHeaderValue(new URL("https://example.com:8443/a")));
        assertEquals("example.com:8080",
                SsrfHostPolicy.hostHeaderValue(new URL("http://example.com:8080/a")));
    }
}
