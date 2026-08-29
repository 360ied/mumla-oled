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

package se.lublin.mumla.ui

import androidx.compose.runtime.Composable
import se.lublin.humla.model.Server
import se.lublin.mumla.ui.navigation.MumlaAppScaffold
import se.lublin.mumla.ui.theme.AppThemeMode
import se.lublin.mumla.ui.theme.MumlaTheme
import se.lublin.mumla.ui.viewmodel.MumlaMainViewModel

@Composable
fun MumlaComposeApp(
    viewModel: MumlaMainViewModel,
    onConnectRequested: (Server) -> Unit,
    onDisconnectRequested: () -> Unit,
    versionName: String = "Mumla OLED"
) {
    MumlaTheme(themeMode = AppThemeMode.OLED_DARK) {
        MumlaAppScaffold(
            viewModel = viewModel,
            onConnectRequested = onConnectRequested,
            onDisconnectRequested = onDisconnectRequested,
            versionName = versionName
        )
    }
}
