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

import junit.framework.TestCase;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Tests for the Phase 1 identity helpers using locally minted certificates.
 * No network, no Android APIs, no trust store I/O.
 */
public class TlsIdentityTest extends TestCase {

    public void testExactDnsMatch() throws Exception {
        X509Certificate leaf = CertMint.leaf("example.com");
        assertTrue(TlsHostnameVerifier.verifyHostname("example.com", leaf));
    }

    public void testWrongHostRejected() throws Exception {
        X509Certificate leaf = CertMint.leaf("other.example");
        assertFalse(TlsHostnameVerifier.verifyHostname("example.com", leaf));
    }

    public void testCaseAndTrailingDot() throws Exception {
        X509Certificate leaf = CertMint.leaf("Example.COM");
        assertTrue(TlsHostnameVerifier.verifyHostname("example.com.", leaf));
        assertTrue(TlsHostnameVerifier.verifyHostname("EXAMPLE.COM", leaf));
    }

    public void testCnFallbackWhenNoSans() throws Exception {
        X509Certificate leaf = CertMint.cnOnly("legacy.example");
        assertTrue(TlsHostnameVerifier.verifyHostname("legacy.example", leaf));
        assertFalse(TlsHostnameVerifier.verifyHostname("other.example", leaf));
    }

    public void testSanPreferredOverCn() throws Exception {
        X509Certificate leaf = CertMint.sanAndCn("san.example", "cn.example");
        assertTrue(TlsHostnameVerifier.verifyHostname("san.example", leaf));
        // CN must not be consulted while a dNSName SAN is present.
        assertFalse(TlsHostnameVerifier.verifyHostname("cn.example", leaf));
    }

    public void testSingleLabelWildcard() throws Exception {
        X509Certificate leaf = CertMint.leaf("*.example.com");
        assertTrue(TlsHostnameVerifier.verifyHostname("voice.example.com", leaf));
        assertFalse(TlsHostnameVerifier.verifyHostname("deep.voice.example.com", leaf));
        assertFalse(TlsHostnameVerifier.verifyHostname("example.com", leaf));
    }

    public void testIpLiteralMatchesIpSanOnly() throws Exception {
        X509Certificate leaf = CertMint.ipLeaf("10.1.2.3");
        assertTrue(TlsHostnameVerifier.verifyHostname("10.1.2.3", leaf));
        assertFalse(TlsHostnameVerifier.verifyHostname("10.1.2.4", leaf));

        X509Certificate dnsLeaf = CertMint.leaf("10.1.2.3");
        assertFalse(TlsHostnameVerifier.verifyHostname("10.1.2.3", dnsLeaf));

        X509Certificate cnLeaf = CertMint.cnOnly("10.1.2.3");
        assertFalse(TlsHostnameVerifier.verifyHostname("10.1.2.3", cnLeaf));
    }

    public void testIpv6CanonicalFormsMatch() throws Exception {
        X509Certificate leaf = CertMint.ipLeaf("2001:db8::1");
        assertTrue(TlsHostnameVerifier.verifyHostname("2001:db8::1", leaf));
        assertTrue(TlsHostnameVerifier.verifyHostname("2001:0db8:0000:0000:0000:0000:0000:0001", leaf));
        assertTrue(TlsHostnameVerifier.verifyHostname("2001:DB8::1", leaf));
        assertFalse(TlsHostnameVerifier.verifyHostname("2001:db8::2", leaf));
    }

    public void testIsIpLiteralForms() {
        assertTrue(TlsHostnameVerifier.isIpLiteral("10.1.2.3"));
        assertTrue(TlsHostnameVerifier.isIpLiteral("2001:db8::1"));
        assertFalse(TlsHostnameVerifier.isIpLiteral("example.com"));
        assertFalse(TlsHostnameVerifier.isIpLiteral(null));
    }

    public void testClaimedNamesIncludesIpAndCnFallback() throws Exception {
        assertTrue(TlsHostnameVerifier.claimedNames(CertMint.ipLeaf("10.1.2.3")).contains("10.1.2.3"));
        assertTrue(TlsHostnameVerifier.claimedNames(CertMint.leaf("san.example")).contains("san.example"));
        assertTrue(TlsHostnameVerifier.claimedNames(CertMint.cnOnly("cn.example")).contains("cn.example"));
        assertTrue(TlsHostnameVerifier.claimedNames(null).isEmpty());
    }

    public void testWildcardEdgesRejected() throws Exception {
        assertFalse(TlsHostnameVerifier.verifyHostname("a.example.com", CertMint.leaf("*")));
        assertFalse(TlsHostnameVerifier.verifyHostname("ab.example.com", CertMint.leaf("a*b.example.com")));
        assertFalse(TlsHostnameVerifier.verifyHostname("a.b.example.com", CertMint.leaf("*.*.example.com")));
    }

