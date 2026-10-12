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

package se.lublin.humla.net

import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.Locale

/**
 * RFC 2818 / RFC 6125 hostname verification for a TLS leaf certificate.
 * Pure Java SE (no Android APIs) so it is unit-testable on the JVM.
 *
 * <p>Matching rules: subjectAltName dNSName (type 2) and iPAddress (type 7)
 * entries first; CN fallback only when no dNSName SAN is present (legacy
 * certs). Wildcards match exactly one left-most DNS label. IP-literal hosts
 * match iPAddress SANs only, never dNSName entries or CN.
 */
object TlsHostnameVerifier {
    /**
     * Canonical form for pin aliases and comparisons: lowercase, no trailing
     * dot. Both the pin write path (activity) and lookup path (factory) must
     * use this so the same host spelled differently hits one alias.
     */
    @JvmStatic
    fun canonicalizeHost(host: String?): String? {
        if (host == null) {
            return null
        }
        return stripTrailingDot(host).lowercase(Locale.US)
    }

    /** Whether the host is a Tor onion address (pin-or-nothing identity). */
    @JvmStatic
    fun isOnionHost(host: String?): Boolean {
        if (host == null) {
            return false
        }
        return stripTrailingDot(host).lowercase(Locale.US).endsWith(".onion")
    }

    /**
     * Whether the string is an IP literal. Callers pass a bare host (no port);
     * anything containing a colon is treated as IPv6 and fails closed on the
     * IP-SAN path if it does not parse.
     */
    @JvmStatic
    fun isIpLiteral(host: String?): Boolean {
        if (host == null || host.isEmpty()) {
            return false
        }
        if (host.contains(":")) {
            return true
        }
        val stripped = if (host.endsWith(".")) host.substring(0, host.length - 1) else host
        val parts = stripped.split('.')
        if (parts.size != 4) {
            return false
        }
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) {
                return false
            }
            for (i in 0 until part.length) {
                if (!Character.isDigit(part[i])) {
                    return false
                }
            }
            try {
                if (part.toInt() > 255) {
                    return false
                }
            } catch (e: NumberFormatException) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun verifyHostname(host: String?, leaf: X509Certificate?): Boolean {
        if (host == null || leaf == null) {
            return false
        }
        val normalized = canonicalizeHost(host)
        if (normalized == null || normalized.isEmpty()) {
            return false
        }
        val dnsSans = ArrayList<String>()
        val ipSans = ArrayList<String>()
        val sans = subjectAlternativeNames(leaf)
        if (sans != null) {
            for (san in sans) {
                if (san == null || san.size < 2 || san[0] !is Int) {
                    continue
                }
                val type = san[0] as Int
                val value = san[1]
                if (value !is String) {
                    continue
                }
                if (type == 2) {
                    dnsSans.add(value)
                } else if (type == 7) {
                    ipSans.add(value)
                }
            }
        }
        // SANs present but unparseable is a hard failure: RFC 6125 forbids CN
        // fallback when SANs exist, and failing closed beats trusting blindly.
        if (sans != null && sans.isNotEmpty() && dnsSans.isEmpty() && ipSans.isEmpty()) {
            return false
        }
        if (isIpLiteral(normalized)) {
            val hostBytes = addressBytes(normalized)
            if (hostBytes == null) {
                return false
            }
            for (ipSan in ipSans) {
                val sanBytes = addressBytes(ipSan)
                if (sanBytes != null && java.util.Arrays.equals(hostBytes, sanBytes)) {
                    return true
                }
            }
            return false
        }
        if (normalized.endsWith(".onion")) {
            return false
        }
        for (dnsSan in dnsSans) {
            if (matchDns(normalized, dnsSan)) {
                return true
            }
        }
        if (dnsSans.isNotEmpty()) {
            return false
        }
        for (cn in getCommonNames(leaf)) {
            if (matchDns(normalized, cn)) {
                return true
            }
        }
        return false
    }

    private fun subjectAlternativeNames(leaf: X509Certificate): Collection<List<*>>? {
        try {
            return leaf.subjectAlternativeNames
        } catch (e: java.security.cert.CertificateParsingException) {
            return null
        }
    }

    private fun addressBytes(ip: String): ByteArray? {
        try {
            return java.net.InetAddress.getByName(stripTrailingDot(ip)).address
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Human-readable names the leaf claims to identify (dNSName and iPAddress
     * SANs, or CN fallback). Used in the mismatch dialog so the user sees
     * what the cert is actually valid for.
     */
    @JvmStatic
    fun claimedNames(leaf: X509Certificate?): List<String> {
        val names = ArrayList<String>()
        if (leaf == null) {
            return names
        }
        val sans = subjectAlternativeNames(leaf)
        if (sans != null) {
            for (san in sans) {
                if (san == null || san.size < 2 || san[0] !is Int) {
                    continue
                }
                val type = san[0] as Int
                val value = san[1]
                if ((type == 2 || type == 7) && value is String) {
                    names.add(value)
                }
            }
        }
        if (names.isEmpty()) {
            names.addAll(getCommonNames(leaf))
        }
        return names
    }

    private fun matchDns(host: String, pattern: String): Boolean {
        val normalizedPattern = stripTrailingDot(pattern).lowercase(Locale.US)
        if (normalizedPattern.isEmpty()) {
            return false
        }
        if (!normalizedPattern.contains("*")) {
            return host == normalizedPattern
        }
        if (!normalizedPattern.startsWith("*.") || normalizedPattern.indexOf('*', 1) != -1) {
            return false
        }
        val suffix = normalizedPattern.substring(1)
        if (!host.endsWith(suffix)) {
            return false
        }
        val leftLabel = host.substring(0, host.length - suffix.length)
        return leftLabel.isNotEmpty() && leftLabel.indexOf('.') == -1
    }

    private fun getCommonNames(leaf: X509Certificate): List<String> {
        val cns = ArrayList<String>()
        for (rdn in splitDn(leaf.subjectX500Principal.name)) {
            val eq = rdn.indexOf('=')
            if (eq > 0 && rdn.substring(0, eq).trim().equals("CN", ignoreCase = true)) {
                cns.add(unescapeDnValue(rdn.substring(eq + 1).trim()))
            }
        }
        return cns
    }

    private fun splitDn(dn: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        var inQuotes = false
        for (i in 0 until dn.length) {
            val c = dn[i]
            if (escaped) {
                current.append(c)
                escaped = false
            } else if (c == '\\') {
                current.append(c)
                escaped = true
            } else if (c == '"') {
                current.append(c)
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun unescapeDnValue(value: String): String {
        var v = value
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
        }
        val out = StringBuilder(v.length)
        var escaped = false
        for (i in 0 until v.length) {
            val c = v[i]
            if (escaped) {
                out.append(c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun stripTrailingDot(host: String): String {
        if (host.endsWith(".") && host.length > 1) {
            return host.substring(0, host.length - 1)
        }
        return host
    }
}
