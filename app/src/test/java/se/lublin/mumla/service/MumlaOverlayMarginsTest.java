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

package se.lublin.mumla.service;

import junit.framework.TestCase;

/**
 * Verifies the pure pinned-overlay margin resolvers in {@link MumlaOverlay}:
 * live insets win, legacy resource heights are the fallback, and hardcoded
 * defaults apply only when neither source yields a height. The edge gutter is
 * always added on top of whichever source wins.
 */
public class MumlaOverlayMarginsTest extends TestCase {

    private static final float DENSITY_2 = 2.0f;

    public void testTopMarginPrefersLiveInset() {
        // 100px inset + 8dp gutter @2x = 116
        assertEquals(116, MumlaOverlay.resolveEdgeMarginPx(100, 60, 40, DENSITY_2));
    }

    public void testTopMarginFallsBackToResourceHeight() {
        // 0 inset -> 60px resource + 16px gutter = 76
        assertEquals(76, MumlaOverlay.resolveEdgeMarginPx(0, 60, 40, DENSITY_2));
    }

    public void testTopMarginFallsBackToHardcodedDefault() {
        // neither source -> 40dp @2x = 80
        assertEquals(80, MumlaOverlay.resolveEdgeMarginPx(0, 0, 40, DENSITY_2));
    }

    public void testBottomMarginPrefersLiveInset() {
        // 48px inset + 8dp gutter @3x = 72
        assertEquals(72, MumlaOverlay.resolveEdgeMarginPx(48, 100, 56, 3.0f));
    }

    public void testBottomMarginFallsBackToResourceHeight() {
        // 0 inset -> 100px resource + 24px gutter @3x = 124
        assertEquals(124, MumlaOverlay.resolveEdgeMarginPx(0, 100, 56, 3.0f));
    }

    public void testBottomMarginFallsBackToHardcodedDefault() {
        // neither source -> 56dp @3x = 168
        assertEquals(168, MumlaOverlay.resolveEdgeMarginPx(0, 0, 56, 3.0f));
    }

    public void testNegativeInputsResolveToDefault() {
        // Negative heights are never valid insets; resolver treats them as absent.
        assertEquals(80, MumlaOverlay.resolveEdgeMarginPx(-10, -5, 40, DENSITY_2));
    }

    public void testSideMarginUsesLiveInset() {
        // 30px side inset + 8dp gutter @2x = 46
        assertEquals(46, MumlaOverlay.resolveSideMarginPx(30, DENSITY_2));
    }

    public void testSideMarginFallsBackToFixedOffset() {
        // no side inset -> 16dp @2x = 32
        assertEquals(32, MumlaOverlay.resolveSideMarginPx(0, DENSITY_2));
    }
}
