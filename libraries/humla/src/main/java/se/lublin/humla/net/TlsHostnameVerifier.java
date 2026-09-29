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

package se.lublin.humla.net;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * RFC 2818 / RFC 6125 hostname verification for a TLS leaf certificate.
 * Pure Java SE (no Android APIs) so it is unit-testable on the JVM.
 *
 * <p>Matching rules: subjectAltName dNSName (type 2) and iPAddress (type 7)
 * entries first; CN fallback only when no dNSName SAN is present (legacy
 * certs). Wildcards match exactly one left-most DNS label. IP-literal hosts
 * match iPAddress SANs only, never dNSName entries or CN.
 */
public final class TlsHostnameVerifier {
    private TlsHostnameVerifier() {
    }

    /**
     * Canonical form for pin aliases and comparisons: lowercase, no trailing
     * dot. Both the pin write path (activity) and lookup path (factory) must
     * use this so the same host spelled differently hits one alias.
     */
    public static String canonicalizeHost(String host) {
        if (host == null) {
            return null;
        }
        return stripTrailingDot(host).toLowerCase(Locale.US);
    }

    /** Whether the host is a Tor onion address (pin-or-nothing identity). */
    public static boolean isOnionHost(String host) {
        if (host == null) {
            return false;
        }
        return stripTrailingDot(host).toLowerCase(Locale.US).endsWith(".onion");
    }

    /**
     * Whether the string is an IP literal. Callers pass a bare host (no port);
     * anything containing a colon is treated as IPv6 and fails closed on the
     * IP-SAN path if it does not parse.
     */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.contains(":")) {
            return true;
        }
        String stripped = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String[] parts = stripped.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean verifyHostname(String host, X509Certificate leaf) {
        if (host == null || leaf == null) {
            return false;
        }
        String normalized = canonicalizeHost(host);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        List<String> dnsSans = new ArrayList<>();
        List<String> ipSans = new ArrayList<>();
        Collection<List<?>> sans = subjectAlternativeNames(leaf);
        if (sans != null) {
            for (List<?> san : sans) {
                if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)) {
                    continue;
                }
                int type = (Integer) san.get(0);
                Object value = san.get(1);
                if (!(value instanceof String)) {
                    continue;
                }
                if (type == 2) {
                    dnsSans.add((String) value);
                } else if (type == 7) {
                    ipSans.add((String) value);
                }
            }
        }
        // SANs present but unparseable is a hard failure: RFC 6125 forbids CN
        // fallback when SANs exist, and failing closed beats trusting blindly.
        if (sans != null && !sans.isEmpty() && dnsSans.isEmpty() && ipSans.isEmpty()) {
            return false;
        }
        if (isIpLiteral(normalized)) {
            byte[] hostBytes = addressBytes(normalized);
            if (hostBytes == null) {
                return false;
            }
            for (String ipSan : ipSans) {
                byte[] sanBytes = addressBytes(ipSan);
                if (sanBytes != null && java.util.Arrays.equals(hostBytes, sanBytes)) {
                    return true;
                }
            }
            return false;
        }
        if (normalized.endsWith(".onion")) {
            return false;
        }
        for (String dnsSan : dnsSans) {
            if (matchDns(normalized, dnsSan)) {
                return true;
            }
        }
        if (!dnsSans.isEmpty()) {
            return false;
        }
        for (String cn : getCommonNames(leaf)) {
            if (matchDns(normalized, cn)) {
                return true;
            }
        }
        return false;
    }

    private static Collection<List<?>> subjectAlternativeNames(X509Certificate leaf) {
        try {
            return leaf.getSubjectAlternativeNames();
        } catch (java.security.cert.CertificateParsingException e) {
            return null;
        }
    }

    private static byte[] addressBytes(String ip) {
        try {
            return java.net.InetAddress.getByName(stripTrailingDot(ip)).getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Human-readable names the leaf claims to identify (dNSName and iPAddress
     * SANs, or CN fallback). Used in the mismatch dialog so the user sees
     * what the cert is actually valid for.
     */
    public static List<String> claimedNames(X509Certificate leaf) {
        List<String> names = new ArrayList<>();
        if (leaf == null) {
            return names;
        }
        Collection<List<?>> sans = subjectAlternativeNames(leaf);
        if (sans != null) {
            for (List<?> san : sans) {
                if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)) {
                    continue;
                }
                int type = (Integer) san.get(0);
                Object value = san.get(1);
                if ((type == 2 || type == 7) && value instanceof String) {
                    names.add((String) value);
                }
            }
        }
        if (names.isEmpty()) {
            names.addAll(getCommonNames(leaf));
        }
        return names;
    }

    private static boolean matchDns(String host, String pattern) {
        String normalizedPattern = stripTrailingDot(pattern).toLowerCase(Locale.US);
        if (normalizedPattern.isEmpty()) {
            return false;
        }
        if (!normalizedPattern.contains("*")) {
            return host.equals(normalizedPattern);
        }
        if (!normalizedPattern.startsWith("*.") || normalizedPattern.indexOf('*', 1) != -1) {
            return false;
        }
        String suffix = normalizedPattern.substring(1);
        if (!host.endsWith(suffix)) {
            return false;
        }
        String leftLabel = host.substring(0, host.length() - suffix.length());
        return !leftLabel.isEmpty() && leftLabel.indexOf('.') == -1;
    }

    private static List<String> getCommonNames(X509Certificate leaf) {
        List<String> cns = new ArrayList<>();
        for (String rdn : splitDn(leaf.getSubjectX500Principal().getName())) {
            int eq = rdn.indexOf('=');
            if (eq > 0 && rdn.substring(0, eq).trim().equalsIgnoreCase("CN")) {
                cns.add(unescapeDnValue(rdn.substring(eq + 1).trim()));
            }
        }
        return cns;
    }

    private static List<String> splitDn(String dn) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        boolean inQuotes = false;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '"') {
                current.append(c);
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unescapeDnValue(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripTrailingDot(String host) {
        if (host != null && host.endsWith(".") && host.length() > 1) {
            return host.substring(0, host.length() - 1);
        }
        return host;
    }
}
