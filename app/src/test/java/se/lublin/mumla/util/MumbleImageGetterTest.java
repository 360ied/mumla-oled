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

    public void testCalculateImageBounds_LandscapeFillsMaxWidth() {
        // 1080x2400 portrait display with 144px padding -> maxWidth = 936, maxHeight = 1560
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                600, 338, 1080, 2400, 144);
        assertNotNull(bounds);
        assertEquals(936, bounds.width);
        assertEquals(527, bounds.height);
    }

    public void testCalculateImageBounds_SquareFillsMaxWidth() {
        // 1080x2400 portrait display with 144px padding -> maxWidth = 936, maxHeight = 1560
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                500, 500, 1080, 2400, 144);
        assertNotNull(bounds);
        assertEquals(936, bounds.width);
        assertEquals(936, bounds.height);
    }

    public void testCalculateImageBounds_PortraitCappedByMaxHeight() {
        // 9:16 portrait image (225x400) on 1080x2400 display -> maxHeight = 1560
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                225, 400, 1080, 2400, 144);
        assertNotNull(bounds);
        assertEquals(1560, bounds.height);
        assertEquals(878, bounds.width);
    }

    public void testCalculateImageBounds_UltraTallImageClampedToMaxHeight() {
        // Ultra-tall 1:10 aspect ratio image (100x1000)
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                100, 1000, 1080, 2400, 144);
        assertNotNull(bounds);
        assertEquals(1560, bounds.height);
        assertEquals(156, bounds.width);
    }

    public void testCalculateImageBounds_LandscapeDeviceOrientation() {
        // 2400x1080 landscape screen with 144px padding -> maxWidth = 2256, maxHeight = 702
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                1920, 1080, 2400, 1080, 144);
        assertNotNull(bounds);
        assertEquals(702, bounds.height);
        assertEquals(1248, bounds.width);
    }

    public void testCalculateImageBounds_TinyIconScaledByDensity() {
        // 32x32 icon on 3.0x density screen (1080x2400) -> 32 * 3.0 = 96x96 px (32dp), not stretched to 936px
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                32, 32, 1080, 2400, 144, 3.0f);
        assertNotNull(bounds);
        assertEquals(96, bounds.width);
        assertEquals(96, bounds.height);
    }

    public void testCalculateImageBounds_StickerScaledByDensity() {
        // 64x64 sticker on 3.0x density screen (1080x2400) -> 64 * 3.0 = 192x192 px (64dp)
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                64, 64, 1080, 2400, 144, 3.0f);
        assertNotNull(bounds);
        assertEquals(192, bounds.width);
        assertEquals(192, bounds.height);
    }

    public void testCalculateImageBounds_PhotoExpandsToMaxWidthWithDensity() {
        // 600x400 photo on 3.0x density screen (1080x2400) -> expands to maxWidth 936
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                600, 400, 1080, 2400, 144, 3.0f);
        assertNotNull(bounds);
        assertEquals(936, bounds.width);
        assertEquals(624, bounds.height);
    }

    public void testCalculateImageBounds_MultiDensityVariations() {
        // 32x32 emote across 1.0x, 2.0x, and 4.0x display densities
        MumbleImageGetter.ImageBounds mdpi = MumbleImageGetter.calculateImageBounds(32, 32, 1080, 2400, 144, 1.0f);
        assertNotNull(mdpi);
        assertEquals(32, mdpi.width);
        assertEquals(32, mdpi.height);

        MumbleImageGetter.ImageBounds xhdpi = MumbleImageGetter.calculateImageBounds(32, 32, 1080, 2400, 144, 2.0f);
        assertNotNull(xhdpi);
        assertEquals(64, xhdpi.width);
        assertEquals(64, xhdpi.height);

        MumbleImageGetter.ImageBounds xxxhdpi = MumbleImageGetter.calculateImageBounds(32, 32, 1080, 2400, 144, 4.0f);
        assertNotNull(xxxhdpi);
        assertEquals(128, xxxhdpi.width);
        assertEquals(128, xxxhdpi.height);
    }

    public void testCalculateImageBounds_PaddingExceedsDisplayWidth() {
        // Padding exceeds display width -> clamped to 1px
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                600, 400, 100, 2400, 144, 3.0f);
        assertNotNull(bounds);
        assertEquals(1, bounds.width);
        assertEquals(1, bounds.height);
    }

    public void testCalculateImageBounds_InvalidInputs() {
        assertNull(MumbleImageGetter.calculateImageBounds(0, 100, 1080, 2400, 144));
        assertNull(MumbleImageGetter.calculateImageBounds(100, 0, 1080, 2400, 144));
        assertNull(MumbleImageGetter.calculateImageBounds(-10, 100, 1080, 2400, 144));
        assertNull(MumbleImageGetter.calculateImageBounds(100, 100, 0, 2400, 144));
        assertNull(MumbleImageGetter.calculateImageBounds(100, 100, 1080, 0, 144));
    }

    public void testCalculateImageBounds_NegativePaddingClampedToZero() {
        // Negative padding clamped to 0 -> maxWidth = displayWidth
        MumbleImageGetter.ImageBounds bounds = MumbleImageGetter.calculateImageBounds(
                600, 400, 1080, 2400, -50, 1.0f);
        assertNotNull(bounds);
        assertEquals(1080, bounds.width);
        assertEquals(720, bounds.height);
    }

    public void testCalculateImageBounds_SpecialDensityValues() {
        // NaN, Infinity, negative density should fall back to safe 1.0f density
        MumbleImageGetter.ImageBounds nanBounds = MumbleImageGetter.calculateImageBounds(
                32, 32, 1080, 2400, 144, Float.NaN);
        assertNotNull(nanBounds);
        assertEquals(32, nanBounds.width);
        assertEquals(32, nanBounds.height);

        MumbleImageGetter.ImageBounds infBounds = MumbleImageGetter.calculateImageBounds(
                32, 32, 1080, 2400, 144, Float.POSITIVE_INFINITY);
        assertNotNull(infBounds);
        assertEquals(32, infBounds.width);
        assertEquals(32, infBounds.height);

        MumbleImageGetter.ImageBounds negBounds = MumbleImageGetter.calculateImageBounds(
                32, 32, 1080, 2400, 144, -2.0f);
        assertNotNull(negBounds);
        assertEquals(32, negBounds.width);
        assertEquals(32, negBounds.height);
    }

    public void testImageBounds_EqualsHashCodeToString() {
        MumbleImageGetter.ImageBounds b1 = new MumbleImageGetter.ImageBounds(100, 200);
        MumbleImageGetter.ImageBounds b2 = new MumbleImageGetter.ImageBounds(100, 200);
        MumbleImageGetter.ImageBounds b3 = new MumbleImageGetter.ImageBounds(100, 300);

        assertEquals(b1, b2);
        assertEquals(b1.hashCode(), b2.hashCode());
        assertFalse(b1.equals(b3));
        assertFalse(b1.equals(null));
        assertFalse(b1.equals("other"));
        assertEquals("ImageBounds{100x200}", b1.toString());
    }
}
