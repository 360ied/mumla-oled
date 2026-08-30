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

package se.lublin.humla.net;

import junit.framework.TestCase;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Enumeration;
import java.util.List;

public class HumlaCertificateGeneratorTest extends TestCase {

    public void testGenerateCertificate() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        X509Certificate cert = HumlaCertificateGenerator.generateCertificate(baos);

        assertNotNull("Generated certificate must not be null", cert);
        assertEquals("SHA256withRSA", cert.getSigAlgName());
        assertTrue("Subject DN must contain Humla Client", cert.getSubjectDN().getName().contains("Humla Client"));
        assertTrue("Issuer DN must contain Humla Client", cert.getIssuerDN().getName().contains("Humla Client"));

        // Verify RSA 2048-bit public key
        assertTrue("Public key must be RSA", cert.getPublicKey() instanceof RSAPublicKey);
        RSAPublicKey rsaPub = (RSAPublicKey) cert.getPublicKey();
        assertEquals(2048, rsaPub.getModulus().bitLength());

        // Verify BasicConstraints (must not be a CA)
        assertEquals(-1, cert.getBasicConstraints());

        // Verify ExtendedKeyUsage (clientAuth OID: 1.3.6.1.5.5.7.3.2)
        List<String> extendedKeyUsage = cert.getExtendedKeyUsage();
        assertNotNull("Extended key usage must be present", extendedKeyUsage);
        assertTrue("Must have clientAuth usage", extendedKeyUsage.contains("1.3.6.1.5.5.7.3.2"));

        // Verify PKCS#12 KeyStore loading
        byte[] p12Bytes = baos.toByteArray();
        assertTrue("PKCS#12 bytes must not be empty", p12Bytes.length > 0);

        KeyStore keyStore = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
        keyStore.load(new ByteArrayInputStream(p12Bytes), "".toCharArray());

        Enumeration<String> aliases = keyStore.aliases();
        assertTrue("KeyStore must have at least one alias", aliases.hasMoreElements());
        String alias = aliases.nextElement();
        assertEquals("Mumble Identity", alias);

        Key key = keyStore.getKey(alias, "".toCharArray());
        assertNotNull("Private key must be recoverable", key);

        Certificate[] chain = keyStore.getCertificateChain(alias);
        assertNotNull("Certificate chain must be present", chain);
        assertEquals(1, chain.length);
        assertEquals(cert, chain[0]);
    }
}
