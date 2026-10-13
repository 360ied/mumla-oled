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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the Bluetooth SCO (HFP) audio route for a voice session.
 *
 * <p>SCO link bring-up is asynchronous and unreliable: the platform call
 * returns before the link exists, establishment takes seconds, and failure is
 * silent without a state callback. This manager hides that behind a small state
 * machine with a connect timeout, one retry, and automatic fallback reporting.
 *
 * <p>SCO link bring-up runs on the single platform backend:
 * {@code setCommunicationDevice()} with a {@code TYPE_BLUETOOTH_SCO} device
 * from {@code getAvailableCommunicationDevices()}, observed via
 * {@code OnCommunicationDeviceChangedListener}.
 *
 * <p>Locking: {@code state}, {@code attempts}, {@code generation}, {@code routeHeld},
 * {@code routeHeldEpoch}, and {@code deviceListener} are guarded by the
 * manager monitor. Bring-up runs outside the monitor and re-validates by
 * session epoch after each IPC step, so a concurrent {@code stop()} aborts
 * the sequence instead of interleaving with it. Observer register/teardown
 * take the monitor themselves; their IPC is idempotent and fast. Route
 * release always runs outside the monitor. All listener
 * callbacks run outside the monitor: {@code onScoConnected} and
 * {@code onScoDisconnected} originate on main-thread platform callbacks;
 * {@code onScoFailed} is always launched in the manager scope (Main.immediate)
 * and dropped when its session generation is stale, so {@code stop()} truly
 * never callbacks. Slice 4: coroutine cancellation is the epoch for the
 * timeout path (stop cancels the timeout child); the generation still guards
 * platform device-listener callbacks, which cannot be cancelled.
 *
 * <p>AudioManager mode is deliberately NOT owned here. The service route
 * policy ({@code HumlaService}) is the single owner of
 * {@code MODE_IN_COMMUNICATION}: it holds the mode for the whole voice-route
 * duration, which covers confirmed SCO sessions. Setting it here as well
 * made the service classify our own hold as externally owned and give up
 * its claim, stranding the mode on later media switches — so this manager
 * only selects and releases the SCO route, never the mode.
 */
class BluetoothScoManager {
    companion object {
        private const val TAG = "BluetoothScoManager"

        /** Per-attempt budget for the link to report connected before retry/fail. */
        private const val CONNECT_TIMEOUT_MS = 8000L

        /** Total bring-up attempts (initial + one retry) before reporting failure. */
        private const val MAX_ATTEMPTS = 2

        private const val STATE_IDLE = 0
        private const val STATE_STARTING = 1
        private const val STATE_ACTIVE = 2

        /** The grant is missing or was revoked mid-bring-up; surface a permission hint. */
        const val REASON_PERMISSION = "permission"

        /** No SCO communication device is available (none paired/connected). */
        const val REASON_NO_DEVICE = "no-device"

        /** The platform refused the device selection. */
        const val REASON_REFUSED = "refused"

        /** The link did not report connected within the attempt budget. */
        const val REASON_TIMEOUT = "timeout"

        /** Any other platform failure. */
        const val REASON_ERROR = "error"

        /**
         * Pure retry-budget decision shared by the timeout and stack-error paths
         * (ODD-23): another bring-up attempt remains while fewer than
         * {@code maxAttempts} attempts have run. Refusal and no-device fail
         * unconditionally in their callers — no budget branch exists to drift.
         * Public for the JVM truth-table test (widened: Kotlin has no package
         * visibility); not part of the production API.
         */
        @JvmStatic
        fun shouldRetryBringUp(attempts: Int, maxAttempts: Int): Boolean {
            return attempts < maxAttempts
        }
    }

    interface Listener {
        /** The SCO link is up; the audio pipeline may now select the voice-call route. */
        fun onScoConnected()

        /** A live link dropped; the pipeline should fall back to the previous route. */
        fun onScoDisconnected()

        /**
         * The link never came up.
         * @param reason one of {@code REASON_*}.
         */
        fun onScoFailed(reason: String?)
    }

    private val context: Context
    private val audioManager: AudioManager
    private val listener: Listener

    // Slice 4: Handler timeout becomes a cancellable child (withTimeout-style
    // delay); stop() cancels it, so cancellation is the epoch for the timer.
    // Main.immediate is hardcoded (not injected like the budgets below):
    // correct on device; JVM tests need a Main-dispatcher rule to cover this.
    private val scoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null

    // Injected budgets (ODD-23): production uses the static defaults above;
    // a future device harness can narrow the timeout without touching the machine.
    private val connectTimeoutMs: Long
    private val maxAttempts: Int

