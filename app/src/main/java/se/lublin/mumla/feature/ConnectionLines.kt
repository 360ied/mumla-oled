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

package se.lublin.mumla.feature

import android.content.Context
import se.lublin.humla.client.ClientError
import se.lublin.mumla.R

/** Shared connection wording: the chat log and TTS announce the same lines. */
object ConnectionLines {
    fun disconnected(context: Context, cause: String?): String {
        val trimmed = cause?.trim()
        return if (!trimmed.isNullOrEmpty()) {
            context.getString(R.string.tts_disconnected_reason, trimmed)
        } else {
            context.getString(R.string.disconnected)
        }
    }

    fun reconnected(context: Context): String = context.getString(R.string.reconnected)
}

/** Mirrors the service's HumlaException messages; Trust stays a dialog. */
fun ClientError.displayMessage(): String? = when (this) {
    is ClientError.Connection -> message ?: "Connection error"
    is ClientError.Rejected -> "Rejected: $reason"
    is ClientError.Removed -> reason ?: "Removed"
    is ClientError.Trust -> null
}
