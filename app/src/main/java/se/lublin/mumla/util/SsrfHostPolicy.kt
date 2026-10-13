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

package se.lublin.mumla.util

import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * Pure-JVM SSRF policy for server-supplied image URLs.
 *
 * No Android imports: this class is unit-testable on the desktop JVM
 * without network access. Callers perform the actual DNS resolution
 * ({@link InetAddress#getAllByName(String)}) and pass the results to
 * {@link #isAnyAddressBlocked(InetAddress[])}; literal IP hosts are
 * classified without DNS via {@link #isLiteralBlocked(String)}.
 *
 * DNS-rebinding TOCTOU: the pre-connect DNS check alone is best-effort — a rebind
 * between the check and {@code connect()} would defeat it. Image fetches therefore
 * resolve once per hop and connect to the checked address: {@code MumbleImageGetter}
 * opens an IP-literal URL (no second DNS lookup) while presenting the original
 * hostname via the Host header, SNI, and the platform hostname verifier.
 *
 * Transition-mechanism coverage: IPv4-mapped, IPv4-compatible, 6to4,
 * Teredo, ISATAP, the well-known NAT64 {@code 64:ff9b::/96}, and the
 * local-use NAT64 {@code 64:ff9b:1::/48} are unwrapped and classified by
 * their embedded IPv4. Operator-specific NAT64 prefixes and 6rd stay an
 * accepted residual: per RFC 6052 section-2.2 the embedded-IPv4 offsets
 * depend on a prefix length the app cannot know statically, and
 * heuristic extraction would overblock genuine public IPv6 addresses.
 */
object SsrfHostPolicy {

    /** Maximum number of manual HTTP redirect hops followed per image fetch. */
    const val MAX_REDIRECTS = 5

    /**
     * Normalizes a URL host for policy checks: strips IPv6 brackets,
     * strips trailing-dot FQDN roots, and lowercases.
     *
     * @return normalized host, or null if the input was null.
     */
    @JvmStatic
    fun normalizeHost(host: String?): String? {
        if (host == null) {
            return null
        }
        var normalized = host.trim()
        if (normalized.length >= 2 &&
            normalized[0] == '[' &&
            normalized[normalized.length - 1] == ']'
        ) {
            normalized = normalized.substring(1, normalized.length - 1).trim()
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized.lowercase(Locale.ROOT)
    }

    /**
     * Rejects URLs carrying userinfo (user@host): credentials in
     * server-supplied URLs enable phishing and parser differentials.
     */
    @JvmStatic
    fun hasUserinfo(url: URL?): Boolean {
        return url != null && url.userInfo != null
    }

    /** Redirect statuses followed by the manual redirect loop. */
    @JvmStatic
    fun isRedirect(responseCode: Int): Boolean {
        return responseCode == 301 ||
            responseCode == 302 ||
            responseCode == 303 ||
            responseCode == 307 ||
            responseCode == 308
    }

    /**
     * Pure guard for the manual redirect loop: follow only redirect
     * statuses with a non-empty Location while fewer than
     * {@link #MAX_REDIRECTS} hops have been followed. Chains longer than
     * the cap are stopped, never followed.
     */
    @JvmStatic
    fun shouldFollowRedirect(redirectsFollowed: Int, responseCode: Int, location: String?): Boolean {
        return redirectsFollowed < MAX_REDIRECTS &&
            isRedirect(responseCode) &&
            location != null &&
            location.isNotEmpty()
    }

    /**
     * Classifies one resolved address. Fails closed on null or
     * unrecognized address lengths.
     */
    @JvmStatic
    fun isBlockedAddress(address: InetAddress?): Boolean {
        if (address == null) {
            return true
        }
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val raw = address.address
        if (raw.size == 4) {
            return isBlockedIPv4(raw)
        }
        if (raw.size == 16) {
            return isBlockedIPv6(raw)
        }
        return true
    }

    /**
     * Classifies a host string without DNS: IP literals are parsed and
     * checked, hostnames return false so the caller falls through to the
     * resolving {@link #isAnyAddressBlocked(InetAddress[])} check.
     * Unparseable literals fail closed.
     */
    @JvmStatic
    fun isLiteralBlocked(host: String?): Boolean {
        val normalized = normalizeHost(host)
        if (normalized == null || normalized.isEmpty()) {
            return true
        }
        if (!isLiteralNormalized(normalized)) {
            return false
        }
        try {
            return isBlockedAddress(InetAddress.getByName(normalized))
        } catch (e: UnknownHostException) {
            return true
        }
    }

    /**
     * True if any resolved address is blocked. Null or empty results fail
     * closed.
     */
    @JvmStatic
    fun isAnyAddressBlocked(addresses: Array<InetAddress?>?): Boolean {
        if (addresses == null || addresses.isEmpty()) {
            return true
        }
        for (address in addresses) {
            if (isBlockedAddress(address)) {
                return true
            }
        }
        return false
    }

    /**
     * Builds the fetch URL for one already-checked address: same scheme, port,
     * and path as the original, host replaced by the IP literal (bracketed for
     * IPv6) so the connection performs no second DNS lookup. Call only with
     * addresses that already passed {@link #isAnyAddressBlocked}.
     *
     * Widened to public: Kotlin has no package visibility (was package-visible
     * in Java for the same-package caller).
     */
    @JvmStatic
    @Throws(MalformedURLException::class)
    fun buildPinnedUrl(original: URL, address: InetAddress): URL {
        var literal = address.hostAddress
        if (literal.indexOf(':') >= 0) {
            literal = "[$literal]"
        }
        val port = original.port
        val authority = if (port == -1) literal else "$literal:$port"
        return URL(original.protocol + "://" + authority + original.file)
    }

    /**
     * Value for the Host header, preserving virtual-host routing through the pinned
     * connection. The host is normalized (lowercased, trailing dot stripped) with
     * IPv6 literals re-bracketed, so header identity matches SNI and verification.
     * Call only with URLs carrying a checked host (see resolveAndCheck).
     *
     * Widened to public: Kotlin has no package visibility (was package-visible
     * in Java for the same-package caller).
     */
    @JvmStatic
    fun hostHeaderValue(original: URL): String? {
        var host = normalizeHost(original.host)
        if (host != null && host.indexOf(':') >= 0) {
            host = "[$host]"
        }
        val port = original.port
        if (port == -1 || port == original.defaultPort) {
            return host
        }
        return "$host:$port"
    }

    /**
     * Conservative literal detector (also used by callers): IPv6 contains
     * a colon; IPv4 is digits and dots (plus all-digit single-number
     * forms, which resolvers parse numerically). Exotic numeric forms
     * (hex, octal) are not literals here on purpose — they fall through
     * to the mandatory resolving DNS check instead of risking hostname
     * overblocking.
     */
    @JvmStatic
    fun looksLikeIpLiteral(host: String?): Boolean {
        return isLiteralNormalized(normalizeHost(host))
    }

    private fun isLiteralNormalized(normalized: String?): Boolean {
        if (normalized == null || normalized.isEmpty()) {
            return false
        }
        if (normalized.indexOf(':') >= 0) {
            return true
        }
        for (i in normalized.indices) {
            val c = normalized[i]
            if ((c < '0' || c > '9') && c != '.') {
                return false
            }
        }
        return true
    }

    // Widened to public for testing: Kotlin has no package visibility.
    // Pins the explicit branches shadowed by the generic InetAddress
    // predicates in isBlockedAddress() (see ODD-12).
    @JvmStatic
    fun isBlockedIPv4(addr: ByteArray?): Boolean {
        if (addr == null || addr.size != 4) {
            return true
        }
        val b0 = addr[0].toInt() and 0xFF
        val b1 = addr[1].toInt() and 0xFF
        val b2 = addr[2].toInt() and 0xFF
        if (b0 == 0) {
            return true // 0.0.0.0/8 ("this network")
        }
        if (b0 == 127) {
            return true // 127.0.0.0/8 loopback (embedded forms bypass InetAddress predicates)
        }
        if (b0 == 169 && b1 == 254) {
            return true // 169.254.0.0/16 link-local (embedded forms bypass InetAddress predicates)
        }
        if ((b0 and 0xF0) == 0xE0) {
            return true // 224.0.0.0/4 multicast (embedded forms bypass InetAddress predicates)
        }
        if (b0 == 10) {
            return true // 10.0.0.0/8
        }
        if (b0 == 172 && (b1 and 0xF0) == 16) {
            return true // 172.16.0.0/12
        }
        if (b0 == 192 && b1 == 168) {
            return true // 192.168.0.0/16
        }
        if (b0 == 100 && (b1 and 0xC0) == 64) {
            return true // 100.64.0.0/10 CGNAT
        }
        if ((b0 and 0xF0) == 240) {
            return true // 240.0.0.0/4 reserved + limited broadcast
        }
        if (b0 == 192 && b1 == 0 && b2 == 2) {
            return true // 192.0.2.0/24 TEST-NET-1
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {
            return true // 198.51.100.0/24 TEST-NET-2
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {
            return true // 203.0.113.0/24 TEST-NET-3
        }
        if (b0 == 192 && b1 == 88 && b2 == 99) {
            return true // 192.88.99.0/24 deprecated 6to4 relay
        }
        if (b0 == 198 && (b1 and 0xFE) == 18) {
            return true // 198.18.0.0/15 benchmarking
        }
        return false
    }

    // Widened to public for testing: Kotlin has no package visibility.
    // Pins the explicit branches shadowed by the generic InetAddress
    // predicates in isBlockedAddress() (see ODD-12).
    @JvmStatic
    fun isBlockedIPv6(addr: ByteArray?): Boolean {
        if (addr == null || addr.size != 16) {
            return true
        }
        if (isIPv4Mapped(addr)) {
            return isBlockedIPv4(byteArrayOf(addr[12], addr[13], addr[14], addr[15]))
        }
        if (isIPv4Compatible(addr)) {
            return isBlockedIPv4(byteArrayOf(addr[12], addr[13], addr[14], addr[15]))
        }
        if (isNat64(addr)) {
            // NAT64 64:ff9b::/96 embeds the IPv4 target in the last 32 bits.
            return isBlockedIPv4(byteArrayOf(addr[12], addr[13], addr[14], addr[15]))
        }
        // OR-combined: several transition embeddings can coincide in one crafted
        // literal (ISATAP constrains only the IID), so every matched embedding is
        // evaluated and any blocked one refuses the address. Do not early-return
        // between these three checks (see phase-8 plan, ODD-11).
        var transitionBlocked = false
        if (isTeredo(addr)) {
            // RFC 4380: 2001::/32. Bytes 4-7 are the server IPv4; the last 32 bits
            // are the client IPv4 XOR 0xFFFFFFFF. Either can carry the target.
            transitionBlocked = transitionBlocked or isBlockedIPv4(byteArrayOf(addr[4], addr[5], addr[6], addr[7]))
            transitionBlocked = transitionBlocked or isBlockedIPv4(
                byteArrayOf(
                    (addr[12].toInt() xor 0xFF).toByte(), (addr[13].toInt() xor 0xFF).toByte(),
                    (addr[14].toInt() xor 0xFF).toByte(), (addr[15].toInt() xor 0xFF).toByte()
                )
            )
        }
        if (isIsatap(addr)) {
            // RFC 5214: interface identifier 00-00-5E-FE (u/l bit masked) + IPv4.
            transitionBlocked = transitionBlocked or isBlockedIPv4(byteArrayOf(addr[12], addr[13], addr[14], addr[15]))
        }
        if (isNat64LocalUse(addr)) {
            // RFC 8215 + RFC 6052 section-2.2 /48 row: prefix 0064:ff9b:0001,
            // u octet (byte 8) zero; v4 = bytes 6,7,9,10. Suffix ignored per
            // RFC 6052 section-2.3 (translators ignore nonzero suffix).
            transitionBlocked = transitionBlocked or isBlockedIPv4(byteArrayOf(addr[6], addr[7], addr[9], addr[10]))
        }
        if (transitionBlocked) {
            return true
        }
        val b0 = addr[0].toInt() and 0xFF
        val b1 = addr[1].toInt() and 0xFF
        if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) {
            return true // fec0::/10 deprecated site-local
        }
        if ((b0 and 0xFE) == 0xFC) {
            return true // fc00::/7 unique-local
        }
        if (b0 == 0x20 && b1 == 0x01 && addr[2].toInt() == 0x0D && addr[3] == 0xB8.toByte()) {
            return true // 2001:db8::/32 documentation
        }
        if (b0 == 0x20 && b1 == 0x02) {
            // 6to4 2002::/16 embeds the IPv4 endpoint in bytes 2-5.
            return isBlockedIPv4(byteArrayOf(addr[2], addr[3], addr[4], addr[5]))
        }
        return false
    }

    private fun isIPv4Mapped(addr: ByteArray): Boolean {
        for (i in 0 until 10) {
            if (addr[i].toInt() != 0) {
                return false
            }
        }
        return addr[10] == 0xFF.toByte() && addr[11] == 0xFF.toByte()
    }

    private fun isNat64(addr: ByteArray): Boolean {
        return addr[0].toInt() == 0x00 &&
            addr[1].toInt() == 0x64 &&
            addr[2] == 0xFF.toByte() &&
            addr[3] == 0x9B.toByte() &&
            addr[4].toInt() == 0x00 &&
            addr[5].toInt() == 0x00 &&
            addr[6].toInt() == 0x00 &&
            addr[7].toInt() == 0x00 &&
            addr[8].toInt() == 0x00 &&
            addr[9].toInt() == 0x00 &&
            addr[10].toInt() == 0x00 &&
            addr[11].toInt() == 0x00
    }

    private fun isTeredo(addr: ByteArray): Boolean {
        return addr[0].toInt() == 0x20 && addr[1].toInt() == 0x01 && addr[2].toInt() == 0x00 && addr[3].toInt() == 0x00
    }

    private fun isIsatap(addr: ByteArray): Boolean {
        return (addr[8].toInt() and 0xFD) == 0x00 && addr[9].toInt() == 0x00 &&
            addr[10].toInt() == 0x5E && addr[11] == 0xFE.toByte()
    }

    private fun isNat64LocalUse(addr: ByteArray): Boolean {
        return addr[0].toInt() == 0x00 && addr[1].toInt() == 0x64 &&
            addr[2] == 0xFF.toByte() && addr[3] == 0x9B.toByte() &&
            addr[4].toInt() == 0x00 && addr[5].toInt() == 0x01 &&
            addr[8].toInt() == 0x00 // u octet must be zero; suffix bytes 11-15 ignored
    }

    private fun isIPv4Compatible(addr: ByteArray): Boolean {
        for (i in 0 until 12) {
            if (addr[i].toInt() != 0) {
                return false
            }
        }
        return true
    }
}
