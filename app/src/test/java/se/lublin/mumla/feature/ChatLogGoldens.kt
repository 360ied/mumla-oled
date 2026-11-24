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

/** Recorded from StateEventLogger before step 6 deleted it; never re-record. */
object ChatLogGoldens {
    data class Line(val warning: Boolean, val text: String)

    val events: Map<String, List<Line>> = mapOf(
        "joined" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> connected.")),
        "left" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> disconnected.")),
        "kicked" to listOf(Line(true, "<font color=\"#33b5e5\">Alice</font> was kicked from the server by <font color=\"#33b5e5\">Bob</font>: rude.")),
        "banned" to listOf(Line(true, "<font color=\"#33b5e5\">Alice</font> was kicked and banned from the server by <font color=\"#33b5e5\">Bob</font>: rude.")),
        "left_no_name" to listOf(Line(false, "<font color=\"#33b5e5\">unknown</font> disconnected.")),
        "kicked_no_actor_name" to listOf(Line(true, "<font color=\"#33b5e5\">Alice</font> was kicked from the server by <font color=\"#33b5e5\">unknown</font>: rude.")),
        "moved_out_self" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> moved to <font color=\"#33b5e5\">Away</font>.")),
        "moved_out_other" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> moved to <font color=\"#33b5e5\">Away</font> by <font color=\"#33b5e5\">Bob</font>.")),
        "moved_out_server" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> moved to <font color=\"#33b5e5\">Away</font> by the server.")),
        "moved_in_self" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> entered channel.")),
        "moved_in_other" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> moved in from <font color=\"#33b5e5\">Away</font> by <font color=\"#33b5e5\">Bob</font>.")),
        "moved_in_server" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> moved in from <font color=\"#33b5e5\">Away</font> by the server.")),
        "moved_self" to listOf(),
        "moved_unrelated" to listOf(),
        "recording_self_on" to listOf(Line(false, "Recording started")),
        "recording_self_off" to listOf(Line(false, "Recording stopped")),
        "recording_other_on" to listOf(Line(false, "<font color=\"#33b5e5\">Bob</font> started recording.")),
        "recording_other_off" to listOf(Line(false, "<font color=\"#33b5e5\">Bob</font> stopped recording.")),
        "recording_unlinked_on" to listOf(),
        "self_mute" to listOf(Line(false, "Muted.")),
        "self_deafen" to listOf(Line(false, "Muted and deafened.")),
        "self_unmute" to listOf(Line(false, "Unmuted.")),
        "user_mute" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> is now muted.")),
        "user_deafen" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> is now muted and deafened.")),
        "user_unmute" to listOf(Line(false, "<font color=\"#33b5e5\">Alice</font> is now unmuted.")),
        "welcome" to listOf(Line(false, "Welcome to the server")),
        "warning" to listOf(Line(true, "UDP packets lost")),
        "text_received" to listOf(),
        "text_sent" to listOf(),
        "permission_denied" to listOf(),
    )

    val denies: Map<String, String> = mapOf(
        "channel_name" to "Denied: Invalid channel name.",
        "text_too_long" to "Denied: Text message too long.",
        "temporary_channel" to "Denied: Operation not permitted in temporary channel.",
        "missing_certificate" to "You need a certificate to perform this operation.",
        "user_name" to "Invalid username.",
        "channel_full" to "Channel is full.",
        "nesting_limit" to "Channel nesting limit reached.",
        "other_reason" to "Reason: because",
        "other_none" to "Permission denied.",
    )
}
