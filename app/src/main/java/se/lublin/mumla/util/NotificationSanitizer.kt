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

import org.jsoup.Jsoup
import java.util.regex.Pattern

/**
 * Strips hostile server-controlled strings (actor names, message bodies)
 * down to single-line plain text for notifications and TTS.
 * Pure JVM (no Android APIs) so it is unit-testable.
 */
object NotificationSanitizer {
    /** Maximum actor name length after sanitizing. */
    const val MAX_ACTOR_LENGTH = 64

    private val CONTROL_FORMAT_CHARS: Pattern = Pattern.compile("[\\p{Cntrl}\\p{Cf}]")
    private val WHITESPACE_RUN: Pattern = Pattern.compile("\\s+")

    /**
     * Sanitizes a server-provided actor name: HTML-stripped, single-line,
     * control- and format-character free, trimmed, and capped at
     * {@link #MAX_ACTOR_LENGTH} code points.
     * Never returns null.
     */
    @JvmStatic
    fun sanitizeActor(actor: String?): String {
        var clean = stripToSingleLine(actor)
        if (clean.codePointCount(0, clean.length) > MAX_ACTOR_LENGTH) {
            val end = clean.offsetByCodePoints(0, MAX_ACTOR_LENGTH)
            clean = clean.substring(0, end).trim()
        }
        return clean
    }

    /**
     * Sanitizes a server-provided message body: HTML-stripped, single-line,
     * control- and format-character free, and trimmed. Not length-capped;
     * callers gate spoken length separately (e.g. TTS threshold).
     * Never returns null.
     */
    @JvmStatic
    fun sanitizeBody(body: String?): String {
        return stripToSingleLine(body)
    }

    private fun stripToSingleLine(raw: String?): String {
        if (raw == null) {
            return ""
        }
        var text = Jsoup.parseBodyFragment(raw).text()
        text = text.replace('\r', ' ').replace('\n', ' ')
        text = CONTROL_FORMAT_CHARS.matcher(text).replaceAll("")
        return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim()
    }
}
