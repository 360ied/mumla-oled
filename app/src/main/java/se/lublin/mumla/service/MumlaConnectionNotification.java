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

package se.lublin.mumla.service;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;

/**
 * Wrapper to create Mumla notifications.
 * Created by andrew on 08/08/14.
 */
public class MumlaConnectionNotification {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "connected_channel_v9";
    private static final String LEGACY_CHANNEL_ID = "connected_channel";
    private static boolean sChannelCreated = false;

    private static final int REQUEST_CODE_ACTIVITY = 0;
    private static final int REQUEST_CODE_MUTE = 1;
    private static final int REQUEST_CODE_DEAFEN = 2;
    private static final int REQUEST_CODE_OVERLAY = 3;
    private static final int REQUEST_CODE_CANCEL_RECONNECT = 4;
    private static final int REQUEST_CODE_DISCONNECT = 5;

    private final Service mService;
    private final OnActionListener mListener;
    private final Settings mSettings;
    private MediaSessionCompat mMediaSession;
    private String mContentTitle;
    private String mContentText;
    private String mSubText;
    private String mBigText;
    private boolean mActionsShown;
    private boolean mReconnectingShown;
    private boolean mMuted;
    private boolean mDeafened;
    private boolean mOverlayShown;

    /**
     * Creates a foreground Mumla notification for the given service.
     * @param service The service to register a foreground notification for.
     * @param listener An listener for notification actions.
     * @return A new MumlaNotification instance.
     */
    public static MumlaConnectionNotification create(Service service, OnActionListener listener) {
        return new MumlaConnectionNotification(service, listener);
    }

    public static MumlaConnectionNotification create(Service service, OnActionListener listener, Settings settings) {
        return new MumlaConnectionNotification(service, listener, settings);
    }

    private MumlaConnectionNotification(Service service, OnActionListener listener) {
        this(service, listener, Settings.getInstance(service));
    }

    MumlaConnectionNotification(Service service, OnActionListener listener, Settings settings) {
        mService = service;
        mListener = listener;
        mSettings = settings != null ? settings : Settings.getInstance(service);
        mActionsShown = false;
        mReconnectingShown = false;
        mMuted = false;
        mDeafened = false;
        mOverlayShown = false;
    }

    boolean isMediaSessionActive() {
        return mMediaSession != null && mMediaSession.isActive();
    }

    Settings getSettings() {
        return mSettings;
    }

    public static int getMuteActionIcon(boolean muted) {
        return muted ? R.drawable.ic_action_microphone_muted : R.drawable.ic_action_microphone;
    }

    public static int getDeafenActionIcon(boolean deafened) {
        return deafened ? R.drawable.ic_action_audio_muted : R.drawable.ic_action_audio;
    }

    public static int getOverlayActionIcon(boolean overlayShown) {
        return overlayShown ? R.drawable.ic_action_overlay_on : R.drawable.ic_action_overlay_off;
    }

    public static String cleanStatusText(String statusText) {
        if (statusText != null && (statusText.endsWith(".") || statusText.endsWith("。"))) {
            return statusText.substring(0, statusText.length() - 1);
        }
        return statusText;
    }

    public static String formatChannelName(String channelName, String defaultChannelName) {
        if (channelName != null && !channelName.isEmpty()) {
            return channelName;
        }
        return defaultChannelName;
    }

    public void showConnecting(String serverName, String host, int port) {
        mContentTitle = serverName;
        mContentText = mService.getString(R.string.connecting_to_server, host);
        mSubText = mService.getString(R.string.mumlaConnecting);
        mBigText = mService.getString(R.string.connecting_to_server, host) + (port > 0 ? (":" + port) : "");
        mActionsShown = false;
        mReconnectingShown = false;
        mMuted = false;
        mDeafened = false;
        mOverlayShown = false;

        if (mMediaSession != null) {
            mMediaSession.setActive(false);
        }

        show();
    }

    public void showConnected(String serverName, String channelName, boolean muted, boolean deafened, String hostInfo) {
        showConnected(serverName, channelName, muted, deafened, false, hostInfo);
    }

