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

import android.Manifest
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
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
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.net.MalformedURLException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.net.HandshakeFailure
import se.lublin.humla.net.TlsHostnameVerifier
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.MumbleURLParser
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.AccessTokenFragment
import se.lublin.mumla.channel.ChannelFragment
import se.lublin.mumla.channel.ServerInfoFragment
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.preference.MumlaCertificateGenerateTask
import se.lublin.mumla.preference.SettingsActivity
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider
import se.lublin.mumla.util.MumlaTrustStore

class MumlaActivity : BaseActivity(), AdapterView.OnItemClickListener,
    FavouriteServerListFragment.ServerConnectHandler, HumlaServiceProvider,
    DrawerAdapter.DrawerDataProvider, ServerEditFragment.ServerEditListener {

    private var mService: IMumlaService? = null
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
    // Switch-server confirmation: tracked like the error/connect dialogs so
    // onPause can dismiss it (its positive button arms a reconnect intent
    // that onPause deliberately drops — see clearReconnectObserver).
    private var mSwitchDialog: AlertDialog? = null
    // Permission-denied notice and back-press disconnect confirmation:
    // tracked for the same pause-dismissal so rotation cannot leak them.
    private var mPermissionDialog: AlertDialog? = null
    private var mBackPressDialog: AlertDialog? = null
    private var mCertDialog: AlertDialog? = null
    // Dedicated host for the first-run certificate guide. It carries an
    // OnDismissListener with first-run side effects that must never fire for
    // the TLS dialogs hosted by mCertDialog above (round-2 D2).
    private var mFirstRunDialog: AlertDialog? = null
    // True while the first-run certificate generation task owns StartupAction.
    private var mFirstRunGenerateInFlight = false

    // The latest one-shot reconnect observer (switch-server and
    // reject-retry flows): unregistered in onDisconnected, or in onPause /
    // onDestroy if the activity dies first, so a stale callback can never
    // drive connectToServer on a destroyed activity with a closed database.
    // The target Server survives separately so a pause-dropped intent can
    // resume on rebind instead of vanishing silently.
    private var mReconnectObserver: HumlaObserver? = null
    private var mPendingReconnectServer: Server? = null

    /** Registers a one-shot reconnect observer, replacing any pending one. */
    private fun setReconnectObserver(observer: HumlaObserver, target: Server) {
        clearReconnectObserver()
        mReconnectObserver = observer
        mPendingReconnectServer = target
        val service = mService
        if (service != null) {
            service.registerObserver(observer)
        }
    }

    /** Drops any pending one-shot reconnect observer without firing it. */
    private fun clearReconnectObserver() {
        dropReconnectObserver()
        mPendingReconnectServer = null
    }

    /**
     * Unregisters the pending observer but keeps the target Server, so a
     * pause-dropped intent can resume on rebind. Use at lifecycle seams
     * (pause/disconnect); use [clearReconnectObserver] when the
     * intent itself is consumed or invalidated.
     */
    private fun dropReconnectObserver() {
        val observer = mReconnectObserver
        if (observer != null) {
            val service = mService
            if (service != null) {
                service.unregisterObserver(observer)
            }
            mReconnectObserver = null
        }
    }

    // Tracks bindService success: the bind uses no BIND_AUTO_CREATE, so no
    // connection exists on a fresh launch before the first connect, and an
    // unconditional unbindService would throw IllegalArgumentException.
    private var mBound: Boolean = false

    /**
     * List of fragments to be notified about service state changes.
     */
    private val mServiceFragments = ArrayList<HumlaServiceFragment>()

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mBound = true
            // CCE-exact: the original cast unconditionally.
            mService = (service as MumlaService.MumlaBinder).getService()
            // NPE-exact: the original dereferenced mService unconditionally below.
            val svc = mService ?: throw NullPointerException("mService")
            svc.setSuppressNotifications(true)
            svc.registerObserver(mObserver)
            // Resume a switch/retry intent dropped by a pause (the observer
            // was cleared, but the target survives): already-connected
            // re-arms and disconnects, otherwise connect directly. Fires at
            // most once — clear first so a second bind cannot double-drive.
            val pending = mPendingReconnectServer
            if (pending != null) {
                clearReconnectObserver()
                if (isFinishing || isDestroyed) {
                    return
                }
                if (svc.isConnected() && isSameServer(svc.getTargetServer(), pending)) {
                    // Already where the intent pointed: nothing to do.
                } else if (svc.isConnected()) {
                    // Still on the old server (disconnect in flight at pause):
                    // re-arm and finish the switch like the original tap.
                    // (A concurrent auto-reconnect to a *different* server is
                    // indistinguishable here; re-arming preserves the explicit
                    // user tap over the silent one — accepted tradeoff.)
                    setReconnectObserver(object : HumlaObserver() {
                        override fun onDisconnected(e: HumlaException?) {
                            clearReconnectObserver()
                            if (isFinishing || isDestroyed) {
                                return
                            }
                            connectToServer(pending)
                        }
                    }, pending)
                    svc.disconnect()
                } else if (svc.getConnectionState() == HumlaService.ConnectionState.CONNECTING) {
                    // In-flight attempt owns the transition: serialize the
                    // pending target behind it like the reject-retry flow.
                    setReconnectObserver(object : HumlaObserver() {
                        override fun onDisconnected(e: HumlaException?) {
                            clearReconnectObserver()
                            if (isFinishing || isDestroyed) {
                                return
                            }
                            connectToServer(pending)
                        }
                    }, pending)
                } else {
                    // Idle or backoff-armed with no live socket: the service
                    // disarms backoff on explicit connect, so go direct
                    // instead of waiting out up to 25s of timer. (Cancel
                    // first so no parallel attempt can race the new one.)
                    svc.cancelReconnect()
                    connectToServer(pending)
                }
            }
            svc.clearChatNotifications() // Clear chat notifications on resume.
            // NPE-exact: the original dereferenced mDrawerAdapter unconditionally.
            val drawerAdapter = mDrawerAdapter ?: throw NullPointerException("mDrawerAdapter")
            drawerAdapter.notifyDataSetChanged()
            // Re-sync volume routing: onCreate ran pre-bind (MUSIC), and a
            // rebound already-active SCO link emits no transition event.
            setVolumeControlStream(
                if (useVoiceCallVolume()) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
            )

            for (fragment in mServiceFragments) {
                fragment.setServiceBound(true)
            }

            // Re-show server list if we're showing a fragment that depends on the service.
            if (supportFragmentManager.findFragmentById(R.id.content_frame) is HumlaServiceFragment &&
                !svc.isConnected()
            ) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            updateConnectionState(getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mBound = false
            // The dead service's callback set is gone; drop the observer
            // but keep the target so rebind can resume the intent.
            dropReconnectObserver()
            mService = null
        }
    }

    private val mObserver = object : HumlaObserver() {
        override fun onConnected() {
            // A pending switch/retry is moot once connected: clear it so a
            // later unrelated drop cannot hijack toward a stale target.
            clearReconnectObserver()
            // NPE-exact: the original dereferenced mSettings unconditionally.
            val settings = mSettings ?: throw NullPointerException("mSettings")
            if (settings.shouldStartUpInPinnedMode) {
                loadDrawerFragment(DrawerAdapter.ITEM_PINNED_CHANNELS)
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_SERVER)
            }
            // Swap screens synchronously: commit() alone leaves the server list visible
            // and tappable until the next traversal, while updateConnectionState() below
            // dismisses the modal connecting dialog first. commit() is the state-loss
            // check, and this observer is unregistered in onPause before
            // onSaveInstanceState on the same Looper, so this adds no new exposure.
            // Drains the whole pending queue in FIFO order, which is benign.
            supportFragmentManager.executePendingTransactions()

            // NPE-exact: the original dereferenced mDrawerAdapter unconditionally.
            val drawerAdapter = mDrawerAdapter ?: throw NullPointerException("mDrawerAdapter")
            drawerAdapter.notifyDataSetChanged()
            supportInvalidateOptionsMenu()

            updateConnectionState(getService())
        }

        override fun onConnecting() {
            updateConnectionState(getService())
        }

        override fun onDisconnected(e: HumlaException?) {
            // Re-show server list if we're showing a fragment that depends on the service.
            if (supportFragmentManager.findFragmentById(R.id.content_frame) is HumlaServiceFragment) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
                // Same synchronous swap as onConnected: the dialog below shows
                // immediately, so don't leave stale channel UI up for a traversal.
                supportFragmentManager.executePendingTransactions()
            }
            // NPE-exact: the original dereferenced mDrawerAdapter unconditionally.
            val drawerAdapter = mDrawerAdapter ?: throw NullPointerException("mDrawerAdapter")
            drawerAdapter.notifyDataSetChanged()
            supportInvalidateOptionsMenu()

            updateConnectionState(getService())
        }

        override fun onBluetoothScoChanged(active: Boolean) {
            // Link transitions refresh what the preference snapshot cannot:
            // volume keys follow the live route. The overflow checkmark
            // mirrors the requested toggle, so this invalidate only gates
            // menu visibility on connect/disconnect.
            setVolumeControlStream(
                if (useVoiceCallVolume()) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
            )
            supportInvalidateOptionsMenu()
        }

        override fun onAudioRouteChanged() {
            // Media-sink transitions change nothing in the preference
            // snapshot and never touch SCO state: volume keys follow the
            // live route instead.
            setVolumeControlStream(
                if (useVoiceCallVolume()) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
            )
            supportInvalidateOptionsMenu()
        }

        override fun onTLSHandshakeFailed(
            chain: Array<X509Certificate>?,
            failure: HandshakeFailure?,
            verifiedHost: String?
        ) {
            if (chain == null || chain.isEmpty() || chain[0] == null) {
                return
            }
            // NPE-exact: the original dereferenced the array element unconditionally.
            val x509 = chain[0] ?: return
            val service = getService()
            if (service == null || service.getTargetServer() == null) {
                return
            }
            // NPE-exact: the original dereferenced getTargetServer() unconditionally.
            val lastServer = service.getTargetServer() ?: return
            // Pins and verification both key on the post-SRV host; the dialog
            // shows that same host so mismatch copy names what was checked.
            var host: String? = verifiedHost ?: lastServer.host
            host = TlsHostnameVerifier.canonicalizeHost(host)
            if (host == null) {
                return
            }
            if (failure == HandshakeFailure.HOSTNAME_MISMATCH) {
                showMismatchDialog(host, x509)
                return
            }
            val pinned = pinnedForHost(host)
            if (failure == HandshakeFailure.PIN_CHANGED || pinned != null) {
                showChangedDialog(lastServer, host, x509, pinned)
                return
            }
            showFirstTrustDialog(lastServer, host, x509)
        }

        /** CA-valid cert for the wrong host: no Allow, no pinning — disconnect only. */
        private fun showMismatchDialog(host: String, x509: X509Certificate) {
            val claimed = TlsHostnameVerifier.claimedNames(x509)
            val names = if (claimed.isEmpty()) getString(R.string.unknown) else TextUtils.join(", ", claimed)
            val layout = layoutInflater.inflate(R.layout.certificate_info, null)
            val textView = layout.findViewById(R.id.certificate_info_text) as TextView
            textView.text = getString(R.string.certificate_identity_mismatch_body, host, names) +
                "\n\n" + certificateDetails(x509)
            dismissCertDialog()
            mCertDialog = MaterialAlertDialogBuilder(this@MumlaActivity)
                .setTitle(R.string.certificate_identity_mismatch)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        /** Self-signed/unknown issuer, no pin yet: clarified Allow flow. */
        private fun showFirstTrustDialog(server: Server, host: String, x509: X509Certificate) {
            val layout = layoutInflater.inflate(R.layout.certificate_info, null)
            val textView = layout.findViewById(R.id.certificate_info_text) as TextView
            textView.text = getString(R.string.untrusted_certificate_body, host) +
                "\n\n" + certificateDetails(x509)
            dismissCertDialog()
            mCertDialog = MaterialAlertDialogBuilder(this@MumlaActivity)
                .setTitle(R.string.untrusted_certificate)
                .setView(layout)
                .setPositiveButton(R.string.allow) { _, _ -> pinAndReconnect(server, host, x509) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Pinned host presenting a different key: explicit Replace with old-vs-new fingerprints. */
        private fun showChangedDialog(
            server: Server,
            host: String,
            presented: X509Certificate,
            pinned: X509Certificate?
        ) {
            val layout = layoutInflater.inflate(R.layout.certificate_info, null)
            val textView = layout.findViewById(R.id.certificate_info_text) as TextView
            val oldPrint = pinned?.let { sha256Fingerprint(it) } ?: getString(R.string.unknown)
            textView.text = getString(
                R.string.certificate_changed_body, host, oldPrint, sha256Fingerprint(presented)
            ) + "\n\n" + certificateDetails(presented)
            dismissCertDialog()
            mCertDialog = MaterialAlertDialogBuilder(this@MumlaActivity)
                .setTitle(R.string.certificate_changed_title)
                .setView(layout)
                .setPositiveButton(R.string.replace_certificate) { _, _ -> pinAndReconnect(server, host, presented) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun pinnedForHost(host: String): X509Certificate? {
            try {
                val trustStore = MumlaTrustStore.getTrustStore(this@MumlaActivity)
                if (trustStore.containsAlias(host)) {
                    val pinned: Certificate? = trustStore.getCertificate(host)
                    if (pinned is X509Certificate) {
                        return pinned
                    }
                }
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Could not read trust store", e)
            } catch (e: IOException) {
                Log.e(TAG, "Could not read trust store", e)
            }
            return null
        }

        private fun pinAndReconnect(server: Server, host: String, x509: X509Certificate) {
            try {
                val trustStore = MumlaTrustStore.getTrustStore(this@MumlaActivity)
                trustStore.setCertificateEntry(host, x509)
                MumlaTrustStore.saveTrustStore(this@MumlaActivity, trustStore)
                Toast.makeText(this@MumlaActivity, R.string.trust_added, Toast.LENGTH_LONG).show()
                connectToServer(server)
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Could not write trust store", e)
                Toast.makeText(this@MumlaActivity, R.string.trust_add_failed, Toast.LENGTH_LONG).show()
            } catch (e: IOException) {
                Log.e(TAG, "Could not write trust store", e)
                Toast.makeText(this@MumlaActivity, R.string.trust_add_failed, Toast.LENGTH_LONG).show()
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
                out.append(hex, i, Math.min(i + 2, hex.length))
                i += 2
            }
            return out.toString()
        }

        override fun onPermissionDenied(reason: String?) {
            val dialog = mPermissionDialog
            if (dialog != null) {
                dialog.dismiss()
                mPermissionDialog = null
            }
            mPermissionDialog = MaterialAlertDialogBuilder(this@MumlaActivity)
                .setTitle(R.string.perm_denied)
                .setMessage(reason)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = Settings.getInstance(this)
        mSettings = settings

        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            mPermBluetoothAsked =
                savedInstanceState.getBoolean(STATE_BT_ASKED, false)
            mPermPostNotificationsAsked =
                savedInstanceState.getBoolean(STATE_POST_NOTIF_ASKED, false)
            mServerPendingPerm =
                savedInstanceState.getParcelable(STATE_SERVER_PENDING, Server::class.java)
            mPendingReconnectServer =
                savedInstanceState.getParcelable(STATE_RECONNECT_TARGET, Server::class.java)
        }
        setContentView(R.layout.activity_main)

        val toolbar = findViewById(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val svc = mService
                if (svc != null && svc.isConnected()) {
                    val current = svc.getTargetServer()
                    var currentName = current?.getName()
                    if (currentName == null) currentName = ""
                    val backPressDialog = mBackPressDialog
                    if (backPressDialog != null) {
                        backPressDialog.dismiss()
                        mBackPressDialog = null
                    }
                    mBackPressDialog = MaterialAlertDialogBuilder(this@MumlaActivity)
                        .setMessage(getString(R.string.disconnectSure, currentName))
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            val confirmSvc = mService
                            if (confirmSvc != null) {
                                confirmSvc.disconnect()
                            }
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

        mDatabase = MumlaRoomDatabase.getInstance(this) // TODO add support for cloud storage
        // NPE-exact: the original dereferenced mDatabase unconditionally below.
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        database.open()

        mDrawerLayout = findViewById(R.id.drawer_layout) as DrawerLayout
        val drawerList = findViewById(R.id.left_drawer) as ListView

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
                // Prevent push to talk from getting stuck on when the drawer is opened.
                val service = getService()
                if (service != null && service.isConnected()) {
                    val session = service.HumlaSession()
                    if (session.isTalking() && !settings.isPushToTalkToggle) {
                        session.setTalkingState(false)
                    }
                }
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

        setVolumeControlStream(
            if (useVoiceCallVolume()) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
        )

        if (savedInstanceState == null) {
            // Got no instance bundle: this is run only on real app startup -- not when Android
            // recreates the activity on configuration change, like screen rotation.
            if (settings.isFirstRun) {
                showFirstRunGuide()
            } else {
                StartupAction().execute(this)
            }
        } else if (pendingFirstRunStartup) {
            // Recreation while first-run generation was in flight on the dead
            // instance: its onPostExecute hands StartupAction here instead of
            // its dead window token (consume before execute: exactly-once).
            pendingFirstRunStartup = false
            StartupAction().execute(this)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // NPE-exact: the original dereferenced mDrawerToggle unconditionally.
        val drawerToggle = mDrawerToggle ?: throw NullPointerException("mDrawerToggle")
        drawerToggle.syncState()
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
        val connectIntent = Intent(this, MumlaService::class.java)
        try {
            mBound = bindService(connectIntent, mConnection, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Service bind failed", e)
            mBound = false
        }
    }

    override fun onPause() {
        super.onPause()
        val errorDialog = mErrorDialog
        if (errorDialog != null) {
            errorDialog.dismiss()
            mErrorDialog = null
        }
        val connectingDialog = mConnectingDialog
        if (connectingDialog != null) {
            connectingDialog.dismiss()
            mConnectingDialog = null
        }
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

        val service = mService
        if (service != null) {
            service.onTalkKeyCancel()
            for (fragment in mServiceFragments) {
                fragment.setServiceBound(false)
            }
            service.unregisterObserver(mObserver)
            service.setSuppressNotifications(false)
        }
        // A pending switch-server/retry observer must not outlive the
        // activity: its onDisconnected would drive connectToServer (and the
        // database) on a dead instance. The target survives for resume.
        dropReconnectObserver()
        if (mBound) {
            try {
                unbindService(mConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service already unbound", e)
            }
            mBound = false
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
        clearReconnectObserver()
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
        val service = mService
        disconnectButton.setVisible(service != null && service.isConnected())

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
            val service = getService()
            if (service != null) {
                service.disconnect()
            }
            return true
        }
        return false
    }

    /**
     * Volume keys follow output routing: voice-call stream on voice routes
     * (the AEC path), music stream on media routes (A2DP volume). Pre-bind
     * default is voice (matches the no-sink route); the route push corrects
     * it right after bind when a media sink is present.
     */
    private fun useVoiceCallVolume(): Boolean {
        val service = mService
        return service == null || !service.isMediaRouteActive()
    }

    /**
     * Asserts Bluetooth headset state. Absolute, not a toggle; writes the
     * requested preference unconditionally so the overflow item mirrors
     * Settings > Audio. Missing-grant handling lives downstream: the
     * service reverts with a hint. No-op writes return early and never
     * dispatch preference listeners.
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
        val service = mService
        if (service != null && Settings.isPttKeyBound(pttKey, keyCode)) {
            service.onTalkKeyDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val pttKey = settings.pushToTalkKey
        val service = mService
        if (service != null && Settings.isPttKeyBound(pttKey, keyCode)) {
            service.onTalkKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val service = mService
        if (!hasFocus && service != null) {
            service.onTalkKeyCancel()
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
                val generateTask = object : MumlaCertificateGenerateTask(this@MumlaActivity) {
                    override fun onPostExecute(result: DatabaseCertificate?) {
                        super.onPostExecute(result)
                        mFirstRunGenerateInFlight = false
                        if (result != null) settings.defaultCertificateId = result.id
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
                generateTask.execute()
                settings.isFirstRun = false
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
                val bound = getService()
                val connectedServer = bound?.getTargetServer()
                if (connectedServer == null) {
                    FavouriteServerListFragment::class.java
                } else {
                    args.putLong("server", connectedServer.id)
                    // NPE-exact: the original dereferenced mDatabase unconditionally.
                    val database = mDatabase ?: throw NullPointerException("mDatabase")
                    args.putStringArrayList(
                        "access_tokens",
                        ArrayList(database.getAccessTokens(connectedServer.id))
                    )
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
        outState.putParcelable(STATE_RECONNECT_TARGET, mPendingReconnectServer)
    }

    override fun connectToServer(server: Server) {
        // NOTE: stale pending-reconnect invalidation happens at the commit
        // point below (before ServerConnectTask), not here: permission and
        // CONNECTING guards may ignore this tap, and must not destroy an
        // armed retry along the way.
        mServerPendingPerm = server
        connectToServerWithPerm()
    }

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
        if (settings.isBluetoothHeadset &&
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

        if (!mPermPostNotificationsAsked) {
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

        val server = pending
        mServerPendingPerm = null

        val service = mService
        // Already connected: tapping the current server is a no-op, tapping
        // another server offers a switch via the reconnect dialog.
        if (service != null && service.isConnected()) {
            // Tapping the server we're already on is a no-op: reconnecting to it
            // would pointlessly tear down the live session.
            if (isSameServer(service.getTargetServer(), server)) {
                Toast.makeText(this, R.string.already_connected, Toast.LENGTH_SHORT).show()
                return
            }
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
                    // Register an observer to reconnect to the new server once disconnected.
                    val svc = getService()
                    if (svc == null) {
                        return@setPositiveButton
                    }
                    setReconnectObserver(object : HumlaObserver() {
                        override fun onDisconnected(e: HumlaException?) {
                            clearReconnectObserver()
                            if (isFinishing || isDestroyed) {
                                return
                            }
                            connectToServer(server)
                        }
                    }, server)
                    svc.disconnect()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        // Ignore rapid taps while a connection attempt is already in progress;
        // each tap would otherwise spawn a parallel connection attempt.
        // (No stale-intent clear on this path: the tap is dropped, so an
        // armed retry behind the in-flight attempt must survive it.)
        if (service != null && service.getConnectionState() == HumlaService.ConnectionState.CONNECTING) {
            if (isSameServer(service.getTargetServer(), server)) {
                Toast.makeText(this, R.string.mumlaConnecting, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.already_connecting, Toast.LENGTH_LONG).show()
            }
            return
        }

        // Commit point: a fresh explicit intent invalidates any stale
        // pending reconnect so a later rebind cannot chase the old target.
        clearReconnectObserver()
        val connectTask = ServerConnectTask(this)
        connectTask.execute(server)
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

    /**
     * Updates the activity to represent the connection state of the given service.
     * Will show reconnecting dialog if reconnecting, dismiss otherwise, etc.
     * Basically, this service will do catch-up if the activity wasn't bound to receive
     * connection state updates.
     *
     * @param service A bound IHumlaService.
     */
    private fun updateConnectionState(service: IHumlaService?) {
        val connectingDialog = mConnectingDialog
        if (connectingDialog != null) {
            connectingDialog.dismiss()
            mConnectingDialog = null
        }
        val errorDialog = mErrorDialog
        if (errorDialog != null) {
            errorDialog.dismiss()
            mErrorDialog = null
        }

        if (service == null) {
            return
        }

        when (service.getConnectionState()) {
            HumlaService.ConnectionState.CONNECTING -> {
                val server = service.getTargetServer()
                val host = if (server?.host != null) server.host else ""
                // SRV lookup is done later, so we no longer show the port in the connection
                // progress dialog (and only the configured hostname)
                mConnectingDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.connecting_to_server, host))
                    .setView(R.layout.dialog_progress)
                    .setCancelable(true)
                    .setOnCancelListener {
                        service.disconnect()
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
            HumlaService.ConnectionState.CONNECTION_LOST -> {
                // Only bother the user if the error hasn't already been shown.
                // Single capture: the service is perished-checkable once here
                // instead of re-querying getService() at every step.
                val lostSvc = getService()
                if (lostSvc != null && !lostSvc.isErrorShown()) {
                    val builder = MaterialAlertDialogBuilder(this@MumlaActivity)
                    builder.setTitle(getString(R.string.connectionRefused))
                    val error = lostSvc.getConnectionError()
                    if (error != null && lostSvc.isReconnecting()) {
                        builder.setMessage(
                            error.message + "\n\n" +
                                getString(
                                    R.string.attempting_reconnect,
                                    error.cause?.message ?: "unknown"
                                )
                        )
                        builder.setPositiveButton(R.string.cancel_reconnect) { _, _ ->
                            val cancelSvc = getService()
                            if (cancelSvc != null) {
                                cancelSvc.cancelReconnect()
                                cancelSvc.markErrorShown()
                            }
                        }
                    } else if (error != null &&
                        error.reason == HumlaException.HumlaDisconnectReason.REJECT &&
                        error.reject != null &&
                        (error.reject?.type == Mumble.Reject.RejectType.WrongUserPW ||
                            error.reject?.type == Mumble.Reject.RejectType.WrongServerPW)
                    ) {
                        val passwordField = EditText(this)
                        passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        passwordField.setHint(R.string.password)
                        builder.setTitle(R.string.invalid_password)
                        builder.setMessage(error.message)
                        builder.setView(passwordField)
                        builder.setPositiveButton(R.string.reconnect) { _, _ ->
                            val svc = getService()
                            if (svc == null) {
                                return@setPositiveButton
                            }
                            val server1 = svc.getTargetServer()
                            if (server1 == null) {
                                return@setPositiveButton
                            }
                            val password = passwordField.text.toString()
                            server1.password = password
                            // NPE-exact: the original dereferenced mDatabase unconditionally.
                            val database = mDatabase ?: throw NullPointerException("mDatabase")
                            if (server1.isSaved()) {
                                database.updateServer(server1)
                            }
                            if (svc.getConnectionState() == HumlaService.ConnectionState.CONNECTING) {
                                // Serialize the retry behind the in-flight attempt, mirroring
                                // the already-connected flow: reconnect once it tears down.
                                setReconnectObserver(object : HumlaObserver() {
                                    override fun onDisconnected(e: HumlaException?) {
                                        clearReconnectObserver()
                                        if (isFinishing || isDestroyed) {
                                            return
                                        }
                                        connectToServer(server1)
                                    }
                                }, server1)
                                svc.disconnect()
                            } else {
                                connectToServer(server1)
                            }
                        }
                        builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                            val svc = getService()
                            if (svc != null) {
                                svc.markErrorShown()
                            }
                        }
                    } else {
                        val msg = error?.message ?: getString(R.string.unknown)
                        builder.setMessage(msg)
                        builder.setPositiveButton(android.R.string.ok) { _, _ ->
                            val svc = getService()
                            if (svc != null) {
                                svc.markErrorShown()
                            }
                        }
                    }
                    builder.setCancelable(false)
                    mErrorDialog = builder.show()
                }
            }
            HumlaService.ConnectionState.DISCONNECTED,
            HumlaService.ConnectionState.CONNECTED -> {
                // Catch-up only covers transitional states; steady states need no dialog.
            }
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    override fun getService(): IMumlaService? {
        return mService
    }

    override fun addServiceFragment(fragment: HumlaServiceFragment) {
        mServiceFragments.add(fragment)
    }

    override fun removeServiceFragment(fragment: HumlaServiceFragment) {
        mServiceFragments.remove(fragment)
    }

    override fun onSettingChanged(key: String?) {
        super.onSettingChanged(key)
        if (key == null) {
            return
        }
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        when (key) {
            Settings.PREF_STAY_AWAKE ->
                setStayAwake(settings.shouldStayAwake)
            Settings.PREF_HANDSET_MODE, Settings.PREF_BLUETOOTH_HEADSET ->
                setVolumeControlStream(
                    if (useVoiceCallVolume()) {
                        AudioManager.STREAM_VOICE_CALL
                    } else {
                        AudioManager.STREAM_MUSIC
                    }
                )
        }
    }

    override fun isConnected(): Boolean {
        val service = mService
        return service != null && service.isConnected()
    }

    override fun getConnectedServerName(): String? {
        val service = mService
        if (service != null && service.isConnected()) {
            val server = service.getTargetServer()
            var name = server?.getName()
            if (name == null || name.isEmpty()) {
                name = server?.host
            }
            return name ?: ""
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
            ServerEditFragment.Action.ADD_ACTION -> {
                database.addServer(server)
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            ServerEditFragment.Action.EDIT_ACTION -> {
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
        private const val STATE_RECONNECT_TARGET = "reconnect_target"
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
            return samePassword && MumlaService.getServerKey(a) == MumlaService.getServerKey(b)
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
