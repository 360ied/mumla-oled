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

package se.lublin.mumla.ui.adaptive

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.mumla.data.model.ChatMessageUiState
import se.lublin.mumla.data.model.ChatTargetUiState
import se.lublin.mumla.data.model.SessionUiState
import se.lublin.mumla.data.model.TreeItem
import se.lublin.mumla.ui.channel.ChannelTreeScreen
import se.lublin.mumla.ui.chat.ChatScreen
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder

@Composable
fun AdaptiveVoiceChatScreen(
    sessionState: SessionUiState,
    treeItems: List<TreeItem>,
    messages: List<ChatMessageUiState>,
    chatTarget: ChatTargetUiState,
    onToggleExpand: (Int) -> Unit,
    onJoinChannel: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onTalkKeyDown: () -> Unit,
    onTalkKeyUp: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSetChatTarget: (ChatTargetUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = configuration.screenWidthDp

    // Wide screen / Dual-pane trigger: Tablets and Large Foldables with width >= 600dp
    val isWideScreen = screenWidthDp >= 600

    if (isWideScreen) {
        // Dual-Pane Layout: Channels on Left, Chat on Right
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(OledBlack)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                ChannelTreeScreen(
                    sessionState = sessionState,
                    treeItems = treeItems,
                    onToggleExpand = onToggleExpand,
                    onJoinChannel = onJoinChannel,
                    onToggleMute = onToggleMute,
                    onToggleDeafen = onToggleDeafen,
                    onTalkKeyDown = onTalkKeyDown,
                    onTalkKeyUp = onTalkKeyUp
                )
            }

            // Subtle vertical OLED divider
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(OledBorder)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                ChatScreen(
                    messages = messages,
                    chatTarget = chatTarget,
                    onSendMessage = onSendMessage,
                    onSetChatTarget = onSetChatTarget
                )
            }
        }
    } else {
        // Phone Portrait: Tab switching between Channels and Chat
        var selectedTabIndex by remember { mutableIntStateOf(0) }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(OledBlack)
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color(0xFF0F0F0F),
                contentColor = Color.White,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = MumlaTealPrimary
                    )
                },
                divider = { Box(modifier = Modifier.height(1.dp).background(OledBorder)) }
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Channels", fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    },
                    selectedContentColor = MumlaTealPrimary,
                    unselectedContentColor = Color(0xFF888888)
                )

                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Chat", fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    },
                    selectedContentColor = MumlaTealPrimary,
                    unselectedContentColor = Color(0xFF888888)
                )
            }

            if (selectedTabIndex == 0) {
                ChannelTreeScreen(
                    sessionState = sessionState,
                    treeItems = treeItems,
                    onToggleExpand = onToggleExpand,
                    onJoinChannel = onJoinChannel,
                    onToggleMute = onToggleMute,
                    onToggleDeafen = onToggleDeafen,
                    onTalkKeyDown = onTalkKeyDown,
                    onTalkKeyUp = onTalkKeyUp,
                    modifier = Modifier.weight(1f)
                )
            } else {
                ChatScreen(
                    messages = messages,
                    chatTarget = chatTarget,
                    onSendMessage = onSendMessage,
                    onSetChatTarget = onSetChatTarget,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
