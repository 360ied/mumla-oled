/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.service

import android.app.Notification
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.HumlaRuntime
import se.lublin.humla.HumlaService
import se.lublin.humla.model.Server
import se.lublin.humla.util.CertRef
import se.lublin.humla.util.ConfigDelta
import se.lublin.humla.util.TrustStoreRef
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.TalkKeyEvent
import se.lublin.mumla.container
import se.lublin.mumla.util.MumlaTrustStore

/**
 * The bound service in step 6: a thin delegation shell. Every responsibility
 * moved into an application-lifetime feature; only binder delegations, the
 * connect half of ConfigDelta, and the error-shown flag remain for step 7.
 */
// Open: the Robolectric suite subclasses this service with test doubles.
open class MumlaService : HumlaService(), IMumlaService {
    /** True once the user dismissed the connection error (step 7 moves it). */
    private var mErrorShown: Boolean = false

    override fun onCreate() {
        super.onCreate()
    }

    override fun onBind(intent: Intent): IBinder = MumlaBinder(this)

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null && action in NOTIFICATION_ACTIONS) {
            container.onNotificationAction(action)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun runtime(): HumlaRuntime = container.runtime

    override fun foregroundNotification(): StateFlow<Notification> =
        container.connectionNotification.notification

    override fun buildConnectDelta(intent: Intent?): ConfigDelta? {
        // NPE-exact: the original dereferenced intent unconditionally.
        val request = intent ?: throw NullPointerException("intent")
        val settings = container.settings
        val database = container.database
        val serverId = request.getLongExtra(EXTRA_SERVER_ID, -1)
        val connected: Server? = if (serverId != -1L) {
            // Database is the source of truth for saved servers; the parcel
            // path below serves unsaved (ad-hoc) servers only.
            database.getServers().firstOrNull { it.id == serverId }
        } else {
            // IntentCompat backports the API 33 typed getParcelableExtra.
            IntentCompat.getParcelableExtra(request, EXTRA_SERVER, Server::class.java)
        }
        if (connected == null) return null
        // A new connect attempt clears the dismissed-error flag so a second
        // failure shows its dialog (step 7 moves this flag to MainViewModel).
        mErrorShown = false

        val builder = ConfigDelta.Builder()
        builder.server = connected
        builder.clientName = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
        builder.forceTcp = settings.isTcpForced
        builder.accessTokens = database.getAccessTokens(connected.id)
        builder.trustStore = TrustStoreRef(
            MumlaTrustStore.getTrustStorePath(this),
            MumlaTrustStore.getTrustStorePassword(),
            MumlaTrustStore.getTrustStoreFormat(),
        )
        if (settings.isUsingCertificate) {
            val certificateId = settings.defaultCertificate
            val certificate = database.getCertificateData(certificateId)
            val certificatePassword = database.getCertificatePassword(certificateId)
            if (certificate != null) {
                builder.certificate = CertRef(certificate, certificatePassword)
            }
        }
        return builder.build()
    }

    // Binder delegations: reads return the feature's current StateFlow value.
    override fun setOverlayShown(showOverlay: Boolean) =
        container.overlay.setShown(showOverlay)

    override fun isOverlayShown(): Boolean = container.overlay.isShown()

    override fun clearChatNotifications() = container.messageNotifications.clear()

    override fun markErrorShown() {
        mErrorShown = true
    }

    override fun isErrorShown(): Boolean = mErrorShown

    override fun onTalkKeyDown() = container.emitTalkKey(TalkKeyEvent.Down)

    override fun onTalkKeyUp() = container.emitTalkKey(TalkKeyEvent.Up)

    override fun onTalkKeyCancel() = container.emitTalkKey(TalkKeyEvent.Cancel)

    override fun setSuppressNotifications(suppressNotifications: Boolean) =
        container.connectionNotification.setSuppressNotifications(suppressNotifications)

    class MumlaBinder internal constructor(private val mService: MumlaService) : Binder() {
        fun getService(): IMumlaService = mService
    }

    companion object {
        /** CONNECT intent extra: database id of the saved server (-1 if unsaved). */
        const val EXTRA_SERVER_ID = "server_id"
        /** CONNECT intent extra: full parcel, only for unsaved (ad-hoc) servers. */
        const val EXTRA_SERVER = "server"

        const val ACTION_DISCONNECT = "se.lublin.mumla.action.DISCONNECT"
        const val ACTION_MUTE = "se.lublin.mumla.action.MUTE"
        const val ACTION_DEAFEN = "se.lublin.mumla.action.DEAFEN"
        const val ACTION_TOGGLE_OVERLAY = "se.lublin.mumla.action.TOGGLE_OVERLAY"
        const val ACTION_CANCEL_RECONNECT = "se.lublin.mumla.action.CANCEL_RECONNECT"

        private val NOTIFICATION_ACTIONS = setOf(
            ACTION_DISCONNECT, ACTION_MUTE, ACTION_DEAFEN,
            ACTION_TOGGLE_OVERLAY, ACTION_CANCEL_RECONNECT,
        )
    }
}
