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

public class SeekBarPreferenceTest extends TestCase {

    public void testFormatValueWithDisplayDivider() {
        // Audio bitrate: 40000 bps displayed with divider 1000 and " kbps" suffix
        assertEquals("40 kbps", SeekBarPreferenceDialogFragment.formatValue(40000, 1000, " kbps"));
        assertEquals("8 kbps", SeekBarPreferenceDialogFragment.formatValue(8000, 1000, " kbps"));
        assertEquals("96 kbps", SeekBarPreferenceDialogFragment.formatValue(96000, 1000, " kbps"));
        assertEquals("192 kbps", SeekBarPreferenceDialogFragment.formatValue(192000, 1000, " kbps"));
    }

    public void testFormatValueDefaultDivider() {
        // Preferences without displayDivider (default 1)
        assertEquals("150 dp", SeekBarPreferenceDialogFragment.formatValue(150, 1, " dp"));
        assertEquals("100%", SeekBarPreferenceDialogFragment.formatValue(100, 1, "%"));
        assertEquals("50", SeekBarPreferenceDialogFragment.formatValue(50, 1, null));
    }

    public void testFormatValueZeroOrNegativeDividerFallback() {
        // Defensive check: non-positive divider falls back to raw value without dividing
        assertEquals("40000 bps", SeekBarPreferenceDialogFragment.formatValue(40000, 0, " bps"));
        assertEquals("40000 bps", SeekBarPreferenceDialogFragment.formatValue(40000, -1, " bps"));
    }
}
