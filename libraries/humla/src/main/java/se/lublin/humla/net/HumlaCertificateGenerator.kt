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

package se.lublin.humla.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates self-signed X.509 v3 RSA client certificates for Mumble authentication
 * and packages them into a PKCS#12 (.p12) keystore using standard Java SE / Android APIs.
 */
object HumlaCertificateGenerator {
    private const val ISSUER_CN = "Humla Client"
    private const val YEARS_VALID = 20
    private const val CLOCK_SKEW_LEEWAY_MS = 10 * 60 * 1000L // 10 minutes leeway for clock skew

    @JvmStatic
    @Throws(
        NoSuchAlgorithmException::class,
        CertificateException::class,
        KeyStoreException::class,
        IOException::class
    )
    fun generateCertificate(output: OutputStream): X509Certificate {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()

        val certificate: X509Certificate
        try {
            certificate = generateSelfSignedCertificate(keyPair)
        } catch (e: GeneralSecurityException) {
            throw CertificateException("Failed to generate self-signed certificate", e)
        }

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("Mumble Identity", keyPair.private, "".toCharArray(), arrayOf<Certificate>(certificate))
        keyStore.store(output, "".toCharArray())

        return certificate
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        // 1. Version v3 -> [0] EXPLICIT INTEGER 2
        val version = tlv(0xa0, tlv(0x02, byteArrayOf(0x02)))

        // 2. Serial Number -> INTEGER 1
        val serialNumber = tlv(0x02, byteArrayOf(0x01))

        // 3. Signature Algorithm -> sha256WithRSAEncryption (OID: 1.2.840.113549.1.1.11, NULL parameters)
        val sigAlg = sequence(
            tlv(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)),
            byteArrayOf(0x05, 0x00)
        )

        // 4. Issuer & Subject -> CN=Humla Client
        // RDN: SET of AttributeTypeAndValue { type: id-at-commonName (2.5.4.3), value: UTF8String "Humla Client" }
        val cnValue = tlv(0x0c, ISSUER_CN.toByteArray(StandardCharsets.UTF_8))
        val atav = sequence(tlv(0x06, byteArrayOf(0x55, 0x04, 0x03)), cnValue)
        val name = sequence(tlv(0x31, atav))

        // 5. Validity -> SEQUENCE { notBefore, notAfter } (RFC 5280 §4.1.2.5: UTCTime < 2050, GeneralizedTime >= 2050)
        val startDate = Date(System.currentTimeMillis() - CLOCK_SKEW_LEEWAY_MS)
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.time = startDate
        calendar.add(Calendar.YEAR, YEARS_VALID)
        val endDate = calendar.time

        val validity = sequence(
            encodeTime(startDate),
            encodeTime(endDate)
        )

        // 6. SubjectPublicKeyInfo (from Java RSA public key encoded DER)
        val spki = keyPair.public.encoded

        // 7. Extensions -> [3] EXPLICIT SEQUENCE of Extension
        // Extension: SEQUENCE { extnID OBJECT IDENTIFIER, critical BOOLEAN DEFAULT FALSE, extnValue OCTET STRING }

        // a) BasicConstraints (OID: 2.5.29.19): critical = true, CA = false (empty sequence)
        val extBasicConstraints = sequence(
            tlv(0x06, byteArrayOf(0x55, 0x1d, 0x13)),
            tlv(0x01, byteArrayOf(0xff.toByte())),
            tlv(0x04, sequence())
        )

        // b) KeyUsage (OID: 2.5.29.15): critical = true, digitalSignature (bit 0) | keyEncipherment (bit 2)
        // BitString: 5 unused bits, byte 0xa0 (10100000)
        val extKeyUsage = sequence(
            tlv(0x06, byteArrayOf(0x55, 0x1d, 0x0f)),
            tlv(0x01, byteArrayOf(0xff.toByte())),
            tlv(0x04, tlv(0x03, byteArrayOf(0x05, 0xa0.toByte())))
        )

        // c) ExtendedKeyUsage (OID: 2.5.29.37): critical = false, clientAuth (OID: 1.3.6.1.5.5.7.3.2)
        val extExtendedKeyUsage = sequence(
            tlv(0x06, byteArrayOf(0x55, 0x1d, 0x25)),
            tlv(0x04, sequence(tlv(0x06, byteArrayOf(0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02))))
        )

        // d) SubjectKeyIdentifier (OID: 2.5.29.14): critical = false, SHA-1 of public key bitstring
        val skiHash = computeSubjectKeyIdentifier(spki)
        val extSubjectKeyIdentifier = sequence(
            tlv(0x06, byteArrayOf(0x55, 0x1d, 0x0e)),
            tlv(0x04, tlv(0x04, skiHash))
        )