    public void testCanonicalizeHost() {
        assertEquals("example.com", TlsHostnameVerifier.canonicalizeHost("EXAMPLE.COM."));
        assertNull(TlsHostnameVerifier.canonicalizeHost(null));
        assertTrue(TlsHostnameVerifier.isOnionHost("Example.ONION."));
        assertFalse(TlsHostnameVerifier.isOnionHost("example.com"));
    }

    public void testSameSpki() throws Exception {
        X509Certificate a = CertMint.leaf("example.com");
        X509Certificate b = CertMint.leaf("example.com");
        // Distinct keys: sameSpki is false, and reflexivity holds.
        assertFalse(HandshakeFailure.sameSpki(a, b));
        assertTrue(HandshakeFailure.sameSpki(a, a));
        assertFalse(HandshakeFailure.sameSpki(a, null));
    }

    public void testSpkiSha256Stable() throws Exception {
        X509Certificate leaf = CertMint.leaf("example.com");
        byte[] first = HandshakeFailure.spkiSha256(leaf);
        byte[] second = HandshakeFailure.spkiSha256(leaf);
        assertEquals(32, first.length);
        assertTrue(java.util.Arrays.equals(first, second));
    }

    public void testFrameValidator() {
        int types = HumlaTCPMessageType.values().length;
        assertEquals(FrameValidator.FrameError.NONE,
                FrameValidator.validateFrame((short) 0, 0, types));
        assertEquals(FrameValidator.FrameError.NONE,
                FrameValidator.validateFrame((short) 3, FrameValidator.MAX_FRAME_BYTES, types));
        assertEquals(FrameValidator.FrameError.BAD_TYPE,
                FrameValidator.validateFrame((short) -1, 10, types));
        assertEquals(FrameValidator.FrameError.BAD_TYPE,
                FrameValidator.validateFrame((short) types, 10, types));
        assertEquals(FrameValidator.FrameError.NEGATIVE_LENGTH,
                FrameValidator.validateFrame((short) 0, -1, types));
        assertEquals(FrameValidator.FrameError.OVERLARGE_LENGTH,
                FrameValidator.validateFrame((short) 0, FrameValidator.MAX_FRAME_BYTES + 1, types));
        // Type checked first: a bad type with a bad length still reports BAD_TYPE.
        assertEquals(FrameValidator.FrameError.BAD_TYPE,
                FrameValidator.validateFrame((short) types, -5, types));
        // Default overload pins the live message-type universe.
        assertEquals(FrameValidator.FrameError.NONE,
                FrameValidator.validateFrame((short) 0, 0));
        assertEquals(FrameValidator.FrameError.BAD_TYPE,
                FrameValidator.validateFrame((short) FrameValidator.MESSAGE_TYPE_COUNT, 0));
    }

    /** Minimal DER certificate minter for hostname/SAN matrix tests. */
    static final class CertMint {
        private CertMint() {
        }

        static X509Certificate leaf(String dnsSan) throws Exception {
            return mint(dnsSan, null, null);
        }

        static X509Certificate ipLeaf(String ipSan) throws Exception {
            return mint(null, ipSan, null);
        }

        static X509Certificate cnOnly(String cn) throws Exception {
            return mint(null, null, cn);
        }

        static X509Certificate sanAndCn(String dnsSan, String cn) throws Exception {
            return mint(dnsSan, null, cn);
        }

