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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.humla.model.Server
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditSheet(
    server: Server?,
    isQuickConnect: Boolean = false,
    onDismiss: () -> Unit,
    onSaveOrConnect: (Server, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(server?.name ?: "") }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var portText by remember { mutableStateOf(if ((server?.port ?: 0) > 0) server!!.port.toString() else "64738") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }

    var hostError by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF121212),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = when {
                    isQuickConnect -> "Quick Connect"
                    server != null && server.id > 0 -> "Edit Server"
                    else -> "Add Favourite Server"
                },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!isQuickConnect) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    hostError = it.isBlank()
                },
                label = { Text("Server Address / Host") },
                isError = hostError,
                supportingText = if (hostError) { { Text("Address is required") } } else null,
                singleLine = true,
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        usernameError = it.isBlank()
                    },
                    label = { Text("Username") },
                    isError = usernameError,
                    supportingText = if (usernameError) { { Text("Username is required") } } else null,
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.weight(1.5f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (Optional)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFAAAAAA)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        if (host.isBlank()) {
                            hostError = true
                            return@Button
                        }
                        if (username.isBlank()) {
                            usernameError = true
                            return@Button
                        }

                        val parsedPort = portText.toIntOrNull() ?: 64738
                        val finalName = if (name.isBlank()) host else name
                        val targetServer = if (server != null) {
                            server.name = finalName
                            server.host = host.trim()
                            server.port = parsedPort
                            server.username = username.trim()
                            server.password = password
                            server
                        } else {
                            Server(0L, finalName, host.trim(), parsedPort, username.trim(), password)
                        }

                        onSaveOrConnect(targetServer, isQuickConnect)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MumlaTealPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isQuickConnect) "Connect" else "Save")
                }
            }
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MumlaTealPrimary,
    unfocusedBorderColor = OledBorder,
    focusedLabelColor = MumlaTealPrimary,
    unfocusedLabelColor = Color(0xFF888888),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = Color(0xFF0A0A0A),
    unfocusedContainerColor = Color(0xFF0A0A0A)
)
