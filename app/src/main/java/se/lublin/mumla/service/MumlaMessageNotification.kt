/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import se.lublin.humla.model.IMessage
import se.lublin.mumla.R
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.util.NotificationSanitizer

/**
 * A notification indicating that new messages have been received.
 * Intended to augment the existing {@link MumlaConnectionNotification} by providing a higher
 * priority heads-up display on Android 5.0+ devices, as well as vibration.
 * Created by andrew on 25/03/16.
 */
class MumlaMessageNotification(context: Context) {
    private val mContext: Context = context
    private val mUnreadMessages = ArrayList<IMessage>()

    /**
     * Shows the notification with the provided message.
     * If the notification is already shown, append the message to the existing notification.
     * @param message The message to notify the user about.
     */
    fun show(message: IMessage) {
        mUnreadMessages.add(message)

        val style = NotificationCompat.InboxStyle()
        style.setBigContentTitle(
            mContext.resources.getQuantityString(
                R.plurals.notification_unread_many, mUnreadMessages.size, mUnreadMessages.size
            )
        )
        for (m in mUnreadMessages) {
            val line = mContext.getString(
                R.string.notification_message,
                NotificationSanitizer.sanitizeActor(m.actorName),
                NotificationSanitizer.sanitizeBody(m.message)
            )
            style.addLine(line)
        }

        val channelListIntent = Intent(mContext, MumlaActivity::class.java)
        channelListIntent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER)
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        val pendingIntent = PendingIntent.getActivity(
            mContext, 0,
            channelListIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "message_channel"
        val channelName = mContext.getString(R.string.messageReceived)
        val chan = NotificationChannel(
            channelId, channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager = mContext.getSystemService(NotificationManager::class.java)
        // NPE-exact: the original dereferenced the manager unconditionally.
        (notificationManager ?: throw NullPointerException("notificationManager"))
            .createNotificationChannel(chan)
        val builder = NotificationCompat.Builder(mContext, channelId)

        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setContentTitle(NotificationSanitizer.sanitizeActor(message.actorName))
            .setContentText(NotificationSanitizer.sanitizeBody(message.message))
            .setVibrate(VIBRATION_PATTERN)
            .setStyle(style)

        if (mUnreadMessages.size > 0) {
            builder.setNumber(mUnreadMessages.size)
        }

        val manager = NotificationManagerCompat.from(mContext)
        val notification = builder.build()
        if (ContextCompat.checkSelfPermission(
                mContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Dismisses the unread messages notification, marking all messages read.
     */
    fun dismiss() {
        mUnreadMessages.clear()
        val manager = NotificationManagerCompat.from(mContext)
        manager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private val VIBRATION_PATTERN = longArrayOf(0, 100)
    }
}
