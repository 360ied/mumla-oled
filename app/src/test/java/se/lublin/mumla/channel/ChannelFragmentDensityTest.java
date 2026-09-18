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

package se.lublin.mumla.channel;

import android.util.DisplayMetrics;

import junit.framework.TestCase;

public class ChannelFragmentDensityTest extends TestCase {

    public void testPttButtonDensityConversion_VariousScales() {
        int heightDp = 150;

        DisplayMetrics mdpi = new DisplayMetrics();
        mdpi.density = 1.0f;
        int pxMdpi = ChannelFragment.calculateButtonHeightPx(heightDp, mdpi);
        assertEquals(150, pxMdpi);

        DisplayMetrics hdpi = new DisplayMetrics();
        hdpi.density = 1.5f;
        int pxHdpi = ChannelFragment.calculateButtonHeightPx(heightDp, hdpi);
        assertEquals(225, pxHdpi);

        DisplayMetrics xhdpi = new DisplayMetrics();
        xhdpi.density = 2.0f;
        int pxXhdpi = ChannelFragment.calculateButtonHeightPx(heightDp, xhdpi);
        assertEquals(300, pxXhdpi);

        DisplayMetrics xxhdpi = new DisplayMetrics();
        xxhdpi.density = 3.0f;
        int pxXxhdpi = ChannelFragment.calculateButtonHeightPx(heightDp, xxhdpi);
        assertEquals(450, pxXxhdpi);

        DisplayMetrics xxxhdpi = new DisplayMetrics();
        xxxhdpi.density = 4.0f;
        int pxXxxhdpi = ChannelFragment.calculateButtonHeightPx(heightDp, xxxhdpi);
        assertEquals(600, pxXxxhdpi);
    }

    public void testPttButtonDensityConversion_CustomHeight() {
        int customHeightDp = 80;

        DisplayMetrics xxhdpi = new DisplayMetrics();
        xxhdpi.density = 3.0f;
        int pxXxhdpi = ChannelFragment.calculateButtonHeightPx(customHeightDp, xxhdpi);
        assertEquals(240, pxXxhdpi);
    }

    public void testPttButtonDensityConversion_NullMetricsFallback() {
        int heightDp = 150;
        int px = ChannelFragment.calculateButtonHeightPx(heightDp, null);
        assertEquals("Null metrics must fallback to raw dp value", 150, px);
    }

    public void testTalkStateMapping_ActivatedState() {
        assertTrue(isTalkingState(se.lublin.humla.model.TalkState.TALKING));
        assertTrue(isTalkingState(se.lublin.humla.model.TalkState.SHOUTING));
        assertTrue(isTalkingState(se.lublin.humla.model.TalkState.WHISPERING));
        assertFalse(isTalkingState(se.lublin.humla.model.TalkState.PASSIVE));
    }

    private static boolean isTalkingState(se.lublin.humla.model.TalkState state) {
        switch (state) {
            case TALKING:
            case SHOUTING:
            case WHISPERING:
                return true;
            case PASSIVE:
            default:
                return false;
        }
    }
}
