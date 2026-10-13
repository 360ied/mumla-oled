package se.lublin.mumla.preference

import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import se.lublin.mumla.R
import se.lublin.mumla.app.BaseActivity

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById(R.id.toolbar) as Toolbar
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

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, RootPreferenceFragment())
                .commit()
        }

        supportFragmentManager.setFragmentResultListener("launchFragment", this) { _, result ->
            val fragmentClassName = result.getString("fragmentClassName")
            if (fragmentClassName != null) {
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

    class RootPreferenceFragment : MumlaPreferenceFragment() {
        override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preference_headers, rootKey)
        }
    }

    companion object {
        private val TAG: String = SettingsActivity::class.java.simpleName
    }
}
