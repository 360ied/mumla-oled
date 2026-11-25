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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app

import se.lublin.mumla.container
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Applies the theme before the first draw and recreates on a theme change,
 * collected from the settings flow. No settings listener remains.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var mCurrentTheme: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MumlaApplication.applyTheme(this)
        super.onCreate(savedInstanceState)
        val repository = this.container.settingsRepository
        mCurrentTheme = repository.state.value.snapshot.theme
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.state
                    .map { it.snapshot.theme }
                    .distinctUntilChanged()
                    .collect { theme ->
                        if (theme != mCurrentTheme) {
                            mCurrentTheme = theme
                            recreate()
                        }
                    }
            }
        }
    }
}
