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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the pure SCO retry-budget decision in [BluetoothScoManager].
 * The connect-timeout path (`onConnectTimeout`) retries while
 * {@code shouldRetryBringUp} holds: another attempt
 * remains while fewer than {@code maxAttempts} attempts have run, otherwise
 * the bring-up fails. Stack-error failures go straight to `fail()` with no
 * retry; refusal and no-device fail unconditionally in their
 * callers, so no budget branch exists to pin for those reasons.
 *
 *
 * The full manager cannot be constructed on the JVM (it needs a platform
 * {@code Context} and main-looper {@code Handler}; this module is JUnit-only
 * with no Robolectric), so this truth table is the JVM-testable seam for the
 * bring-up matrix.
 */
public class BluetoothScoRetryPolicyTest {

    @Test
    public void freshBringUpAlwaysRetries() {
        // Pure-function pin: attempt 0 never reaches onConnectTimeout in production
        // (attemptBringUp increments before arming), but the function itself retries.
        assertTrue("Zero attempts must always retry",
                BluetoothScoManager.shouldRetryBringUp(0, 2));
        assertTrue("Zero attempts must retry even with a budget of one",
                BluetoothScoManager.shouldRetryBringUp(0, 1));
    }

    @Test
    public void timeoutAfterFirstAttemptRetries() {
        assertTrue("First timeout of two must retry",
                BluetoothScoManager.shouldRetryBringUp(1, 2));
    }

    @Test
    public void exhaustedBudgetFails() {
        assertFalse("Second timeout of two must fail, not retry",
                BluetoothScoManager.shouldRetryBringUp(2, 2));
        assertFalse("Overrun attempts must fail, not retry",
                BluetoothScoManager.shouldRetryBringUp(5, 2));
    }

    @Test
    public void singleAttemptBudget() {
        assertTrue("No attempts run yet must retry even with a budget of one",
                BluetoothScoManager.shouldRetryBringUp(0, 1));
        assertFalse("Budget of one fails once it has run",
                BluetoothScoManager.shouldRetryBringUp(1, 1));
    }

    @Test
    public void zeroBudgetNeverRetries() {
        // Unreachable via ctor (require maxAttempts >= 1); pure-function pin for symmetry
        // with the negative-attempts drift detector above.
        assertFalse("Zero budget must fail immediately",
                BluetoothScoManager.shouldRetryBringUp(0, 0));
    }

    @Test
    public void negativeAttemptsAreAboveBudget() {
        // Unreachable in production (attempts start at 0 and only increment);
        // fail-closed: a negative count must not read as retryable.
        assertFalse(BluetoothScoManager.shouldRetryBringUp(-1, 2));
    }
}
