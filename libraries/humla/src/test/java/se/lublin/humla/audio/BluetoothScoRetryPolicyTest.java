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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio;

import junit.framework.TestCase;

/**
 * Pins the pure SCO retry-budget decision in {@link BluetoothScoManager}
 * (ODD-23). The timeout path (`onConnectTimeout`) and the stack-error path
 * (`onLegacyStateChanged`) share {@code shouldRetryBringUp}: another attempt
 * remains while fewer than {@code maxAttempts} attempts have run, otherwise
 * the bring-up fails. Refusal and no-device fail unconditionally in their
 * callers, so no budget branch exists to pin for those reasons.
 *
 * <p>The full manager cannot be constructed on the JVM (it needs a platform
 * {@code Context} and main-looper {@code Handler}; this module is JUnit-only
 * with no Robolectric), so this truth table is the JVM-testable seam for the
 * bring-up matrix.
 */
public class BluetoothScoRetryPolicyTest extends TestCase {

    public void testFreshBringUpAlwaysRetries() {
        assertTrue("Zero attempts must always retry",
                BluetoothScoManager.shouldRetryBringUp(0, 2));
        assertTrue("Zero attempts must retry even with a budget of one",
                BluetoothScoManager.shouldRetryBringUp(0, 1));
    }

    public void testTimeoutAfterFirstAttemptRetries() {
        assertTrue("First timeout of two must retry",
                BluetoothScoManager.shouldRetryBringUp(1, 2));
    }

    public void testExhaustedBudgetFails() {
        assertFalse("Second timeout of two must fail, not retry",
                BluetoothScoManager.shouldRetryBringUp(2, 2));
        assertFalse("Overrun attempts must fail, not retry",
                BluetoothScoManager.shouldRetryBringUp(5, 2));
    }

    public void testSingleAttemptBudget() {
        assertTrue("Budget of one retries the first attempt",
                BluetoothScoManager.shouldRetryBringUp(0, 1));
        assertFalse("Budget of one fails once it has run",
                BluetoothScoManager.shouldRetryBringUp(1, 1));
    }

    public void testZeroBudgetNeverRetries() {
        assertFalse("Zero budget must fail immediately",
                BluetoothScoManager.shouldRetryBringUp(0, 0));
    }
}
