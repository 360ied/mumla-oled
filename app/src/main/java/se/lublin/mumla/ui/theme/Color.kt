/*
 * Copyright (C) 2026 Mumla Contributors
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

package se.lublin.mumla.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Primary Branding & Accent Colors
val MumlaTealPrimary = Color(0xFF80CBC4)
val MumlaTealOnPrimary = Color(0xFF00332C)
val MumlaTealContainer = Color(0xFF004D40)
val MumlaTealOnContainer = Color(0xFFA7F0E4)

// OLED Strict Black & Surfaces
val OledBlack = Color(0xFF000000)
val OledCardSurface = Color(0xFF0A0A0A)
val OledBorder = Color(0xFF222222)
val OledBorderSubtle = Color(0xFF141414)
val OledPressed = Color(0xFF1E1E1E)

// Dark Palette (Alternative standard dark gray)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceContainer = Color(0xFF242424)

// Light Palette (for accessibility / daytime mode)
val LightBackground = Color(0xFFF9FBF9)
val LightSurface = Color(0xFFFFFFFF)
val LightPrimary = Color(0xFF00695C)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFA7F0E4)
val LightOnPrimaryContainer = Color(0xFF00201A)

@Immutable
data class VoiceColors(
    val talking: Color = Color(0xFF00E676),
    val talkingWhisper: Color = Color(0xFF00E5FF),
    val muted: Color = Color(0xFFFF5252),
    val deafened: Color = Color(0xFF448AFF),
    val suppressed: Color = Color(0xFFFFAB00),
    val prioritySpeaker: Color = Color(0xFFFFD600),
    val idle: Color = Color(0xFF757575)
)

val LocalVoiceColors = staticCompositionLocalOf { VoiceColors() }
