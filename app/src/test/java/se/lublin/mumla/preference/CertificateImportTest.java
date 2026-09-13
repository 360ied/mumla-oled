/*
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

package se.lublin.mumla.preference;

import junit.framework.TestCase;

import java.io.EOFException;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;

public class CertificateImportTest extends TestCase {

    public void testIsPasswordFailureWithUnrecoverableKeyException() {
        Exception e = new UnrecoverableKeyException("failed to decrypt safe contents entry");
        assertTrue(CertificateImportActivity.isPasswordFailure(e));
    }

    public void testIsPasswordFailureWithBadPaddingException() {
        Exception e = new BadPaddingException("Given final block not properly padded");
        assertTrue(CertificateImportActivity.isPasswordFailure(e));
    }

    public void testIsPasswordFailureWithAEADBadTagException() {
        Exception e = new AEADBadTagException("Tag mismatch");
        assertTrue(CertificateImportActivity.isPasswordFailure(e));
    }

    public void testIsPasswordFailureWithWrappedBadPadding() {
        BadPaddingException bpe = new BadPaddingException("Given final block not properly padded");
        IOException ioe = new IOException("keystore password was incorrect", bpe);
        assertTrue(CertificateImportActivity.isPasswordFailure(ioe));
    }

    public void testIsPasswordFailureWithStandardMessages() {
        // Standard Sun/Oracle PKCS12 message
        assertTrue(CertificateImportActivity.isPasswordFailure(new IOException("keystore password was incorrect")));

        // Conscrypt / OpenSSL internal error
        assertTrue(CertificateImportActivity.isPasswordFailure(new IOException("error:1e000065:Cipher functions:OPENSSL_internal:BAD_DECRYPT")));

        // BouncyCastle message
        assertTrue(CertificateImportActivity.isPasswordFailure(new IOException("PKCS12 key store mac invalid - wrong password or corrupted file")));

        // Generic MAC failure
        assertTrue(CertificateImportActivity.isPasswordFailure(new IOException("MAC verification failed")));

        // Key recovery failure
        assertTrue(CertificateImportActivity.isPasswordFailure(new KeyStoreException("Get Key failed")));
    }

    public void testIsPasswordFailureNegativeCases() {
        // Truncated / empty file
        assertFalse(CertificateImportActivity.isPasswordFailure(new EOFException()));

        // Corrupted ASN.1 DER tag
        assertFalse(CertificateImportActivity.isPasswordFailure(new IOException("Tag number over 30 at 18 is not supported")));

        // Incompatible file / random stream
        assertFalse(CertificateImportActivity.isPasswordFailure(new IOException("toDerInputStream rejects tag type 0")));

        // Generic I/O error
        assertFalse(CertificateImportActivity.isPasswordFailure(new IOException("Read failed")));

        // Word boundary: "machine" should not match "\bmac\b"
        assertFalse(CertificateImportActivity.isPasswordFailure(new IOException("Error reading file from machine storage")));

        // Corrupted certificate payload must fail fast without prompting for password
        assertFalse(CertificateImportActivity.isPasswordFailure(new CertificateException("Could not parse certificate")));
        assertFalse(CertificateImportActivity.isPasswordFailure(new CertificateEncodingException("Invalid encoding")));

        // Unsupported cipher algorithm must fail fast without prompting for password
        assertFalse(CertificateImportActivity.isPasswordFailure(new NoSuchAlgorithmException("Unsupported algorithm")));
    }
}
