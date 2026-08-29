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
import org.spongycastle.util.encoders.Hex;

import java.net.MalformedURLException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.Server;
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
import se.lublin.mumla.ui.viewmodel.MumlaMainViewModel;
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
    private MumlaMainViewModel mViewModel;

    private ActionBarDrawerToggle mDrawerToggle;
    private DrawerLayout mDrawerLayout;
    private DrawerAdapter mDrawerAdapter;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2;
    private Server mServerPendingPerm = null;
    private boolean mPermPostNotificationsAsked = false;

    private AlertDialog mConnectingDialog;
    private AlertDialog mErrorDialog;

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

            if (mViewModel != null) {
                mViewModel.onServiceBound(mService);
            }

            if (mDrawerAdapter != null) {
                mDrawerAdapter.notifyDataSetChanged();
            }

            for (HumlaServiceFragment fragment : mServiceFragments)
                fragment.setServiceBound(true);

            updateConnectionState(getService());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mViewModel != null) {
                mViewModel.onServiceUnbound();
            }
            mService = null;
        }
    };

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnected() {
            if (mDrawerAdapter != null) {
                mDrawerAdapter.notifyDataSetChanged();
            }
            supportInvalidateOptionsMenu();
            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (mDrawerAdapter != null) {
                mDrawerAdapter.notifyDataSetChanged();
            }
            supportInvalidateOptionsMenu();
            updateConnectionState(getService());
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            if (chain.length == 0) {
                return;
            }
            final Server lastServer = getService().getTargetServer();
            try {
                final X509Certificate x509 = chain[0];
                View layout = getLayoutInflater().inflate(R.layout.certificate_info, null);
                TextView textView = layout.findViewById(R.id.certificate_info_text);
                try {
                    MessageDigest digest1 = MessageDigest.getInstance("SHA-1");
                    MessageDigest digest2 = MessageDigest.getInstance("SHA-256");
                    String hexDigest1 = new String(Hex.encode(digest1.digest(x509.getEncoded())))
                            .replaceAll("(..)", "$1:");
                    String hexDigest2 = new String(Hex.encode(digest2.digest(x509.getEncoded())))
                            .replaceAll("(..)", "$1:");

                    textView.setText(getString(R.string.certificate_info,
                            x509.getSubjectDN().getName(),
                            x509.getNotBefore().toString(),
                            x509.getNotAfter().toString(),
                            hexDigest1.substring(0, hexDigest1.length() - 1),
                            hexDigest2.substring(0, hexDigest2.length() - 1)));
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                    textView.setText(x509.toString());
                }
                new MaterialAlertDialogBuilder(MumlaActivity.this)
                        .setTitle(R.string.untrusted_certificate)
                        .setView(layout)
                        .setPositiveButton(R.string.allow, (dialog, which) -> {
                            // Try to add to trust store
                            try {
                                String alias = lastServer.getHost();
                                KeyStore trustStore = MumlaTrustStore.getTrustStore(MumlaActivity.this);
                                trustStore.setCertificateEntry(alias, x509);
                                MumlaTrustStore.saveTrustStore(MumlaActivity.this, trustStore);
                                Toast.makeText(MumlaActivity.this, R.string.trust_added, Toast.LENGTH_LONG).show();
                                connectToServer(lastServer);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(MumlaActivity.this, R.string.trust_add_failed, Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } catch (CertificateException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onPermissionDenied(String reason) {
            new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.perm_denied)
                    .setMessage(reason)
                    .show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mSettings = Settings.getInstance(this);

        super.onCreate(savedInstanceState);

        setStayAwake(mSettings.shouldStayAwake());

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        mDatabase = new MumlaSQLiteDatabase(this); // TODO add support for cloud storage
        mDatabase.open();

        mViewModel = new MumlaMainViewModel(mDatabase);

        View composeView = MumlaActivityBridge.setupComposeView(
                this,
                mViewModel,
                this::connectToServer,
                () -> {
                    if (mService != null) {
                        mService.disconnect();
                    }
                },
                BuildConfig.VERSION_NAME
        );
        setContentView(composeView);

        // If we're given a Mumble URL to show, connect to it
        if (getIntent() != null &&
                Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String url = getIntent().getDataString();
            try {
                Server server = MumbleURLParser.parseURL(url);
                connectToServer(server);
            } catch (MalformedURLException e) {
                Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }

        setVolumeControlStream(mSettings.isHandsetMode() ?
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
        if (mDrawerToggle != null) {
            mDrawerToggle.syncState();
        }
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

        if (mService != null) {
            for (HumlaServiceFragment fragment : mServiceFragments) {
                fragment.setServiceBound(false);
            }
            mService.unregisterObserver(mObserver);
            mService.setSuppressNotifications(false);
        }
        if (mViewModel != null) {
            mViewModel.onServiceUnbound();
        }
        unbindService(mConnection);
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
        if (mDrawerToggle != null && mDrawerToggle.onOptionsItemSelected(item))
            return true;
        if (item.getItemId() == R.id.action_disconnect) {
            if (getService() != null) {
                getService().disconnect();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(@NotNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mDrawerToggle != null) {
            mDrawerToggle.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
            mService.onTalkKeyDown();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
            mService.onTalkKeyUp();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mDrawerLayout != null) {
            mDrawerLayout.closeDrawers();
        }
        loadDrawerFragment((int) id);
    }

    private void showFirstRunGuide() {
        // Prompt the user to generate a certificate.
        if (mSettings.isUsingCertificate()) {
            mSettings.setFirstRun(false);
            return;
        }
        String msg = getString(R.string.first_run_generate_certificate);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.first_run_generate_certificate_title)
                .setMessage(msg)
                .setPositiveButton(R.string.generate, (DialogInterface dialog, int which) -> {
                    MumlaCertificateGenerateTask generateTask = new MumlaCertificateGenerateTask(MumlaActivity.this) {
                        @Override
                        protected void onPostExecute(DatabaseCertificate result) {
                            super.onPostExecute(result);
                            if (result != null) mSettings.setDefaultCertificateId(result.getId());
                        }
                    };
                    generateTask.execute();
                    mSettings.setFirstRun(false);
                })
                .show();
    }

    /**
     * Loads a fragment or action from the drawer.
     */
    private void loadDrawerFragment(int fragmentId) {
        if (fragmentId == DrawerAdapter.ITEM_SETTINGS) {
            Intent prefIntent = new Intent(this, SettingsActivity.class);
            startActivity(prefIntent);
        }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !mPermPostNotificationsAsked) {
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

        // Check if we're already connected to a server; if so, inform user.
        if (mService != null && mService.isConnected()) {
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

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0) {
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
                            connectToServer(server1);
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
                setVolumeControlStream(mSettings.isHandsetMode() ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
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
                if (mViewModel != null) {
                    mViewModel.refreshServers();
                }
                break;
            case EDIT_ACTION:
                mDatabase.updateServer(server);
                if (mViewModel != null) {
                    mViewModel.refreshServers();
                }
                break;
            case CONNECT_ACTION:
                connectToServer(server);
                break;
        }
    }
}