        private static X509Certificate mint(String dnsSan, String ipSan, String cn) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();
            // Subject CN defaults to the SAN so subject/issuer logging stays sane.
            String subjectCn = cn != null ? cn : (dnsSan != null ? dnsSan : ipSan);
            TestCertificateBuilder builder = new TestCertificateBuilder(
                    keyPair, new X500Principal("CN=" + subjectCn), dnsSan, ipSan);
            return builder.build();
        }
    }

    /**
     * Tiny self-signed v3 certificate builder: TBSCertificate with optional
     * subjectAltName, SHA256withRSA signature. Test-only; not for production use.
     */
    static final class TestCertificateBuilder {
        private final KeyPair keyPair;
        private final X500Principal subject;
        private final String dnsSan;
        private final String ipSan;

        TestCertificateBuilder(KeyPair keyPair, X500Principal subject, String dnsSan, String ipSan) {
            this.keyPair = keyPair;
            this.subject = subject;
            this.dnsSan = dnsSan;
            this.ipSan = ipSan;
        }

        X509Certificate build() throws Exception {
            Date now = new Date();
            Date expiry = new Date(now.getTime() + 86400000L);
            byte[] tbs = tbsCertificate(now, expiry);
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(tbs);
            byte[] signature = signer.sign();
            byte[] cert = derSequence(
                    tbs,
                    derSequence(derOid("1.2.840.113549.1.1.11"), derNull()),
                    derBitString(signature));
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(cert));
        }

        private byte[] tbsCertificate(Date notBefore, Date notAfter) throws Exception {
            byte[] version = derExplicit(0, derInteger(new byte[] { 0x02 }));
            byte[] serial = derInteger(new byte[] { 0x01 });
            byte[] sigAlg = derSequence(derOid("1.2.840.113549.1.1.11"), derNull());
            byte[] name = subject.getEncoded();
            byte[] validity = derSequence(derTime(notBefore), derTime(notAfter));
            byte[] spki = keyPair.getPublic().getEncoded();
            byte[] extensions = extensionsBlock();
            if (extensions == null) {
                return derSequence(version, serial, sigAlg, name, validity, name, spki);
            }
            return derSequence(version, serial, sigAlg, name, validity, name, spki,
                    derExplicit(3, derSequence(extensions)));
        }

        private byte[] extensionsBlock() throws Exception {
            if (dnsSan == null && ipSan == null) {
                return null;
            }
            java.io.ByteArrayOutputStream names = new java.io.ByteArrayOutputStream();
            if (dnsSan != null) {
                byte[] encoded = dnsSan.getBytes("US-ASCII");
                byte[] tagged = derTag(0x82, encoded);
                names.write(tagged, 0, tagged.length);
            }
            if (ipSan != null) {
                byte[] raw = parseIp(ipSan);
                byte[] tagged = derTag(0x87, raw);
                names.write(tagged, 0, tagged.length);
            }
            byte[] sanSequence = derSequence(names.toByteArray());
            byte[] sanOctets = derOctetString(sanSequence);
            return derSequence(derOid("2.5.29.17"), sanOctets);
        }

        private static byte[] parseIp(String ip) throws Exception {
            if (ip.contains(":")) {
                return java.net.InetAddress.getByName(ip).getAddress();
            }
            String[] parts = ip.split("\\.", -1);
            byte[] raw = new byte[4];
            for (int i = 0; i < 4; i++) {
                raw[i] = (byte) Integer.parseInt(parts[i]);
            }
            return raw;
        }

        private static byte[] derSequence(byte[]... parts) {
            return derTag(0x30, concat(parts));
        }

        private static byte[] derExplicit(int tag, byte[] inner) {
            return derTag(0xA0 + tag, inner);
        }

        private static byte[] derInteger(byte[] value) {
            return derTag(0x02, value);
        }

        private static byte[] derNull() {
            return new byte[] { 0x05, 0x00 };
        }

        private static byte[] derOid(String oid) {
            String[] arcs = oid.split("\\.");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(Integer.parseInt(arcs[0]) * 40 + Integer.parseInt(arcs[1]));
            for (int i = 2; i < arcs.length; i++) {
                long arc = Long.parseLong(arcs[i]);
                java.util.Stack<Byte> stack = new java.util.Stack<>();
                stack.push((byte) (arc & 0x7F));
                arc >>= 7;
                while (arc > 0) {
                    stack.push((byte) ((arc & 0x7F) | 0x80));
                    arc >>= 7;
                }
                while (!stack.isEmpty()) {
                    out.write(stack.pop());
                }
            }
            return derTag(0x06, out.toByteArray());
        }

        private static byte[] derBitString(byte[] payload) {
            byte[] withUnused = new byte[payload.length + 1];
            withUnused[0] = 0x00;
            System.arraycopy(payload, 0, withUnused, 1, payload.length);
            return derTag(0x03, withUnused);
        }

        private static byte[] derOctetString(byte[] payload) {
            return derTag(0x04, payload);
        }

        private static byte[] derTime(Date date) throws Exception {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return derTag(0x17, format.format(date).getBytes("US-ASCII"));
        }

        private static byte[] derTag(int tag, byte[] payload) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(tag);
            int length = payload.length;
            if (length < 128) {
                out.write(length);
            } else {
                int size = 0;
                int tmp = length;
                while (tmp > 0) {
                    size++;
                    tmp >>= 8;
                }
                out.write(0x80 | size);
                for (int i = size - 1; i >= 0; i--) {
                    out.write((length >> (8 * i)) & 0xFF);
                }
            }
            out.write(payload, 0, payload.length);
            return out.toByteArray();
        }

        private static byte[] concat(byte[]... parts) {
            int total = 0;
            for (byte[] part : parts) {
                total += part.length;
            }
            byte[] out = new byte[total];
            int offset = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, out, offset, part.length);
                offset += part.length;
            }
            return out;
        }
    }
}
