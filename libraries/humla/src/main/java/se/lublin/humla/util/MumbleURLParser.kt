/*
 * Copyright (C) 2014 Andrew Comminos
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

    private val URL_PATTERN: Pattern =
        Pattern.compile("mumble://(([^:]+)?(:(.+?))?@)?(.+?)(:([0-9]+?))?/")

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
        if (matcher.find()) {
            val username = matcher.group(2)
            val password = matcher.group(4)
            val host = matcher.group(5)
            val portString = matcher.group(7)
            var port = Constants.DEFAULT_PORT
            if (portString != null) {
                try {
                    port = portString.toInt()
                } catch (e: NumberFormatException) {
                    throw MalformedURLException("Invalid port: " + portString)
                }
                if (port > 65535) {
                    throw MalformedURLException("Port out of range: " + portString)
                }
            }
            return Server(-1, null, host, port, username, password)
        } else {
            throw MalformedURLException()
        }
    }
}
