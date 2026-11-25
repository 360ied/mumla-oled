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

package se.lublin.mumla.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.ReconnectPolicy
import se.lublin.mumla.container

/**
 * The foreground service that lives exactly while a connection does. It owns
 * no logic: it posts the connection notification feature's current
 * notification, holds the keep-alive shields with today's windows, and stops
 * itself when the client state reaches Idle or Failed.
 *
 * Nothing binds to it (`onBind` returns null). The UI's connect path starts it
 * at the first visible attempt (see `AppContainer.connect`).
 */
class ConnectionService : Service() {

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager?
        if (powerManager != null) {
            val lock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Humla:ConnectionService",
            )
            lock.setReferenceCounted(false)
            wakeLock = lock
        } else {
            Log.w(TAG, "No PowerManager; running without wake lock")
            wakeLock = null
        }
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager?
            if (wifiManager != null) {
                val lock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Humla:ConnectionServiceWifi",
                )
                lock.setReferenceCounted(false)
                wifiLock = lock
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create WifiLock; radio may sleep on battery.", e)
            wifiLock = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext.container
        // Meets the five-second rule however late client.state moves.
        postForeground(app.connectionNotification.notification.value)
        // A later connect starts the already-running service again; keep one
        // collector for the instance instead of one per start request.
        if (scope == null) {
            val job = SupervisorJob()
            val serviceScope = CoroutineScope(job + Dispatchers.Main.immediate)
            scope = serviceScope
            serviceScope.launch {
                combine(
                    app.runtime.client.state,
                    app.connectionNotification.notification,
                ) { state, notification -> state to notification }
                    .collect { (state, notification) -> onConnectionState(state, notification) }
            }
        }
        return START_NOT_STICKY
    }

    private fun onConnectionState(state: ClientState, notification: Notification) {
        when (state) {
            is ClientState.Idle, is ClientState.Failed -> {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            is ClientState.Connecting -> {
                acquireWakeLock(WAKELOCK_GRACE_MS)
                acquireWifiLock()
                postForeground(notification)
            }
            is ClientState.Connected -> {
                acquireWakeLock(null)
                acquireWifiLock()
                postForeground(notification)
            }
            is ClientState.Reconnecting -> {
                // Backoff delay plus the grace window, today's keep-alive rule.
                val delayMs = ReconnectPolicy.delayForAttempts(state.attempt)
                acquireWakeLock(delayMs + WAKELOCK_GRACE_MS)
                acquireWifiLock()
                postForeground(notification)
            }
        }
    }

    override fun onDestroy() {
        // A connection never outlives its foreground service: an unrequested
        // destroy ends it, while a normal stop already saw Idle/Failed.
        scope?.cancel()
        scope = null
        releaseLocks()
        try {
            val client = applicationContext.container.runtime.client
            val state = client.state.value
            if (state !is ClientState.Idle && state !is ClientState.Failed) {
                client.disconnect()
            }
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }

    private fun acquireWakeLock(timeoutMs: Long?) {
        try {
            val lock = wakeLock ?: return
            if (lock.isHeld) {
                lock.release()
            }
            if (timeoutMs == null) {
                lock.acquire()
            } else {
                lock.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock.", e)
        }
    }

    private fun acquireWifiLock() {
        try {
            val lock = wifiLock
            if (lock != null && !lock.isHeld) {
                lock.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WifiLock.", e)
        }
    }

    private fun releaseLocks() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release WakeLock.", e)
        }
        try {
            val lock = wifiLock
            if (lock != null && lock.isHeld) {
                lock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release WifiLock.", e)
        }
    }

    private fun postForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private val TAG = "ConnectionService"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_GRACE_MS = 15000L
    }
}
