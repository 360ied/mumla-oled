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

package se.lublin.mumla.ui.channel

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.mumla.ui.theme.LocalVoiceColors
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder

@Composable
fun PushToTalkBar(
    isSelfMuted: Boolean,
    isSelfDeafened: Boolean,
    isTransmitting: Boolean,
    onTalkKeyDown: () -> Unit,
    onTalkKeyUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val voiceColors = LocalVoiceColors.current
    var isPressed by remember { mutableStateOf(false) }

    val activeTalking = isTransmitting || isPressed
    val buttonBgColor by animateColorAsState(
        targetValue = when {
            activeTalking -> voiceColors.talking.copy(alpha = 0.25f)
            isSelfMuted || isSelfDeafened -> Color(0xFF141414)
            else -> Color(0xFF0F0F0F)
        },
        label = "pttBg"
    )

    val buttonBorderColor by animateColorAsState(
        targetValue = when {
            activeTalking -> voiceColors.talking
            isSelfMuted || isSelfDeafened -> voiceColors.muted.copy(alpha = 0.5f)
            else -> OledBorder
        },
        label = "pttBorder"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .background(OledBlack)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Quick Mute Toggle Button
        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onToggleMute()
            },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isSelfMuted) voiceColors.muted.copy(alpha = 0.2f) else Color(0xFF1A1A1A))
                .border(1.dp, if (isSelfMuted) voiceColors.muted else OledBorder, CircleShape)
        ) {
            Icon(
                imageVector = if (isSelfMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isSelfMuted) "Unmute" else "Mute",
                tint = if (isSelfMuted) voiceColors.muted else MumlaTealPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Large Center PTT Button
        Box(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(buttonBgColor)
                .border(1.5.dp, buttonBorderColor, RoundedCornerShape(28.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                            onTalkKeyDown()
                            tryAwaitRelease()
                            isPressed = false
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                            onTalkKeyUp()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (activeTalking) voiceColors.talking else Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (activeTalking) "TRANSMITTING" else "HOLD TO TALK",
                    color = if (activeTalking) voiceColors.talking else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Quick Deafen Toggle Button
        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onToggleDeafen()
            },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isSelfDeafened) voiceColors.deafened.copy(alpha = 0.2f) else Color(0xFF1A1A1A))
                .border(1.dp, if (isSelfDeafened) voiceColors.deafened else OledBorder, CircleShape)
        ) {
            Icon(
                imageVector = if (isSelfDeafened) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isSelfDeafened) "Undeafen" else "Deafen",
                tint = if (isSelfDeafened) voiceColors.deafened else MumlaTealPrimary
            )
        }
    }
}
