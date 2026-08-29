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

package se.lublin.mumla.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.lublin.mumla.ui.theme.LocalVoiceColors

/**
 * Animated VU Meter displaying live microphone input level against the configured VAD threshold.
 * @param currentLevel Normalized audio amplitude (0.0f .. 1.0f).
 * @param threshold Normalized voice activation threshold (0.0f .. 1.0f).
 */
@Composable
fun VuMeter(
    currentLevel: Float,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val voiceColors = LocalVoiceColors.current
    val animatedLevelState = animateFloatAsState(
        targetValue = currentLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 60),
        label = "vuLevel"
    )

    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(shape)
            .background(Color(0xFF0F0F0F))
            .border(1.dp, Color(0xFF262626), shape)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val level = animatedLevelState.value
            val width = size.width
            val height = size.height

            val levelWidth = width * level
            val thresholdX = width * threshold.coerceIn(0f, 1f)

            // Draw level bar
            val isAboveThreshold = level >= threshold
            val barColor = if (isAboveThreshold) voiceColors.talking else Color(0xFF555555)

            drawRect(
                color = barColor,
                topLeft = Offset(0f, 0f),
                size = Size(levelWidth, height)
            )

            // Draw threshold vertical indicator line
            drawLine(
                color = Color.White,
                start = Offset(thresholdX, 0f),
                end = Offset(thresholdX, height),
                strokeWidth = 3.dp.toPx()
            )
        }
    }
}
