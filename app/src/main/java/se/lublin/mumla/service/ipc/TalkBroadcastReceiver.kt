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

package se.lublin.mumla.service.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Feature-side bridge: the receiver validates, the feature owns the guards. */
fun interface TalkStatusSink {
    fun onTalk(status: String)
}

/**
 * Receives TALK broadcasts. The feature supplies a [TalkStatusSink] and owns
 * the connected / push-to-talk guards; the receiver only parses and validates.
 */
class TalkBroadcastReceiver private constructor(
    private val sink: TalkStatusSink,
) : BroadcastReceiver() {

    /** Feature path: parse and validate, then hand the status to the feature. */
    constructor(sink: (String) -> Unit) : this(TalkStatusSink { sink(it) })

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || BROADCAST_TALK != intent.action) {
            // Ignore rather than throw: a mis-wired filter must not crash the
            // service, but it stays visible at warning level.
            Log.w(TAG, "Ignoring unexpected broadcast: " + if (intent == null) "null intent" else intent.action)
            return
        }
        val status = intent.getStringExtra(EXTRA_TALK_STATUS) ?: TALK_STATUS_TOGGLE
        if (status != TALK_STATUS_ON && status != TALK_STATUS_OFF && status != TALK_STATUS_TOGGLE) {
            Log.w(TAG, "Ignoring unknown TALK status: $status")
            return
        }
        sink.onTalk(status)
    }

    companion object {
        private val TAG: String = TalkBroadcastReceiver::class.java.simpleName

        const val BROADCAST_TALK = "se.lublin.mumla.action.TALK"
        const val EXTRA_TALK_STATUS = "status"
        const val TALK_STATUS_ON = "on"
        const val TALK_STATUS_OFF = "off"
        const val TALK_STATUS_TOGGLE = "toggle"
    }
}
