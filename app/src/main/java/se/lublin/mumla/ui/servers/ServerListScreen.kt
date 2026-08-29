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

package se.lublin.mumla.ui.servers

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.mumla.data.model.ServerCardUiState
import se.lublin.mumla.ui.theme.LocalSpacing
import se.lublin.mumla.ui.theme.LocalVoiceColors
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder
import se.lublin.mumla.ui.theme.OledCardSurface

@Composable
fun ServerListScreen(
    servers: List<ServerCardUiState>,
    onConnectServer: (ServerCardUiState) -> Unit,
    onAddServer: () -> Unit,
    onQuickConnect: () -> Unit,
    onEditServer: (ServerCardUiState) -> Unit,
    onDeleteServer: (ServerCardUiState) -> Unit,
    onShareServer: (ServerCardUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = OledBlack,
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = onQuickConnect,
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = MumlaTealPrimary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = "Quick Connect",
                        modifier = Modifier.size(22.dp)
                    )
                }

                FloatingActionButton(
                    onClick = onAddServer,
                    containerColor = MumlaTealPrimary,
                    contentColor = Color.Black
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Favorite Server"
                    )
                }
            }
        }
    ) { innerPadding ->
        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = Color(0xFF444444),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No saved servers",
                        color = Color(0xFF888888),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to add your favourite Mumble server",
                        color = Color(0xFF555555),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                contentPadding = PaddingValues(
                    start = spacing.medium,
                    top = innerPadding.calculateTopPadding() + spacing.medium,
                    end = spacing.medium,
                    bottom = innerPadding.calculateBottomPadding() + 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
                modifier = Modifier.fillMaxSize()
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        onConnect = { onConnectServer(server) },
                        onEdit = { onEditServer(server) },
                        onDelete = { onDeleteServer(server) },
                        onShare = { onShareServer(server) }
                    )
                }
            }
        }
    }
}

@Composable
fun ServerCard(
    server: ServerCardUiState,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val voiceColors = LocalVoiceColors.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onConnect() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = OledCardSurface),
        border = BorderStroke(
            width = if (server.isConnected) 1.5.dp else 1.dp,
            color = if (server.isConnected) voiceColors.talking else OledBorder
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (server.isConnected) voiceColors.talking.copy(alpha = 0.2f) else Color(0xFF161616)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = if (server.isConnected) voiceColors.talking else MumlaTealPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${server.host}:${server.port}",
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color(0xFF888888)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color(0xFF1A1A1A))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit", color = Color.White) },
                            onClick = { menuExpanded = false; onEdit() },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MumlaTealPrimary) }
                        )
                        DropdownMenuItem(
                            text = { Text("Share Link", color = Color.White) },
                            onClick = { menuExpanded = false; onShare() },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color.White) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Color(0xFFFF5252)) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252)) }
                        )
                    }
                }
            }

            if (server.username.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "User: ",
                        color = Color(0xFF666666),
                        fontSize = 12.sp
                    )
                    Text(
                        text = server.username,
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    if (server.isConnected) {
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(voiceColors.talking.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CONNECTED",
                                color = voiceColors.talking,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
