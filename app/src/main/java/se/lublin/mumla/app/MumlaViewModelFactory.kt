/*
 * Copyright (C) 2026 Brian Zhu
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import se.lublin.mumla.channel.ServerInfoViewModel
import se.lublin.mumla.container

/**
 * The one [ViewModelProvider.Factory]. It reads the container from the
 * application and hands each ViewModel only the flows it collects and the
 * functions it calls, never the container or `HumlaClient` itself. Tests
 * build ViewModels directly with plain flows.
 *
 * Per-screen arguments (server ids, session ids) arrive through
 * [CreationExtras]' default-args bundle, which fragments and activities
 * populate from their own arguments/intent.
 */
class MumlaViewModelFactory(private val application: Application) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val container = application.container
        val model: ViewModel = when {
            modelClass.isAssignableFrom(ServerInfoViewModel::class.java) -> ServerInfoViewModel(
                clientState = container.runtime.client.state,
                sessionState = container.runtime.sessionState,
            )
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return model as T
    }
}
