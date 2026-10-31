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

package se.lublin.mumla.util

import java.util.regex.Pattern

/**
 * Minimal redactor for crash report payloads: credential-bearing material
 * only — `mumble://` URLs (which can carry `user:password@`), plus
 * password/token-style and username/login-style `key=value` material.
 *
 * Deliberately narrow: hostnames, IPs, hashes, paths, and stack content
 * pass through verbatim so reports stay debuggable (native tombstone
 * build IDs must survive for symbolization). Server addresses may appear
 * in shared reports; the detail screen warns before sharing.
 *
 * Single-token values only: `password=my secret` redacts the first token
 * (`my`); the remainder stays. Typical tokens/passwords are spaceless, and
 * extending to end-of-line would over-redact same-line stack frames —
 * accepted limitation, documented here.
 *
 * Never returns null; never throws.
 */
object CrashReportScrubber {
    // Stops at whitespace/quotes/brackets; trailing prose punctuation
    // (.,;)]) may ride along — cosmetic, still redacted.
    private val MUMBLE_URL: Pattern = Pattern.compile("(?i)\\bmumble://[^\\s\"'<>]+")
    // Credential keys allow [_\\w-]* prefixes so underscore-joined forms
    // (access_token, refresh_token, client_secret) still match: '_' is a
    // word char, so \\b alone never fires inside them. Space allowed in
    // "api key" via [ _-]?.
    private val CREDENTIAL: Pattern = Pattern.compile("(?i)\\b([\\w-]*?(password|passwd|pwd|token|secret|credential|api[ _-]?key|auth|bearer))\\s*[:=]\\s*\\S+")
    // Bare "auth" cannot match "Authorization:" ("orization" intervenes
    // before ':'), so the header and bearer forms get their own pattern.
    private val AUTH_HEADER: Pattern = Pattern.compile("(?i)\\b(authorization\\s*:[^\\n\\r]+|bearer\\s+\\S+)")
    private val USER_KEY: Pattern = Pattern.compile("(?i)\\b([\\w-]*?(user(name)?|login))\\s*[:=]\\s*\\S+")

    /**
     * Scrubs a full crash payload (header + stack trace).
     * Never returns null; never throws.
     */
    @JvmStatic
    fun scrub(input: String?): String {
        if (input.isNullOrEmpty()) {
            return ""
        }
        try {
            // Normalize CRLF/lone-CR so lines never carry a trailing \r.
            var out = input.replace("\r\n", "\n").replace('\r', '\n')
            out = MUMBLE_URL.matcher(out).replaceAll("[MUMBLE-URL-REDACTED]")
            // Hex blobs and the like intentionally survive: guilty until
            // proven innocent applies to credentials, not debug data.
            out = CREDENTIAL.matcher(out).replaceAll("${'$'}1=[CREDENTIAL-REDACTED]")
            out = AUTH_HEADER.matcher(out).replaceAll("[AUTH-REDACTED]")
            out = USER_KEY.matcher(out).replaceAll("${'$'}1=[USER-REDACTED]")
            return out
        } catch (_: Throwable) {
            // Crash path must never throw (swallows ThreadDeath/OOM by intent).
            return ""
        }
    }
}
