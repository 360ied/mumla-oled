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
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lublin.humla.route.RouteThread

/**
 * Owns the Bluetooth SCO (HFP) audio route for a voice session.
 *
 * SCO link bring-up is asynchronous and unreliable: the platform call
 * returns before the link exists, establishment takes seconds, and failure is
 * silent without a state callback. This manager hides that behind a small state
 * machine with a connect timeout, one retry, and automatic fallback reporting.
 *
 * SCO link bring-up runs on one of two platform backends by API level:
 * API 31+ uses `setCommunicationDevice()` with a
 * `TYPE_BLUETOOTH_SCO` device from
 * `getAvailableCommunicationDevices()`, observed via
 * `OnCommunicationDeviceChangedListener`. API 30 uses the legacy
 * `startBluetoothSco()`/`stopBluetoothSco()` pair, observed via
 * the `ACTION_SCO_AUDIO_STATE_UPDATED` broadcast. Both backends share
 * the same state machine, timeout, retry budget, and epoch guards below.
 *
 * Threading: confined to the route thread (docs/target-architecture/route.md,
 * Thread). Control calls arrive from the route controller on it, and the
 * manager's timers, S+ device listener, and API 30 broadcast receiver are
 * all delivered on it, so no two of them ever run at once. The one
 * exception is [isActive], which the service also reads from the UI.
 * Confinement removes concurrency, not queued work: a platform event or
 * failure callback posted for one session can still run after `stop()` or
 * the next `start()`. The session generation drops those on delivery, so
 * `stop()` truly never callbacks; the timeout is cancelled by `stop()`
 * instead. Debug builds throw when an entry point runs off the thread.
 *
 * AudioManager mode is deliberately NOT owned here. The service route
 * policy (`HumlaService`) is the single owner of
 * `MODE_IN_COMMUNICATION`: it holds the mode for the whole voice-route
 * duration, which covers confirmed SCO sessions. Setting it here as well
 * made the service classify our own hold as externally owned and give up
 * its claim, stranding the mode on later media switches — so this manager
 * only selects and releases the SCO route, never the mode.
 */
class BluetoothScoManager : ScoLink {
    companion object {
        private val TAG = BluetoothScoManager::class.java.simpleName

        /** Per-attempt budget for the link to report connected before retry/fail. */
        private const val CONNECT_TIMEOUT_MS = 8000L

        /** Total bring-up attempts (initial + one retry) before reporting failure. */
        private const val MAX_ATTEMPTS = 2

        private enum class ScoState { IDLE, STARTING, ACTIVE }

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
         * Pure retry-budget decision for the connect-timeout path: another
         * bring-up attempt remains while fewer than `maxAttempts` attempts
         * have run. Refusal and no-device fail unconditionally in their
         * callers — no budget branch exists to drift. (Stack-error
         * failures go straight to `fail()` with no retry.)
         * Visible for the JVM truth-table test; not part of the production API.
         */
        @VisibleForTesting
        @JvmStatic
        fun shouldRetryBringUp(attempts: Int, maxAttempts: Int): Boolean {
            return attempts in 0 until maxAttempts
        }
    }

    interface Listener {
        /**
         * Callbacks run on the manager scope and must not throw: a throwing
         * observer is logged and contained for [RuntimeException], but an
         * [Error] still propagates to the scope. In practice HumlaService
         * never throws from these paths.
         */
        /** The SCO link is up; the audio pipeline may now select the voice-call route. */
        fun onScoConnected()

        /** A live link dropped; the pipeline should fall back to the previous route. */
        fun onScoDisconnected()

        /**
         * The link never came up.
         * @param reason one of `REASON_*`.
         */
        fun onScoFailed(reason: String?)
    }

    private val context: Context
    private val audioManager: AudioManager
    private val listener: Listener

    // Thread the manager's timers and platform observers run on: the
    // dispatcher backs scoScope, the executor the S+ device listener, and
    // the handler the API 30 broadcast receiver.
    private val thread: RouteThread

    // Handler timeout becomes a cancellable child (withTimeout-style
    // delay); stop() cancels it, so cancellation is the epoch for the timer.
    private val scoScope: CoroutineScope
    // Armed and cancelled on the route thread; onConnectTimeout re-checks
    // state, so a missed cancel only orphans a harmless no-op timer.
    private val timeoutJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    // Injected budgets (ODD-23): production uses the static defaults above;
    // a future device harness can narrow the timeout without touching the machine.
    private val connectTimeoutMs: Long
    private val maxAttempts: Int

