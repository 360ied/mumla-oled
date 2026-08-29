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

package se.lublin.mumla.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import se.lublin.mumla.data.model.ChatMessageUiState
import se.lublin.mumla.data.model.ChatTargetMode
import se.lublin.mumla.data.model.ChatTargetUiState
import se.lublin.mumla.data.model.LogMessageType
import se.lublin.mumla.ui.theme.LocalSpacing
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder
import se.lublin.mumla.ui.theme.OledCardSurface

@Composable
fun ChatScreen(
    messages: List<ChatMessageUiState>,
    chatTarget: ChatTargetUiState,
    onSendMessage: (String) -> Unit,
    onSetChatTarget: (ChatTargetUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Target Mode Selector Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Target:",
                color = Color(0xFF888888),
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )

            FilterChip(
                selected = chatTarget.mode == ChatTargetMode.CURRENT_CHANNEL,
                onClick = { onSetChatTarget(ChatTargetUiState(mode = ChatTargetMode.CURRENT_CHANNEL, targetName = "Channel")) },
                label = { Text("Channel", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MumlaTealPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = MumlaTealPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = chatTarget.mode == ChatTargetMode.CURRENT_CHANNEL,
                    borderColor = OledBorder,
                    selectedBorderColor = MumlaTealPrimary
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilterChip(
                selected = chatTarget.mode == ChatTargetMode.SUBCHANNEL_TREE,
                onClick = { onSetChatTarget(ChatTargetUiState(mode = ChatTargetMode.SUBCHANNEL_TREE, targetName = "Subchannel Tree")) },
                label = { Text("Tree", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MumlaTealPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = MumlaTealPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = chatTarget.mode == ChatTargetMode.SUBCHANNEL_TREE,
                    borderColor = OledBorder,
                    selectedBorderColor = MumlaTealPrimary
                )
            )
        }

        // Messages List (reverseLayout so newest messages are at bottom)
        LazyColumn(
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatMessageBubble(message = msg)
            }
        }

        // Chat Input Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F0F0F))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, OledBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = "Message ${chatTarget.targetName}...",
                        color = Color(0xFF666666),
                        fontSize = 14.sp
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(MumlaTealPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (inputText.isNotBlank()) MumlaTealPrimary else Color(0xFF262626))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank()) Color.Black else Color(0xFF666666),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessageUiState,
    modifier: Modifier = Modifier
) {
    val plainText = remember(message.contentHtml) {
        HtmlCompat.fromHtml(message.contentHtml, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    when (message.messageType) {
        LogMessageType.INFO, LogMessageType.WARNING, LogMessageType.ERROR -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                val icon = if (message.messageType == LogMessageType.ERROR) Icons.Default.Warning else Icons.Default.Info
                val tint = when (message.messageType) {
                    LogMessageType.ERROR -> Color(0xFFFF5252)
                    LogMessageType.WARNING -> Color(0xFFFFAB00)
                    else -> Color(0xFF888888)
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = plainText,
                    color = tint,
                    fontSize = 12.sp
                )
            }
        }
        LogMessageType.TEXT -> {
            val bubbleBg = if (message.isSelf) Color(0xFF003830) else OledCardSurface
            val border = if (message.isPrivate) Color(0xFF448AFF) else OledBorder

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bubbleBg)
                    .border(1.dp, border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message.senderName,
                        color = if (message.isSelf) MumlaTealPrimary else Color(0xFF80DEEA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = message.formattedTime,
                        color = Color(0xFF666666),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = plainText,
                    color = Color(0xFFEEEEEE),
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
