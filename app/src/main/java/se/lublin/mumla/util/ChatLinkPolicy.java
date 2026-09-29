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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Allowlist policy for links rendered from server-controlled chat HTML.
 *
 * <p>Incoming chat bodies may carry arbitrary {@code href} values; only
 * {@code http} and {@code https} links may be dispatched to an external
 * viewer. Every other scheme ({@code javascript:}, {@code intent:},
 * {@code file:}, {@code tel:}, {@code data:}, ...) is inert.
 *
 * <p>Pure JVM helper: no {@code android.*} imports so it stays unit
 * testable. Callers replace {@code URLSpan}s (or gate a custom
 * {@code MovementMethod}) using {@link #shouldIntercept} /
 * {@link #handleClick}.
 */
public final class ChatLinkPolicy {
    private ChatLinkPolicy() {
    }

    /**
     * Returns true only for {@code http}/{@code https} URLs.
     * Fail-closed: null, empty, whitespace-only, and malformed URIs
     * return false.
     */
    public static boolean isAllowedUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    /**
     * Returns true when a link must be rendered inert (the negation of
     * {@link #isAllowedUrl}), suitable for span replacement or a custom
     * {@code MovementMethod}.
     */
    public static boolean shouldIntercept(String url) {
        return !isAllowedUrl(url);
    }

    /**
     * Resolves the URL to open for a clicked link.
     *
     * @param href the span {@code href}; the visible display text is
     *             deliberately not taken, so a phishing lookalike (display
     *             text differing from the href) still opens the actual
     *             href when allowed, never the display text.
     * @return trimmed href when allowed, null when the link must stay inert.
     */
    public static String handleClick(String href) {
        if (!isAllowedUrl(href)) {
            return null;
        }
        return href.trim();
    }
}
