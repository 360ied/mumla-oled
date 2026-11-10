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

package se.lublin.humla.state;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the target-architecture immutable state roots.
 *
 * <p>Scaffold for step 1: the root list is empty until step 2 introduces
 * {@code SessionState}, so this passes trivially. Step 2 fills in the walk:
 * every class reachable from the roots by primary-constructor properties
 * must be an immutable value (data class/data object/enum/sealed interface
 * or an explicit leaf type), with no {@code var}s, mutable collections,
 * arrays, or non-persistent immutable interfaces.
 *
 * <p>See {@code docs/tech-debt-whales/target-architecture/enforcement.md}.
 */
// TODO(step2): walk SessionState roots via Kotlin reflection.
public class StateImmutabilityTest {
    @Test
    public void scaffoldWalksNothing() {
        assertTrue("step-1 scaffold: no state roots yet", true);
    }
}
