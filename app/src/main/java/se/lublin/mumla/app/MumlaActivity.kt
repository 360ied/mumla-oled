/*
 * Copyright (C) 2014 Andrew Comminos
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
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.net.MalformedURLException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.humla.net.TlsHostnameVerifier
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.MumbleURLParser
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.AccessTokenFragment
import se.lublin.mumla.channel.ChannelFragment
import se.lublin.mumla.channel.ServerInfoFragment
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.feature.ChatLogFeature
import se.lublin.mumla.feature.displayMessage
import se.lublin.mumla.preference.CrashReportsFragment
import se.lublin.mumla.preference.generateClientCertificate
import se.lublin.mumla.preference.SettingsActivity
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.util.MumlaTrustStore

class MumlaActivity : BaseActivity(), AdapterView.OnItemClickListener,
    FavouriteServerListFragment.ServerConnectHandler,
    DrawerAdapter.DrawerDataProvider, ServerEditFragment.ServerEditListener {

    private val viewModel: MainViewModel by viewModels {
        MumlaViewModelFactory(application)
    }

    private var mDatabase: MumlaDatabase? = null
    private var mSettings: Settings? = null

    private var mDrawerToggle: ActionBarDrawerToggle? = null
    private var mDrawerLayout: DrawerLayout? = null
    private var mDrawerAdapter: DrawerAdapter? = null

    private var mServerPendingPerm: Server? = null
    private var mPermPostNotificationsAsked = false
    private var mPermBluetoothAsked = false

    private var mConnectingDialog: AlertDialog? = null
    private var mErrorDialog: AlertDialog? = null
    private var mSwitchDialog: AlertDialog? = null
    private var mPermissionDialog: AlertDialog? = null
    private var mBackPressDialog: AlertDialog? = null
    private var mCertDialog: AlertDialog? = null
    private var mCrashSnackbar: Snackbar? = null
    // Dedicated host for the first-run certificate guide. It carries an
    // OnDismissListener with first-run side effects that must never fire for
    // the TLS dialogs hosted by mCertDialog above (round-2 D2).
    private var mFirstRunDialog: AlertDialog? = null
    // True while the first-run certificate generation coroutine owns StartupAction.
    private var mFirstRunGenerateInFlight = false
    private var mGenerateProgressDialog: AlertDialog? = null

    /** The prompt the current certificate dialog renders; null when dismissed. */
    private var mShownTrust: TrustPrompt? = null
    /** Cancelled with the reconnect dialog. */
    private var mCountdownJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = this.container.settings
        mSettings = settings

        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            mPermBluetoothAsked =
                savedInstanceState.getBoolean(STATE_BT_ASKED, false)
            mPermPostNotificationsAsked =
                savedInstanceState.getBoolean(STATE_POST_NOTIF_ASKED, false)
            mServerPendingPerm =
                BundleCompat.getParcelable(savedInstanceState, STATE_SERVER_PENDING, Server::class.java)
        }
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.connection.value is ConnectionUi.Connected) {
                    val currentName = viewModel.connection.value.serverName ?: ""
                    val backPressDialog = mBackPressDialog
                    if (backPressDialog != null) {
                        backPressDialog.dismiss()
                        mBackPressDialog = null
                    }
                    mBackPressDialog = MaterialAlertDialogBuilder(this@MumlaActivity)
                        .setMessage(getString(R.string.disconnectSure, currentName))
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            viewModel.disconnect()
                            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setStayAwake(settings.shouldStayAwake)

        mDatabase = this.container.database // TODO add support for cloud storage
        // NPE-exact: the original dereferenced mDatabase unconditionally below.
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        database.open()

        mDrawerLayout = findViewById(R.id.drawer_layout) as DrawerLayout
        val drawerList = findViewById<ListView>(R.id.left_drawer)

        val headerView = layoutInflater.inflate(R.layout.list_drawer_headerlogo, drawerList, false)
        drawerList.addHeaderView(headerView, null, false)

        drawerList.onItemClickListener = this
        mDrawerAdapter = DrawerAdapter(this, this)
        drawerList.adapter = mDrawerAdapter
        mDrawerToggle = object : ActionBarDrawerToggle(
            this, mDrawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close
        ) {
            override fun onDrawerClosed(drawerView: View) {
                supportInvalidateOptionsMenu()
            }

            override fun onDrawerStateChanged(newState: Int) {
                super.onDrawerStateChanged(newState)
                // Prevent push to talk from getting stuck on when the drawer
                // is opened. A cancel always ends a held press; toggle mode
                // ignores it, as the talk-key feature decides.
                viewModel.talkKeyCancel()
            }

            override fun onDrawerOpened(drawerView: View) {
                supportInvalidateOptionsMenu()
            }
        }

        // NPE-exact: the original dereferenced the toggle, layout, and action bar unconditionally.
        val drawerToggle = mDrawerToggle ?: throw NullPointerException("mDrawerToggle")
        val drawerLayout = mDrawerLayout ?: throw NullPointerException("mDrawerLayout")
        drawerLayout.addDrawerListener(drawerToggle)
        val actionBar = supportActionBar ?: throw NullPointerException("actionBar")
        actionBar.setDisplayHomeAsUpEnabled(true)
        actionBar.setHomeButtonEnabled(true)

        if (savedInstanceState == null) {
            if (intent != null && intent.hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(
                    intent.getIntExtra(
                        EXTRA_DRAWER_FRAGMENT,
                        DrawerAdapter.ITEM_FAVOURITES
                    )
                )
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
        }

        // If we're given a Mumble URL to show, open up a server edit dialog prompting the
        // user to connect. Only on a fresh launch: after recreation the fragment manager
        // restores the dialog itself.
        if (savedInstanceState == null) {
            handleViewIntent(intent)
        }

        applyVolumeControlStream()

        if (savedInstanceState == null) {
            // Got no instance bundle: this is run only on real app startup -- not when Android
            // recreates the activity on configuration change, like screen rotation.
            // Crash prompt is non-first-run only so it never overlays the
            // first-run certificate guide. A crash that predates first-run
            // completion therefore waits an extra launch (accepted tradeoff).
            if (settings.isFirstRun) {
                showFirstRunGuide()
            } else {
                StartupAction().execute(this)
                viewModel.startCrashChecks()
            }
        } else if (pendingFirstRunStartup) {
            // Recreation while first-run generation was in flight on the dead
            // instance: its onPostExecute hands StartupAction here instead of
            // its dead window token (consume before execute: exactly-once).
            pendingFirstRunStartup = false
            StartupAction().execute(this)
        }

        collectState()
        collectSettings()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // NPE-exact: the original dereferenced mDrawerToggle unconditionally.
        val drawerToggle = mDrawerToggle ?: throw NullPointerException("mDrawerToggle")
        drawerToggle.syncState()
    }

    /** Collects the connection, its dialogs, and the crash prompt. */
    private fun collectState() {
        val runtime = container.runtime
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.connection.collect { renderConnection(it) } }
                launch { viewModel.connectionError.collect { updateConnectionDialogs() } }
                launch { viewModel.trustPrompt.collect { renderTrustPrompt(it) } }
                launch { viewModel.permissionDenied.collect { renderPermissionDenied(it) } }
                launch { viewModel.crashReportId.collect { renderCrashPrompt(it) } }
                // Volume keys follow the live route, never the SCO link itself.
                launch {
                    runtime.routeState
                        .map { runtime.isMediaRouteActive }
                        .distinctUntilChanged()
                        .collect {
                            applyVolumeControlStream()
                            supportInvalidateOptionsMenu()
                        }
                }
            }
        }
    }

    /** Stay-awake and the volume stream follow the settings they read. */
    private fun collectSettings() {
        val repository = container.settingsRepository
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.state
                        .map { it.snapshot.stayAwake }
                        .distinctUntilChanged()
                        .collect { setStayAwake(it) }
                }
                launch {
                    repository.state
                        .map { it.snapshot.handsetMode to it.snapshot.bluetoothHeadset }
                        .distinctUntilChanged()
                        .collect {
                            applyVolumeControlStream()
                            supportInvalidateOptionsMenu()
                        }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onActivityStarted()
    }

    override fun onStop() {
        viewModel.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Late completion after recreation: the dead instance's task finished
        // after this instance resumed, so consume here (next resume at the
        // latest — e.g. on return from background).
        if (pendingFirstRunStartup) {
            pendingFirstRunStartup = false
            StartupAction().execute(this)
        }
        viewModel.setSuppressNotifications(true)
        viewModel.clearChatNotifications()
        // Re-arm the crash check when nothing is showing; the seen-store keeps
        // repeated resumes harmless.
        viewModel.recheckCrashReports()
    }

    override fun onPause() {
        super.onPause()
        dismissAllDialogs()
        dismissCrashSnackbar()
        viewModel.dismissCrashPrompt()
        viewModel.talkKeyCancel()
        viewModel.dismissPermissionDenied()
        viewModel.setSuppressNotifications(false)
    }

    private fun dismissAllDialogs() {
        dismissConnectingDialog()
        dismissErrorDialog()
        val switchDialog = mSwitchDialog
        if (switchDialog != null) {
            switchDialog.dismiss()
            mSwitchDialog = null
        }
        val permissionDialog = mPermissionDialog
        if (permissionDialog != null) {
            permissionDialog.dismiss()
            mPermissionDialog = null
        }
        val backPressDialog = mBackPressDialog
        if (backPressDialog != null) {
            backPressDialog.dismiss()
            mBackPressDialog = null
        }
        dismissCertDialog()
        dismissFirstRunDialog()
        val progress = mGenerateProgressDialog
        mGenerateProgressDialog = null
        if (progress != null) {
            progress.dismiss()
        }
    }

    /**
     * If the intent is a Mumble URL view request, opens a server edit dialog prompting the
     * user to connect.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || Intent.ACTION_VIEW != intent.action) {
            return
        }
        try {
            val server = MumbleURLParser.parseURL(intent.dataString)

            // Flag a password embedded in the link so the dialog can warn (not block).
            val urlPassword = server.password
            val hasUrlPassword = urlPassword != null && urlPassword.isNotEmpty()
            val fragment: DialogFragment = ServerEditFragment.createServerEditDialog(
                server, ServerEditFragment.Action.CONNECT_ACTION, true,
                hasUrlPassword
            )
            fragment.show(supportFragmentManager, "url_edit")
        } catch (e: MalformedURLException) {
            Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show()
            Log.w(TAG, "Could not parse Mumble URL", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: links opened while the activity is running arrive here.
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
        // NPE-exact: the original dereferenced mDatabase unconditionally.
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        database.close()
        super.onDestroy()
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu == null) {
            return super.onPrepareOptionsMenu(menu)
        }
        val disconnectButton = menu.findItem(R.id.action_disconnect)
        if (disconnectButton == null) {
            return super.onPrepareOptionsMenu(menu)
        }
        disconnectButton.setVisible(viewModel.connection.value is ConnectionUi.Connected)

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.mumla, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // NPE-exact: the original dereferenced mDrawerToggle unconditionally.
        val drawerToggle = mDrawerToggle ?: throw NullPointerException("mDrawerToggle")
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        if (item.itemId == R.id.action_disconnect) {
            viewModel.disconnect()
            return true
        }
        return false
    }

    /**
     * Volume keys follow output routing: voice-call stream on voice routes
     * (the AEC path), music stream on media routes (A2DP volume). The default
     * is voice, matching the no-sink route.
     */
    private fun useVoiceCallVolume(): Boolean = !container.runtime.isMediaRouteActive

    private fun applyVolumeControlStream() {
        setVolumeControlStream(
            if (useVoiceCallVolume()) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
        )
    }

    /**
     * Asserts Bluetooth headset state. Absolute, not a toggle; writes the
     * requested preference unconditionally so the overflow item mirrors
     * Settings > Audio. Missing-grant handling lives downstream: the
     * Bluetooth permission feature reverts with a hint.
     */
    fun setBluetoothHeadset(enabled: Boolean) {
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (settings.isBluetoothHeadset == enabled) {
            return
        }
        settings.isBluetoothHeadset = enabled
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // NPE-exact: the original dereferenced mDrawerToggle unconditionally.
        val drawerToggle = mDrawerToggle ?: throw NullPointerException("mDrawerToggle")
        drawerToggle.onConfigurationChanged(newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val pttKey = settings.pushToTalkKey
        if (Settings.isPttKeyBound(pttKey, keyCode)) {
            viewModel.talkKeyDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val pttKey = settings.pushToTalkKey
        if (Settings.isPttKeyBound(pttKey, keyCode)) {
            viewModel.talkKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            viewModel.talkKeyCancel()
        }
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        // NPE-exact: the original dereferenced mDrawerLayout unconditionally.
        val drawerLayout = mDrawerLayout ?: throw NullPointerException("mDrawerLayout")
        drawerLayout.closeDrawers()
        loadDrawerFragment(id.toInt())
    }

    private fun showFirstRunGuide() {
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        // Prompt the user to generate a certificate.
        if (settings.isUsingCertificate) {
            settings.isFirstRun = false
            StartupAction().execute(this)
            return
        }
        val msg = getString(R.string.first_run_generate_certificate)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_generate_certificate_title)
            .setMessage(msg)
            .setPositiveButton(R.string.generate) { _, _ ->
                mFirstRunGenerateInFlight = true
                settings.isFirstRun = false
                val progress = MaterialAlertDialogBuilder(this@MumlaActivity)
                    .setTitle(R.string.generateCertProgress)
                    .setView(R.layout.dialog_progress)
                    .setCancelable(false)
                    .create()
                progress.show()
                mGenerateProgressDialog = progress
                val appContext = applicationContext
                container.appScope.launch {
                    val result = generateClientCertificate(appContext)
                    withContext(Dispatchers.Main.immediate) {
                        val dialog = mGenerateProgressDialog
                        mGenerateProgressDialog = null
                        if (!isFinishing && !isDestroyed) {
                            dialog?.dismiss()
                        }
                        mFirstRunGenerateInFlight = false
                        if (result != null) settings.defaultCertificateId = result.id
                        if (result == null && !isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this@MumlaActivity,
                                R.string.generateCertFailure,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        // The news dialog shows from this activity's window token; skip
                        // it if the activity died while generation was in flight —
                        // and hand StartupAction to the recreated instance via
                        // the pending flag instead of dropping it silently.
                        if (!isFinishing && !isDestroyed) {
                            StartupAction().execute(this@MumlaActivity)
                        } else {
                            pendingFirstRunStartup = true
                        }
                    }
                }
            }
            // The dismiss listener below owns the flag-clear/StartupAction path,
            // so every dismissal (button, back-press, outside-tap, onPause)
            // converges there instead of each path duplicating it (ODD-06).
            // A null button listener keeps the default auto-dismiss behavior.
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                // Stale reference must not survive: onPause's
                // dismissFirstRunDialog() would otherwise re-dismiss a dead
                // dialog (safe no-op, but misleading).
                mFirstRunDialog = null
                // The generate path owns StartupAction via onPostExecute.
                if (mFirstRunGenerateInFlight) return@setOnDismissListener
                settings.isFirstRun = false
                // Never run startup UI off a pausing/dying instance: onPause
                // dismisses this dialog on rotation, backgrounding, and finish
                // (round-2 D1). The flag is still cleared so no re-prompt loop.
                if (isFinishing || isDestroyed || isChangingConfigurations) return@setOnDismissListener
                StartupAction().execute(this@MumlaActivity)
            }
        mFirstRunDialog = builder.create()
        // NPE-exact: the original dereferenced mFirstRunDialog unconditionally.
        val firstRunDialog = mFirstRunDialog ?: throw NullPointerException("mFirstRunDialog")
        firstRunDialog.show()
    }

    /**
     * Loads a fragment from the drawer.
     */
    private fun loadDrawerFragment(fragmentId: Int) {
        val args = Bundle()
        val fragmentClass: Class<out Fragment> = when (fragmentId) {
            DrawerAdapter.ITEM_SERVER -> ChannelFragment::class.java
            DrawerAdapter.ITEM_INFO -> ServerInfoFragment::class.java
            DrawerAdapter.ITEM_ACCESS_TOKENS -> {
                val connected = viewModel.connection.value as? ConnectionUi.Connected
                if (connected == null) {
                    FavouriteServerListFragment::class.java
                } else {
                    // The screen's ViewModel loads the tokens itself.
                    args.putLong(MumlaViewModelFactory.ARG_SERVER, connected.target.server.serverId)
                    AccessTokenFragment::class.java
                }
            }
            DrawerAdapter.ITEM_PINNED_CHANNELS -> {
                args.putBoolean("pinned", true)
                ChannelFragment::class.java
            }
            DrawerAdapter.ITEM_FAVOURITES -> FavouriteServerListFragment::class.java
            DrawerAdapter.ITEM_SETTINGS -> {
                val prefIntent = Intent(this, SettingsActivity::class.java)
                startActivity(prefIntent)
                return
            }
            else -> {
                // Unknown id (e.g. a stale EXTRA_DRAWER_FRAGMENT): show
                // favourites instead of leaving the content frame empty.
                // Loud in debug only for internal misuse: the id space
                // mixes internal headers (never valid content) with truly
                // arbitrary intent extras, and only the former is ours.
                if (BuildConfig.DEBUG && (fragmentId == DrawerAdapter.HEADER_CONNECTED_SERVER ||
                        fragmentId == DrawerAdapter.HEADER_SERVERS ||
                        fragmentId == DrawerAdapter.HEADER_GENERAL)
                ) {
                    throw AssertionError("Internal drawer header used as content: $fragmentId")
                }
                Log.w(TAG, "Unknown drawer fragment id $fragmentId; showing favourites")
                FavouriteServerListFragment::class.java
            }
        }
        val fragment = Fragment.instantiate(this, fragmentClass.name, args)
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, fragment, fragmentClass.name)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .commit()
        // NPE-exact: the original dereferenced mDrawerAdapter unconditionally.
        val drawerAdapter = mDrawerAdapter ?: throw NullPointerException("mDrawerAdapter")
        var row = drawerAdapter.getItemWithId(fragmentId)
        // Headers are not content: show the favourites title under
        // favourites content instead of a stale header caption.
        if (row is DrawerAdapter.DrawerHeader) {
            row = drawerAdapter.getItemWithId(DrawerAdapter.ITEM_FAVOURITES)
        }
        if (row != null) {
            val actionBar = supportActionBar
            if (actionBar != null) {
                actionBar.title = row.title
            }
        } else {
            // Unknown id (e.g. a stale EXTRA_DRAWER_FRAGMENT): fall back to
            // the app title so TalkBack still announces a sane screen name.
            val actionBar = supportActionBar
            if (actionBar != null) {
                actionBar.setTitle(R.string.app_name)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BT_ASKED, mPermBluetoothAsked)
        outState.putBoolean(STATE_POST_NOTIF_ASKED, mPermPostNotificationsAsked)
        outState.putParcelable(STATE_SERVER_PENDING, mServerPendingPerm)
    }

    // Connection state rendering.

    private fun renderConnection(connection: ConnectionUi) {
        updateConnectionDialogs()
        val drawerAdapter = mDrawerAdapter
        drawerAdapter?.notifyDataSetChanged()
        supportInvalidateOptionsMenu()

        val previous = mLastRendered
        if (connection is ConnectionUi.Connected && previous !is ConnectionUi.Connected) {
            // A pending switch/retry is moot once connected: the ViewModel
            // clears its own pending target at the Idle it connected from.
            val settings = mSettings ?: throw NullPointerException("mSettings")
            if (settings.shouldStartUpInPinnedMode) {
                loadDrawerFragment(DrawerAdapter.ITEM_PINNED_CHANNELS)
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_SERVER)
            }
            // Swap screens synchronously: commit() alone leaves the server
            // list visible and tappable until the next traversal, while the
            // connecting dialog was dismissed above.
            supportFragmentManager.executePendingTransactions()
            drawerAdapter?.notifyDataSetChanged()
            supportInvalidateOptionsMenu()
        } else if (connection !is ConnectionUi.Connected && previous is ConnectionUi.Connected) {
            // Re-show the server list when the connection ends while a
            // session-only screen is up.
            if (isConnectedOnlyContent()) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
                supportFragmentManager.executePendingTransactions()
            }
        }
        mLastRendered = connection
    }

    /** The fragment currently showing, if it needs a live session. */
    private fun isConnectedOnlyContent(): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.content_frame) ?: return false
        return fragment is ChannelFragment ||
            fragment is ServerInfoFragment ||
            fragment is AccessTokenFragment
    }

    private var mLastRendered: ConnectionUi? = null

    private fun updateConnectionDialogs() {
        val connection = viewModel.connection.value
        val error = viewModel.connectionError.value
        dismissConnectingDialog()
        dismissErrorDialog()
        when {
            connection is ConnectionUi.Connecting -> showConnectingDialog(connection)
            error != null && connection is ConnectionUi.Reconnecting ->
                showReconnectingDialog(connection, error)
            error != null && connection is ConnectionUi.Failed ->
                showFailedDialog(connection, error)
        }
    }

    private fun showConnectingDialog(connection: ConnectionUi.Connecting) {
        mConnectingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.connecting_to_server, connection.host))
            .setView(R.layout.dialog_progress)
            .setCancelable(true)
            .setOnCancelListener {
                viewModel.disconnect()
                Toast.makeText(
                    this@MumlaActivity, R.string.cancelled,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .create()
        // NPE-exact: the original dereferenced mConnectingDialog unconditionally.
        val dialog = mConnectingDialog ?: throw NullPointerException("mConnectingDialog")
        dialog.show()
    }

    private fun showReconnectingDialog(connection: ConnectionUi.Reconnecting, error: se.lublin.humla.client.ClientError) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.connectionRefused))
        builder.setMessage(reconnectMessage(connection, error))
        builder.setPositiveButton(R.string.cancel_reconnect) { _, _ ->
            viewModel.cancelReconnect()
            viewModel.dismissError()
        }
        builder.setCancelable(false)
        mErrorDialog = builder.show()
        startReconnectCountdown(connection, error)
    }

    private fun reconnectMessage(connection: ConnectionUi.Reconnecting, error: se.lublin.humla.client.ClientError): String {
        val remaining = ((connection.retryAtMillis - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
        val cause = error.displayMessage() ?: "unknown"
        return getString(R.string.attempting_reconnect, cause) + "\n" + remaining + " s"
    }

    /** Re-renders the countdown once a second while the dialog is up. */
    private fun startReconnectCountdown(connection: ConnectionUi.Reconnecting, error: se.lublin.humla.client.ClientError) {
        mCountdownJob?.cancel()
        mCountdownJob = lifecycleScope.launch {
            while (true) {
                val dialog = mErrorDialog ?: break
                dialog.setMessage(reconnectMessage(connection, error))
                if (connection.retryAtMillis - System.currentTimeMillis() <= 0) {
                    break
                }
                delay(1000)
            }
        }
    }

    private fun showFailedDialog(connection: ConnectionUi.Failed, error: se.lublin.humla.client.ClientError) {
        val rejected = error as? se.lublin.humla.client.ClientError.Rejected
        val wrongPassword = rejected != null &&
            (rejected.type == Mumble.Reject.RejectType.WrongUserPW.number ||
                rejected.type == Mumble.Reject.RejectType.WrongServerPW.number)
        val builder = MaterialAlertDialogBuilder(this)
        if (wrongPassword) {
            val passwordField = EditText(this)
            passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            passwordField.setHint(R.string.password)
            builder.setTitle(R.string.invalid_password)
            builder.setMessage(error.displayMessage())
            builder.setView(passwordField)
            builder.setPositiveButton(R.string.reconnect) { _, _ ->
                val server = MainViewModel.serverOf(connection.target)
                server.password = passwordField.text.toString()
                viewModel.retryWithPassword(server)
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.dismissError()
            }
        } else {
            builder.setTitle(getString(R.string.connectionRefused))
            builder.setMessage(error.displayMessage() ?: getString(R.string.unknown))
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.dismissError()
            }
        }
        builder.setCancelable(false)
        mErrorDialog = builder.show()
    }

    private fun dismissConnectingDialog() {
        val dialog = mConnectingDialog
        if (dialog != null) {
            dialog.dismiss()
            mConnectingDialog = null
        }
    }

    private fun dismissErrorDialog() {
        mCountdownJob?.cancel()
        mCountdownJob = null
        val dialog = mErrorDialog
        if (dialog != null) {
            dialog.dismiss()
            mErrorDialog = null
        }
    }

    // TLS trust prompts.

    private fun renderTrustPrompt(prompt: TrustPrompt?) {
        if (prompt == null) {
            mShownTrust = null
            dismissCertDialog()
            return
        }
        if (prompt == mShownTrust) {
            return
        }
        mShownTrust = prompt
        when (prompt.kind) {
            TrustPrompt.Kind.HOSTNAME_MISMATCH -> showMismatchDialog(prompt)
            TrustPrompt.Kind.FIRST_TRUST -> showFirstTrustDialog(prompt)
            TrustPrompt.Kind.CHANGED_PIN -> showChangedDialog(prompt)
        }
    }

    /** CA-valid cert for the wrong host: no Allow, no pinning — disconnect only. */
    private fun showMismatchDialog(prompt: TrustPrompt) {
        val claimed = TlsHostnameVerifier.claimedNames(prompt.certificate)
        val names = if (claimed.isEmpty()) getString(R.string.unknown) else TextUtils.join(", ", claimed)
        val layout = layoutInflater.inflate(R.layout.certificate_info, null)
        val textView = layout.findViewById<TextView>(R.id.certificate_info_text)
        textView.text = getString(R.string.certificate_identity_mismatch_body, prompt.host, names) +
            "\n\n" + certificateDetails(prompt.certificate)
        dismissCertDialog()
        mCertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.certificate_identity_mismatch)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> mShownTrust = null }
            .show()
    }

    /** Self-signed/unknown issuer, no pin yet: clarified Allow flow. */
    private fun showFirstTrustDialog(prompt: TrustPrompt) {
        val layout = layoutInflater.inflate(R.layout.certificate_info, null)
        val textView = layout.findViewById<TextView>(R.id.certificate_info_text)
        textView.text = getString(R.string.untrusted_certificate_body, prompt.host) +
            "\n\n" + certificateDetails(prompt.certificate)
        dismissCertDialog()
        mCertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.untrusted_certificate)
            .setView(layout)
            .setPositiveButton(R.string.allow) { _, _ -> pinAndReconnect(prompt) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> mShownTrust = null }
            .show()
    }

    /** Pinned host presenting a different key: explicit Replace with old-vs-new fingerprints. */
    private fun showChangedDialog(prompt: TrustPrompt) {
        val layout = layoutInflater.inflate(R.layout.certificate_info, null)
        val textView = layout.findViewById<TextView>(R.id.certificate_info_text)
        val oldPrint = prompt.pinned?.let { sha256Fingerprint(it) } ?: getString(R.string.unknown)
        textView.text = getString(
            R.string.certificate_changed_body,
            prompt.host,
            oldPrint,
            sha256Fingerprint(prompt.certificate),
        ) + "\n\n" + certificateDetails(prompt.certificate)
        dismissCertDialog()
        mCertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.certificate_changed_title)
            .setView(layout)
            .setPositiveButton(R.string.replace_certificate) { _, _ -> pinAndReconnect(prompt) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> mShownTrust = null }
            .show()
    }

    private fun pinAndReconnect(prompt: TrustPrompt) {
        mShownTrust = null
        if (viewModel.pinAndConnect(prompt)) {
            Toast.makeText(this, R.string.trust_added, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.trust_add_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun certificateDetails(x509: X509Certificate): String {
        try {
            val hexDigest1 = colonHex(MessageDigest.getInstance("SHA-1").digest(x509.encoded))
            val hexDigest2 = colonHex(MessageDigest.getInstance("SHA-256").digest(x509.encoded))

            return getString(
                R.string.certificate_info,
                x509.subjectX500Principal.name,
                x509.notBefore.toString(),
                x509.notAfter.toString(),
                hexDigest1,
                hexDigest2
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Could not fingerprint certificate", e)
            return x509.toString()
        }
    }

    private fun sha256Fingerprint(x509: X509Certificate): String {
        try {
            return colonHex(MessageDigest.getInstance("SHA-256").digest(x509.encoded))
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Could not fingerprint certificate", e)
            return getString(R.string.unknown)
        }
    }

    private fun colonHex(bytes: ByteArray): String {
        val hex = bytesToHex(bytes)
        val out = StringBuilder(hex.length + hex.length / 2)
        var i = 0
        while (i < hex.length) {
            if (i > 0) {
                out.append(':')
            }
            out.append(hex, i, minOf(i + 2, hex.length))
            i += 2
        }
        return out.toString()
    }

    private fun dismissCertDialog() {
        val dialog = mCertDialog
        if (dialog != null) {
            dialog.dismiss()
            mCertDialog = null
        }
    }

    private fun dismissFirstRunDialog() {
        val dialog = mFirstRunDialog
        if (dialog != null) {
            dialog.dismiss()
            mFirstRunDialog = null
        }
    }

    // Permission-denied notice.

    private fun renderPermissionDenied(reason: String?) {
        val dialog = mPermissionDialog
        if (dialog != null) {
            dialog.dismiss()
            mPermissionDialog = null
        }
        if (reason == null) {
            return
        }
        mPermissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_denied)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.dismissPermissionDenied()
            }
            .setOnDismissListener {
                mPermissionDialog = null
            }
            .show()
    }

    // Crash-report prompt.

    private fun renderCrashPrompt(id: String?) {
        if (id == null) {
            dismissCrashSnackbar()
            return
        }
        // Never resurface over a paused activity: the worker recheck can land
        // after onPause dismissed the prompt.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        showCrashSnackbar(id)
    }

    private fun dismissCrashSnackbar() {
        val snackbar = mCrashSnackbar
        if (snackbar != null) {
            snackbar.dismiss()
            mCrashSnackbar = null
        }
    }

    private fun showCrashSnackbar(newestId: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        // Dismiss any orphan before showing: the startup check and the 5s
        // recheck can otherwise overwrite the field and leak the first Snackbar.
        mCrashSnackbar?.dismiss()
        val root = findViewById<View>(android.R.id.content) ?: return
        // Indefinite: this is the only gateway to the report, and a timed
        // prompt is easily missed behind the system crash dialog or startup
        // churn. Swipe or the View action still dismisses it.
        val snackbar = Snackbar.make(root, R.string.crash_prompt, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.crash_view) { openCrashReports() }
        // Single-shot per report: mark seen only once the Snackbar reports
        // shown, so rotation dismisses without re-prompting and
        // swipe/timeout never resurfaces — while a prompt that never
        // reaches the screen is retried next launch instead of being
        // silently consumed.
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onShown(sb: Snackbar?) {
                viewModel.markCrashReportSeen(newestId)
            }

            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                // Swipe/timeout leaves a dismissed instance behind; clear so
                // the onResume re-arm sees no prompt and can reschedule.
                if (mCrashSnackbar?.view == transientBottomBar?.view) {
                    mCrashSnackbar = null
                }
            }
        })
        mCrashSnackbar = snackbar
        snackbar.show()
    }

    private fun openCrashReports() {
        try {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra(
                SettingsActivity.EXTRA_LAUNCH_FRAGMENT,
                CrashReportsFragment::class.java.name,
            )
            startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not open crash reports", e)
        }
    }

    override fun connectToServer(server: Server) {
        mServerPendingPerm = server
        connectToServerWithPerm()
    }

    // InlinedApi: BLUETOOTH_CONNECT (31) and POST_NOTIFICATIONS (33) are
    // referenced below but guarded by SDK_INT; the inlined values are safe
    // on older platforms (unknown permission strings simply check/request
    // as denied/ignored, and the guards prevent ever reaching them).
    @SuppressLint("InlinedApi")
    fun connectToServerWithPerm() {
        if (ContextCompat.checkSelfPermission(
                this@MumlaActivity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this@MumlaActivity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSIONS_REQUEST_RECORD_AUDIO
            )
            return
        }

        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        // BLUETOOTH_CONNECT is an API 31+ runtime permission; below S the
        // legacy BLUETOOTH/BLUETOOTH_ADMIN manifest grants (maxSdk 30) cover
        // SCO routing and no runtime ask exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            settings.isBluetoothHeadset &&
            ContextCompat.checkSelfPermission(
                this@MumlaActivity,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!mPermBluetoothAsked) {
                // First use: ask. shouldShowRequestPermissionRationale is
                // false both before the first ask and after permanent denial,
                // so the asked-flag (not rationale) tells them apart.
                mPermBluetoothAsked = true
                ActivityCompat.requestPermissions(
                    this@MumlaActivity,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    PERMISSIONS_REQUEST_BLUETOOTH_CONNECT
                )
                return
            }
            // Asked before and still denied: revert with an explanation and
            // continue on phone audio instead of dead-ending every connect.
            settings.isBluetoothHeadset = false
            Toast.makeText(
                this@MumlaActivity, getString(R.string.grant_perm_bluetooth),
                Toast.LENGTH_LONG
            ).show()
        }

        // POST_NOTIFICATIONS is API 33+; below TIRAMISU notifications are
        // enabled by default with no runtime grant to ask for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !mPermPostNotificationsAsked) {
            // Mark asked before requesting: a cancelled dialog must not loop.
            mPermPostNotificationsAsked = true
            if (ContextCompat.checkSelfPermission(
                    this@MumlaActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MumlaActivity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSIONS_REQUEST_POST_NOTIFICATIONS
                )
                return
            }
        }

        // NPE-exact: the original logged and returned on null pending server.
        val pending = mServerPendingPerm
        if (pending == null) {
            Log.w(TAG, "No pending server after getting permissions")
            return
        }

        mServerPendingPerm = null
        when (viewModel.connect(pending)) {
            ConnectRequest.STARTED -> Unit
            ConnectRequest.ALREADY_CONNECTED ->
                Toast.makeText(this, R.string.already_connected, Toast.LENGTH_SHORT).show()
            ConnectRequest.CONNECTING_SAME ->
                Toast.makeText(this, R.string.mumlaConnecting, Toast.LENGTH_SHORT).show()
            ConnectRequest.CONNECTING_OTHER ->
                Toast.makeText(this, R.string.already_connecting, Toast.LENGTH_LONG).show()
            ConnectRequest.SWITCH_CONFIRM -> showSwitchDialog(pending)
        }
    }

    private fun showSwitchDialog(server: Server) {
        // Dismiss-before-assign: rapid taps must not orphan a shown
        // dialog by overwriting its only tracked reference.
        val switchDialog = mSwitchDialog
        if (switchDialog != null) {
            switchDialog.dismiss()
            mSwitchDialog = null
        }
        mSwitchDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.reconnect_dialog_message)
            .setPositiveButton(R.string.connect) { _, _ ->
                viewModel.confirmSwitch(server)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NPE-exact: the original dereferenced mSettings unconditionally below.
        val settings = mSettings ?: throw NullPointerException("mSettings")

        if (grantResults.isEmpty()) {
            // Dismissed (not denied) system dialog: resume or explain instead
            // of stranding the pending action.
            if (requestCode == PERMISSIONS_REQUEST_BLUETOOTH_CONNECT) {
                // Connect-flow request only; menu taps persist directly.
                if (mServerPendingPerm != null) {
                    settings.isBluetoothHeadset = false
                    Toast.makeText(
                        this@MumlaActivity, getString(R.string.grant_perm_bluetooth),
                        Toast.LENGTH_LONG
                    ).show()
                    connectToServerWithPerm()
                }
            } else if (requestCode == PERMISSIONS_REQUEST_POST_NOTIFICATIONS) {
                // Cancelled dialog: proceed without notifications instead of
                // stranding the pending connect.
                connectToServerWithPerm()
            } else if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
                Toast.makeText(
                    this@MumlaActivity, getString(R.string.grant_perm_microphone),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        when (requestCode) {
            PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm()
                } else {
                    Toast.makeText(
                        this@MumlaActivity, getString(R.string.grant_perm_microphone),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            PERMISSIONS_REQUEST_BLUETOOTH_CONNECT -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm()
                } else {
                    // Denied after asking: revert so future connects proceed,
                    // then resume this connect on phone audio.
                    settings.isBluetoothHeadset = false
                    Toast.makeText(
                        this@MumlaActivity, getString(R.string.grant_perm_bluetooth),
                        Toast.LENGTH_LONG
                    ).show()
                    connectToServerWithPerm()
                }
            }
            PERMISSIONS_REQUEST_POST_NOTIFICATIONS -> {
                mPermPostNotificationsAsked = true
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    // This is inspired by https://stackoverflow.com/a/34612503
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MumlaActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    ) {
                        Toast.makeText(
                            this@MumlaActivity,
                            getString(R.string.grant_perm_notifications), Toast.LENGTH_LONG
                        ).show()
                    }
                }
                connectToServerWithPerm()
            }
        }
    }

    private fun setStayAwake(stayAwake: Boolean) {
        if (stayAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    override fun isConnected(): Boolean = viewModel.connection.value is ConnectionUi.Connected

    override fun getConnectedServerName(): String? {
        val connection = viewModel.connection.value
        if (connection is ConnectionUi.Connected) {
            val server = connection.target.server
            val name = server.name
            if (name == null || name.isEmpty()) {
                return server.host
            }
            return name
        }
        if (BuildConfig.DEBUG) {
            throw RuntimeException("getConnectedServerName should only be called if connected!")
        }
        return ""
    }

    override fun onServerEdited(action: ServerEditFragment.Action, server: Server) {
        // NPE-exact: the original dereferenced mDatabase unconditionally.
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        when (action) {
            ServerEditFragment.Action.ADD_ACTION -> lifecycleScope.launch {
                database.addServer(server)
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            ServerEditFragment.Action.EDIT_ACTION -> lifecycleScope.launch {
                database.updateServer(server)
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            ServerEditFragment.Action.CONNECT_ACTION -> connectToServer(server)
        }
    }

    companion object {
        private val TAG: String = MumlaActivity::class.java.name

        /**
         * If specified, the provided integer drawer fragment ID is shown when the activity is created.
         */
        const val EXTRA_DRAWER_FRAGMENT = "drawer_fragment"

        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
        private const val PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2
        private const val PERMISSIONS_REQUEST_BLUETOOTH_CONNECT = 3
        private const val STATE_SERVER_PENDING = "server_pending"
        private const val STATE_POST_NOTIF_ASKED = "post_notif_asked"
        private const val STATE_BT_ASKED = "bt_asked"

        // Process-wide pending StartupAction from a dead first-run instance:
        // rotation mid-generate destroys the activity before its task
        // completes, and the dead onPostExecute must skip its window token.
        // It sets this flag instead (main-thread confined on both sides),
        // and the recreated instance consumes it in onCreate/onResume —
        // exactly-once by construction (set only on the skip path, cleared
        // before execute). Without this, the news dialog is silently skipped
        // for that launch.
        private var pendingFirstRunStartup = false

        /**
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun isSameServer(a: Server?, b: Server?): Boolean {
            if (a == null || b == null) {
                return a == b
            }
            // A corrected password must reconnect, not no-op: compare it even
            // when the endpoint identity matches (saved or unsaved). Null and
            // empty both mean "no password" (DB columns are nullable while the
            // edit dialog produces ""), so normalize before comparing.
            var pwA = a.password
            var pwB = b.password
            if (pwA == null) pwA = ""
            if (pwB == null) pwB = ""
            val samePassword = pwA == pwB
            if (a.isSaved() && b.isSaved()) {
                return a.id == b.id && samePassword
            }
            // Fall back to the chat-log identity convention (normalized endpoint + username).
            return samePassword && ChatLogFeature.serverKey(a) == ChatLogFeature.serverKey(b)
        }

        private val HEX_ARRAY = "0123456789abcdef".toCharArray()

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = HEX_ARRAY[v ushr 4]
                hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
            }
            return String(hexChars)
        }
    }
}
