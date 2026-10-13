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

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate

/**
 * Why a TLS handshake failed identity verification. The reason travels
 * alongside the certificate chain (see
 * {@link HumlaSSLSocketFactory#getLastHandshakeFailure()}) so the UI can show
 * distinct dialogs instead of one generic "untrusted certificate" prompt.
 */
enum class HandshakeFailure {
    /** CA-valid chain whose leaf does not identify the expected host. No pin bypass. */
    HOSTNAME_MISMATCH,

    /** Self-signed or unknown-issuer chain with no pin stored for this host. */
    UNTRUSTED_ISSUER,

    /** A pin exists for this host but the presented leaf has a different SPKI. */
    PIN_CHANGED,

    /** No failure; handshake identity checks passed. */
    NONE;

    companion object {
        /**
         * SHA-256 of the leaf's SubjectPublicKeyInfo. Null leaves throw; use
         * {@link #sameSpki} for the null-tolerant comparison.
         */
        @JvmStatic
        @Throws(CertificateEncodingException::class)
        fun spkiSha256(leaf: X509Certificate?): ByteArray {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                // Explicit NPE (not requireNotNull/IAE): the original
                // dereferenced the null leaf, and Java callers may catch NPE.
                val l = leaf ?: throw NullPointerException("leaf")
                return digest.digest(l.publicKey.encoded)
            } catch (e: NoSuchAlgorithmException) {
                throw CertificateEncodingException("SHA-256 unavailable", e)
            }
        }

        /** Null-tolerant SPKI comparison; null either side returns false. */
        @JvmStatic
        fun sameSpki(a: X509Certificate?, b: X509Certificate?): Boolean {
            if (a == null || b == null) {
                return false
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val first = digest.digest(a.publicKey.encoded)
                val second = digest.digest(b.publicKey.encoded)
                if (first.size != second.size) {
                    return false
                }
                var diff = 0
                for (i in first.indices) {
                    diff = diff or (first[i].toInt() xor second[i].toInt())
                }
                return diff == 0
            } catch (e: NoSuchAlgorithmException) {
                return false
            } catch (e: RuntimeException) {
                return false
            }
        }
    }
}
