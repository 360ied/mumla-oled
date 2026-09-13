/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2026 Mumla OLED Contributors
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Generates self-signed X.509 v3 RSA client certificates for Mumble authentication
 * and packages them into a PKCS#12 (.p12) keystore using standard Java SE / Android APIs.
 */
public class HumlaCertificateGenerator {
    private static final String ISSUER_CN = "Humla Client";
    private static final int YEARS_VALID = 20;

    public static X509Certificate generateCertificate(OutputStream output)
            throws NoSuchAlgorithmException, CertificateException, KeyStoreException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        X509Certificate certificate;
        try {
            certificate = generateSelfSignedCertificate(keyPair);
        } catch (GeneralSecurityException e) {
            throw new CertificateException("Failed to generate self-signed certificate", e);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("Mumble Identity", keyPair.getPrivate(), "".toCharArray(), new Certificate[] { certificate });
        keyStore.store(output, "".toCharArray());

        return certificate;
    }

    private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair)
            throws GeneralSecurityException, IOException {
        // 1. Version v3 -> [0] EXPLICIT INTEGER 2
        byte[] version = tlv(0xa0, tlv(0x02, new byte[] { 0x02 }));

        // 2. Serial Number -> INTEGER 1
        byte[] serialNumber = tlv(0x02, new byte[] { 0x01 });

        // 3. Signature Algorithm -> sha256WithRSAEncryption (OID: 1.2.840.113549.1.1.11, NULL parameters)
        byte[] sigAlg = sequence(
                tlv(0x06, new byte[] { 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b }),
                new byte[] { 0x05, 0x00 }
        );

        // 4. Issuer & Subject -> CN=Humla Client
        // RDN: SET of AttributeTypeAndValue { type: id-at-commonName (2.5.4.3), value: UTF8String "Humla Client" }
        byte[] cnValue = tlv(0x0c, ISSUER_CN.getBytes(StandardCharsets.UTF_8));
        byte[] atav = sequence(tlv(0x06, new byte[] { 0x55, 0x04, 0x03 }), cnValue);
        byte[] name = sequence(tlv(0x31, atav));

        // 5. Validity -> SEQUENCE { notBefore UTCTime, notAfter UTCTime }
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date startDate = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        calendar.add(Calendar.YEAR, YEARS_VALID);
        Date endDate = calendar.getTime();

        byte[] validity = sequence(
                tlv(0x17, sdf.format(startDate).getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, sdf.format(endDate).getBytes(StandardCharsets.US_ASCII))
        );

        // 6. SubjectPublicKeyInfo (from Java RSA public key encoded DER)
        byte[] spki = keyPair.getPublic().getEncoded();

        // 7. Extensions -> [3] EXPLICIT SEQUENCE of Extension
        // Extension: SEQUENCE { extnID OBJECT IDENTIFIER, critical BOOLEAN DEFAULT FALSE, extnValue OCTET STRING }

        // a) BasicConstraints (OID: 2.5.29.19): critical = true, CA = false (empty sequence)
        byte[] extBasicConstraints = sequence(
                tlv(0x06, new byte[] { 0x55, 0x1d, 0x13 }),
                tlv(0x01, new byte[] { (byte) 0xff }),
                tlv(0x04, sequence())
        );

        // b) KeyUsage (OID: 2.5.29.15): critical = true, digitalSignature (bit 0) | keyEncipherment (bit 2)
        // BitString: 5 unused bits, byte 0xa0 (10100000)
        byte[] extKeyUsage = sequence(
                tlv(0x06, new byte[] { 0x55, 0x1d, 0x0f }),
                tlv(0x01, new byte[] { (byte) 0xff }),
                tlv(0x04, tlv(0x03, new byte[] { 0x05, (byte) 0xa0 }))
        );

        // c) ExtendedKeyUsage (OID: 2.5.29.37): critical = false, clientAuth (OID: 1.3.6.1.5.5.7.3.2)
        byte[] extExtendedKeyUsage = sequence(
                tlv(0x06, new byte[] { 0x55, 0x1d, 0x25 }),
                tlv(0x04, sequence(tlv(0x06, new byte[] { 0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02 })))
        );

        // d) SubjectKeyIdentifier (OID: 2.5.29.14): critical = false, SHA-1 of public key bitstring
        byte[] skiHash = computeSubjectKeyIdentifier(spki);
        byte[] extSubjectKeyIdentifier = sequence(
                tlv(0x06, new byte[] { 0x55, 0x1d, 0x0e }),
                tlv(0x04, tlv(0x04, skiHash))
        );

        byte[] extensions = tlv(0xa3, sequence(
                extBasicConstraints,
                extKeyUsage,
                extExtendedKeyUsage,
                extSubjectKeyIdentifier
        ));

        // 8. TBSCertificate
        byte[] tbsCertificate = sequence(
                version,
                serialNumber,
                sigAlg,
                name,
                validity,
                name,
                spki,
                extensions
        );

        // 9. Sign TBSCertificate with SHA256withRSA
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(tbsCertificate);
        byte[] signatureBytes = signature.sign();

        // 10. Wrap signature into BIT STRING (0 unused bits)
        byte[] sigBitString = new byte[signatureBytes.length + 1];
        sigBitString[0] = 0x00;
        System.arraycopy(signatureBytes, 0, sigBitString, 1, signatureBytes.length);
        byte[] signatureTlv = tlv(0x03, sigBitString);

        // 11. Final X.509 Certificate DER
        byte[] certDer = sequence(tbsCertificate, sigAlg, signatureTlv);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    private static byte[] computeSubjectKeyIdentifier(byte[] spki) throws NoSuchAlgorithmException {
        // Find public key BIT STRING (tag 0x03) in SubjectPublicKeyInfo
        // SPKI format: SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }
        int bitStringOffset = -1;
        for (int i = 0; i < spki.length - 2; i++) {
            if (spki[i] == 0x03) {
                int skip = 1;
                if ((spki[i + 1] & 0x80) != 0) {
                    skip += 1 + (spki[i + 1] & 0x7f);
                } else {
                    skip += 1;
                }
                bitStringOffset = i + skip + 1;
                break;
            }
        }
        if (bitStringOffset < 0 || bitStringOffset >= spki.length) {
            throw new IllegalArgumentException("Invalid SubjectPublicKeyInfo structure");
        }

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(spki, bitStringOffset, spki.length - bitStringOffset);
        return sha1.digest();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(tag);
        int len = value.length;
        if (len < 128) {
            baos.write(len);
        } else if (len < 256) {
            baos.write(0x81);
            baos.write(len);
        } else {
            baos.write(0x82);
            baos.write((len >> 8) & 0xff);
            baos.write(len & 0xff);
        }
        baos.write(value, 0, len);
        return baos.toByteArray();
    }

    private static byte[] sequence(byte[]... elements) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] el : elements) {
            baos.write(el, 0, el.length);
        }
        return tlv(0x30, baos.toByteArray());
    }
}
