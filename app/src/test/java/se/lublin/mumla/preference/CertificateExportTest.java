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

    private static final String UUID_P12 = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.p12";

    public void testNullFallsBackToUuid() {
        assertTrue(CertificateExportActivity.sanitizeExportFilename(null).matches(UUID_P12));
    }

    public void testUuidFallbackHasCanonicalLayout() {
        assertTrue(CertificateExportActivity.sanitizeExportFilename("").matches(UUID_P12));
    }

    public void testBackslashPathUsesBasename() {
        assertEquals("cert.p12", CertificateExportActivity.sanitizeExportFilename("..\\..\\cert"));
    }

    public void testDotDotAloneFallsBackToUuid() {
        assertTrue(CertificateExportActivity.sanitizeExportFilename("..").matches(UUID_P12));
    }

    public void testLeadingDotsStripped() {
        assertEquals("hidden.p12", CertificateExportActivity.sanitizeExportFilename("...hidden"));
    }

    public void testReservedCharactersReplaced() {
        assertEquals("a_b_c_d_e_f_g_h.p12",
                CertificateExportActivity.sanitizeExportFilename("a:b*c?d\"e<f>g|h"));
    }

    public void testControlCharactersReplaced() {
        assertEquals("a_b.p12", CertificateExportActivity.sanitizeExportFilename("a\0b"));
    }

    public void testReservedNameWithExtensionPrefixed() {
        assertEquals("_CON.p12", CertificateExportActivity.sanitizeExportFilename("CON.txt"));
    }

    public void testReservedNameWithMultipleDotsPrefixed() {
        assertEquals("_CON.a.p12", CertificateExportActivity.sanitizeExportFilename("CON.a.b"));
    }

    public void testReservedNamePrefixWordNotPrefixed() {
        assertEquals("CONSOLE.p12", CertificateExportActivity.sanitizeExportFilename("CONSOLE"));
    }

    public void testUpperCaseP12SuffixNotDuplicated() {
        assertEquals("mycert.p12", CertificateExportActivity.sanitizeExportFilename("mycert.P12"));
    }

    public void testTrailingDotsAndSpacesStripped() {
        assertEquals("foo.p12", CertificateExportActivity.sanitizeExportFilename("foo ."));
        assertEquals("foo.p12", CertificateExportActivity.sanitizeExportFilename("foo..."));
    }

    public void testBaseOfExactly64CharsKept() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            name.append('a');
        }
        assertEquals(name + ".p12", CertificateExportActivity.sanitizeExportFilename(name.toString()));
    }

    public void testTruncationDoesNotSplitSurrogatePair() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 63; i++) {
            name.append('a');
        }
        name.append("\uD83D\uDE00"); // emoji straddling the 64-unit limit
        String result = CertificateExportActivity.sanitizeExportFilename(name.toString());
        String stem = result.substring(0, result.length() - 4);
        assertEquals(63, stem.length());
        assertFalse(Character.isHighSurrogate(stem.charAt(stem.length() - 1)));
    }

    public void testTruncationDoesNotLeaveTrailingDot() {
        StringBuilder base = new StringBuilder();
        for (int i = 0; i < 63; i++) {
            base.append('a');
        }
        // Stem "aaa...a.bbb" (after dropping ".cert") is cut at 64 units, i.e. right after the dot.
        assertEquals(base + ".p12",
                CertificateExportActivity.sanitizeExportFilename(base + ".bbb.cert"));
    }
}