    private val state = java.util.concurrent.atomic.AtomicReference(ScoState.IDLE)
    private val attempts = java.util.concurrent.atomic.AtomicInteger(0)
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val routeHeld = java.util.concurrent.atomic.AtomicBoolean(false)

    // S+-only listener type is deliberately erased to Any: naming
    // AudioManager.OnCommunicationDeviceChangedListener in a field
    // descriptor risks class verification failure on API 30, where the
    // nested class does not exist. Only the S+ methods below ever cast it
    // back, and they run solely behind an SDK_INT >= S dispatch.
    private var deviceListener: Any? = null

    // Legacy backend (API 30): broadcast receiver for
    // ACTION_SCO_AUDIO_STATE_UPDATED. Assigned on the route dispatcher like
    // deviceListener above; only one backend is ever active (dispatch on
    // SDK_INT), but teardown clears both for safety.
    private var scoReceiver: BroadcastReceiver? = null

    // The route thread the manager is confined to; budgets default to
    // production and are narrowed by the JVM tests.
    internal constructor(
        context: Context?,
        listener: Listener?,
        thread: RouteThread,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        maxAttempts: Int = MAX_ATTEMPTS
    ) {
        // Nullability is explicit but the fail-fast contract is Java-identical:
        // Objects.requireNonNull returns its input type, so the Elvis throw
        // below is what actually narrows to non-null. Messages name the
        // contract (not just the value) for readable NPE reports.
        val ctx = context ?: throw NullPointerException("BluetoothScoManager context must not be null")
        val lis = listener ?: throw NullPointerException("BluetoothScoManager listener must not be null")
        require(connectTimeoutMs >= 0) { "connectTimeoutMs must be >= 0" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        this.context = ctx.applicationContext
        this.listener = lis
        this.thread = thread
        this.scoScope = CoroutineScope(SupervisorJob() + thread.dispatcher)
        this.connectTimeoutMs = connectTimeoutMs
        this.maxAttempts = maxAttempts
        val audioService = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        this.audioManager = audioService ?: throw NullPointerException("AudioManager")
    }

    /**
     * Begins SCO bring-up. No-op while starting or active. Every failure —
     * including pre-flight checks — reports through a posted
     * {@link Listener#onScoFailed} (never throws), so
     * callers must not assume the link is up when this returns.
     */
    override fun start() {
        checkOnThread()
        run {
            if (state.get() != ScoState.IDLE) {
                return
            }
            // Flip first: pre-flight failures below report through fail(), which
            // only notifies when leaving a non-IDLE state. Bump the generation
            // so callbacks posted by earlier sessions are dropped on delivery.
            // A new session never inherits a stale route hold.
            state.set(ScoState.STARTING)
            attempts.set(0)
            routeHeld.set(false)
            generation.incrementAndGet()
        }
        if (!hasConnectPermission()) {
            fail(REASON_PERMISSION)
            return
        }
        // Bring-up IPC runs here on the route thread, never on main. (No
        // mode work here: the service route policy owns
        // MODE_IN_COMMUNICATION; see class note.)
        attemptBringUp()
    }

    /**
     * Tears down the link. Idempotent and safe to call when idle; a failed
     * bring-up may still hold a requested route, so release is guarded by
     * the hold flag. Never fires listener callbacks: the generation bump
     * drops any already-posted failure on delivery. AudioManager mode is
     * owned by the service route policy, never touched here (see class note).
     */
    override fun stop() {
        checkOnThread()
        val taken: Observers
        run {
            // One atomic unit with the flip: timeout cancel and the
            // generation bump cannot interleave with a concurrent
            // bring-up's register/arm, matching fail() and dropActiveLink().
            // The unregister IPC runs below without holding shared state; route
            // release stays outside too.
            timeoutJob.get()?.cancel()
            timeoutJob.set(null)
            taken = takeObserversLocked()
            state.set(ScoState.IDLE)
            generation.incrementAndGet()
        }
        unregisterObservers(taken)
        // Guarded release: a stale hold must never clear another app's
        // routing selection.
        releaseRouteIfHeld()
    }

    /** Cancels the manager scope; called once from the service teardown. */
    override fun destroy() {
        checkOnThread()
        stop()
        scoScope.cancel()
    }

    /** True when no bring-up is in progress and no link is up. */
    override fun isIdle(): Boolean {
        checkOnThread()
        return state.get() == ScoState.IDLE
    }

    /**
     * True only once the link has reported connected and not since dropped.
     * Unlike the other entry points, safe from any thread: the service's
     * `isBluetoothScoActive` reads it from the UI.
     */
    override fun isActive(): Boolean {
        return state.get() == ScoState.ACTIVE
    }

    // Debug builds throw so a wiring mistake fails fast in tests and daily
    // use; release builds log and carry on, like checkNotMainThread.
    private fun checkOnThread() {
        if (thread.isCurrent()) return
        if (se.lublin.humla.BuildConfig.DEBUG) {
            throw IllegalStateException("BluetoothScoManager used off the route thread")
        }
        Log.w(TAG, "BluetoothScoManager used off the route thread")
    }

    // Bring-up runs to completion on the route thread: nothing else runs
    // between its IPC steps (platform events and the controller's callbacks
    // are posted), so its session stays current throughout. The epoch keys
    // the work it leaves queued: listener events and the timeout.
    private fun attemptBringUp() {
        val epoch: Int
        run {
            attempts.incrementAndGet()
            epoch = generation.get()
        }
        // Dual backend: communication-device path on S+, legacy
        // startBluetoothSco path on 30. The inline SDK_INT guard is also
        // what satisfies lint's NewApi check for the modern call below.
        val failure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bringUpModern(epoch)
        } else {
            bringUpLegacy(epoch)
        }
        if (failure != null) {
            fail(failure)
        }
    }

