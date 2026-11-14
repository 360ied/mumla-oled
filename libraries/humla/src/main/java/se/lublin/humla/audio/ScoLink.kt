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

package se.lublin.humla.audio

/**
 * SCO link port behind the route policy.
 *
 * The production implementation is [BluetoothScoManager]; controller
 * tests use trivial fakes. Instance-method-for-instance-method with the manager's
 * public SCO surface (the manager additionally exposes REASON_*, retry policy,
 * and constructors) so the in-place substitution is mechanical.
 */
internal interface ScoLink {
    /** Begins SCO bring-up; no-op while starting or active. */
    fun start()

    /**
     * Tears down the link; idempotent, never fires callbacks.
     *
     * Must leave [isActive] reading `false` before returning: the route
     * controller applies a stop and then decides from a live read in the same
     * step (see docs/target-architecture/route.md), so a deferred flip would
     * publish the route just released. The never-callbacks half is what lets a
     * teardown run without racing a stale connect or drop event.
     */
    fun stop()

    /** Cancels the manager scope; called once from the service teardown. */
    fun destroy()

    /** True when no bring-up is in progress and no link is up. */
    fun isIdle(): Boolean

    /**
     * True only once the link has reported connected and not since dropped.
     *
     * The route decision samples this on every recompute instead of caching
     * it, so it must be a synchronous read of the implementation's own state,
     * and the connected/dropped callbacks must fire **after** the state flip.
     * Event-driven and sampled reads are then interchangeable, and a stop the
     * caller performs itself needs no callback at all.
     */
    fun isActive(): Boolean

    /**
     * Fail-closed availability probe: false on any error. S+ queries the
     * communication-device list; pre-S falls back to the legacy off-call flag.
     */
    fun isScoDeviceAvailable(): Boolean
}