    private var state: Int = STATE_IDLE
    private var attempts: Int = 0
    private var generation: Int = 0
    private var routeHeld: Boolean = false

    // Generation that holds the route request (see markRouteHeld): stale
    // abort paths only release their own epoch so a newer session's route
    // is never cleared by an older session's teardown.
    private var routeHeldEpoch: Int = -1

    private var deviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    constructor(context: Context?, listener: Listener?) : this(
        context,
        listener,
        CONNECT_TIMEOUT_MS,
        MAX_ATTEMPTS
    )

    // Widened to public: Kotlin has no package visibility (was package-private
    // for the JVM truth-table test).
    constructor(
        context: Context?,
        listener: Listener?,
        connectTimeoutMs: Long,
        maxAttempts: Int
    ) {
        // Nullability is explicit but the fail-fast contract is Java-identical:
        // Objects.requireNonNull returns its input type, so the Elvis throw
        // below is what actually narrows to non-null (same NPE + message).
        val ctx = context ?: throw NullPointerException("context")
        val lis = listener ?: throw NullPointerException("listener")
        require(connectTimeoutMs >= 0) { "connectTimeoutMs must be >= 0" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        this.context = ctx.applicationContext
        this.listener = lis
        this.connectTimeoutMs = connectTimeoutMs
        this.maxAttempts = maxAttempts
        val audioService = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        this.audioManager = audioService ?: throw NullPointerException("AudioManager")
    }

    /**
     * Begins SCO bring-up. No-op while starting or active. Every failure —
     * including pre-flight checks — reports through a posted
     * {@link Listener#onScoFailed} (never {@code onScoDisconnected}), so
     * callers must not assume the link is up when this returns.
     */
    fun start() {
        synchronized(this) {
            if (state != STATE_IDLE) {
                return
            }
            // Flip first: pre-flight failures below report through fail(), which
            // only notifies when leaving a non-IDLE state. Bump the generation
            // so callbacks posted by earlier sessions are dropped on delivery.
            // A new session never inherits a stale route hold.
            state = STATE_STARTING
            attempts = 0
            routeHeld = false
            routeHeldEpoch = -1
            ++generation
        }
        if (!hasConnectPermission()) {
            fail(REASON_PERMISSION)
            return
        }
        // Outside the monitor: bring-up IPC must never pin the lock that
        // main-thread callbacks and stop() need. (No mode work here: the
        // service route policy owns MODE_IN_COMMUNICATION; see class note.)
        attemptBringUp()
    }

    /**
     * Tears down the link. Idempotent and safe to call when idle; a failed
     * bring-up may still hold a requested route, so release is guarded by
     * the hold flag. Never fires listener callbacks: the generation bump
     * drops any already-posted failure on delivery. AudioManager mode is
     * owned by the service route policy, never touched here (see class note).
     */
    fun stop() {
        synchronized(this) {
            // One atomic unit with the flip: timeout cancel, observer
            // teardown, and the generation bump cannot interleave with a
            // concurrent bring-up's register/arm, matching fail() and
            // dropActiveLink(). Unregister calls are idempotent and fast;
            // route release stays outside the monitor.
            timeoutJob?.cancel()
            timeoutJob = null
            teardownObservers()
            state = STATE_IDLE
            ++generation
        }
        // Guarded release: a stale hold must never clear another app's
        // routing selection.
        releaseRouteIfHeld()
    }

    /** Cancels the manager scope; called once from the service teardown. */
    fun destroy() {
        stop()
        scoScope.cancel()
    }

    /** True when no bring-up is in progress and no link is up. */
    @Synchronized
    fun isIdle(): Boolean {
        return state == STATE_IDLE
    }

    /** True only once the link has reported connected and not since dropped. */
    @Synchronized
    fun isActive(): Boolean {
        return state == STATE_ACTIVE
    }

    // Bring-up runs outside the monitor (see class locking note), keyed by
    // session epoch: every step re-checks that its session is still current.
    private fun attemptBringUp() {
        val epoch: Int
        synchronized(this) {
            attempts++
            epoch = generation
        }
        val failure = bringUpModern(epoch)
        if (failure != null) {
            fail(failure)
        }
    }

    private fun isStartingEpoch(epoch: Int): Boolean {
        synchronized(this) {
            return state == STATE_STARTING && epoch == generation
        }
    }

    // Returns a REASON_* on failure, null on success or concurrent-stop abort
    // (stop() owns teardown there, so there is nothing to report).
    private fun bringUpModern(epoch: Int): String? {
        val sco: AudioDeviceInfo?
        try {
            sco = findScoDevice()
        } catch (e: SecurityException) {
            Log.w(TAG, "getAvailableCommunicationDevices denied", e)
            return REASON_PERMISSION
        } catch (e: Exception) {
            Log.w(TAG, "getAvailableCommunicationDevices threw", e)
            return REASON_ERROR
        }
        if (!isStartingEpoch(epoch)) {
            return null
        }
        if (sco == null) {
            return REASON_NO_DEVICE
        }
        try {
            if (!registerDeviceListener()) {
                return REASON_ERROR
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Device listener denied", e)
            return REASON_PERMISSION
        }
        if (!isStartingEpoch(epoch)) {
            // Stopped after registration: unregister what was just added.
            teardownObservers()
            return null
        }
        val accepted: Boolean
        try {
            accepted = audioManager.setCommunicationDevice(sco)
        } catch (e: SecurityException) {
            Log.w(TAG, "setCommunicationDevice denied", e)
            return REASON_PERMISSION
        } catch (e: Exception) {
            Log.w(TAG, "setCommunicationDevice threw", e)
            return REASON_ERROR
        }
        if (!accepted) {
            return REASON_REFUSED
        }
        markRouteHeld()
        if (!isStartingEpoch(epoch)) {
            // Stopped after the request landed: undo it, since stop() ran
            // before the request existed. Epoch-scoped: a newer session may
            // hold its own route by now, which this stale path must not clear.
            releaseRouteIfHeldForEpoch(epoch)
            return null
        }
        if (isScoActiveNow()) {
            // Already on SCO (preselected route or idempotent re-request):
            // complete now instead of burning the timeout waiting for a
            // change event that will never come. The timeout stays armed as
            // fallback; setActive (or stop) removes it.
            armTimeout()
            scoScope.launch {
                if (isStartingEpoch(epoch)) {
                    setActive()
                } else {
                    Log.i(TAG, "Dropping stale SCO fast-path activation")
                }
            }
            return null
        }
        armTimeout()
        return null
    }

    // Whether SCO audio is currently routed, independent of the state
    // machine. Fail-closed false: doubt falls through to event-driven setup.
    private fun isScoActiveNow(): Boolean {
        try {
            val current = audioManager.communicationDevice
            return current != null && current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } catch (e: Exception) {
            return false
        }
    }

    private fun findScoDevice(): AudioDeviceInfo? {
        // Explicitly nullable: the framework annotates this @NonNull, but a
        // null return from an OEM HAL must stay fail-closed, not warn away.
        val devices: List<AudioDeviceInfo>? = audioManager.availableCommunicationDevices
        if (devices == null) {
            return null
        }
        for (device in devices) {
            if (device != null && device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return device
            }
        }
        return null
    }

    /**
     * Whether an SCO-capable device is currently available for bring-up,
     * without starting anything. Used by the service attach trigger as a
     * fallback when the added array carries no SCO entry itself (A2DP-first
     * stagger, HALs that surface SCO only in the availability query).
     * Fail-closed false on any error: doubt falls through to event-driven
     * setup, never to a blind bring-up attempt. Public for the service
     * route policy (same library, adjacent package).
     */
    fun isScoDeviceAvailable(): Boolean {
        try {
            return findScoDevice() != null
        } catch (e: Exception) {
            return false
        }
    }

    private fun onModernDeviceChanged(device: AudioDeviceInfo?, epoch: Int) {
        val scoNow: Boolean
        val stateCopy: Int
        val currentEpoch: Int
        synchronized(this) {
            stateCopy = state
            currentEpoch = generation
            scoNow = device != null && device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (epoch != currentEpoch) {
            // Queued callback from a torn-down session: a newer start() owns
            // the route now and this stale event must not move the machine.
            return
        }
        if (stateCopy == STATE_STARTING && scoNow) {
            setActive()
        } else if (stateCopy == STATE_ACTIVE && !scoNow) {
            dropActiveLink()
        }
        // A system move to a non-SCO device mid-bring-up is left to burn the
        // attempt timeout: selections flap transiently during routing, and
        // failing fast here would turn every blip into a fallback.
    }

    private fun onConnectTimeout() {
        val retry: Boolean
        synchronized(this) {
            if (state != STATE_STARTING) {
                return
            }
            retry = shouldRetryBringUp(attempts, maxAttempts)
        }
        if (!retry) {
            fail(REASON_TIMEOUT)
            return
        }
        Log.i(TAG, "SCO bring-up attempt timed out, retrying")
        // Clear observers and any half-requested route before re-attempting.
        teardownObservers()
        releaseRouteIfHeld()
        synchronized(this) {
            if (state != STATE_STARTING) {
                return
            }
        }
        // Outside the monitor: bring-up IPC must never pin the lock.
        attemptBringUp()
    }

    private fun setActive() {
        synchronized(this) {
            if (state != STATE_STARTING) {
                return
            }
            state = STATE_ACTIVE
            timeoutJob?.cancel()
            timeoutJob = null
        }
        Log.i(TAG, "Bluetooth SCO connected")
        // Main-thread origin (broadcast, device listener, or timeout), so a
        // direct call preserves connected-then-callback ordering.
        listener.onScoConnected()
    }

    private fun dropActiveLink() {
        synchronized(this) {
            if (state != STATE_ACTIVE) {
                return
            }
            state = STATE_IDLE
            timeoutJob?.cancel()
            timeoutJob = null
            teardownObservers()
        }
        releaseRouteIfHeld()
        Log.i(TAG, "Bluetooth SCO disconnected")
        // Main-thread origin; see setActive.
        listener.onScoDisconnected()
    }

    private fun fail(reason: String?) {
        val generationCopy: Int
        synchronized(this) {
            if (state == STATE_IDLE) {
                return
            }
            state = STATE_IDLE
            timeoutJob?.cancel()
            timeoutJob = null
            teardownObservers()
            generationCopy = generation
        }
        releaseRouteIfHeld()
        Log.w(TAG, "Bluetooth SCO failed: " + reason)
        // Always launched in the manager scope: failures can surface on the
        // start() caller's thread. The generation check drops delivery when
        // stop() or a newer start() has since moved on, honoring stop()'s
        // never-callbacks contract.
        scoScope.launch {
            val current: Boolean = synchronized(this@BluetoothScoManager) {
                generationCopy == generation
            }
            if (current) {
                listener.onScoFailed(reason)
            } else {
                Log.i(TAG, "Dropping stale SCO failure callback: " + reason)
            }
        }
    }

    // Releases only when this session holds a route request, so a teardown
    // that follows a completed fail()/drop() cannot clear another app's
    // routing selection.
    private fun releaseRouteIfHeld() {
        val held: Boolean
        synchronized(this) {
            held = routeHeld
            routeHeld = false
            routeHeldEpoch = -1
        }
        if (held) {
            releaseRoute()
        }
    }

    private fun markRouteHeld() {
        synchronized(this) {
            routeHeld = true
            routeHeldEpoch = generation
        }
    }

    /**
     * Epoch-scoped release for stale abort paths: only clears the hold when
     * it still belongs to the given session generation. Authoritative
     * teardowns (stop/fail/drop/timeout) use {@link #releaseRouteIfHeld}.
     */
    private fun releaseRouteIfHeldForEpoch(epoch: Int) {
        val release: Boolean
        synchronized(this) {
            release = routeHeld && routeHeldEpoch == epoch
            if (release) {
                routeHeld = false
                routeHeldEpoch = -1
            }
        }
        if (release) {
            releaseRoute()
        }
    }

    private fun releaseRoute() {
        try {
            audioManager.clearCommunicationDevice()
        } catch (e: Exception) {
            Log.w(TAG, "SCO route release threw", e)
        }
    }

    private fun armTimeout() {
        // Assigns outside the monitor while stop()/setActive()/fail()
        // cancel-and-null under it: an interleaved stop() can orphan an 8 s
        // no-op timer, but its onConnectTimeout still finds state != STARTING
        // and returns, so the window is harmless (kept unsynchronized to
        // avoid blocking bring-up on teardown).
        timeoutJob?.cancel()
        timeoutJob = scoScope.launch {
            delay(connectTimeoutMs)
            onConnectTimeout()
        }
    }

    private fun hasConnectPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Self-locking: safe from bring-up (outside the monitor) and teardown paths.
    // Returns false when registration threw, in which case bring-up must fail
    // fast: without an observer nothing can ever report the link connected.
    @Synchronized
    private fun registerDeviceListener(): Boolean {
        if (deviceListener != null) {
            return true
        }
        val epoch = generation
        val listenerObj = AudioManager.OnCommunicationDeviceChangedListener { device ->
            onModernDeviceChanged(device, epoch)
        }
        deviceListener = listenerObj
        try {
            audioManager.addOnCommunicationDeviceChangedListener(
                context.mainExecutor,
                listenerObj
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener denied", e)
            deviceListener = null
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener threw", e)
            deviceListener = null
            return false
        }
        return true
    }

    @Synchronized
    private fun teardownObservers() {
        val current = deviceListener
        if (current != null) {
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(current)
            } catch (e: Exception) {
                Log.w(TAG, "Device listener removal threw", e)
            }
            deviceListener = null
        }
    }
}
