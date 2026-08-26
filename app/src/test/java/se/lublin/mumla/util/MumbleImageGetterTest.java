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

package se.lublin.mumla.util;

import junit.framework.TestCase;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

public class MumbleImageGetterTest extends TestCase {

    public void testPercentDecode() {
        assertEquals("Hello World", MumbleImageGetter.percentDecode("Hello%20World"));
        assertEquals("/+=", MumbleImageGetter.percentDecode("%2F%2B%3D"));
        assertEquals("regular_string", MumbleImageGetter.percentDecode("regular_string"));
        assertNull(MumbleImageGetter.percentDecode(null));
        assertEquals("", MumbleImageGetter.percentDecode(""));
    }

    public void testDecodeBase64Bytes_RawBase64() {
        byte[] original = new byte[]{1, 2, 3, 4, 5, (byte) 250, (byte) 255};
        String base64 = Base64.getEncoder().encodeToString(original);

        byte[] decoded = MumbleImageGetter.decodeBase64Bytes(base64);
        assertNotNull(decoded);
        assertTrue(Arrays.equals(original, decoded));
    }

    public void testDecodeBase64Bytes_MumbleDesktopFormat() {
        // Upstream desktop Mumble format: percent-encoded 72-char chunks with newlines
        byte[] original = new byte[256];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) i;
        }

        String rawBase64 = Base64.getEncoder().encodeToString(original);

        // Simulate upstream Mumble Log::imageToImg formatting
        StringBuilder mumbleDesktopFormatted = new StringBuilder();
        int begin = 0;
        while (begin < rawBase64.length()) {
            int end = Math.min(begin + 72, rawBase64.length());
            String chunk = rawBase64.substring(begin, end);
            try {
                // Percent encode each chunk
                String encodedChunk = URLEncoder.encode(chunk, StandardCharsets.UTF_8.name())
                        .replace("+", "%20"); // Standard percent encoding
                mumbleDesktopFormatted.append(encodedChunk);
            } catch (Exception e) {
                fail(e.getMessage());
            }
            if (end < rawBase64.length()) {
                mumbleDesktopFormatted.append('\n');
            }
            begin = end;
        }

        byte[] decoded = MumbleImageGetter.decodeBase64Bytes(mumbleDesktopFormatted.toString());
        assertNotNull(decoded);
        assertTrue(Arrays.equals(original, decoded));
    }

    public void testDecodeBase64Bytes_MumlaUrlEncodedFormat() {
        byte[] original = new byte[]{10, 20, 30, 40, 50, 60, 70, 80};
        String base64 = Base64.getEncoder().encodeToString(original);
        String urlEncoded = URLEncoder.encode(base64, StandardCharsets.UTF_8);

        byte[] decoded = MumbleImageGetter.decodeBase64Bytes(urlEncoded);
        assertNotNull(decoded);
        assertTrue(Arrays.equals(original, decoded));
    }

    public void testDecodeBase64Bytes_LargeImageOver64KB() {
        // Test an image payload larger than the former 64000 byte limit (e.g. 120 KB)
        byte[] original = new byte[120 * 1024];
        new Random(42).nextBytes(original);

        String base64 = Base64.getEncoder().encodeToString(original);
        String urlEncoded = URLEncoder.encode(base64, StandardCharsets.UTF_8);

        byte[] decoded = MumbleImageGetter.decodeBase64Bytes(urlEncoded);
        assertNotNull("Image >64KB must not be rejected by MAX_LENGTH", decoded);
        assertEquals(original.length, decoded.length);
        assertTrue(Arrays.equals(original, decoded));
    }

    public void testDecodeBase64Bytes_NullOrEmpty() {
        assertNull(MumbleImageGetter.decodeBase64Bytes(null));
        assertNull(MumbleImageGetter.decodeBase64Bytes(""));
    }

    public void testPercentDecode_MalformedPercent() {
        assertEquals("test%", MumbleImageGetter.percentDecode("test%"));
        assertEquals("test%2", MumbleImageGetter.percentDecode("test%2"));
        assertEquals("test%ZZ", MumbleImageGetter.percentDecode("test%ZZ"));
        assertEquals("test%2G", MumbleImageGetter.percentDecode("test%2G"));
        assertEquals("hello%world", MumbleImageGetter.percentDecode("hello%world"));
    }

    public void testDecodeBase64Bytes_MalformedBase64() {
        assertNull(MumbleImageGetter.decodeBase64Bytes("!invalid_base64_data!"));
        assertNull(MumbleImageGetter.decodeBase64Bytes("a"));
    }

    public void testDecodeBase64Bytes_ExceedsMaxLength() {
        // Exceeds 10MB limit (MAX_LENGTH = 10 * 1024 * 1024)
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        String base64 = Base64.getEncoder().encodeToString(oversized);
        assertNull("Payload exceeding MAX_LENGTH must return null", MumbleImageGetter.decodeBase64Bytes(base64));
    }
}
