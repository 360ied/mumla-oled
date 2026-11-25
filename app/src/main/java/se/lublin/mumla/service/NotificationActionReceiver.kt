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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import se.lublin.mumla.container

/**
 * Explicit, non-exported target for connection-notification actions. It
 * reaches the process-lifetime feature through the container, so the actions
 * work with or without a bound service instance.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context?.applicationContext ?: return
        appContext.container.onNotificationAction(action)
    }

    companion object {
        // The notification's explicit actions; the feature and its view build
        // and consume them, so they live here with the receiver.
        const val ACTION_DISCONNECT = "se.lublin.mumla.action.DISCONNECT"
        const val ACTION_MUTE = "se.lublin.mumla.action.MUTE"
        const val ACTION_DEAFEN = "se.lublin.mumla.action.DEAFEN"
        const val ACTION_TOGGLE_OVERLAY = "se.lublin.mumla.action.TOGGLE_OVERLAY"
        const val ACTION_CANCEL_RECONNECT = "se.lublin.mumla.action.CANCEL_RECONNECT"
    }
}
