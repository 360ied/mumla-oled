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

package se.lublin.mumla.util;

import junit.framework.TestCase;

public class ChatLinkPolicyTest extends TestCase {

    public void testHttpAllowed() {
        assertTrue(ChatLinkPolicy.isAllowedUrl("http://example.com/"));
    }

    public void testHttpsAllowed() {
        assertTrue(ChatLinkPolicy.isAllowedUrl("https://example.com/path?q=1"));
    }

    public void testDangerousSchemesBlocked() {
        assertFalse(ChatLinkPolicy.isAllowedUrl("javascript:alert(1)"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("intent://example.com/#Intent;scheme=https;end"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("file:///etc/passwd"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("tel:+123456789"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("data:text/html,<script>alert(1)</script>"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("content://contacts/people"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("market://details?id=x"));
    }

    public void testUppercaseSchemeAllowed() {
        assertTrue(ChatLinkPolicy.isAllowedUrl("HTTP://example.com/"));
        assertTrue(ChatLinkPolicy.isAllowedUrl("HTTPS://example.com/"));
        assertTrue(ChatLinkPolicy.isAllowedUrl("HtTpS://example.com/"));
    }

    public void testUppercaseDangerousSchemeStillBlocked() {
        assertFalse(ChatLinkPolicy.isAllowedUrl("JAVASCRIPT:alert(1)"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("FILE:///etc/passwd"));
    }

    public void testWhitespaceTrimmed() {
        assertTrue(ChatLinkPolicy.isAllowedUrl("  https://example.com/  "));
        assertTrue(ChatLinkPolicy.isAllowedUrl("\thttp://example.com/\n"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("   "));
    }

    public void testNullAndEmptyFailClosed() {
        assertFalse(ChatLinkPolicy.isAllowedUrl(null));
        assertFalse(ChatLinkPolicy.isAllowedUrl(""));
    }

    public void testMalformedUriFailsClosed() {
        assertFalse(ChatLinkPolicy.isAllowedUrl("http://exa mple.com/"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("http://[::1"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("not a url at all"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("example.com/no-scheme"));
    }

    public void testShouldInterceptIsNegation() {
        assertTrue(ChatLinkPolicy.shouldIntercept("javascript:alert(1)"));
        assertTrue(ChatLinkPolicy.shouldIntercept(null));
        assertFalse(ChatLinkPolicy.shouldIntercept("https://example.com/"));
    }

    public void testHandleClickReturnsHrefWhenAllowed() {
        assertEquals("https://example.com/real",
                ChatLinkPolicy.handleClick("https://example.com/real"));
    }

    public void testHandleClickNullWhenBlocked() {
        assertNull(ChatLinkPolicy.handleClick("javascript:alert(1)"));
        assertNull(ChatLinkPolicy.handleClick(null));
        assertNull(ChatLinkPolicy.handleClick("tel:+123"));
    }

    public void testHandleClickTrimsWhitespace() {
        assertEquals("https://example.com/",
                ChatLinkPolicy.handleClick("  https://example.com/  "));
    }

    public void testHttpLookalikePrefixBlocked() {
        // "https:evil.com" parses with an https scheme, so it is allowed.
        assertTrue(ChatLinkPolicy.isAllowedUrl("https:evil.com"));
        // But near-miss schemes and scheme-less hosts stay inert.
        assertFalse(ChatLinkPolicy.isAllowedUrl("httpsfoo://evil.com/"));
        assertFalse(ChatLinkPolicy.isAllowedUrl("http.evil.com/path"));
    }
}
