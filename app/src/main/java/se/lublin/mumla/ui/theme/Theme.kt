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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val OledDarkColorScheme = darkColorScheme(
    primary = MumlaTealPrimary,
    onPrimary = MumlaTealOnPrimary,
    primaryContainer = MumlaTealContainer,
    onPrimaryContainer = MumlaTealOnContainer,
    secondary = MumlaTealPrimary,
    onSecondary = MumlaTealOnPrimary,
    background = OledBlack,
    onBackground = Color(0xFFE6E6E6),
    surface = OledBlack,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = OledCardSurface,
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainer = OledBlack,
    surfaceContainerLow = OledBlack,
    surfaceContainerLowest = OledBlack,
    surfaceContainerHigh = Color(0xFF0F0F0F),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    outline = OledBorder,
    outlineVariant = OledBorderSubtle
)

private val StandardDarkColorScheme = darkColorScheme(
    primary = MumlaTealPrimary,
    onPrimary = MumlaTealOnPrimary,
    primaryContainer = MumlaTealContainer,
    onPrimaryContainer = MumlaTealOnContainer,
    secondary = MumlaTealPrimary,
    onSecondary = MumlaTealOnPrimary,
    background = DarkBackground,
    onBackground = Color(0xFFE6E6E6),
    surface = DarkSurface,
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = Color(0xFFCACACA),
    outline = Color(0xFF383838)
)

private val StandardLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = Color(0xFF191C1B),
    surface = LightSurface,
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977)
)

enum class AppThemeMode {
    OLED_DARK,
    DARK,
    LIGHT,
    SYSTEM
}

@Composable
fun MumlaTheme(
    themeMode: AppThemeMode = AppThemeMode.OLED_DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.OLED_DARK, AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme: ColorScheme = when {
        themeMode == AppThemeMode.OLED_DARK -> OledDarkColorScheme
        darkTheme -> StandardDarkColorScheme
        else -> StandardLightColorScheme
    }

    val voiceColors = VoiceColors()
    val spacing = MumlaSpacing()

    CompositionLocalProvider(
        LocalVoiceColors provides voiceColors,
        LocalSpacing provides spacing
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
