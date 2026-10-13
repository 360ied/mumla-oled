/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.util

import java.net.MalformedURLException
import java.util.regex.Pattern
import se.lublin.humla.Constants
import se.lublin.humla.model.Server

/**
 * An implementation of the Mumble URL scheme.
 * @see <a href="http://mumble.sourceforge.net/Mumble_URL">http://mumble.sourceforge.net/Mumble_URL</a>
 * Created by andrew on 03/03/14.
 */
object MumbleURLParser {

    // Host is either a bare hostname/IPv4 or a bracketed IPv6 literal; userinfo
    // (when `@` is present) must be non-empty, and the host class excludes `@`
    // and whitespace so `a@b@c` cannot smuggle credentials into the host.
    // Port is validated to 1..65535 below; path/query (trailing slash,
    // channel links, `?query`) is accepted and ignored. Full-match:
    // surrounding junk must not parse.
    private val URL_PATTERN: Pattern =
        Pattern.compile("mumble://(([^:@/\\s]+)?(:(.*?))?@)?(\\[[^\\]]+\\]|[^:@/?\\s]+)(:([0-9]+))?([/?].*)?")

    /**
     * Parses the passed Mumble URL into a Server object.
     * @param url A URL with the Mumble scheme.
     * @return A server with the data specified in the Mumble URL.
     * @throws MalformedURLException if the URL cannot be parsed.
     */
    @JvmStatic
    @Throws(MalformedURLException::class)
    fun parseURL(url: String?): Server {
        if (url == null) {
            throw MalformedURLException("URL is null")
        }
        val matcher = URL_PATTERN.matcher(url)
        if (matcher.matches()) {
            val username = matcher.group(2)?.ifEmpty { null }
            val password = matcher.group(4)?.ifEmpty { null }
            // A bare `@` with empty userinfo is not credentials — reject.
            if (matcher.group(1) != null && username == null && password == null) {
                throw MalformedURLException("Empty userinfo")
            }
            val rawHost = matcher.group(5) ?: throw MalformedURLException()
            // Strip brackets from IPv6 literals ([::1] -> ::1).
            val host = if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
                rawHost.substring(1, rawHost.length - 1)
            } else {
                rawHost
            }
            if (host.isBlank()) throw MalformedURLException("Empty host")
            val portString = matcher.group(7)
            var port = Constants.DEFAULT_PORT
            if (portString != null) {
                try {
                    port = portString.toInt()
                } catch (e: NumberFormatException) {
                    throw MalformedURLException("Invalid port: " + portString)
                }
                if (port < 1 || port > 65535) {
                    throw MalformedURLException("Port out of range: " + portString)
                }
            }
            return Server(-1, null, host, port, username, password)
        } else {
            throw MalformedURLException()
        }
    }
}
