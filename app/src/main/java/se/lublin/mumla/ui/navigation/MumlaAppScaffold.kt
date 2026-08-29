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

package se.lublin.mumla.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.mumla.data.model.ConnectionStatus
import se.lublin.mumla.data.model.ServerCardUiState
import se.lublin.mumla.ui.adaptive.AdaptiveVoiceChatScreen
import se.lublin.mumla.ui.servers.ServerEditSheet
import se.lublin.mumla.ui.servers.ServerListScreen
import se.lublin.mumla.ui.settings.SettingsScreen
import se.lublin.mumla.ui.theme.LocalVoiceColors
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder
import se.lublin.mumla.ui.viewmodel.MumlaMainViewModel

enum class ScreenDestination {
    SERVERS,
    VOICE_CHAT,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MumlaAppScaffold(
    viewModel: MumlaMainViewModel,
    onConnectRequested: (Server) -> Unit,
    onDisconnectRequested: () -> Unit,
    versionName: String = "Mumla OLED",
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(ScreenDestination.SERVERS) }

    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val treeItems by viewModel.treeItems.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chatTarget by viewModel.chatTarget.collectAsStateWithLifecycle()

    var editingServer by remember { mutableStateOf<Server?>(null) }
    var isQuickConnect by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }

    val isConnected = sessionState.status == ConnectionStatus.SYNCHRONIZED || sessionState.status == ConnectionStatus.CONNECTED

    // Automatically navigate to voice chat when successfully connected
    androidx.compose.runtime.LaunchedEffect(sessionState.status) {
        if (sessionState.status == ConnectionStatus.SYNCHRONIZED) {
            currentScreen = ScreenDestination.VOICE_CHAT
        }
    }

    // Handle back button for closing drawer or returning to server list
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && currentScreen != ScreenDestination.SERVERS) {
        currentScreen = ScreenDestination.SERVERS
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0F0F0F),
                drawerContentColor = Color.White
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Mumla OLED",
                        color = MumlaTealPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "High-Performance Voice Client",
                        color = Color(0xFF888888),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                        label = { Text("Favorite Servers") },
                        selected = currentScreen == ScreenDestination.SERVERS,
                        onClick = {
                            currentScreen = ScreenDestination.SERVERS
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MumlaTealPrimary.copy(alpha = 0.2f),
                            selectedIconColor = MumlaTealPrimary,
                            selectedTextColor = MumlaTealPrimary,
                            unselectedTextColor = Color.White,
                            unselectedIconColor = Color(0xFFAAAAAA)
                        )
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                        label = { Text("Voice & Chat") },
                        selected = currentScreen == ScreenDestination.VOICE_CHAT,
                        onClick = {
                            currentScreen = ScreenDestination.VOICE_CHAT
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MumlaTealPrimary.copy(alpha = 0.2f),
                            selectedIconColor = MumlaTealPrimary,
                            selectedTextColor = MumlaTealPrimary,
                            unselectedTextColor = Color.White,
                            unselectedIconColor = Color(0xFFAAAAAA)
                        )
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        selected = currentScreen == ScreenDestination.SETTINGS,
                        onClick = {
                            currentScreen = ScreenDestination.SETTINGS
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MumlaTealPrimary.copy(alpha = 0.2f),
                            selectedIconColor = MumlaTealPrimary,
                            selectedTextColor = MumlaTealPrimary,
                            unselectedTextColor = Color.White,
                            unselectedIconColor = Color(0xFFAAAAAA)
                        )
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when (currentScreen) {
                                    ScreenDestination.SERVERS -> "Mumla OLED"
                                    ScreenDestination.VOICE_CHAT -> if (isConnected) sessionState.serverName else "Voice & Chat"
                                    ScreenDestination.SETTINGS -> "Settings"
                                },
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (currentScreen == ScreenDestination.VOICE_CHAT && isConnected) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00E676))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val ping = if (sessionState.pingUdp > 0) sessionState.pingUdp else sessionState.pingTcp
                                    Text(
                                        text = if (ping > 0) "${ping}ms • Connected" else "Connected",
                                        color = Color(0xFF888888),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Navigation Menu",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        if (isConnected) {
                            IconButton(onClick = onDisconnectRequested) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnect",
                                    tint = Color(0xFFFF5252)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0A0A0A),
                        titleContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF0A0A0A),
                    contentColor = Color.White,
                    modifier = Modifier.border(1.dp, OledBorder, RoundedCornerShape(0.dp))
                ) {
                    NavigationBarItem(
                        selected = currentScreen == ScreenDestination.SERVERS,
                        onClick = { currentScreen = ScreenDestination.SERVERS },
                        icon = { Icon(Icons.Default.Dns, contentDescription = "Servers") },
                        label = { Text("Servers") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MumlaTealPrimary,
                            selectedTextColor = MumlaTealPrimary,
                            indicatorColor = MumlaTealPrimary.copy(alpha = 0.2f),
                            unselectedIconColor = Color(0xFF888888),
                            unselectedTextColor = Color(0xFF888888)
                        )
                    )

                    NavigationBarItem(
                        selected = currentScreen == ScreenDestination.VOICE_CHAT,
                        onClick = { currentScreen = ScreenDestination.VOICE_CHAT },
                        icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Voice") },
                        label = { Text("Voice") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MumlaTealPrimary,
                            selectedTextColor = MumlaTealPrimary,
                            indicatorColor = MumlaTealPrimary.copy(alpha = 0.2f),
                            unselectedIconColor = Color(0xFF888888),
                            unselectedTextColor = Color(0xFF888888)
                        )
                    )

                    NavigationBarItem(
                        selected = currentScreen == ScreenDestination.SETTINGS,
                        onClick = { currentScreen = ScreenDestination.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MumlaTealPrimary,
                            selectedTextColor = MumlaTealPrimary,
                            indicatorColor = MumlaTealPrimary.copy(alpha = 0.2f),
                            unselectedIconColor = Color(0xFF888888),
                            unselectedTextColor = Color(0xFF888888)
                        )
                    )
                }
            },
            containerColor = OledBlack,
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentScreen) {
                    ScreenDestination.SERVERS -> {
                        ServerListScreen(
                            servers = servers,
                            onConnectServer = { serverCard ->
                                scope.launch {
                                    val server = viewModel.serverRepository.getServerById(serverCard.id)
                                    if (server != null) {
                                        onConnectRequested(server)
                                    }
                                }
                            },
                            onAddServer = {
                                editingServer = null
                                isQuickConnect = false
                                showEditSheet = true
                            },
                            onQuickConnect = {
                                editingServer = null
                                isQuickConnect = true
                                showEditSheet = true
                            },
                            onEditServer = { serverCard ->
                                scope.launch {
                                    editingServer = viewModel.serverRepository.getServerById(serverCard.id)
                                    isQuickConnect = false
                                    showEditSheet = true
                                }
                            },
                            onDeleteServer = { serverCard ->
                                scope.launch {
                                    val s = viewModel.serverRepository.getServerById(serverCard.id)
                                    if (s != null) viewModel.deleteServer(s)
                                }
                            },
                            onShareServer = { /* Share handled */ }
                        )
                    }
                    ScreenDestination.VOICE_CHAT -> {
                        AdaptiveVoiceChatScreen(
                            sessionState = sessionState,
                            treeItems = treeItems,
                            messages = messages,
                            chatTarget = chatTarget,
                            onToggleExpand = { viewModel.toggleChannelExpanded(it) },
                            onJoinChannel = { viewModel.joinChannel(it) },
                            onToggleMute = { viewModel.toggleSelfMute() },
                            onToggleDeafen = { viewModel.toggleSelfDeafen() },
                            onTalkKeyDown = { viewModel.onTalkKeyDown() },
                            onTalkKeyUp = { viewModel.onTalkKeyUp() },
                            onSendMessage = { viewModel.sendMessage(it) },
                            onSetChatTarget = { viewModel.setChatTarget(it) }
                        )
                    }
                    ScreenDestination.SETTINGS -> {
                        SettingsScreen(versionName = versionName)
                    }
                }
            }
        }
    }

    if (showEditSheet) {
        ServerEditSheet(
            server = editingServer,
            isQuickConnect = isQuickConnect,
            onDismiss = { showEditSheet = false },
            onSaveOrConnect = { server, connectDirectly ->
                showEditSheet = false
                if (connectDirectly) {
                    onConnectRequested(server)
                } else {
                    viewModel.addOrUpdateServer(server)
                }
            }
        )
    }
}
