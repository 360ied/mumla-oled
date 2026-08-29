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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.humla.model.TalkState
import se.lublin.mumla.data.model.SessionUiState
import se.lublin.mumla.data.model.TreeItem
import se.lublin.mumla.ui.components.UserAvatar
import se.lublin.mumla.ui.theme.LocalSpacing
import se.lublin.mumla.ui.theme.LocalVoiceColors
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledCardSurface

@Composable
fun ChannelTreeScreen(
    sessionState: SessionUiState,
    treeItems: List<TreeItem>,
    onToggleExpand: (Int) -> Unit,
    onJoinChannel: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onTalkKeyDown: () -> Unit,
    onTalkKeyUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        if (treeItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (sessionState.status == se.lublin.mumla.data.model.ConnectionStatus.DISCONNECTED)
                        "Not connected to any server"
                    else
                        "Synchronizing channels...",
                    color = Color(0xFF888888),
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = spacing.small)
            ) {
                items(treeItems, key = { it.id }) { item ->
                    when (item) {
                        is TreeItem.ChannelItem -> {
                            ChannelRow(
                                channel = item,
                                onToggleExpand = { onToggleExpand(item.channelId) },
                                onJoinChannel = { onJoinChannel(item.channelId) }
                            )
                        }
                        is TreeItem.UserItem -> {
                            UserRow(user = item)
                        }
                    }
                }
            }
        }

        // Anchored PTT bar at bottom
        PushToTalkBar(
            isSelfMuted = sessionState.isSelfMuted,
            isSelfDeafened = sessionState.isSelfDeafened,
            isTransmitting = sessionState.isTransmitting,
            onTalkKeyDown = onTalkKeyDown,
            onTalkKeyUp = onTalkKeyUp,
            onToggleMute = onToggleMute,
            onToggleDeafen = onToggleDeafen
        )
    }
}

@Composable
fun ChannelRow(
    channel: TreeItem.ChannelItem,
    onToggleExpand: () -> Unit,
    onJoinChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (channel.isExpanded) 90f else 0f,
        label = "arrowRotation"
    )

    val rowBg = if (channel.isCurrentChannel) Color(0xFF162522) else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onJoinChannel() }
            .padding(
                start = (channel.depth * 18 + 8).dp,
                end = 12.dp,
                top = 6.dp,
                bottom = 6.dp
            )
    ) {
        IconButton(
            onClick = onToggleExpand,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = if (channel.isExpanded) "Collapse" else "Expand",
                tint = if (channel.isCurrentChannel) MumlaTealPrimary else Color(0xFF888888),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation)
            )
        }

        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = if (channel.isCurrentChannel) MumlaTealPrimary else Color(0xFFB0BEC5),
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = channel.name,
            color = if (channel.isCurrentChannel) MumlaTealPrimary else Color.White,
            fontSize = 15.sp,
            fontWeight = if (channel.isCurrentChannel) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (channel.isLinked) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = "Linked",
                tint = Color(0xFF80CBC4),
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 4.dp)
            )
        }

        if (channel.userCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF262626))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = channel.userCount.toString(),
                    color = Color(0xFFDDDDDD),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun UserRow(
    user: TreeItem.UserItem,
    modifier: Modifier = Modifier
) {
    val voiceColors = LocalVoiceColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    val nameColor = when {
        user.isSelf -> MumlaTealPrimary
        user.talkState == TalkState.TALKING -> voiceColors.talking
        user.talkState == TalkState.WHISPERING -> voiceColors.talkingWhisper
        user.isSelfMuted || user.isMuted -> voiceColors.muted
        user.isSelfDeafened || user.isDeafened -> voiceColors.deafened
        else -> Color.White
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { menuExpanded = true }
            .padding(
                start = (user.depth * 18 + 12).dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 4.dp
            )
    ) {
        UserAvatar(
            name = user.name,
            talkState = user.talkState,
            isMuted = user.isMuted,
            isDeafened = user.isDeafened,
            isSelfMuted = user.isSelfMuted,
            isSelfDeafened = user.isSelfDeafened,
            isSuppressed = user.isSuppressed,
            isPrioritySpeaker = user.isPrioritySpeaker,
            size = 32.dp
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = if (user.isSelf) "${user.name} (You)" else user.name,
            color = nameColor,
            fontSize = 14.sp,
            fontWeight = if (user.isSelf || user.talkState == TalkState.TALKING) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (!user.comment.isNullOrBlank()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Comment,
                contentDescription = "User Comment",
                tint = Color(0xFFAAAAAA),
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 4.dp)
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.background(Color(0xFF1C1C1C))
        ) {
            DropdownMenuItem(
                text = { Text(user.name, fontWeight = FontWeight.Bold, color = Color.White) },
                onClick = { menuExpanded = false }
            )
            if (!user.comment.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("Comment: ${user.comment}", color = Color(0xFFCCCCCC), fontSize = 12.sp) },
                    onClick = { menuExpanded = false }
                )
            }
        }
    }
}
