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

import android.view.Gravity
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.Settings

/** Typed reads a feature needs, straight from the non-blocking snapshot. */
fun PrefSnapshot.isPushToTalk(): Boolean =
    audioInputMethod == Settings.ARRAY_INPUT_METHOD_PTT

fun PrefSnapshot.isHotCornerEnabled(): Boolean = hotCornerGravity() != 0

fun PrefSnapshot.hotCornerGravity(): Int = when (hotCorner) {
    Settings.ARRAY_HOT_CORNER_BOTTOM_LEFT -> Gravity.LEFT or Gravity.BOTTOM
    Settings.ARRAY_HOT_CORNER_BOTTOM_RIGHT -> Gravity.RIGHT or Gravity.BOTTOM
    Settings.ARRAY_HOT_CORNER_TOP_LEFT -> Gravity.LEFT or Gravity.TOP
    Settings.ARRAY_HOT_CORNER_TOP_RIGHT -> Gravity.RIGHT or Gravity.TOP
    else -> 0
}

fun PrefSnapshot.bigTextNotifications(): Boolean =
    notificationStyle == Settings.NOTIFICATION_STYLE_BIGTEXT ||
        (notificationStyle != Settings.NOTIFICATION_STYLE_MEDIA &&
            Settings.DEFAULT_NOTIFICATION_STYLE == Settings.NOTIFICATION_STYLE_BIGTEXT)
