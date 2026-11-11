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

package se.lublin.humla.interop;

/**
 * Category marker for the murmur interop suite.
 *
 * <p>Tests tagged with this category need a live {@code mumble-server} and
 * take a minute or more, so they never run in {@code check.sh}'s fast gate.
 * They run only via the dedicated {@code murmurInteropTest} Gradle task
 * (see {@code scripts/test_interop.sh}). Until step 3 the harness drives
 * {@code HumlaConnection} under Robolectric; from step 3 on it drives
 * {@code HumlaClient} directly.
 */
public interface MurmurInterop {
}