    private fun isStartingEpoch(epoch: Int): Boolean {
        run {
            return state.get() == ScoState.STARTING && epoch == generation.get()
        }
    }

    // Returns a REASON_* on failure, null on success.
    // Modern backend (S+): callers guarantee SDK_INT >= S via attemptBringUp.
    @TargetApi(Build.VERSION_CODES.S)
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
        routeHeld.set(true)
        if (isScoActiveNow()) {
            // Already on SCO (preselected route or idempotent re-request):
            // complete now instead of burning the timeout waiting for a
            // change event that will never come. The timeout stays armed as
            // fallback; setActive (or stop) removes it. Posted, so the
            // epoch check drops it if stop() or a newer start() runs first.
            armTimeout(epoch)
            scoScope.launch {
                if (isStartingEpoch(epoch) && isScoActiveNow()) {
                    setActive()
                } else {
                    Log.i(TAG, "Dropping stale SCO fast-path activation")
                }
            }
            return null
        }
        armTimeout(epoch)
        return null
    }

    // Whether SCO audio is currently routed, independent of the state
    // machine. Fail-closed false: doubt falls through to event-driven setup.
    private fun isScoActiveNow(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return isScoActiveNowModern()
            }
            return audioManager.isBluetoothScoOn
        } catch (e: Exception) {
            return false
        }
    }

    // Modern active check (S+): callers guarantee the version via isScoActiveNow.
    @TargetApi(Build.VERSION_CODES.S)
    private fun isScoActiveNowModern(): Boolean {
        val current = audioManager.communicationDevice
        return current != null && current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    // Modern device query (S+): callers guarantee the version via
    // bringUpModern/isScoDeviceAvailableModern (both @TargetApi(S)).
    // Null-element guard stays despite the @NonNull element type: an OEM
    // HAL may return nulls, which must stay fail-closed, not warn away.
    @Suppress("SENSELESS_COMPARISON")
    @TargetApi(Build.VERSION_CODES.S)
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
     * S+ queries the communication-device list; pre-S falls back to the
     * legacy off-call availability flag.
     */
    override fun isScoDeviceAvailable(): Boolean {
        checkOnThread()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return isScoDeviceAvailableModern()
            }
            return audioManager.isBluetoothScoAvailableOffCall
        } catch (e: Exception) {
            return false
        }
    }

    // Modern availability query (S+): callers guarantee the version above.
    @TargetApi(Build.VERSION_CODES.S)
    private fun isScoDeviceAvailableModern(): Boolean {
        return findScoDevice() != null
    }

    private fun onModernDeviceChanged(device: AudioDeviceInfo?, epoch: Int) {
        checkOnThread()
        val scoNow: Boolean
        val stateCopy: ScoState
        val currentEpoch: Int
        run {
            stateCopy = state.get()
            currentEpoch = generation.get()
            scoNow = device != null && device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (epoch != currentEpoch) {
            // Queued callback from a torn-down session: a newer start() owns
            // the route now and this stale event must not move the machine.
            return
        }
        if (stateCopy == ScoState.STARTING && scoNow) {
            setActive()
        } else if (stateCopy == ScoState.ACTIVE && !scoNow) {
            dropActiveLink()
        }
        // A system move to a non-SCO device mid-bring-up is left to burn the
        // attempt timeout: selections flap transiently during routing, and
        // failing fast here would turn every blip into a fallback.
    }

    // Legacy bring-up (API 30): startBluetoothSco is async like the modern
    // path - the link reports via ACTION_SCO_AUDIO_STATE_UPDATED, watched
    // with the same timeout/retry budget. Returns REASON_* or null like
    // bringUpModern. Callers guarantee SDK_INT < S via attemptBringUp.
    private fun bringUpLegacy(epoch: Int): String? {
        try {
            if (!audioManager.isBluetoothScoAvailableOffCall) {
                return REASON_NO_DEVICE
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Legacy SCO availability denied", e)
            return REASON_PERMISSION
        } catch (e: Exception) {
            Log.w(TAG, "Legacy SCO availability threw", e)
            return REASON_ERROR
        }
        // registerLegacyReceiver swallows all registration errors to false and
        // never throws, so no SecurityException catch here (modern path keeps
        // its own permission mapping).
        if (!registerDeviceListener()) {
            return REASON_ERROR
        }
        try {
            audioManager.startBluetoothSco()
        } catch (e: SecurityException) {
            Log.w(TAG, "startBluetoothSco denied", e)
            return REASON_PERMISSION
        } catch (e: Exception) {
            Log.w(TAG, "startBluetoothSco threw", e)
            return REASON_ERROR
        }
        try {
            audioManager.isBluetoothScoOn = true
        } catch (e: SecurityException) {
            Log.w(TAG, "SCO flag set denied after start; stopping link", e)
            try {
                audioManager.stopBluetoothSco()
            } catch (ignored: Exception) {
                Log.w(TAG, "SCO stop after flag failure threw", ignored)
            }
            return REASON_PERMISSION
        } catch (e: Exception) {
            Log.w(TAG, "SCO flag set threw after start; stopping link", e)
            try {
                audioManager.stopBluetoothSco()
            } catch (ignored: Exception) {
                Log.w(TAG, "SCO stop after flag failure threw", ignored)
            }
            return REASON_ERROR
        }
        routeHeld.set(true)
        // No fast-path here (unlike the modern backend): isBluetoothScoOn
        // is the request flag just set above, so reading it back would
        // always report "active" and complete before the link exists -
        // cancelling the timeout and disabling the retry/failure net.
        // Always await the CONNECTED broadcast.
        armTimeout(epoch)
        return null
    }

    // Legacy broadcast mapping: CONNECTED while starting completes the
    // bring-up; a drop or error while active falls back. A legacy ERROR
    // while STARTING is terminal link failure (unlike transient modern
    // selection flaps): fail fast instead of burning the attempt timeout.
    // A mid-bring-up DISCONNECTED still burns the timeout — the legacy
    // stack may flap before the first CONNECTED.
    private fun onLegacyScoStateChanged(scoState: Int, epoch: Int) {
        checkOnThread()
        val stateCopy: ScoState
        val currentEpoch: Int
        run {
            stateCopy = state.get()
            currentEpoch = generation.get()
        }
        if (epoch != currentEpoch) {
            return
        }
        if (stateCopy == ScoState.STARTING &&
            scoState == AudioManager.SCO_AUDIO_STATE_ERROR
        ) {
            fail(REASON_ERROR)
            return
        }
        if (stateCopy == ScoState.STARTING &&
            scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED
        ) {
            setActive()
        } else if (stateCopy == ScoState.ACTIVE &&
            (scoState == AudioManager.SCO_AUDIO_STATE_DISCONNECTED ||
                scoState == AudioManager.SCO_AUDIO_STATE_ERROR)
        ) {
            dropActiveLink()
        }
    }

    private fun onConnectTimeout(epoch: Int) {
        checkOnThread()
        val retry: Boolean
        run {
            if (state.get() != ScoState.STARTING || epoch != generation.get()) {
                return
            }
            retry = shouldRetryBringUp(attempts.get(), maxAttempts)
        }
        if (!retry) {
            fail(REASON_TIMEOUT)
            return
        }
        Log.i(TAG, "SCO bring-up attempt timed out, retrying")
        // Clear observers and any half-requested route before re-attempting.
        // Reusing the same generation is safe: the teardown drops every
        // in-flight callback registration, so the first attempt has no live
        // observer left to be mistaken for the retry's.
        teardownObservers()
        releaseRouteIfHeld()
        attemptBringUp()
    }

    private fun setActive() {
        run {
            if (state.get() != ScoState.STARTING) {
                return
            }
            state.set(ScoState.ACTIVE)
            timeoutJob.get()?.cancel()
            timeoutJob.set(null)
        }
        Log.i(TAG, "Bluetooth SCO connected")
        // Direct call after the flip preserves connected-then-callback
        // ordering; the controller re-posts it to its own scope.
        try {
            listener.onScoConnected()
        } catch (e: Exception) {
            Log.w(TAG, "SCO connected listener threw", e)
        }
    }

    private fun dropActiveLink() {
        val taken: Observers
        run {
            if (state.get() != ScoState.ACTIVE) {
                return
            }
            state.set(ScoState.IDLE)
            timeoutJob.get()?.cancel()
            timeoutJob.set(null)
            taken = takeObserversLocked()
        }
        unregisterObservers(taken)
        releaseRouteIfHeld()
        Log.i(TAG, "Bluetooth SCO disconnected")
        // Direct call after the flip; see setActive.
        try {
            listener.onScoDisconnected()
        } catch (e: Exception) {
            Log.w(TAG, "SCO disconnected listener threw", e)
        }
    }

    private fun fail(reason: String?) {
        val generationCopy: Int
        val taken: Observers
        run {
            if (state.get() == ScoState.IDLE) {
                return
            }
            state.set(ScoState.IDLE)
            timeoutJob.get()?.cancel()
            timeoutJob.set(null)
            taken = takeObserversLocked()
            generationCopy = generation.get()
        }
        unregisterObservers(taken)
        releaseRouteIfHeld()
        Log.w(TAG, "Bluetooth SCO failed: $reason")
        // Always launched in the manager scope: failures can surface on the
        // start() caller's thread. The generation check drops delivery when
        // stop() or a newer start() has since moved on, honoring stop()'s
        // never-callbacks contract.
        scoScope.launch {
            val current: Boolean = run {
                generationCopy == generation.get()
            }
            if (current) {
                try {
                    listener.onScoFailed(reason)
                } catch (e: Exception) {
                    Log.w(TAG, "SCO failure listener threw", e)
                }
            } else {
                Log.i(TAG, "Dropping stale SCO failure callback: " + reason)
            }
        }
    }

    // Releases only when this session holds a route request, so a teardown
    // that follows a completed fail()/drop() cannot clear another app's
    // routing selection. A failed release keeps the hold for the next try.
    private fun releaseRouteIfHeld() {
        if (!routeHeld.get()) {
            return
        }
        if (releaseRoute()) {
            routeHeld.set(false)
        }
    }

    private fun releaseRoute(): Boolean {
        var ok = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                releaseRouteModern()
            } else {
                // Stop-then-clear: stopping first tears down the link, and
                // clearing the routing flag second mirrors start (start-then-
                // flag) in reverse.
                try {
                    audioManager.stopBluetoothSco()
                } catch (ignored: Exception) {
                    Log.w(TAG, "Legacy SCO stop threw", ignored)
                    ok = false
                }
                try {
                    audioManager.isBluetoothScoOn = false
                } catch (ignored: Exception) {
                    Log.w(TAG, "Legacy SCO routing clear threw", ignored)
                    ok = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SCO route release threw", e)
            ok = false
        }
        return ok
    }

    // Modern route release (S+): callers guarantee the version above.
    @TargetApi(Build.VERSION_CODES.S)
    private fun releaseRouteModern() {
        audioManager.clearCommunicationDevice()
    }

    // Arms this attempt's timeout, replacing any earlier one. stop(),
    // fail(), setActive() and dropActiveLink() cancel it; onConnectTimeout
    // still re-checks the epoch, so a timer that escapes a cancel is a no-op.
    private fun armTimeout(epoch: Int) {
        timeoutJob.get()?.cancel()
        timeoutJob.set(scoScope.launch {
            delay(connectTimeoutMs)
            onConnectTimeout(epoch)
        })
    }

    // BLUETOOTH_CONNECT is an API 31+ runtime permission; below S the legacy
    // BLUETOOTH/BLUETOOTH_ADMIN manifest grants apply and no runtime check
    // exists (the permission string is unknown there and would always check
    // DENIED, falsely failing every bring-up with REASON_PERMISSION).
    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Idempotent teardown: safe from bring-up and teardown paths.
    // Returns false when registration threw, in which case bring-up must fail
    // fast: without an observer nothing can ever report the link connected.
    // Dispatches on SDK_INT: modern communication-device listener on S+,
    // legacy SCO broadcast receiver on 30.
    private fun registerDeviceListener(): Boolean {
        if (deviceListener != null || scoReceiver != null) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return registerModernListener()
        }
        return registerLegacyReceiver()
    }

    // Modern observer (S+): callers guarantee the version above.
    @TargetApi(Build.VERSION_CODES.S)
    private fun registerModernListener(): Boolean {
        val epoch = generation.get()
        val listenerObj = AudioManager.OnCommunicationDeviceChangedListener { device ->
            onModernDeviceChanged(device, epoch)
        }
        deviceListener = listenerObj
        try {
            audioManager.addOnCommunicationDeviceChangedListener(
                thread.executor,
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

    // Legacy observer (API 30): SCO audio-state broadcast. The inline
    // SDK_INT dispatch in registerDeviceListener is what keeps this off S+,
    // and context-registered system broadcasts need no exported flag below
    // 33 (the flagless overload is used deliberately to avoid an androidx
    // dependency in this module). Delivered on the manager's thread handler.
    private fun registerLegacyReceiver(): Boolean {
        val epoch = generation.get()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val scoState = intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                ) ?: return
                onLegacyScoStateChanged(scoState, epoch)
            }
        }
        scoReceiver = receiver
        try {
            context.registerReceiver(
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                null,
                thread.handler
            )
        } catch (e: Exception) {
            Log.w(TAG, "SCO receiver registration threw", e)
            scoReceiver = null
            return false
        }
        return true
    }

    /** Snapshot holder so observer unregister IPC never blocks the route dispatcher. */
    private class Observers(val deviceListener: Any?, val scoReceiver: BroadcastReceiver?)

    /** Nulls the observer fields; route-dispatcher confined. */
    private fun takeObserversLocked(): Observers {
        val taken = Observers(deviceListener, scoReceiver)
        deviceListener = null
        scoReceiver = null
        return taken
    }

    /** Unregisters a snapshot (idempotent, fast). */
    private fun unregisterObservers(taken: Observers) {
        val current = taken.deviceListener
        if (current != null) {
            // Guarded: the listener class and remove call exist only on S+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    removeModernListener(current)
                } catch (e: Exception) {
                    Log.w(TAG, "Device listener removal threw", e)
                }
            }
        }
        val receiver = taken.scoReceiver
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "SCO receiver removal threw", e)
            }
        }
    }

    /**
     * Clears both observer backends. Snapshots under the monitor, unregisters
     * outside it: bring-up IPC must never pin the lock that main-thread
     * callbacks and stop() need (see class note). Callers already holding
     * the monitor must use takeObserversLocked + unregisterObservers instead.
     */
    private fun teardownObservers() {
        unregisterObservers(run { takeObserversLocked() })
    }

    // Modern removal (S+): callers guarantee the version above. Takes Any
    // so no field or method signature names the S+-only listener type
    // (class-verification safety on API 30); the cast only ever executes
    // on S+. The explicit check (rather than relying on the caller's
    // catch) keeps a wiring bug from surfacing as a ClassCastException.
    @TargetApi(Build.VERSION_CODES.S)
    private fun removeModernListener(listener: Any) {
        val modern = listener as? AudioManager.OnCommunicationDeviceChangedListener
        if (modern == null) {
            Log.w(TAG, "Device listener has unexpected type; skipping removal")
            return
        }
        audioManager.removeOnCommunicationDeviceChangedListener(modern)
    }
}
