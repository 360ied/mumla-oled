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

package se.lublin.mumla.app

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import se.lublin.humla.model.Server
import se.lublin.mumla.ui.MumlaComposeApp
import se.lublin.mumla.ui.viewmodel.MumlaMainViewModel

object MumlaActivityBridge {

    fun interface OnConnectListener {
        fun onConnect(server: Server)
    }

    fun interface OnDisconnectListener {
        fun onDisconnect()
    }

    @JvmStatic
    fun setupComposeView(
        activity: ComponentActivity,
        viewModel: MumlaMainViewModel,
        onConnect: OnConnectListener,
        onDisconnect: OnDisconnectListener,
        versionName: String
    ): View {
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MumlaComposeApp(
                    viewModel = viewModel,
                    onConnectRequested = { onConnect.onConnect(it) },
                    onDisconnectRequested = { onDisconnect.onDisconnect() },
                    versionName = versionName
                )
            }
        }
    }
}