        val extensions = tlv(0xa3, sequence(
            extBasicConstraints,
            extKeyUsage,
            extExtendedKeyUsage,
            extSubjectKeyIdentifier
        ))

        // 8. TBSCertificate
        val tbsCertificate = sequence(
            version,
            serialNumber,
            sigAlg,
            name,
            validity,
            name,
            spki,
            extensions
        )

        // 9. Sign TBSCertificate with SHA256withRSA
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(tbsCertificate)
        val signatureBytes = signature.sign()

        // 10. Wrap signature into BIT STRING (0 unused bits)
        val sigBitString = ByteArray(signatureBytes.size + 1)
        sigBitString[0] = 0x00
        System.arraycopy(signatureBytes, 0, sigBitString, 1, signatureBytes.size)
        val signatureTlv = tlv(0x03, sigBitString)

        // 11. Final X.509 Certificate DER
        val certDer = sequence(tbsCertificate, sigAlg, signatureTlv)

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    @JvmStatic
    fun encodeTime(date: Date): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = date
        val year = cal.get(Calendar.YEAR)
        if (year < 2050) {
            val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return tlv(0x17, sdf.format(date).toByteArray(StandardCharsets.US_ASCII))
        } else {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return tlv(0x18, sdf.format(date).toByteArray(StandardCharsets.US_ASCII))
        }
    }

    // Widened to public: Kotlin has no package visibility; JVM tests pin this.
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class)
    fun computeSubjectKeyIdentifier(spki: ByteArray): ByteArray {
        // SPKI format (RFC 5280 §4.1):
        // SubjectPublicKeyInfo ::= SEQUENCE {
        //     algorithm AlgorithmIdentifier,
        //     subjectPublicKey BIT STRING
        // }
        val offset = intArrayOf(0)
        if (spki.size < 2 || spki[offset[0]++] != 0x30.toByte()) {
            throw IllegalArgumentException("SPKI must start with SEQUENCE")
        }
        readLength(spki, offset) // Skip outer SEQUENCE length

        // 1. Skip AlgorithmIdentifier SEQUENCE
        if (offset[0] >= spki.size || spki[offset[0]++] != 0x30.toByte()) {
            throw IllegalArgumentException("Expected AlgorithmIdentifier SEQUENCE")
        }
        val algIdLen = readLength(spki, offset)
        offset[0] += algIdLen

        // 2. Locate subjectPublicKey BIT STRING
        if (offset[0] >= spki.size || spki[offset[0]++] != 0x03.toByte()) {
            throw IllegalArgumentException("Expected subjectPublicKey BIT STRING")
        }
        val bitStringLen = readLength(spki, offset)
        if (offset[0] >= spki.size || bitStringLen <= 1) {
            throw IllegalArgumentException("Truncated or invalid BIT STRING")
        }

        // Skip unused bits indicator byte (0x00)
        offset[0]++
        val keyLength = bitStringLen - 1
        if (offset[0] + keyLength > spki.size) {
            throw IllegalArgumentException("BIT STRING length exceeds buffer")
        }

        // RFC 5280 §4.2.1.2 Method 1: SHA-1 of the BIT STRING subjectPublicKey
        // (excluding tag, length, and unused-bits indicator)
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(spki, offset[0], keyLength)
        return sha1.digest()
    }

    private fun readLength(data: ByteArray, offset: IntArray): Int {
        if (offset[0] >= data.size) {
            throw IllegalArgumentException("Unexpected EOF reading DER length")
        }
        val b = data[offset[0]++].toInt() and 0xff
        if (b and 0x80 == 0) {
            return b
        }
        val count = b and 0x7f
        if (count == 0 || count > 4 || offset[0] + count > data.size) {
            throw IllegalArgumentException("Invalid DER length encoding")
        }
        var len = 0
        for (i in 0 until count) {
            len = (len shl 8) or (data[offset[0]++].toInt() and 0xff)
        }
        if (len < 0) {
            throw IllegalArgumentException("Negative DER length")
        }
        return len
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        if (value.size > 65535) {
            throw IllegalArgumentException("DER value length exceeds 65535 bytes")
        }
        val baos = ByteArrayOutputStream()
        baos.write(tag)
        val len = value.size
        if (len < 128) {
            baos.write(len)
        } else if (len < 256) {
            baos.write(0x81)
            baos.write(len)
        } else {
            baos.write(0x82)
            baos.write((len shr 8) and 0xff)
            baos.write(len and 0xff)
        }
        baos.write(value, 0, len)
        return baos.toByteArray()
    }

    private fun sequence(vararg elements: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (el in elements) {
            baos.write(el, 0, el.size)
        }
        return tlv(0x30, baos.toByteArray())
    }
}
