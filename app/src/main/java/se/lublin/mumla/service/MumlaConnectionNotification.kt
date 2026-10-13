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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.media.app.NotificationCompat.MediaStyle
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity

/**
 * Wrapper to create Mumla notifications.
 * Created by andrew on 08/08/14.
 */
class MumlaConnectionNotification {
    private val mService: Service?
    private val mListener: OnActionListener?
    private val mSettings: Settings
    private var mMediaSession: MediaSessionCompat? = null
    private var mContentTitle: String? = null
    private var mContentText: String? = null
    private var mSubText: String? = null
    private var mBigText: String? = null
    private var mActionsShown: Boolean = false
    private var mReconnectingShown: Boolean = false
    private var mMuted: Boolean = false
    private var mDeafened: Boolean = false
    private var mOverlayShown: Boolean = false

    private constructor(service: Service?, listener: OnActionListener?) :
        this(service, listener, service?.let { Settings.getInstance(it) })

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package service and its tests).
    constructor(service: Service?, listener: OnActionListener?, settings: Settings?) {
        mService = service
        mListener = listener
        // NPE-exact: the original dereferenced service unconditionally for the default.
        mSettings = settings ?: Settings.getInstance(service ?: throw NullPointerException("service"))
        mActionsShown = false
        mReconnectingShown = false
        mMuted = false
        mDeafened = false
        mOverlayShown = false
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package tests).
    fun isMediaSessionActive(): Boolean {
        val session = mMediaSession
        return session != null && session.isActive
    }

    // Widened to public: Kotlin has no package visibility (was package-visible
    // in Java for the same-package tests).
    fun getSettings(): Settings {
        return mSettings
    }

    fun showConnecting(serverName: String, host: String, port: Int) {
        // NPE-exact: the original dereferenced mService unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        mContentTitle = serverName
        mContentText = service.getString(R.string.connecting_to_server, host)
        mSubText = service.getString(R.string.mumlaConnecting)
        mBigText = service.getString(R.string.connecting_to_server, host) + (if (port > 0) ":$port" else "")
        mActionsShown = false
        mReconnectingShown = false
        mMuted = false
        mDeafened = false
        mOverlayShown = false

        val session = mMediaSession
        if (session != null) {
            session.isActive = false
        }

        show()
    }

    fun showConnected(serverName: String, channelName: String?, muted: Boolean, deafened: Boolean, hostInfo: String?) {
        showConnected(serverName, channelName, muted, deafened, false, hostInfo)
    }

    fun showConnected(
        serverName: String,
        channelName: String?,
        muted: Boolean,
        deafened: Boolean,
        overlayShown: Boolean,
        hostInfo: String?
    ) {
        // NPE-exact: the original dereferenced mService unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        // NPE-exact: the original dereferenced mListener unconditionally.
        val listenerSnapshot = mListener ?: throw NullPointerException("mListener")
        mContentTitle = serverName
        mMuted = muted
        mDeafened = deafened
        mOverlayShown = overlayShown
        var statusText: String? = if (muted && deafened) {
            service.getString(R.string.status_notify_muted_and_deafened)
        } else if (muted) {
            service.getString(R.string.status_notify_muted)
        } else if (deafened) {
            service.getString(R.string.status_notify_deafened)
        } else {
            service.getString(R.string.connected)
        }

        statusText = cleanStatusText(statusText)

        if (channelName != null && channelName.isNotEmpty()) {
            mContentText = statusText + " • " + channelName
        } else {
            mContentText = statusText
        }

        mSubText = service.getString(R.string.connected)
        val ch = formatChannelName(channelName, service.getString(R.string.channel))
        val srv = hostInfo ?: serverName
        mBigText = service.getString(R.string.notification_connected_expanded, ch, statusText, srv)
        mActionsShown = true
        mReconnectingShown = false

        val useBigText = mSettings.isNotificationStyleBigText
        if (useBigText) {
            val session = mMediaSession
            if (session != null) {
                session.isActive = false
                session.release()
                mMediaSession = null
            }
        } else {
            if (mMediaSession == null) {
                val session = MediaSessionCompat(service, "MumlaMediaSession")
                session.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                session.setCallback(MediaSessionCallback(listenerSnapshot, mSettings))
                mMediaSession = session
            }
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, serverName)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mContentText)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, ch)
                .build()
            val session = mMediaSession ?: throw NullPointerException("mMediaSession")
            session.setMetadata(metadata)

            val muteCustomAction = PlaybackStateCompat.CustomAction.Builder(
                MumlaService.ACTION_MUTE,
                service.getString(R.string.mute),
                getMuteActionIcon(muted)
            )
                .build()
            val deafenCustomAction = PlaybackStateCompat.CustomAction.Builder(
                MumlaService.ACTION_DEAFEN,
                service.getString(R.string.deafen),
                getDeafenActionIcon(deafened)
            )
                .build()
            val overlayCustomAction = PlaybackStateCompat.CustomAction.Builder(
                MumlaService.ACTION_TOGGLE_OVERLAY,
                service.getString(R.string.overlay),
                getOverlayActionIcon(overlayShown)
            )
                .build()
            val disconnectCustomAction = PlaybackStateCompat.CustomAction.Builder(
                MumlaService.ACTION_DISCONNECT,
                service.getString(R.string.disconnect),
                R.drawable.ic_action_delete_dark
            )
                .build()

            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .addCustomAction(muteCustomAction)
                .addCustomAction(deafenCustomAction)
                .addCustomAction(overlayCustomAction)
                .addCustomAction(disconnectCustomAction)
                .setState(
                    if (muted) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f
                )
                .build()
            session.setPlaybackState(state)
            session.isActive = true
        }

        show()
    }

    fun showReconnecting(serverName: String?, error: String?, attempt: Int, delaySec: Int, hostInfo: String?) {
        // NPE-exact: the original dereferenced mService unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        val srvTitle = serverName ?: (hostInfo ?: service.getString(R.string.app_name))
        mContentTitle = service.getString(R.string.notification_reconnecting_title, srvTitle)
        mContentText = error ?: service.getString(R.string.mumlaDisconnected)

        if (attempt > 0 && delaySec > 0) {
            mSubText = service.getString(R.string.notification_reconnect_attempt_delay, attempt, delaySec)
        } else if (attempt > 0) {
            mSubText = service.getString(R.string.notification_reconnect_attempt, attempt)
        } else {
            mSubText = service.getString(R.string.reconnect)
        }

        val err = error ?: service.getString(R.string.mumlaDisconnected)
        val srv = hostInfo ?: srvTitle
        mBigText = service.getString(
            R.string.notification_reconnect_expanded, err,
            maxOf(attempt, 1), maxOf(delaySec, 1), srv
        )
        mActionsShown = false
        mReconnectingShown = true
        mMuted = false
        mDeafened = false

        val session = mMediaSession
        if (session != null) {
            session.isActive = false
        }

        show()
    }

    /**
     * Shows the notification.
     */
    fun show() {
        createNotification()
    }

    /**
     * Hides the notification.
     */
    fun hide() {
        // NPE-exact: the original dereferenced mService unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        val session = mMediaSession
        if (session != null) {
            session.isActive = false
            session.release()
            mMediaSession = null
        }
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID)
        }
        service.stopForeground(true)
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        // NPE-exact: the original dereferenced mService unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        val intent = Intent(service, MumlaService::class.java)
        intent.action = action
        return PendingIntent.getService(
            service, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Called to update/create the service's foreground Mumla notification.
     */
    private fun createNotification(): Notification {
        // NPE-exact: the original dereferenced mService unconditionally.
        val service = mService ?: throw NullPointerException("mService")
        if (!sChannelCreated) {
            val channelName = service.getString(R.string.connected)
            val manager = service.getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
                val chan = NotificationChannel(
                    CHANNEL_ID, channelName,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                manager.createNotificationChannel(chan)
                sChannelCreated = true
            }
        }
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)

        val useBigText = mSettings.isNotificationStyleBigText
        if (useBigText && mMediaSession != null) {
            val session = mMediaSession ?: throw NullPointerException("mMediaSession")
            session.isActive = false
            session.release()
            mMediaSession = null
        }

        val contentTitle = mContentTitle
        if (contentTitle != null && contentTitle.isNotEmpty()) {
            builder.setContentTitle(contentTitle)
        } else {
            builder.setContentTitle(service.getString(R.string.app_name))
        }

        val contentText = mContentText
        val bigText = mBigText
        val text: String?
        if (useBigText) {
            text = if (contentText != null && contentText.isNotEmpty()) contentText else bigText
        } else {
            text = if (bigText != null && bigText.isNotEmpty()) bigText else contentText
        }
        if (text != null && text.isNotEmpty()) {
            builder.setContentText(text)
        }

        val subText = mSubText
        if (subText != null && subText.isNotEmpty()) {
            builder.setSubText(subText)
        }

        val session = mMediaSession
        if (!useBigText && mActionsShown && session != null) {
            val mediaStyle = MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            builder.setStyle(mediaStyle)
        } else if (bigText != null && bigText.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        builder.setSmallIcon(R.drawable.ic_stat_notify)
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setCategory(if (useBigText) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_TRANSPORT)
        builder.setOnlyAlertOnce(true)
        builder.setShowWhen(false)

        val channelListIntent = Intent(service, MumlaActivity::class.java)
        channelListIntent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER)
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        val pendingIntent = PendingIntent.getActivity(
            service, REQUEST_CODE_ACTIVITY,
            channelListIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        if (mReconnectingShown) {
            builder.addAction(
                R.drawable.ic_action_delete_dark,
                service.getString(R.string.cancel_reconnect),
                createServicePendingIntent(MumlaService.ACTION_CANCEL_RECONNECT, REQUEST_CODE_CANCEL_RECONNECT)
            )
        } else if (mActionsShown) {
            builder.addAction(
                getMuteActionIcon(mMuted),
                service.getString(R.string.mute),
                createServicePendingIntent(MumlaService.ACTION_MUTE, REQUEST_CODE_MUTE)
            )
            builder.addAction(
                getDeafenActionIcon(mDeafened),
                service.getString(R.string.deafen),
                createServicePendingIntent(MumlaService.ACTION_DEAFEN, REQUEST_CODE_DEAFEN)
            )
            builder.addAction(
                getOverlayActionIcon(mOverlayShown),
                service.getString(R.string.overlay),
                createServicePendingIntent(MumlaService.ACTION_TOGGLE_OVERLAY, REQUEST_CODE_OVERLAY)
            )
            builder.addAction(
                R.drawable.ic_action_delete_dark,
                service.getString(R.string.disconnect),
                createServicePendingIntent(MumlaService.ACTION_DISCONNECT, REQUEST_CODE_DISCONNECT)
            )
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
        return notification
    }

    /**
     * Media-session callback for the media notification style. Play, pause,
     * play/pause, and headset-hook all toggle self-mute; a key bound as the
     * push-to-talk key is consumed silently so the activity's PTT path keeps
     * precedence.
     *
     * <p>Named (rather than anonymous) for direct Robolectric coverage of
     * the dispatch rules.
     *
     * <p>Note: with two-way Bluetooth (SCO) headsets, hook/pause presses
     * made while the SCO link is still coming up after joining are consumed
     * by the telephony stack and never arrive here as media keys. That
     * settle window is OS behavior outside this callback's reach.
     *
     * Widened to public: Kotlin has no package visibility (was package-visible
     * static in Java for the same-package tests).
     */
    class MediaSessionCallback(listener: OnActionListener, settings: Settings) : MediaSessionCompat.Callback() {
        private val mListener: OnActionListener = listener
        private val mSettings: Settings = settings

        override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
            if (MumlaService.ACTION_DISCONNECT == action) {
                mListener.onDisconnect()
            } else if (MumlaService.ACTION_MUTE == action) {
                mListener.onMuteToggled()
            } else if (MumlaService.ACTION_DEAFEN == action) {
                mListener.onDeafenToggled()
            } else if (MumlaService.ACTION_TOGGLE_OVERLAY == action) {
                mListener.onOverlayToggled()
            }
        }

        override fun onPlay() {
            if (mSettings.isMediaKeyMuteEnabled) {
                mListener.onMediaKeyMuteToggled()
            }
        }

        override fun onPause() {
            if (mSettings.isMediaKeyMuteEnabled) {
                mListener.onMediaKeyMuteToggled()
            }
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            // Opt-in toggle living right below the notification-style
            // preference: off means fully inert, falling through to the
            // default (no-op) dispatch.
            if (!mSettings.isMediaKeyMuteEnabled) {
                return false
            }
            // Hardware keys arrive here and are handled without delegating
            // to super (which would re-enter onPlay()/onPause()); returning
            // true then consumes them. Software transport controls call
            // onPlay()/onPause() directly instead.
            val event: KeyEvent? = if (mediaButtonEvent != null) {
                IntentCompat.getParcelableExtra(
                    mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
                )
            } else {
                null
            }
            if (event == null || !isMuteToggleKeyCode(event.keyCode)) {
                return false
            }
            if (isPttClaimedMediaKey(event.keyCode, mSettings.pushToTalkKey)) {
                return true
            }
            // Ignore auto-repeat: a held key re-fires ACTION_DOWN with a
            // nonzero repeat count, and toggling on each would flap
            // mute/unmute for a single hold.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                mListener.onMediaKeyMuteToggled()
            }
            return true
        }
    }

    interface OnActionListener {
        fun onMuteToggled()
        fun onMediaKeyMuteToggled()
        fun onDeafenToggled()
        fun onOverlayToggled()
        fun onCancelReconnect()
        fun onDisconnect()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "connected_channel_v9"
        private const val LEGACY_CHANNEL_ID = "connected_channel"
        @Volatile
        private var sChannelCreated: Boolean = false

        private const val REQUEST_CODE_ACTIVITY = 0
        private const val REQUEST_CODE_MUTE = 1
        private const val REQUEST_CODE_DEAFEN = 2
        private const val REQUEST_CODE_OVERLAY = 3
        private const val REQUEST_CODE_CANCEL_RECONNECT = 4
        private const val REQUEST_CODE_DISCONNECT = 5

        /**
         * Creates a foreground Mumla notification for the given service.
         * @param service The service to register a foreground notification for.
         * @param listener A listener for notification actions.
         * @return A new MumlaNotification instance.
         */
        @JvmStatic
        fun create(service: Service?, listener: OnActionListener?): MumlaConnectionNotification {
            return MumlaConnectionNotification(service, listener)
        }

        @JvmStatic
        fun create(service: Service?, listener: OnActionListener?, settings: Settings): MumlaConnectionNotification {
            return MumlaConnectionNotification(service, listener, settings)
        }

        @JvmStatic
        fun getMuteActionIcon(muted: Boolean): Int {
            return if (muted) R.drawable.ic_action_microphone_muted else R.drawable.ic_action_microphone
        }

        @JvmStatic
        fun getDeafenActionIcon(deafened: Boolean): Int {
            return if (deafened) R.drawable.ic_action_audio_muted else R.drawable.ic_action_audio
        }

        @JvmStatic
        fun getOverlayActionIcon(overlayShown: Boolean): Int {
            return if (overlayShown) R.drawable.ic_action_overlay_on else R.drawable.ic_action_overlay_off
        }

        /**
         * Media keycodes that toggle self-mute while the media notification style
         * is active. Covers the dedicated pause/play keys, the combined
         * play/pause key, and the single-button headset hook most Bluetooth
         * headsets send. Deliberately excludes transport keys with unrelated
         * semantics (e.g. stop, next/previous).
         */
        @JvmStatic
        fun isMuteToggleKeyCode(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        }

        /**
         * Whether the given media key is claimed by the configured push-to-talk
         * key. A PTT binding wins over mute-toggle: the activity owns the key
         * for transmission, so the media session must consume it silently
         * instead of toggling mute.
         */
        @JvmStatic
        fun isPttClaimedMediaKey(keyCode: Int, pttKey: Int): Boolean {
            return isMuteToggleKeyCode(keyCode) && Settings.isPttKeyBound(pttKey, keyCode)
        }

        @JvmStatic
        fun cleanStatusText(statusText: String?): String? {
            if (statusText != null && (statusText.endsWith(".") || statusText.endsWith("。"))) {
                return statusText.substring(0, statusText.length - 1)
            }
            return statusText
        }

        @JvmStatic
        fun formatChannelName(channelName: String?, defaultChannelName: String): String {
            if (channelName != null && channelName.isNotEmpty()) {
                return channelName
            }
            return defaultChannelName
        }
    }
}
