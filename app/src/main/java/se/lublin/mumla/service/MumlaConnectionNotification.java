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

import androidx.core.content.ContextCompat;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import se.lublin.mumla.R;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;

/**
 * Wrapper to create Mumla notifications.
 * Created by andrew on 08/08/14.
 */
public class MumlaConnectionNotification {
    private static final int NOTIFICATION_ID = 1;
    private static final String BROADCAST_MUTE = "b_mute";
    private static final String BROADCAST_DEAFEN = "b_deafen";
    private static final String BROADCAST_OVERLAY = "b_overlay";
    private static final String BROADCAST_CANCEL_RECONNECT = "b_cancel_reconnect";

    private Service mService;
    private OnActionListener mListener;
    private String mContentTitle;
    private String mContentText;
    private String mSubText;
    private String mBigText;
    private boolean mActionsShown;
    private boolean mReconnectingShown;
    private boolean mReceiverRegistered;

    private BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BROADCAST_MUTE.equals(intent.getAction())) {
                mListener.onMuteToggled();
            } else if (BROADCAST_DEAFEN.equals(intent.getAction())) {
                mListener.onDeafenToggled();
            } else if (BROADCAST_OVERLAY.equals(intent.getAction())) {
                mListener.onOverlayToggled();
            } else if (BROADCAST_CANCEL_RECONNECT.equals(intent.getAction())) {
                mListener.onCancelReconnect();
            }
        }
    };

    /**
     * Creates a foreground Mumla notification for the given service.
     * @param service The service to register a foreground notification for.
     * @param listener An listener for notification actions.
     * @return A new MumlaNotification instance.
     */
    public static MumlaConnectionNotification create(Service service, OnActionListener listener) {
        return new MumlaConnectionNotification(service, listener);
    }

    private MumlaConnectionNotification(Service service, OnActionListener listener) {
        mService = service;
        mListener = listener;
        mActionsShown = false;
        mReconnectingShown = false;
        mReceiverRegistered = false;
    }

    public void setContentTitle(String title) {
        mContentTitle = title;
    }

    public void setContentText(String text) {
        mContentText = text;
    }

    public void setSubText(String subText) {
        mSubText = subText;
    }

    public void setBigText(String bigText) {
        mBigText = bigText;
    }

    public void setActionsShown(boolean actionsShown) {
        mActionsShown = actionsShown;
    }

    public void setReconnectingShown(boolean reconnectingShown) {
        mReconnectingShown = reconnectingShown;
    }

    public void showConnecting(String serverName, String host, int port) {
        mContentTitle = serverName;
        mContentText = mService.getString(R.string.connecting_to_server, host);
        mSubText = mService.getString(R.string.mumlaConnecting);
        mBigText = mService.getString(R.string.connecting_to_server, host) + (port > 0 ? (":" + port) : "");
        mActionsShown = false;
        mReconnectingShown = false;
        show();
    }

    public void showConnected(String serverName, String channelName, boolean muted, boolean deafened, String hostInfo) {
        mContentTitle = serverName;
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

        if (channelName != null && !channelName.isEmpty()) {
            mContentText = statusText + " • " + channelName;
        } else {
            mContentText = statusText;
        }

        mSubText = mService.getString(R.string.connected);
        String ch = channelName != null ? channelName : mService.getString(R.string.channel);
        String srv = hostInfo != null ? hostInfo : (serverName != null ? serverName : "");
        mBigText = mService.getString(R.string.notification_connected_expanded, ch, statusText, srv);
        mActionsShown = true;
        mReconnectingShown = false;
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
        show();
    }

    /**
     * Shows the notification and registers the notification action button receiver.
     */
    public void show() {
        createNotification();

        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BROADCAST_DEAFEN);
            filter.addAction(BROADCAST_MUTE);
            filter.addAction(BROADCAST_OVERLAY);
            filter.addAction(BROADCAST_CANCEL_RECONNECT);
            try {
                ContextCompat.registerReceiver(mService, mNotificationReceiver, filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                mReceiverRegistered = true;
            } catch (IllegalArgumentException e) {
                // Thrown if receiver is already registered.
                e.printStackTrace();
            }
        }
    }

    /**
     * Hides the notification and unregisters the action receiver.
     */
    public void hide() {
        if (mReceiverRegistered) {
            try {
                mService.unregisterReceiver(mNotificationReceiver);
            } catch (IllegalArgumentException e) {
                // Thrown if receiver is not registered.
                e.printStackTrace();
            }
            mReceiverRegistered = false;
        }
        mService.stopForeground(true);
    }

    /**
     * Called to update/create the service's foreground Mumla notification.
     */
    private Notification createNotification() {
        String channelId = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = "connected_channel";
            String channelName = mService.getString(R.string.connected);
            NotificationChannel chan = new NotificationChannel(channelId, channelName,
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = mService.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(chan);
        }
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mService, channelId);

        if (mContentTitle != null && !mContentTitle.isEmpty()) {
            builder.setContentTitle(mContentTitle);
        } else {
            builder.setContentTitle(mService.getString(R.string.app_name));
        }

        if (mContentText != null && !mContentText.isEmpty()) {
            builder.setContentText(mContentText);
        }

        if (mSubText != null && !mSubText.isEmpty()) {
            builder.setSubText(mSubText);
        }

        if (mBigText != null && !mBigText.isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(mBigText));
        }

        builder.setSmallIcon(R.drawable.ic_stat_notify);
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setShowWhen(false);
        builder.setOngoing(true);

        if (mReconnectingShown) {
            Intent cancelIntent = new Intent(BROADCAST_CANCEL_RECONNECT);
            cancelIntent.setPackage(mService.getPackageName());
            builder.addAction(R.drawable.ic_action_delete_dark,
                    mService.getString(R.string.cancel_reconnect), PendingIntent.getBroadcast(mService, 4,
                            cancelIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
        } else if (mActionsShown) {
            // Add notification triggers
            Intent muteIntent = new Intent(BROADCAST_MUTE);
            muteIntent.setPackage(mService.getPackageName());
            Intent deafenIntent = new Intent(BROADCAST_DEAFEN);
            deafenIntent.setPackage(mService.getPackageName());
            Intent overlayIntent = new Intent(BROADCAST_OVERLAY);
            overlayIntent.setPackage(mService.getPackageName());

            builder.addAction(R.drawable.ic_action_microphone,
                    mService.getString(R.string.mute), PendingIntent.getBroadcast(mService, 1,
                            muteIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
            builder.addAction(R.drawable.ic_action_audio,
                    mService.getString(R.string.deafen), PendingIntent.getBroadcast(mService, 2,
                            deafenIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
            builder.addAction(R.drawable.ic_action_channels,
                    mService.getString(R.string.overlay), PendingIntent.getBroadcast(mService, 3,
                            overlayIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
        }

        Intent channelListIntent = new Intent(mService, MumlaActivity.class);
        channelListIntent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER);
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        PendingIntent pendingIntent = PendingIntent.getActivity(mService, 0,
                channelListIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);

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
    }
}
