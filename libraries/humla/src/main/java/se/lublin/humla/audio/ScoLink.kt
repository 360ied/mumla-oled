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
 * Slice 3 of the HumlaService decomposition: the production implementation
 * is [BluetoothScoManager] (retrofitted without constructor or timeout-scope
 * changes); controller tests use trivial fakes. Method-for-method with the
 * manager's public surface so the in-place substitution is mechanical.
 */
interface ScoLink {
    /** Begins SCO bring-up; no-op while starting or active. */
    fun start()

    /** Tears down the link; idempotent, never fires callbacks. */
    fun stop()

    /** Cancels the manager scope; called once from the service teardown. */
    fun destroy()

    /** True when no bring-up is in progress and no link is up. */
    fun isIdle(): Boolean

    /** True only once the link has reported connected and not since dropped. */
    fun isActive(): Boolean

    /**
     * Fail-closed availability probe: false on any error. S+ queries the
     * communication-device list; 30 falls back to the legacy off-call flag.
     */
    fun isScoDeviceAvailable(): Boolean
}
