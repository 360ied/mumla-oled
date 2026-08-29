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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.humla.model.TalkState
import se.lublin.mumla.ui.theme.LocalVoiceColors

@Composable
fun UserAvatar(
    name: String,
    talkState: TalkState = TalkState.PASSIVE,
    isMuted: Boolean = false,
    isDeafened: Boolean = false,
    isSelfMuted: Boolean = false,
    isSelfDeafened: Boolean = false,
    isSuppressed: Boolean = false,
    isPrioritySpeaker: Boolean = false,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val voiceColors = LocalVoiceColors.current
    val isTalking = talkState == TalkState.TALKING || talkState == TalkState.WHISPERING
    val talkColor = if (talkState == TalkState.WHISPERING) voiceColors.talkingWhisper else voiceColors.talking

    val infiniteTransition = rememberInfiniteTransition(label = "TalkHaloPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier.size(size + 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring when talking
        if (isTalking) {
            Box(
                modifier = Modifier
                    .size(size + 6.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .clip(CircleShape)
                    .border(2.5.dp, talkColor.copy(alpha = 0.7f), CircleShape)
            )
        }

        // Avatar base circle with user initials
        val initial = if (name.isNotEmpty()) name.take(1).uppercase() else "?"
        val avatarBg = when {
            isSelfDeafened || isDeafened -> voiceColors.deafened.copy(alpha = 0.25f)
            isSelfMuted || isMuted -> voiceColors.muted.copy(alpha = 0.25f)
            isTalking -> talkColor.copy(alpha = 0.25f)
            else -> Color(0xFF262626)
        }

        val avatarBorder = when {
            isTalking -> talkColor
            isSelfDeafened || isDeafened -> voiceColors.deafened
            isSelfMuted || isMuted -> voiceColors.muted
            isSuppressed -> voiceColors.suppressed
            isPrioritySpeaker -> voiceColors.prioritySpeaker
            else -> voiceColors.idle.copy(alpha = 0.4f)
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarBg)
                .border(1.5.dp, avatarBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Status Badge Overlay at bottom-right
        val hasStatusBadge = isSelfDeafened || isDeafened || isSelfMuted || isMuted
        if (hasStatusBadge) {
            val badgeIcon = when {
                isSelfDeafened || isDeafened -> Icons.AutoMirrored.Filled.VolumeOff
                else -> Icons.Default.MicOff
            }
            val badgeColor = when {
                isSelfDeafened || isDeafened -> voiceColors.deafened
                else -> voiceColors.muted
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(1.dp, badgeColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}
