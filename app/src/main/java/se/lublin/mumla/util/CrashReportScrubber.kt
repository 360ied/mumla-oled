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
 * Pure-JVM redactor for crash report payloads.
 *
 * Redaction list: IPv4/IPv6 literals, user@host tokens, mumble:// URLs,
 * bare DNS hostnames, TLS identity strings (verified host, SAN names,
 * fingerprints), usernames/passwords/tokens, file system paths, and
 * email-shaped tokens.
 *
 * Exception message transform ("message skeleton"): the exception class name
 * and stack frames are kept verbatim; the message body is redacted, and if
 * any redaction fires inside it — or it contains `=`-delimited key material
 * or a credential/user-shaped `key: value` pair — the body is replaced with
 * `<redacted>`. Applies to the first line and to `Caused by:` chain lines;
 * other lines use token redaction only. Bare-host matching is best-effort
 * over a common-TLD allowlist; single-label hosts (no dot) never match.
 */
object CrashReportScrubber {
    private val IPV4: Pattern = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val IPV6: Pattern = Pattern.compile("(?i)(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}")
    private val EMAIL: Pattern = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    // No-TLD form catches single-label hosts (alice@intranet); EMAIL covers
    // the dotted form first so the token reads consistently.
    private val USER_AT_HOST: Pattern = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)*\\b")
    // Stops at whitespace/quotes/brackets; trailing prose punctuation
    // (.,;)]) may ride along — cosmetic, still redacted.
    private val MUMBLE_URL: Pattern = Pattern.compile("(?i)\\bmumble://[^\\s\"'<>]+")
    // Best-effort common-TLD allowlist (unbounded set; single-label hosts
    // never match — see KDoc). Trailing lookahead keeps Java package
    // segments (a.b.app.Foo) from matching.
    private val BARE_HOST: Pattern = Pattern.compile("\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|edu|gov|mil|int|io|dev|app|info|biz|name|me|co|us|eu|uk|ca|au|in|br|it|es|ch|at|be|ie|nz|no|dk|fi|pl|pt|cz|hu|gr|tr|il|ae|sa|za|mx|ar|kr|tw|hk|sg|se|de|fr|nl|ru|cn|jp|xyz|top|site|online|store|tech|club|space|blog|cloud|live|world|today|email|support|onion|local|internal|lan)(?::\\d{1,5})?\\b(?!\\.[A-Za-z0-9])", Pattern.CASE_INSENSITIVE)
    private val FINGERPRINT_COLON: Pattern = Pattern.compile("(?:[0-9A-Fa-f]{2}:){7,}[0-9A-Fa-f]{2}")
    private val FINGERPRINT_HEX: Pattern = Pattern.compile("\\b[0-9A-Fa-f]{32,}\\b")
    private val CREDENTIAL: Pattern = Pattern.compile("(?i)\\b(password|passwd|pwd|token|secret|auth|credential|api[_-]?key)\\s*[:=]\\s*\\S+")
    private val USER_KEY: Pattern = Pattern.compile("(?i)\\b(user(name)?|login)\\s*[:=]\\s*\\S+")
    private val UNIX_PATH: Pattern = Pattern.compile("(?:/data|/home|/sdcard|/storage|/mnt|/emulated|/system|/proc|/cache|/etc|/tmp|/var|/Users)[^\\s:]*")
    private val WINDOWS_PATH: Pattern = Pattern.compile("\\b[A-Za-z]:[\\\\/][^\\s]*")
    private val GENERIC_ABS_PATH: Pattern = Pattern.compile("(?m)(?:^|[\\s\"'\\(=\\[])/[\\w.\\-]+(?:/[\\w.\\-]+)+")

    /**
     * Scrubs a full crash payload (header + stack trace).
     * Never returns null; never throws.
     */
    @JvmStatic
    fun scrub(input: String?): String {
        if (input == null || input.isEmpty()) {
            return ""
        }
        try {
            // Normalize CRLF/lone-CR so lines never carry a trailing \r.
            val lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            if (lines.isEmpty()) {
                return ""
            }
            val first = skeletonFirstLine(lines[0])
            val rest = StringBuilder()
            rest.append(first)
            for (i in 1 until lines.size) {
                rest.append("\n")
                rest.append(skeletonCausedByOrGeneral(lines[i]))
            }
            return rest.toString()
        } catch (_: Throwable) {
            // Crash path must never throw (swallows ThreadDeath/OOM by intent).
            return ""
        }
    }

    private fun skeletonCausedByOrGeneral(line: String): String {
        // "Caused by:" chains carry exception messages on later lines;
        // apply the same guilty-until-proven-innocent collapse there,
        // token redaction elsewhere.
        val prefix = line.indexOf("Caused by:")
        if (prefix < 0) {
            return redactGeneral(line)
        }
        val head = line.substring(0, prefix + "Caused by:".length)
        val tail = line.substring(prefix + "Caused by:".length)
        val sep = tail.indexOf(": ")
        if (sep < 0) {
            return redactGeneral(line)
        }
        val classPart = tail.substring(0, sep)
        val messagePart = tail.substring(sep + 2)
        val redactedMessage = redactGeneral(messagePart)
        val fired = redactedMessage != messagePart
        // "=" collapses everything; credential/user patterns add the
        // colon-form ("password: x") signal that "=" alone would miss.
        val keyMaterial = messagePart.contains("=") ||
            CREDENTIAL.matcher(messagePart).find() ||
            USER_KEY.matcher(messagePart).find()
        val body = if (fired || keyMaterial) "<redacted>" else redactedMessage
        return redactGeneral(head + classPart) + ": " + body
    }

    private fun skeletonFirstLine(firstLine: String): String {
        // Preserve "com.foo.BarException" prefix; transform the message after ": ".
        val colon = firstLine.indexOf(": ")
        if (colon < 0) {
            return redactGeneral(firstLine)
        }
        val classPart = firstLine.substring(0, colon)
        val messagePart = firstLine.substring(colon + 2)
        val redactedMessage = redactGeneral(messagePart)
        val fired = redactedMessage != messagePart
        // "=" collapses everything; credential/user patterns add the
        // colon-form ("password: x") signal that "=" alone would miss.
        val keyMaterial = messagePart.contains("=") ||
            CREDENTIAL.matcher(messagePart).find() ||
            USER_KEY.matcher(messagePart).find()
        val body = if (fired || keyMaterial) {
            "<redacted>"
        } else {
            redactedMessage
        }
        return redactGeneral(classPart) + ": " + body
    }

    private fun redactGeneral(s: String): String {
        var out = s
        out = MUMBLE_URL.matcher(out).replaceAll("[MUMBLE-URL-REDACTED]")
        out = EMAIL.matcher(out).replaceAll("[EMAIL-REDACTED]")
        out = USER_AT_HOST.matcher(out).replaceAll("[USER-HOST-REDACTED]")
        out = FINGERPRINT_COLON.matcher(out).replaceAll("[FINGERPRINT-REDACTED]")
        out = FINGERPRINT_HEX.matcher(out).replaceAll("[FINGERPRINT-REDACTED]")
        out = IPV4.matcher(out).replaceAll("[IP-REDACTED]")
        // IPv6 after IPv4 so embedded v4-in-v6 still collapses; "::" alone is skipped.
        val ipv6m = IPV6.matcher(out)
        val sb = StringBuffer()
        while (ipv6m.find()) {
            val hit = ipv6m.group()
            if (hit.contains(":") && hit.length > 2) {
                ipv6m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("[IP-REDACTED]"))
            } else {
                ipv6m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(hit))
            }
        }
        ipv6m.appendTail(sb)
        out = sb.toString()
        // Hex blobs (UUID/MD5/SHA) intentionally collapse: guilty until proven innocent.
        out = CREDENTIAL.matcher(out).replaceAll("${'$'}1=[CREDENTIAL-REDACTED]")
        out = USER_KEY.matcher(out).replaceAll("${'$'}1=[USER-REDACTED]")
        out = UNIX_PATH.matcher(out).replaceAll("[PATH-REDACTED]")
        out = WINDOWS_PATH.matcher(out).replaceAll("[PATH-REDACTED]")
        out = GENERIC_ABS_PATH.matcher(out).replaceAll("[PATH-REDACTED]")
        out = BARE_HOST.matcher(out).replaceAll("[HOST-REDACTED]")
        return out
    }
}
