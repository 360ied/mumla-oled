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

package se.lublin.mumla.preference;

import junit.framework.TestCase;

public class CertificateExportTest extends TestCase {

    public void testTraversalStrippedToBasename() {
        assertEquals("x.p12", CertificateExportActivity.sanitizeExportFilename("../../x"));
    }

    public void testWindowsReservedNamePrefixed() {
        assertEquals("_CON.p12", CertificateExportActivity.sanitizeExportFilename("CON"));
    }

    public void testWindowsReservedNameCaseInsensitive() {
        assertEquals("_con.p12", CertificateExportActivity.sanitizeExportFilename("con"));
    }

    public void testEmptyFallsBackToUuid() {
        String result = CertificateExportActivity.sanitizeExportFilename("");
        assertTrue(result.endsWith(".p12"));
        assertTrue(result.matches("[0-9a-fA-F\\-]{36}\\.p12"));
    }

    public void testBlankFallsBackToUuid() {
        String result = CertificateExportActivity.sanitizeExportFilename("   ");
        assertTrue(result.endsWith(".p12"));
        assertTrue(result.matches("[0-9a-fA-F\\-]{36}\\.p12"));
    }

    public void testLongNameTruncatedTo64CharBase() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('a');
        }
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            expected.append('a');
        }
        expected.append(".p12");
        assertEquals(expected.toString(),
                CertificateExportActivity.sanitizeExportFilename(longName.toString()));
    }

    public void testOtherExtensionReplaced() {
        assertEquals("foo.p12", CertificateExportActivity.sanitizeExportFilename("foo.cert"));
    }

    public void testP12SuffixKept() {
        assertEquals("mycert.p12", CertificateExportActivity.sanitizeExportFilename("mycert.p12"));
    }

    public void testNestedPathUsesBasename() {
        assertEquals("cert.p12", CertificateExportActivity.sanitizeExportFilename("a/b/cert"));
    }
}