    public void showConnected(String serverName, String channelName, boolean muted, boolean deafened, boolean overlayShown, String hostInfo) {
        mContentTitle = serverName;
        mMuted = muted;
        mDeafened = deafened;
        mOverlayShown = overlayShown;
        String statusText;
        if (muted && deafened) {
            statusText = mService.getString(R.string.status_notify_muted_and_deafened);
        } else if (muted) {
            statusText = mService.getString(R.string.status_notify_muted);
        } else if (deafened) {
            statusText = mService.getString(R.string.status_notify_deafened);
        } else {
            statusText = mService.getString(R.string.connected);
        }

        statusText = cleanStatusText(statusText);

        if (channelName != null && !channelName.isEmpty()) {
            mContentText = statusText + " • " + channelName;
        } else {
            mContentText = statusText;
        }

        mSubText = mService.getString(R.string.connected);
        String ch = formatChannelName(channelName, mService.getString(R.string.channel));
        String srv = hostInfo != null ? hostInfo : (serverName != null ? serverName : "");
        mBigText = mService.getString(R.string.notification_connected_expanded, ch, statusText, srv);
        mActionsShown = true;
        mReconnectingShown = false;

        boolean useBigText = mSettings.isNotificationStyleBigText();
        if (useBigText) {
            if (mMediaSession != null) {
                mMediaSession.setActive(false);
                mMediaSession.release();
                mMediaSession = null;
            }
        } else {
            if (mMediaSession == null) {
                mMediaSession = new MediaSessionCompat(mService, "MumlaMediaSession");
                mMediaSession.setCallback(new MediaSessionCompat.Callback() {
                    @Override
                    public void onCustomAction(String action, android.os.Bundle extras) {
                        if (MumlaService.ACTION_DISCONNECT.equals(action)) {
                            mListener.onDisconnect();
                        } else if (MumlaService.ACTION_MUTE.equals(action)) {
                            mListener.onMuteToggled();
                        } else if (MumlaService.ACTION_DEAFEN.equals(action)) {
                            mListener.onDeafenToggled();
                        } else if (MumlaService.ACTION_TOGGLE_OVERLAY.equals(action)) {
                            mListener.onOverlayToggled();
                        }
                    }
                });
            }
            MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, serverName != null ? serverName : mService.getString(R.string.app_name))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mContentText)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, ch)
                    .build();
            mMediaSession.setMetadata(metadata);

            PlaybackStateCompat.CustomAction muteCustomAction = new PlaybackStateCompat.CustomAction.Builder(
                    MumlaService.ACTION_MUTE,
                    mService.getString(R.string.mute),
                    getMuteActionIcon(muted))
                    .build();
            PlaybackStateCompat.CustomAction deafenCustomAction = new PlaybackStateCompat.CustomAction.Builder(
                    MumlaService.ACTION_DEAFEN,
                    mService.getString(R.string.deafen),
                    getDeafenActionIcon(deafened))
                    .build();
            PlaybackStateCompat.CustomAction overlayCustomAction = new PlaybackStateCompat.CustomAction.Builder(
                    MumlaService.ACTION_TOGGLE_OVERLAY,
                    mService.getString(R.string.overlay),
                    getOverlayActionIcon(overlayShown))
                    .build();
            PlaybackStateCompat.CustomAction disconnectCustomAction = new PlaybackStateCompat.CustomAction.Builder(
                    MumlaService.ACTION_DISCONNECT,
                    mService.getString(R.string.disconnect),
                    R.drawable.ic_action_delete_dark)
                    .build();

            PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                    .addCustomAction(muteCustomAction)
                    .addCustomAction(deafenCustomAction)
                    .addCustomAction(overlayCustomAction)
                    .addCustomAction(disconnectCustomAction)
                    .setState(muted ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build();
            mMediaSession.setPlaybackState(state);
            mMediaSession.setActive(true);
        }

        show();
    }

    public void showReconnecting(String serverName, String error, int attempt, int delaySec, String hostInfo) {
        String srvTitle = serverName != null ? serverName : (hostInfo != null ? hostInfo : mService.getString(R.string.app_name));
        mContentTitle = mService.getString(R.string.notification_reconnecting_title, srvTitle);
        mContentText = error != null ? error : mService.getString(R.string.mumlaDisconnected);

        if (attempt > 0 && delaySec > 0) {
            mSubText = mService.getString(R.string.notification_reconnect_attempt_delay, attempt, delaySec);
        } else if (attempt > 0) {
            mSubText = mService.getString(R.string.notification_reconnect_attempt, attempt);
        } else {
            mSubText = mService.getString(R.string.reconnect);
        }

        String err = error != null ? error : mService.getString(R.string.mumlaDisconnected);
        String srv = hostInfo != null ? hostInfo : srvTitle;
        mBigText = mService.getString(R.string.notification_reconnect_expanded, err, Math.max(attempt, 1), Math.max(delaySec, 1), srv);
        mActionsShown = false;
        mReconnectingShown = true;
        mMuted = false;
        mDeafened = false;

        if (mMediaSession != null) {
            mMediaSession.setActive(false);
        }

        show();
    }

    /**
     * Shows the notification.
     */
    public void show() {
        createNotification();
    }

    /**
     * Hides the notification.
     */
    public void hide() {
        if (mMediaSession != null) {
            mMediaSession.setActive(false);
            mMediaSession.release();
            mMediaSession = null;
        }
        NotificationManager manager = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
        mService.stopForeground(true);
    }

    private PendingIntent createServicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(mService, MumlaService.class);
        intent.setAction(action);
        return PendingIntent.getService(mService, requestCode, intent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    }

    /**
     * Called to update/create the service's foreground Mumla notification.
     */
    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !sChannelCreated) {
            String channelName = mService.getString(R.string.connected);
            NotificationManager manager = mService.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
                NotificationChannel chan = new NotificationChannel(CHANNEL_ID, channelName,
                        NotificationManager.IMPORTANCE_DEFAULT);
                chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(chan);
                sChannelCreated = true;
            }
        }
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mService, CHANNEL_ID);

        boolean useBigText = mSettings.isNotificationStyleBigText();
        if (useBigText && mMediaSession != null) {
            mMediaSession.setActive(false);
            mMediaSession.release();
            mMediaSession = null;
        }

        if (mContentTitle != null && !mContentTitle.isEmpty()) {
            builder.setContentTitle(mContentTitle);
        } else {
            builder.setContentTitle(mService.getString(R.string.app_name));
        }

        String text;
        if (useBigText) {
            text = (mContentText != null && !mContentText.isEmpty()) ? mContentText : mBigText;
        } else {
            text = (mBigText != null && !mBigText.isEmpty()) ? mBigText : mContentText;
        }
        if (text != null && !text.isEmpty()) {
            builder.setContentText(text);
        }

        if (mSubText != null && !mSubText.isEmpty()) {
            builder.setSubText(mSubText);
        }

        if (!useBigText && mActionsShown && mMediaSession != null) {
            MediaStyle mediaStyle = new MediaStyle()
                    .setMediaSession(mMediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2);
            builder.setStyle(mediaStyle);
        } else if (mBigText != null && !mBigText.isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(mBigText));
        }

        builder.setSmallIcon(R.drawable.ic_stat_notify);
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setCategory(useBigText ? NotificationCompat.CATEGORY_SERVICE : NotificationCompat.CATEGORY_TRANSPORT);
        builder.setOnlyAlertOnce(true);
        builder.setShowWhen(false);

        Intent channelListIntent = new Intent(mService, MumlaActivity.class);
        channelListIntent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER);
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        PendingIntent pendingIntent = PendingIntent.getActivity(mService, REQUEST_CODE_ACTIVITY,
                channelListIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);

        if (mReconnectingShown) {
            builder.addAction(R.drawable.ic_action_delete_dark,
                    mService.getString(R.string.cancel_reconnect),
                    createServicePendingIntent(MumlaService.ACTION_CANCEL_RECONNECT, REQUEST_CODE_CANCEL_RECONNECT));
        } else if (mActionsShown) {
            builder.addAction(getMuteActionIcon(mMuted),
                    mService.getString(R.string.mute),
                    createServicePendingIntent(MumlaService.ACTION_MUTE, REQUEST_CODE_MUTE));
            builder.addAction(getDeafenActionIcon(mDeafened),
                    mService.getString(R.string.deafen),
                    createServicePendingIntent(MumlaService.ACTION_DEAFEN, REQUEST_CODE_DEAFEN));
            builder.addAction(getOverlayActionIcon(mOverlayShown),
                    mService.getString(R.string.overlay),
                    createServicePendingIntent(MumlaService.ACTION_TOGGLE_OVERLAY, REQUEST_CODE_OVERLAY));
            builder.addAction(R.drawable.ic_action_delete_dark,
                    mService.getString(R.string.disconnect),
                    createServicePendingIntent(MumlaService.ACTION_DISCONNECT, REQUEST_CODE_DISCONNECT));
        }

        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mService.startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            mService.startForeground(NOTIFICATION_ID, notification);
        }
        return notification;
    }

    public interface OnActionListener {
        void onMuteToggled();
        void onDeafenToggled();
        void onOverlayToggled();
        void onCancelReconnect();
        void onDisconnect();
    }
}
