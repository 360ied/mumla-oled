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

package se.lublin.mumla.preference

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import se.lublin.mumla.R
import se.lublin.mumla.app.BaseActivity
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.service.MumlaService

class SettingsActivity : BaseActivity() {
    private var crashService: IMumlaService? = null
    private var crashBound: Boolean = false

    private val crashConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                crashService = (service as MumlaService.MumlaBinder).getService()
                crashBound = true
            } catch (_: Throwable) {
                crashService = null
                crashBound = false
            }
            notifyCrashFragment()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            crashBound = false
            crashService = null
            notifyCrashFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // NPE-exact: the original dereferenced the lookup unconditionally.
        val toolbar = findViewById(R.id.toolbar) as Toolbar?
            ?: throw NullPointerException("toolbar")
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setTitle(R.string.action_settings)
        }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!supportFragmentManager.popBackStackImmediate()) {
                    // Finish activity if nothing was popped from stack
                    finish()
                }
            }
        })

        // Deep-link entry (e.g. from the crash Snackbar) replaces without a
        // back-stack entry, so Back finishes the activity. The in-app About
        // path below uses addToBackStack so Back returns to About. Same
        // screen, intentionally different Back behavior.
        if (savedInstanceState == null) {
            val deepLink = intent?.getStringExtra(EXTRA_LAUNCH_FRAGMENT)
            if (deepLink != null && ALLOWED_FRAGMENTS.contains(deepLink)) {
                try {
                    val fragmentClass = Class.forName(deepLink)
                    val fragment = fragmentClass.getDeclaredConstructor().newInstance() as Fragment
                    val args = Bundle()
                    // Title follows the fragment: only the crash path has a
                    // dedicated label today; others fall back to their own.
                    if (deepLink == CrashReportsFragment::class.java.name) {
                        args.putCharSequence("title", getString(R.string.crash_reports))
                    }
                    fragment.arguments = args
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings_container, fragment)
                        .commit()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not instantiate deep-link fragment $deepLink", e)
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings_container, RootPreferenceFragment())
                        .commit()
                }
            } else {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings_container, RootPreferenceFragment())
                    .commit()
            }
        }

        supportFragmentManager.setFragmentResultListener("launchFragment", this) { _, result ->
            val fragmentClassName = result.getString("fragmentClassName")
            if (fragmentClassName != null) {
                // Allowlist: the name originates from the XML app:fragment
                // attribute via a same-process FragmentResult (not externally
                // exploitable), but only the known settings fragments
                // may ever be instantiated here — never an arbitrary
                // no-arg Fragment from a mis-authored XML value.
                if (!ALLOWED_FRAGMENTS.contains(fragmentClassName)) {
                    Log.w(TAG, "Refusing to instantiate non-settings fragment $fragmentClassName")
                    return@setFragmentResultListener
                }
                try {
                    val fragmentClass = Class.forName(fragmentClassName)
                    val fragment = fragmentClass.getDeclaredConstructor().newInstance() as Fragment
                    // Pass it on to the fragment so its onResume can set the title, see MumlaPreferenceFragment
                    fragment.arguments = result
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings_container, fragment)
                        .addToBackStack(null)
                        .commit()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not instantiate settings fragment $fragmentClassName", e)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            val intent = Intent(this, MumlaService::class.java)
            // Flag 0 like MumlaActivity: the bind only succeeds while the
            // service is already running; buttons sit disabled while unbound.
            crashBound = bindService(intent, crashConnection, 0)
            if (!crashBound) {
                crashService = null
            }
        } catch (_: Throwable) {
            crashBound = false
            crashService = null
        }
    }

    override fun onStop() {
        if (crashBound) {
            try {
                unbindService(crashConnection)
            } catch (_: Throwable) {
            }
            crashBound = false
            crashService = null
        }
        super.onStop()
    }

    fun getCrashService(): IMumlaService? {
        return crashService
    }

    fun isCrashBound(): Boolean {
        return crashBound
    }

    fun isCrashConnected(): Boolean {
        return try {
            crashService?.isConnected() == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun notifyCrashFragment() {
        try {
            val fragment = supportFragmentManager.findFragmentById(R.id.settings_container)
            if (fragment is CrashReportsFragment) {
                fragment.onServiceStateChanged()
            }
        } catch (_: Throwable) {
        }
    }

    class RootPreferenceFragment : MumlaPreferenceFragment() {
        override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preference_headers, rootKey)
        }
    }

    companion object {
        private val TAG: String = SettingsActivity::class.java.simpleName

        const val EXTRA_LAUNCH_FRAGMENT = "launch_fragment"

        // The fragments declared in preference_headers.xml (app:fragment) plus
        // the crash-reports screen (launched from About). Class references
        // (not raw strings) so renames break at compile time.
        private val ALLOWED_FRAGMENTS: Set<String> = setOf(
            GeneralSettingsFragment::class.java.name,
            AuthenticationSettingsFragment::class.java.name,
            AudioSettingsFragment::class.java.name,
            AppearanceSettingsFragment::class.java.name,
            AboutSettingsFragment::class.java.name,
            CrashReportsFragment::class.java.name,
        )
    }
}
