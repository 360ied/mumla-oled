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

package se.lublin.mumla.app;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;

import android.text.TextUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.Server;
import se.lublin.humla.net.HandshakeFailure;
import se.lublin.humla.net.TlsHostnameVerifier;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.MumbleURLParser;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.channel.AccessTokenFragment;
import se.lublin.mumla.channel.ChannelFragment;
import se.lublin.mumla.channel.ServerInfoFragment;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.preference.MumlaCertificateGenerateTask;
import se.lublin.mumla.preference.SettingsActivity;
import se.lublin.mumla.servers.FavouriteServerListFragment;
import se.lublin.mumla.servers.ServerEditFragment;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;
import se.lublin.mumla.util.HumlaServiceProvider;
import se.lublin.mumla.util.MumlaTrustStore;

public class MumlaActivity extends BaseActivity implements ListView.OnItemClickListener,
        FavouriteServerListFragment.ServerConnectHandler, HumlaServiceProvider, DatabaseProvider,
        DrawerAdapter.DrawerDataProvider, ServerEditFragment.ServerEditListener {
    private static final String TAG = MumlaActivity.class.getName();

    /**
     * If specified, the provided integer drawer fragment ID is shown when the activity is created.
     */
    public static final String EXTRA_DRAWER_FRAGMENT = "drawer_fragment";

    private IMumlaService mService;
    private MumlaDatabase mDatabase;
    private Settings mSettings;

    private ActionBarDrawerToggle mDrawerToggle;
    private DrawerLayout mDrawerLayout;
    private DrawerAdapter mDrawerAdapter;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2;
    private static final int PERMISSIONS_REQUEST_BLUETOOTH_CONNECT = 3;
    private Server mServerPendingPerm = null;
    private boolean mPermPostNotificationsAsked = false;
    private boolean mPermBluetoothAsked = false;
    private boolean mBluetoothMenuPendingPerm = false;
    private static final String STATE_SERVER_PENDING = "server_pending";
    private static final String STATE_BT_MENU_PENDING = "bt_menu_pending";
    private static final String STATE_POST_NOTIF_ASKED = "post_notif_asked";
    private static final String STATE_BT_ASKED = "bt_asked";

    private AlertDialog mConnectingDialog;
    private AlertDialog mErrorDialog;
    private AlertDialog mCertDialog;
    // Dedicated host for the first-run certificate guide. It carries an
    // OnDismissListener with first-run side effects that must never fire for
    // the TLS dialogs hosted by mCertDialog above (round-2 D2).
    private AlertDialog mFirstRunDialog;
    // True while the first-run certificate generation task owns StartupAction.
    private boolean mFirstRunGenerateInFlight = false;

    /**
     * List of fragments to be notified about service state changes.
     */
    private final List<HumlaServiceFragment> mServiceFragments = new ArrayList<HumlaServiceFragment>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ((MumlaService.MumlaBinder) service).getService();
            mService.setSuppressNotifications(true);
            mService.registerObserver(mObserver);
            mService.clearChatNotifications(); // Clear chat notifications on resume.
            mDrawerAdapter.notifyDataSetChanged();

            for (HumlaServiceFragment fragment : mServiceFragments)
                fragment.setServiceBound(true);

            // Re-show server list if we're showing a fragment that depends on the service.
            if (getSupportFragmentManager().findFragmentById(R.id.content_frame) instanceof HumlaServiceFragment &&
                    !mService.isConnected()) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            updateConnectionState(getService());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnected() {
            if (mSettings.shouldStartUpInPinnedMode()) {
                loadDrawerFragment(DrawerAdapter.ITEM_PINNED_CHANNELS);
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_SERVER);
            }
            // Swap screens synchronously: commit() alone leaves the server list visible
            // and tappable until the next traversal, while updateConnectionState() below
            // dismisses the modal connecting dialog first. commit() is the state-loss
            // check, and this observer is unregistered in onPause before
            // onSaveInstanceState on the same Looper, so this adds no new exposure.
            // Drains the whole pending queue in FIFO order, which is benign.
            getSupportFragmentManager().executePendingTransactions();

            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(HumlaException e) {
            // Re-show server list if we're showing a fragment that depends on the service.
            if (getSupportFragmentManager().findFragmentById(R.id.content_frame) instanceof HumlaServiceFragment) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                // Same synchronous swap as onConnected: the dialog below shows
                // immediately, so don't leave stale channel UI up for a traversal.
                getSupportFragmentManager().executePendingTransactions();
            }
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onBluetoothScoChanged(boolean active) {
            // Link transitions refresh what the preference snapshot cannot:
            // volume keys and the overflow checkmark follow the live route.
            setVolumeControlStream(useVoiceCallVolume() ?
                    AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
            supportInvalidateOptionsMenu();
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure, String verifiedHost) {
            if (chain == null || chain.length == 0 || chain[0] == null) {
                return;
            }
            if (getService() == null || getService().getTargetServer() == null) {
                return;
            }
            final Server lastServer = getService().getTargetServer();
            final X509Certificate x509 = chain[0];
            // Pins and verification both key on the post-SRV host; the dialog
            // shows that same host so mismatch copy names what was checked.
            String host = verifiedHost != null ? verifiedHost : lastServer.getHost();
            host = TlsHostnameVerifier.canonicalizeHost(host);
            if (host == null) {
                return;
            }
            if (failure == HandshakeFailure.HOSTNAME_MISMATCH) {
                showMismatchDialog(host, x509);
                return;
            }
            X509Certificate pinned = pinnedForHost(host);
            if (failure == HandshakeFailure.PIN_CHANGED || pinned != null) {
                showChangedDialog(lastServer, host, x509, pinned);
                return;
            }
            showFirstTrustDialog(lastServer, host, x509);
        }

        /** CA-valid cert for the wrong host: no Allow, no pinning — disconnect only. */
        private void showMismatchDialog(String host, X509Certificate x509) {
            List<String> claimed = TlsHostnameVerifier.claimedNames(x509);
            String names = claimed.isEmpty() ? getString(R.string.unknown) : TextUtils.join(", ", claimed);
            View layout = getLayoutInflater().inflate(R.layout.certificate_info, null);
            TextView textView = layout.findViewById(R.id.certificate_info_text);
            textView.setText(getString(R.string.certificate_identity_mismatch_body, host, names)
                    + "\n\n" + certificateDetails(x509));
            dismissCertDialog();
            mCertDialog = new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.certificate_identity_mismatch)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        /** Self-signed/unknown issuer, no pin yet: clarified Allow flow. */
        private void showFirstTrustDialog(Server server, String host, X509Certificate x509) {
            View layout = getLayoutInflater().inflate(R.layout.certificate_info, null);
            TextView textView = layout.findViewById(R.id.certificate_info_text);
            textView.setText(getString(R.string.untrusted_certificate_body, host)
                    + "\n\n" + certificateDetails(x509));
            dismissCertDialog();
            mCertDialog = new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.untrusted_certificate)
                    .setView(layout)
                    .setPositiveButton(R.string.allow, (dialog, which) -> pinAndReconnect(server, host, x509))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /** Pinned host presenting a different key: explicit Replace with old-vs-new fingerprints. */
        private void showChangedDialog(Server server, String host, X509Certificate presented, X509Certificate pinned) {
            View layout = getLayoutInflater().inflate(R.layout.certificate_info, null);
            TextView textView = layout.findViewById(R.id.certificate_info_text);
            String oldPrint = pinned != null ? sha256Fingerprint(pinned) : getString(R.string.unknown);
            textView.setText(getString(R.string.certificate_changed_body, host, oldPrint, sha256Fingerprint(presented))
                    + "\n\n" + certificateDetails(presented));
            dismissCertDialog();
            mCertDialog = new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.certificate_changed_title)
                    .setView(layout)
                    .setPositiveButton(R.string.replace_certificate, (dialog, which) -> pinAndReconnect(server, host, presented))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private X509Certificate pinnedForHost(String host) {
            try {
                KeyStore trustStore = MumlaTrustStore.getTrustStore(MumlaActivity.this);
                if (host != null && trustStore.containsAlias(host)) {
                    Certificate pinned = trustStore.getCertificate(host);
                    if (pinned instanceof X509Certificate) {
                        return (X509Certificate) pinned;
                    }
                }
            } catch (CertificateException | IOException | KeyStoreException | NoSuchAlgorithmException e) {
                Log.e(TAG, "Could not read trust store", e);
            }
            return null;
        }

        private void pinAndReconnect(Server server, String host, X509Certificate x509) {
            try {
                KeyStore trustStore = MumlaTrustStore.getTrustStore(MumlaActivity.this);
                trustStore.setCertificateEntry(host, x509);
                MumlaTrustStore.saveTrustStore(MumlaActivity.this, trustStore);
                Toast.makeText(MumlaActivity.this, R.string.trust_added, Toast.LENGTH_LONG).show();
                connectToServer(server);
            } catch (CertificateException | IOException | KeyStoreException | NoSuchAlgorithmException e) {
                Log.e(TAG, "Could not write trust store", e);
                Toast.makeText(MumlaActivity.this, R.string.trust_add_failed, Toast.LENGTH_LONG).show();
            }
        }

        private String certificateDetails(X509Certificate x509) {
            try {
                String hexDigest1 = colonHex(MessageDigest.getInstance("SHA-1").digest(x509.getEncoded()));
                String hexDigest2 = colonHex(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));

                return getString(R.string.certificate_info,
                        x509.getSubjectX500Principal().getName(),
                        x509.getNotBefore().toString(),
                        x509.getNotAfter().toString(),
                        hexDigest1,
                        hexDigest2);
            } catch (NoSuchAlgorithmException | CertificateException e) {
                Log.e(TAG, "Could not fingerprint certificate", e);
                return x509.toString();
            }
        }

        private String sha256Fingerprint(X509Certificate x509) {
            try {
                return colonHex(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));
            } catch (NoSuchAlgorithmException | CertificateException e) {
                Log.e(TAG, "Could not fingerprint certificate", e);
                return getString(R.string.unknown);
            }
        }

        private String colonHex(byte[] bytes) {
            String hex = bytesToHex(bytes);
            StringBuilder out = new StringBuilder(hex.length() + hex.length() / 2);
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) {
                    out.append(':');
                }
                out.append(hex, i, Math.min(i + 2, hex.length()));
            }
            return out.toString();
        }

        @Override
        public void onPermissionDenied(String reason) {
            new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.perm_denied)
                    .setMessage(reason)
                    .show();
        }
    };

    private void dismissCertDialog() {
        if (mCertDialog != null) {
            mCertDialog.dismiss();
            mCertDialog = null;
        }
    }

    private void dismissFirstRunDialog() {
        if (mFirstRunDialog != null) {
            mFirstRunDialog.dismiss();
            mFirstRunDialog = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mSettings = Settings.getInstance(this);

        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mBluetoothMenuPendingPerm =
                    savedInstanceState.getBoolean(STATE_BT_MENU_PENDING, false);
            mPermBluetoothAsked =
                    savedInstanceState.getBoolean(STATE_BT_ASKED, false);
            mPermPostNotificationsAsked =
                    savedInstanceState.getBoolean(STATE_POST_NOTIF_ASKED, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mServerPendingPerm =
                        savedInstanceState.getParcelable(STATE_SERVER_PENDING, Server.class);
            } else {
                mServerPendingPerm = savedInstanceState.getParcelable(STATE_SERVER_PENDING);
            }
        }
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mService != null && mService.isConnected()) {
                    new MaterialAlertDialogBuilder(MumlaActivity.this)
                            .setMessage(getString(R.string.disconnectSure, mService.getTargetServer().getName()))
                            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                mService.disconnect();
                                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        setStayAwake(mSettings.shouldStayAwake());

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        mDatabase = new MumlaSQLiteDatabase(this); // TODO add support for cloud storage
        mDatabase.open();

        mDrawerLayout = findViewById(R.id.drawer_layout);
        ListView mDrawerList = findViewById(R.id.left_drawer);

        View headerView = getLayoutInflater().inflate(R.layout.list_drawer_headerlogo, mDrawerList, false);
        mDrawerList.addHeaderView(headerView, null, false);

        mDrawerList.setOnItemClickListener(this);
        mDrawerAdapter = new DrawerAdapter(this, this);
        mDrawerList.setAdapter(mDrawerAdapter);
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerClosed(View drawerView) {
                supportInvalidateOptionsMenu();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                super.onDrawerStateChanged(newState);
                // Prevent push to talk from getting stuck on when the drawer is opened.
                if (getService() != null && getService().isConnected()) {
                    IHumlaSession session = getService().HumlaSession();
                    if (session.isTalking() && !mSettings.isPushToTalkToggle()) {
                        session.setTalkingState(false);
                    }
                }
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                supportInvalidateOptionsMenu();
            }
        };

        mDrawerLayout.setDrawerListener(mDrawerToggle);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        if (savedInstanceState == null) {
            if (getIntent() != null && getIntent().hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(getIntent().getIntExtra(EXTRA_DRAWER_FRAGMENT,
                        DrawerAdapter.ITEM_FAVOURITES));
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
        }

        // If we're given a Mumble URL to show, open up a server edit fragment. Only on a
        // fresh launch: after recreation the fragment manager restores the dialog itself.
        if (savedInstanceState == null) {
            handleViewIntent(getIntent());
        }

        setVolumeControlStream(useVoiceCallVolume() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);

        if (savedInstanceState == null) {
            // Got no instance bundle: this is run only on real app startup -- not when Android
            // recreates the activity on configuration change, like screen rotation.
            if (mSettings.isFirstRun()) {
                showFirstRunGuide();
            } else {
                new StartupAction().execute(this);
            }
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent connectIntent = new Intent(this, MumlaService.class);
        bindService(connectIntent, mConnection, 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mErrorDialog != null)
            mErrorDialog.dismiss();
        if (mConnectingDialog != null)
            mConnectingDialog.dismiss();
        dismissCertDialog();
        dismissFirstRunDialog();

        if (mService != null) {
            mService.onTalkKeyCancel();
            for (HumlaServiceFragment fragment : mServiceFragments) {
                fragment.setServiceBound(false);
            }
            mService.unregisterObserver(mObserver);
            mService.setSuppressNotifications(false);
        }
        unbindService(mConnection);
    }

    /**
     * If the intent is a Mumble URL view request, opens a server edit dialog prompting the
     * user to connect.
     */
    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        try {
            Server server = MumbleURLParser.parseURL(intent.getDataString());

            // Flag a password embedded in the link so the dialog can warn (not block).
            String urlPassword = server.getPassword();
            boolean hasUrlPassword = urlPassword != null && !urlPassword.isEmpty();
            DialogFragment fragment = ServerEditFragment.createServerEditDialog(
                    server, ServerEditFragment.Action.CONNECT_ACTION, true,
                    hasUrlPassword);
            fragment.show(getSupportFragmentManager(), "url_edit");
        } catch (MalformedURLException e) {
            Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show();
            Log.w(TAG, "Could not parse Mumble URL", e);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop: links opened while the activity is running arrive here.
        setIntent(intent);
        handleViewIntent(intent);
    }

    @Override
    protected void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        mDatabase.close();
        super.onDestroy();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem disconnectButton = menu.findItem(R.id.action_disconnect);
        disconnectButton.setVisible(mService != null && mService.isConnected());

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.mumla, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NotNull MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item))
            return true;
        if (item.getItemId() == R.id.action_disconnect) {
            getService().disconnect();
            return true;
        }
        return false;
    }

    /**
     * Volume keys follow the voice-call stream when the handset path is in
     * use or a SCO link is confirmed up. Confirmed state (not the raw
     * toggle) keeps keys coherent with the actual route, including after a
     * failed bring-up that falls back to phone audio.
     */
    private boolean useVoiceCallVolume() {
        if (mSettings.isHandsetMode()) {
            return true;
        }
        return mService != null && mService.isBluetoothScoActive();
    }

    /**
     * Asserts Bluetooth headset state. Absolute, not a toggle; enabling
     * without the grant defers past the permission result. Explicit retries
     * go through retryBluetoothSco, not same-value preference writes.
     */
    public void setBluetoothHeadset(boolean enabled) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(MumlaActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            mBluetoothMenuPendingPerm = true;
            ActivityCompat.requestPermissions(MumlaActivity.this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSIONS_REQUEST_BLUETOOTH_CONNECT);
            return;
        }
        mSettings.setBluetoothHeadset(enabled);
    }

    @Override
    public void onConfigurationChanged(@NotNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int pttKey = mSettings.getPushToTalkKey();
        if (mService != null && Settings.isPttKeyBound(pttKey, keyCode)) {
            mService.onTalkKeyDown();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int pttKey = mSettings.getPushToTalkKey();
        if (mService != null && Settings.isPttKeyBound(pttKey, keyCode)) {
            mService.onTalkKeyUp();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && mService != null) {
            mService.onTalkKeyCancel();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mDrawerLayout.closeDrawers();
        loadDrawerFragment((int) id);
    }

    private void showFirstRunGuide() {
        // Prompt the user to generate a certificate.
        if (mSettings.isUsingCertificate()) {
            mSettings.setFirstRun(false);
            new StartupAction().execute(this);
            return;
        }
        String msg = getString(R.string.first_run_generate_certificate);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.first_run_generate_certificate_title)
                .setMessage(msg)
                .setPositiveButton(R.string.generate, (DialogInterface dialog, int which) -> {
                    mFirstRunGenerateInFlight = true;
                    MumlaCertificateGenerateTask generateTask = new MumlaCertificateGenerateTask(MumlaActivity.this) {
                        @Override
                        protected void onPostExecute(DatabaseCertificate result) {
                            super.onPostExecute(result);
                            mFirstRunGenerateInFlight = false;
                            if (result != null) mSettings.setDefaultCertificateId(result.getId());
                            // The news dialog shows from this activity's window token; skip
                            // it if the activity died while generation was in flight.
                            if (!isFinishing() && !isDestroyed()) {
                                new StartupAction().execute(MumlaActivity.this);
                            }
                        }
                    };
                    generateTask.execute();
                    mSettings.setFirstRun(false);
                })
                // The dismiss listener below owns the flag-clear/StartupAction path,
                // so every dismissal (button, back-press, outside-tap, onPause)
                // converges there instead of each path duplicating it (ODD-06).
                // A null button listener keeps the default auto-dismiss behavior.
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialogInterface -> {
                    // The generate path owns StartupAction via onPostExecute.
                    if (mFirstRunGenerateInFlight) return;
                    mSettings.setFirstRun(false);
                    // Never run startup UI off a pausing/dying instance: onPause
                    // dismisses this dialog on rotation, backgrounding, and finish
                    // (round-2 D1). The flag is still cleared so no re-prompt loop.
                    if (isFinishing() || isDestroyed() || isChangingConfigurations()) return;
                    new StartupAction().execute(MumlaActivity.this);
                });
        mFirstRunDialog = builder.create();
        mFirstRunDialog.show();
    }

    /**
     * Loads a fragment from the drawer.
     */
    private void loadDrawerFragment(int fragmentId) {
        Class<? extends Fragment> fragmentClass = null;
        Bundle args = new Bundle();
        switch (fragmentId) {
            case DrawerAdapter.ITEM_SERVER:
                fragmentClass = ChannelFragment.class;
                break;
            case DrawerAdapter.ITEM_INFO:
                fragmentClass = ServerInfoFragment.class;
                break;
            case DrawerAdapter.ITEM_ACCESS_TOKENS:
                fragmentClass = AccessTokenFragment.class;
                Server connectedServer = getService().getTargetServer();
                args.putLong("server", connectedServer.getId());
                args.putStringArrayList("access_tokens", (ArrayList<String>) mDatabase.getAccessTokens(connectedServer.getId()));
                break;
            case DrawerAdapter.ITEM_PINNED_CHANNELS:
                fragmentClass = ChannelFragment.class;
                args.putBoolean("pinned", true);
                break;
            case DrawerAdapter.ITEM_FAVOURITES:
                fragmentClass = FavouriteServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_SETTINGS:
                Intent prefIntent = new Intent(this, SettingsActivity.class);
                startActivity(prefIntent);
                return;
            default:
                return;
        }
        Fragment fragment = Fragment.instantiate(this, fragmentClass.getName(), args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, fragment, fragmentClass.getName())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();
        requireNonNull(getSupportActionBar()).setTitle(mDrawerAdapter.getItemWithId(fragmentId).title);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_BT_MENU_PENDING, mBluetoothMenuPendingPerm);
        outState.putBoolean(STATE_BT_ASKED, mPermBluetoothAsked);
        outState.putBoolean(STATE_POST_NOTIF_ASKED, mPermPostNotificationsAsked);
        outState.putParcelable(STATE_SERVER_PENDING, mServerPendingPerm);
    }

    public void connectToServer(final Server server) {
        mServerPendingPerm = server;
        connectToServerWithPerm();
    }

    public void connectToServerWithPerm() {
        if (ContextCompat.checkSelfPermission(MumlaActivity.this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MumlaActivity.this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (mSettings.isBluetoothHeadset()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(MumlaActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            if (!mPermBluetoothAsked) {
                // First use: ask. shouldShowRequestPermissionRationale is
                // false both before the first ask and after permanent denial,
                // so the asked-flag (not rationale) tells them apart.
                mPermBluetoothAsked = true;
                ActivityCompat.requestPermissions(MumlaActivity.this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        PERMISSIONS_REQUEST_BLUETOOTH_CONNECT);
                return;
            }
            // Asked before and still denied: revert with an explanation and
            // continue on phone audio instead of dead-ending every connect.
            mSettings.setBluetoothHeadset(false);
            Toast.makeText(MumlaActivity.this, getString(R.string.grant_perm_bluetooth),
                    Toast.LENGTH_LONG).show();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !mPermPostNotificationsAsked) {
            // Mark asked before requesting: a cancelled dialog must not loop.
            mPermPostNotificationsAsked = true;
            if (ContextCompat.checkSelfPermission(MumlaActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MumlaActivity.this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSIONS_REQUEST_POST_NOTIFICATIONS);
                return;
            }
        }

        if (mServerPendingPerm == null) {
            Log.w(TAG, "No pending server after getting permissions");
            return;
        }

        Server server = mServerPendingPerm;
        mServerPendingPerm = null;

        // Already connected: tapping the current server is a no-op, tapping
        // another server offers a switch via the reconnect dialog.
        if (mService != null && mService.isConnected()) {
            // Tapping the server we're already on is a no-op: reconnecting to it
            // would pointlessly tear down the live session.
            if (isSameServer(mService.getTargetServer(), server)) {
                Toast.makeText(this, R.string.already_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.reconnect_dialog_message)
                    .setPositiveButton(R.string.connect, (dialog, which) -> {
                        // Register an observer to reconnect to the new server once disconnected.
                        mService.registerObserver(new HumlaObserver() {
                            @Override
                            public void onDisconnected(HumlaException e) {
                                connectToServer(server);
                                mService.unregisterObserver(this);
                            }
                        });
                        mService.disconnect();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        // Ignore rapid taps while a connection attempt is already in progress;
        // each tap would otherwise spawn a parallel connection attempt.
        if (mService != null && mService.getConnectionState() == HumlaService.ConnectionState.CONNECTING) {
            if (isSameServer(mService.getTargetServer(), server)) {
                Toast.makeText(this, R.string.mumlaConnecting, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.already_connecting, Toast.LENGTH_LONG).show();
            }
            return;
        }

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
    }

    static boolean isSameServer(Server a, Server b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isSaved() && b.isSaved()) {
            return a.getId() == b.getId();
        }
        // Fall back to the chat-log identity convention (normalized endpoint + username).
        return MumlaService.getServerKey(a).equals(MumlaService.getServerKey(b));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0) {
            if (requestCode == PERMISSIONS_REQUEST_BLUETOOTH_CONNECT) {
                mBluetoothMenuPendingPerm = false;
            } else if (requestCode == PERMISSIONS_REQUEST_POST_NOTIFICATIONS) {
                // Cancelled dialog: proceed without notifications instead of
                // stranding the pending connect.
                connectToServerWithPerm();
            }
            return;
        }

        switch (requestCode) {
            case PERMISSIONS_REQUEST_RECORD_AUDIO:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm();
                } else {
                    Toast.makeText(MumlaActivity.this, getString(R.string.grant_perm_microphone),
                            Toast.LENGTH_LONG).show();
                }
                break;
            case PERMISSIONS_REQUEST_BLUETOOTH_CONNECT:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (mBluetoothMenuPendingPerm) {
                        mBluetoothMenuPendingPerm = false;
                        setBluetoothHeadset(true);
                    } else {
                        connectToServerWithPerm();
                    }
                } else if (mBluetoothMenuPendingPerm) {
                    // Menu-flow denial reverts (nothing was persisted there);
                    // skip the write when already off to avoid a no-op storm.
                    mBluetoothMenuPendingPerm = false;
                    if (mSettings.isBluetoothHeadset()) {
                        setBluetoothHeadset(false);
                    }
                    Toast.makeText(MumlaActivity.this, getString(R.string.grant_perm_bluetooth),
                            Toast.LENGTH_LONG).show();
                } else {
                    // Denied after asking: revert so future connects proceed,
                    // then resume this connect on phone audio.
                    mSettings.setBluetoothHeadset(false);
                    Toast.makeText(MumlaActivity.this, getString(R.string.grant_perm_bluetooth),
                            Toast.LENGTH_LONG).show();
                    connectToServerWithPerm();
                }
                break;
            case PERMISSIONS_REQUEST_POST_NOTIFICATIONS:
                mPermPostNotificationsAsked = true;
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    // This is inspired by https://stackoverflow.com/a/34612503
                    if (ActivityCompat.shouldShowRequestPermissionRationale(MumlaActivity.this,
                            Manifest.permission.POST_NOTIFICATIONS)) {
                        Toast.makeText(MumlaActivity.this,
                                getString(R.string.grant_perm_notifications), Toast.LENGTH_LONG).show();
                    }
                }
                connectToServerWithPerm();
                break;
        }
    }

    private void setStayAwake(boolean stayAwake) {
        if (stayAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
    private void updateConnectionState(IHumlaService service) {
        if (mConnectingDialog != null) {
            mConnectingDialog.dismiss();
        }
        if (mErrorDialog != null)
            mErrorDialog.dismiss();

        if (service == null) {
            return;
        }

        switch (service.getConnectionState()) {
            case CONNECTING:
                Server server = service.getTargetServer();
                String host = (server != null && server.getHost() != null) ? server.getHost() : "";
                // SRV lookup is done later, so we no longer show the port in the connection
                // progress dialog (and only the configured hostname)
                mConnectingDialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.connecting_to_server, host))
                        .setView(R.layout.dialog_progress)
                        .setCancelable(true)
                        .setOnCancelListener(dialog -> {
                            service.disconnect();
                            Toast.makeText(MumlaActivity.this, R.string.cancelled,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .create();
                mConnectingDialog.show();
                break;
            case CONNECTION_LOST:
                // Only bother the user if the error hasn't already been shown.
                if (getService() != null && !getService().isErrorShown()) {
                    // TODO? bail out if service gone -- it is happening!
                    if (getService() == null) {
                        break;
                    }
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MumlaActivity.this);
                    builder.setTitle(getString(R.string.connectionRefused));
                    HumlaException error = getService().getConnectionError();
                    if (error != null && service.isReconnecting()) {
                        builder.setMessage(error.getMessage() + "\n\n"
                                + getString(R.string.attempting_reconnect,
                                error.getCause() != null ? error.getCause().getMessage() : "unknown"));
                        builder.setPositiveButton(R.string.cancel_reconnect, (dialog, which) -> {
                            if (getService() != null) {
                                getService().cancelReconnect();
                                getService().markErrorShown();
                            }
                        });
                    } else if (error != null &&
                            error.getReason() == HumlaException.HumlaDisconnectReason.REJECT &&
                            (error.getReject().getType() == Mumble.Reject.RejectType.WrongUserPW ||
                                    error.getReject().getType() == Mumble.Reject.RejectType.WrongServerPW)) {
                        final EditText passwordField = new EditText(this);
                        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        passwordField.setHint(R.string.password);
                        builder.setTitle(R.string.invalid_password);
                        builder.setMessage(error.getMessage());
                        builder.setView(passwordField);
                        builder.setPositiveButton(R.string.reconnect, (dialog, which) -> {
                            Server server1 = getService().getTargetServer();
                            if (server1 == null) {
                                return;
                            }
                            String password = passwordField.getText().toString();
                            server1.setPassword(password);
                            if (server1.isSaved()) {
                                mDatabase.updateServer(server1);
                            }
                            if (mService != null && mService.getConnectionState() == HumlaService.ConnectionState.CONNECTING) {
                                // Serialize the retry behind the in-flight attempt, mirroring
                                // the already-connected flow: reconnect once it tears down.
                                mService.registerObserver(new HumlaObserver() {
                                    @Override
                                    public void onDisconnected(HumlaException e) {
                                        connectToServer(server1);
                                        mService.unregisterObserver(this);
                                    }
                                });
                                mService.disconnect();
                            } else {
                                connectToServer(server1);
                            }
                        });
                        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                            if (getService() != null) {
                                getService().markErrorShown();
                            }
                        });
                    } else {
                        String msg = error != null ? error.getMessage() : getString(R.string.unknown);
                        builder.setMessage(msg);
                        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            if (getService() != null) {
                                getService().markErrorShown();
                            }
                        });
                    }
                    builder.setCancelable(false);
                    mErrorDialog = builder.show();
                }
                break;
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    @Override
    public IMumlaService getService() {
        return mService;
    }

    @Override
    public MumlaDatabase getDatabase() {
        return mDatabase;
    }

    @Override
    public void addServiceFragment(HumlaServiceFragment fragment) {
        mServiceFragments.add(fragment);
    }

    @Override
    public void removeServiceFragment(HumlaServiceFragment fragment) {
        mServiceFragments.remove(fragment);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        super.onSharedPreferenceChanged(sharedPreferences, key);
        if (key == null) {
            return;
        }
        switch (key) {
            case Settings.PREF_STAY_AWAKE:
                setStayAwake(mSettings.shouldStayAwake());
                break;
            case Settings.PREF_HANDSET_MODE:
            case Settings.PREF_BLUETOOTH_HEADSET:
                setVolumeControlStream(useVoiceCallVolume() ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
        }
    }

    @Override
    public boolean isConnected() {
        return mService != null && mService.isConnected();
    }

    @Override
    public String getConnectedServerName() {
        if (mService != null && mService.isConnected()) {
            Server server = mService.getTargetServer();
            return server.getName().isEmpty() ? server.getHost() : server.getName();
        }
        if (BuildConfig.DEBUG)
            throw new RuntimeException("getConnectedServerName should only be called if connected!");
        return "";
    }

    @Override
    public void onServerEdited(ServerEditFragment.Action action, Server server) {
        switch (action) {
            case ADD_ACTION:
                mDatabase.addServer(server);
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                break;
            case EDIT_ACTION:
                mDatabase.updateServer(server);
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                break;
            case CONNECT_ACTION:
                connectToServer(server);
                break;
        }
    }

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
