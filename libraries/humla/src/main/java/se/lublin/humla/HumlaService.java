/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties;
import org.minidns.hla.ResolverApi;

import java.io.File;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.audio.BluetoothScoManager;
import se.lublin.humla.audio.inputmode.ActivityInputMode;
import se.lublin.humla.audio.inputmode.ContinuousInputMode;
import se.lublin.humla.audio.inputmode.IInputMode;
import se.lublin.humla.audio.inputmode.ToggleInputMode;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.exception.NotConnectedException;
import se.lublin.humla.exception.NotSynchronizedException;
import se.lublin.humla.model.Channel;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.ServerSettings;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;
import se.lublin.humla.model.WhisperTarget;
import se.lublin.humla.model.WhisperTargetList;
import se.lublin.humla.net.HandshakeFailure;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaTCPMessageType;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protocol.AudioHandler;
import se.lublin.humla.protocol.ModelHandler;
import se.lublin.humla.util.HumlaCallbacks;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;

public class HumlaService extends Service implements IHumlaService, IHumlaSession, HumlaConnection.HumlaConnectionListener, HumlaLogger {
    private static final String TAG = HumlaService.class.getName();

    /**
     * An action to immediately connect to a given Mumble server.
     * Requires that {@link #EXTRAS_SERVER} is provided.
     */
    public static final String ACTION_CONNECT = "se.lublin.humla.CONNECT";

    /** A {@link Server} specifying the server to connect to. */
    public static final String EXTRAS_SERVER = "server";
    /** @deprecated Auto-reconnect is now unconditionally enabled. */
    @Deprecated
    public static final String EXTRAS_AUTO_RECONNECT = "auto_reconnect";
    /** @deprecated Auto-reconnect uses dynamic exponential backoff. */
    @Deprecated
    public static final String EXTRAS_AUTO_RECONNECT_DELAY = "auto_reconnect_delay";
    public static final String EXTRAS_CERTIFICATE = "certificate";
    public static final String EXTRAS_CERTIFICATE_PASSWORD = "certificate_password";
    public static final String EXTRAS_DETECTION_THRESHOLD = "detection_threshold";
    public static final String EXTRAS_AMPLITUDE_BOOST = "amplitude_boost";
    public static final String EXTRAS_TRANSMIT_MODE = "transmit_mode";
    public static final String EXTRAS_INPUT_RATE = "input_frequency";
    public static final String EXTRAS_INPUT_QUALITY = "input_quality";
    /** @deprecated Obsolete since 0.18.2; Opus is mandatory and legacy codecs are dropped. */
    @Deprecated
    public static final String EXTRAS_USE_OPUS = "use_opus";
    public static final String EXTRAS_FORCE_TCP = "force_tcp";
    public static final String EXTRAS_CLIENT_NAME = "client_name";
    public static final String EXTRAS_ACCESS_TOKENS = "access_tokens";
    public static final String EXTRAS_AUDIO_SOURCE = "audio_source";
    public static final String EXTRAS_AUDIO_STREAM = "audio_stream";
    public static final String EXTRAS_FRAMES_PER_PACKET = "frames_per_packet";
    /** An optional path to a trust store for CA certificates. */
    public static final String EXTRAS_TRUST_STORE = "trust_store";
    /** The trust store's password. */
    public static final String EXTRAS_TRUST_STORE_PASSWORD = "trust_store_password";
    /** The trust store's format. */
    public static final String EXTRAS_TRUST_STORE_FORMAT = "trust_store_format";
    public static final String EXTRAS_HALF_DUPLEX = "half_duplex";
    /** A list of users that should be local muted upon connection. */
    public static final String EXTRAS_LOCAL_MUTE_HISTORY = "local_mute_history";
    /** A list of users that should be local ignored upon connection. */
    public static final String EXTRAS_LOCAL_IGNORE_HISTORY = "local_ignore_history";
    public static final String EXTRAS_ADAPTIVE_LEVELER = "adaptive_leveler";
    /** Request Bluetooth SCO headset routing for this connection. */
    public static final String EXTRAS_BLUETOOTH_SCO = "bluetooth_sco";

    // Service settings
    private Server mServer;
    private byte[] mCertificate;
    private String mCertificatePassword;
    private boolean mForceTcp;
    private String mClientName;
    private List<String> mAccessTokens;
    private String mTrustStore;
    private String mTrustStorePassword;
    private String mTrustStoreFormat;
    private List<Integer> mLocalMuteHistory;
    private List<Integer> mLocalIgnoreHistory;
    private AudioHandler.Builder mAudioBuilder;
    private int mTransmitMode;
    private boolean mHalfDuplex;
    // Volatile: written in configureExtras, read across service threads in
    // updateBluetoothScoRoute, retryBluetoothSco, and the route callbacks.
    // (mHalfDuplex stays plain: it is only ever touched on the main thread.)
    private volatile boolean mScoRequested;
    private BluetoothScoManager mScoManager;
    private boolean mScoRouteActive;

    private byte mVoiceTargetId;
    private WhisperTargetList mWhisperTargetList;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    private Handler mHandler;
    private HumlaCallbacks mCallbacks;

    private HumlaConnection mConnection;
    private volatile ConnectionState mConnectionState;
    private ModelHandler mModelHandler;
    private AudioHandler mAudioHandler;

    private ActivityInputMode mActivityInputMode;
    private ToggleInputMode mToggleInputMode;
    private ContinuousInputMode mContinuousInputMode;

    private boolean mReconnecting;
    private int mReconnectAttempts = 0;
    private Runnable mReconnectRunnable;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private boolean mNetworkCallbackRegistered = false;

    private final AudioHandler.AudioEncodeListener mAudioInputListener =
            new AudioHandler.AudioEncodeListener() {
                @Override
                public void onAudioEncoded(byte[] data, int length) {
                    if(mConnection != null && mConnection.isSynchronized()) {
                        mConnection.sendUDPMessage(data, length, false);
                    }
                }

                @Override
                public void onTalkingStateChanged(final boolean talking) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                // If the server session is inactive, ignore this message.
                                // It's likely that this is leftover from a terminated connection.
                                if (!isSynchronized())
                                    return;

                                if (mModelHandler == null || mConnection == null) {
                                    return;
                                }
                                final User currentUser = mModelHandler.getUser(mConnection.getSession());
                                if (currentUser == null) return;

                                currentUser.setTalkState(talking ? TalkState.TALKING : TalkState.PASSIVE);
                                mCallbacks.onUserTalkStateUpdated(currentUser);
                            } catch (NotSynchronizedException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            };

    private AudioOutput.AudioOutputListener mAudioOutputListener = new AudioOutput.AudioOutputListener() {
        @Override
        public void onUserTalkStateUpdated(final User user) {
            mCallbacks.onUserTalkStateUpdated(user);
        }

        @Override
        public User getUser(int session) {
            if (mModelHandler != null) {
                return mModelHandler.getUser(session);
            }
            return null;
        }
    };

    /**
     * Stops the service for a start request that was ignored, unless a
     * connection attempt or established connection is in progress.
     */
    private void stopSelfIfIdle(int startId) {
        if (mConnectionState == ConnectionState.DISCONNECTED) {
            stopSelf(startId);
        }
    }

    /**
     * Acquires the high-performance WiFi lock, keeping the radio out of power-save
     * while the connection (including auto-reconnect gaps) is alive. No-op when the
     * lock could not be created. Mirrors the mWakeLock lifecycle.
     */
    private void acquireWifiLock() {
        try {
            if (mWifiLock != null && !mWifiLock.isHeld()) {
                mWifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WifiLock.", e);
        }
    }

    /**
     * Releases the WiFi lock on final teardown so the radio may sleep again.
     * Never called across auto-reconnect retries: the radio must stay up while
     * redialing or the handshake itself stalls past the server timeout.
     */
    private void releaseWifiLock() {
        try {
            if (mWifiLock != null && mWifiLock.isHeld()) {
                mWifiLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not release WifiLock.", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            boolean isConnect = ACTION_CONNECT.equals(intent.getAction());
            Bundle extras = intent.getExtras();
            if (isConnect && (extras == null || !extras.containsKey(EXTRAS_SERVER))) {
                // Malformed CONNECT: log and ignore. Never throw from
                // onStartCommand (crash primitive) and never proceed to
                // configureExtras/connect() on the malformed intent.
                Log.w(TAG, ACTION_CONNECT + " requires a server provided in extras; ignoring.");
                stopSelfIfIdle(startId);
                return START_NOT_STICKY;
            }
            if (isConnect && mConnectionState == ConnectionState.CONNECTING) {
                // Ignore duplicate connect requests while a connection attempt is already
                // in progress; each would otherwise spawn a parallel connection. Guarded
                // here (before configureExtras) so the in-flight attempt's target server
                // isn't overwritten by the duplicate request.
                Log.v(TAG, "Ignoring duplicate connect request while already connecting");
                return START_NOT_STICKY;
            }
            if (extras != null) {
                try {
                    configureExtras(extras);
                } catch (AudioException e) {
                    Log.e(TAG, "Attempted to initialize audio in onStartCommand erroneously.", e);
                    stopSelfIfIdle(startId);
                    return START_NOT_STICKY;
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Ignoring invalid service extras.", e);
                    stopSelfIfIdle(startId);
                    return START_NOT_STICKY;
                }
            }

            if (isConnect) {
                // A user-initiated connect supersedes any scheduled automatic reconnect.
                // Disarm it so a stale retry can't orphan this attempt once it succeeds.
                setReconnecting(false);
                connect();
            }
        }

        return START_NOT_STICKY;
    }

    public static void init(Context context) {
        try {
            Context appContext = context != null ? context.getApplicationContext() : null;
            if (appContext != null) {
                AndroidUsingLinkProperties.setup(appContext);
            }
            ResolverApi.INSTANCE.getClient().getDataSource().setTimeout(800);
        } catch (Exception e) {
            Log.w(TAG, "Could not configure MiniDNS: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService");
        mWakeLock.setReferenceCounted(false);

        // Doze shield: keep the WiFi radio out of firmware power-save for the
        // lifetime of the connection. Without a WifiLock (dumpsys wifi showed
        // zero locks acquired), on battery + screen-off the radio slept between
        // Doze maintenance windows, stalling keepalives past Murmur's 30 s
        // timeout. Created here, acquired in connect(), released on final
        // teardown. Uses the application context so the lock outlives config
        // changes; reference-counted false to mirror mWakeLock semantics.
        // WIFI_MODE_FULL_HIGH_PERF (not FULL_LOW_LATENCY): LOW_LATENCY needs
        // API 30+ while this library targets API 21+, and HIGH_PERF is the
        // documented VoIP-compatible mode available on all supported devices.
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                mWifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Humla:HumlaServiceWifi");
                mWifiLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create WifiLock; radio may sleep on battery.", e);
            mWifiLock = null;
        }

        mHandler = new Handler(getMainLooper());
        mCallbacks = new HumlaCallbacks();
        mAudioBuilder = new AudioHandler.Builder()
                .setContext(this)
                .setLogger(this)
                .setEncodeListener(mAudioInputListener)
                .setTalkingListener(mAudioOutputListener)
                .setFramesPerPacketListener(fpp -> {
                    // Bandwidth-degraded effective packet size: rescale the UDP send queue
                    // to preserve the ~200ms latency ceiling (ODD-09). The connection is
                    // resolved at call time; it may be gone on a disconnect race.
                    HumlaConnection connection = mConnection;
                    if (connection != null) {
                        connection.setTargetFramesPerPacket(fpp);
                    }
                });
        mConnectionState = ConnectionState.DISCONNECTED;
        mScoManager = new BluetoothScoManager(this, new BluetoothScoManager.Listener() {
            @Override
            public void onScoConnected() {
                applyScoRoute(true, "Bluetooth SCO connected");
            }

            @Override
            public void onScoDisconnected() {
                applyScoRoute(false, "Bluetooth SCO disconnected, falling back to phone audio");
            }

            @Override
            public void onScoFailed(String reason) {
                applyScoRoute(false, "Bluetooth SCO unavailable, falling back to phone audio");
                // onScoFailed is posted to the main thread; connected and
                // disconnected arrive on main-thread platform callbacks.
                Toast.makeText(HumlaService.this,
                        BluetoothScoManager.REASON_PERMISSION.equals(reason)
                                ? R.string.bluetooth_sco_permission
                                : R.string.bluetooth_sco_failed,
                        Toast.LENGTH_LONG).show();
            }
        });
        mToggleInputMode = new ToggleInputMode();
        mActivityInputMode = new ActivityInputMode(ActivityInputMode.DEFAULT_VAD_MAX);
        mContinuousInputMode = new ContinuousInputMode();
        mWhisperTargetList = new WhisperTargetList();

        // initialize minidns dns lookup mechanisms
        init(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mReconnectAttempts = 0;
        setReconnecting(false);
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        // Bare teardown, no notify: observers detach in onDestroy and posted
        // UI work (SoundPool recreate, proximity lock) would revive audio on
        // a dead service.
        if (mScoManager != null) {
            mScoManager.stop();
        }
        mAudioBuilder.setScoEnabled(false);
        mScoRouteActive = false;
        releaseWifiLock();
    }

    public IBinder onBind(Intent intent) {
        return new HumlaBinder(this);
    }

    protected void connect() {
        if (mServer == null) {
            Log.w(TAG, "connect() called with null mServer, cancelling reconnect");
            setReconnecting(false);
            return;
        }

        try {
            mConnectionState = ConnectionState.DISCONNECTED;
            mVoiceTargetId = 0;
            mWhisperTargetList.clear();

            if (mWakeLock != null) {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                mWakeLock.acquire(15000);
            }
            acquireWifiLock();

            mConnection = new HumlaConnection(this);
            mConnection.setTargetFramesPerPacket(mAudioBuilder.getTargetFramesPerPacket());
            mConnection.setForceTCP(mForceTcp);
            mConnection.setKeys(mCertificate, mCertificatePassword);
            mConnection.setTrustStore(mTrustStore, mTrustStorePassword, mTrustStoreFormat);

            mModelHandler = new ModelHandler(this, mCallbacks, this,
                    mLocalMuteHistory, mLocalIgnoreHistory);
            mConnection.addTCPMessageHandlers(mModelHandler);

            mConnectionState = ConnectionState.CONNECTING;

            mCallbacks.onConnecting();

            mConnection.connect(mServer.getHost(), mServer.getPort());
        } catch (HumlaException e) {
            e.printStackTrace();
            onConnectionDisconnected(e);
        }
    }

    public void disconnect() {
        mReconnectAttempts = 0;
        setReconnecting(false);
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        releaseWifiLock();
        // Tear down SCO synchronously: the async disconnect callback may lag,
        // and a quick reconnect must not reuse a stale link or route flag.
        stopScoWithNotify();
        if (mConnection != null) {
            mConnection.disconnect();
        }
    }

    @Override
    public boolean isBluetoothScoActive() {
        return mScoManager != null && mScoManager.isActive();
    }

    @Override
    public boolean retryBluetoothSco() {
        if (mScoRequested && mConnectionState == ConnectionState.CONNECTED
                && mScoManager != null && mScoManager.isIdle()) {
            mScoManager.start();
            return true;
        }
        return false;
    }

    public boolean isConnectionEstablished() {
        return mConnection != null && mConnection.isConnected();
    }

    /**
     * @return true if Humla has received the ServerSync message, indicating synchronization with
     * the server's model and settings. This is the main state of the service.
     */
    public boolean isSynchronized() {
        return mConnection != null && mConnection.isSynchronized();
    }

    @Override
    public void onConnectionEstablished() {
        if (mServer != null && mConnection != null) {
            mServer.setResolved(mConnection.getHost(), mConnection.getPort());
        }

        // Send version information and authenticate.
        final Mumble.Version.Builder version = Constants.createVersionMessage(mClientName);

        final Mumble.Authenticate.Builder auth = Mumble.Authenticate.newBuilder();
        auth.setUsername(mServer.getUsername());
        auth.setPassword(mServer.getPassword());
        auth.setOpus(true);
        auth.addAllTokens(mAccessTokens);

        mConnection.sendTCPMessage(version.build(), HumlaTCPMessageType.Version);
        mConnection.sendTCPMessage(auth.build(), HumlaTCPMessageType.Authenticate);
    }

    @Override
    public void onConnectionSynchronized() {
        // early disconnected?
        if (!mConnection.isConnected()) {
            return;
        }

        // TODO hackish, but this seems to happen?!
        if (mModelHandler == null) {
            Log.e(TAG, "onConnectionSynchronized: mModelHandler is null");
            return;
        }

        mReconnectAttempts = 0;
        setReconnecting(false);
        mConnectionState = ConnectionState.CONNECTED;

        Log.v(TAG, "Connected");
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            mWakeLock.acquire();
        }

        try {
            createAudioHandler();
        } catch (AudioException e) {
            e.printStackTrace();
            onConnectionWarning(e.getMessage());
        }
        updateBluetoothScoRoute();

        mCallbacks.onConnected();
    }

    @Override
    public void onConnectionHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure, String verifiedHost) {
        mCallbacks.onTLSHandshakeFailed(chain, failure, verifiedHost);
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        boolean reconnect = (e != null && e.getReason() == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR);
        if (e != null) {
            Log.e(TAG, "Error: " + e.getMessage() + " (reason: " + e.getReason().name() + ")");
            mConnectionState = ConnectionState.CONNECTION_LOST;
            setReconnecting(reconnect);
        } else {
            Log.v(TAG, "Disconnected");
            mConnectionState = ConnectionState.DISCONNECTED;
            setReconnecting(false);
        }

        if (!reconnect) {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            releaseWifiLock();
        }

        if (mAudioHandler != null) {
            mAudioHandler.shutdown();
        }

        mModelHandler = null;
        mAudioHandler = null;
        mVoiceTargetId = 0;
        mWhisperTargetList.clear();

        // Never leak the SCO link across disconnects: the next sync re-arms
        // it via updateBluetoothScoRoute when still requested.
        stopScoWithNotify();

        mCallbacks.onDisconnected(e);
    }

    @Override
    public void onConnectionWarning(String warning) {
        logWarning(warning);
    }

    @Override
    public void logInfo(String message) {
        if (mConnection == null || !mConnection.isSynchronized())
            return; // don't log info prior to synchronization
        mCallbacks.onLogInfo(message);
    }

    @Override
    public void logWarning(String message) {
        mCallbacks.onLogWarning(message);
    }

    @Override
    public void logError(String message) {
        mCallbacks.onLogError(message);
    }

    public static final int RECONNECT_DELAY_INITIAL = 1000;
    public static final int RECONNECT_DELAY_MAX = 25000;

    @Override
    public int getReconnectDelay() {
        switch (mReconnectAttempts) {
            case 0:
                return RECONNECT_DELAY_INITIAL;
            case 1:
                return 3000;
            case 2:
                return 6000;
            case 3:
                return 12000;
            default:
                return RECONNECT_DELAY_MAX;
        }
    }

    private void registerNetworkCallback() {
        if (mNetworkCallbackRegistered) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (mNetworkCallback == null) {
                    mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mReconnecting) {
                                        Log.v(TAG, "Network available, triggering immediate reconnect.");
                                        if (mReconnectRunnable != null) {
                                            mHandler.removeCallbacks(mReconnectRunnable);
                                            mReconnectRunnable = null;
                                        }
                                        mReconnectAttempts++;
                                        connect();
                                    }
                                }
                            });
                        }
                    };
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(mNetworkCallback);
                } else {
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    cm.registerNetworkCallback(request, mNetworkCallback);
                }
                mNetworkCallbackRegistered = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering network callback: " + e.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        if (!mNetworkCallbackRegistered) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && mNetworkCallback != null) {
                cm.unregisterNetworkCallback(mNetworkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering network callback: " + e.getMessage());
        } finally {
            mNetworkCallbackRegistered = false;
        }
    }

    public void setReconnecting(boolean reconnecting) {
        if (!reconnecting) {
            mReconnecting = false;
            if (mReconnectRunnable != null) {
                mHandler.removeCallbacks(mReconnectRunnable);
                mReconnectRunnable = null;
            }
            unregisterNetworkCallback();
            return;
        }

        mReconnecting = true;
        registerNetworkCallback();
        int delay = getReconnectDelay();
        Log.v(TAG, "Scheduling reconnect in " + delay + " ms (attempt " + mReconnectAttempts + ")");
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            mWakeLock.acquire(delay + 15000);
        }
        if (mReconnectRunnable != null) {
            mHandler.removeCallbacks(mReconnectRunnable);
        }
        mReconnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (mReconnecting) {
                    mReconnectAttempts++;
                    connect();
                }
            }
        };
        mHandler.postDelayed(mReconnectRunnable, delay);
    }

    /**
     * Stops SCO and clears the confirmed-route flag, emitting route-change
     * notifications when a live link was up. stop() itself never callbacks,
     * so synchronous teardowns must notify explicitly or volume, cues, and
     * menu state strand on the voice route.
     */
    private void stopScoWithNotify() {
        if (mScoManager == null) {
            return;
        }
        boolean wasActive = mScoManager.isActive();
        mScoManager.stop();
        mAudioBuilder.setScoEnabled(false);
        mScoRouteActive = false;
        if (wasActive) {
            // Posted: teardown callers (disconnect, destroy, mid-call disable)
            // may run off the main thread, but hook and observers are
            // main-thread confined. Posting also orders the event after the
            // synchronous disable-path pipeline reload.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mScoManager != null && mScoManager.isActive()) {
                        // Re-connected since teardown queued: fresh callbacks
                        // own the UI; a stale false must not overwrite them.
                        return;
                    }
                    onScoRouteChanged();
                    mCallbacks.onBluetoothScoChanged(false);
                }
            });
        }
    }

    /**
     * Invoked on the main thread after every confirmed SCO route change,
     * after the pipeline reload. Subclasses override to refresh
     * route-dependent audio that the pipeline does not own (e.g. cue streams).
     * Overrides must be idempotent and non-blocking, and must not call back
     * into configureExtras; this also runs when no pipeline exists.
     */
    protected void onScoRouteChanged() {
    }

    /**
     * Applies a confirmed route to the pipeline, cue hook, and observers in
     * that order. All three route callbacks converge here. Skips reload and
     * notify when the applied route is unchanged (e.g. a failure that never
     * went active); the failure toast is the caller's job, not this method's.
     */
    private void applyScoRoute(boolean active, String reason) {
        if (active == mScoRouteActive) {
            Log.i(TAG, "SCO route unchanged, skipping reload (" + reason + ").");
            return;
        }
        mScoRouteActive = active;
        mAudioBuilder.setScoEnabled(active);
        reloadAudioForRoute(reason);
        onScoRouteChanged();
        mCallbacks.onBluetoothScoChanged(active);
    }

    /**
     * Starts or stops SCO bring-up to match the requested toggle and the
     * connection state. Safe to call redundantly: the manager ignores
     * start requests while starting or active, and stop is idempotent.
     */
    private void updateBluetoothScoRoute() {
        if (mScoManager == null) {
            return;
        }
        if (mScoRequested && mConnectionState == ConnectionState.CONNECTED) {
            mScoManager.start();
        } else if (!mScoManager.isIdle()) {
            // Skip the platform round-trip when already idle; stop() is safe
            // but needlessly releases and bumps the callback generation.
            mScoManager.stop();
        }
    }

    /**
     * Recreates the audio pipeline so a route change takes effect on both
     * capture and playback. Route callbacks (already on the main thread)
     * converge here; no-ops unless the pipeline is live.
     */
    private void reloadAudioForRoute(String reason) {
        if (mAudioHandler != null && mAudioHandler.isInitialized()
                && mConnectionState == ConnectionState.CONNECTED) {
            try {
                createAudioHandler();
                Log.i(TAG, reason + "; audio pipeline recreated.");
            } catch (AudioException e) {
                Log.e(TAG, "Route-change audio reload failed", e);
                onConnectionWarning(e.getMessage());
            }
        }
    }

    /**
     * Whether a settings bundle carries audio-affecting keys beyond the
     * Bluetooth toggle, which alone never justifies a pipeline reload.
     * Detection threshold is excluded: it applies live via setVadThresholds.
     */
    private static boolean bundleHasOtherAudioKeys(Bundle extras) {
        return extras.containsKey(EXTRAS_AMPLITUDE_BOOST)
                || extras.containsKey(EXTRAS_TRANSMIT_MODE)
                || extras.containsKey(EXTRAS_INPUT_RATE)
                || extras.containsKey(EXTRAS_INPUT_QUALITY)
                || extras.containsKey(EXTRAS_AUDIO_SOURCE)
                || extras.containsKey(EXTRAS_AUDIO_STREAM)
                || extras.containsKey(EXTRAS_FRAMES_PER_PACKET)
                || extras.containsKey(EXTRAS_HALF_DUPLEX)
                || extras.containsKey(EXTRAS_ADAPTIVE_LEVELER);
    }

    /**
     * Instantiates an audio handler with the current service settings, destroying any previous
     * handler. Requires synchronization with the server, as the maximum bandwidth and session must
     * be known.
     */
    private void createAudioHandler() throws AudioException {
        if (BuildConfig.DEBUG && mConnectionState != ConnectionState.CONNECTED) {
            throw new AssertionError("Attempted to instantiate audio handler when not connected!");
        }

        if (mAudioHandler != null) {
            mConnection.removeTCPMessageHandler(mAudioHandler);
            mConnection.removeUDPMessageHandler(mAudioHandler);
            mAudioHandler.shutdown();
        }

        try {
            mAudioHandler = mAudioBuilder.initialize(
                    mModelHandler.getUser(mConnection.getSession()),
                    mConnection.getMaxBandwidth(), mConnection.getCodec(),
                    mVoiceTargetId);
            mAudioHandler.setProtobufUdp(mConnection.isProtobufUdpSupported());
            mConnection.addTCPMessageHandlers(mAudioHandler);
            mConnection.addUDPMessageHandlers(mAudioHandler);
        } catch (NotSynchronizedException e) {
            throw new RuntimeException("Attempted to create audio handler when not synchronized!");
        }
    }

    /**
     * Validates a trust-store path for {@link #configureExtras}. The null
     * (system store) common case always passes. Non-null paths must
     * canonical-resolve under the app files directory and must end in
     * '.bks'. Canonicalisation resolves any '..' segments, so a separate
     * substring check is unnecessary.
     *
     * @param path Trust-store path, or null for the system store.
     * @return true if the path may be applied.
     */
    private boolean isTrustStorePathAllowed(String path) {
        if (path == null) {
            return true;
        }
        if (!path.endsWith(".bks")) {
            return false;
        }
        try {
            File filesDir = getFilesDir();
            if (filesDir == null) {
                return false;
            }
            String base = filesDir.getCanonicalFile().getPath();
            String candidate = new File(path).getCanonicalFile().getPath();
            return candidate.startsWith(base + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Loads all defined settings from the given bundle into the HumlaService.
     * Some settings may only take effect after a reconnect.
     * @param extras A bundle with settings.
     * @return true if a reconnect is required for changes to take effect.
     * @see se.lublin.humla.HumlaService
     */
    public boolean configureExtras(Bundle extras) throws AudioException {
        boolean reconnectNeeded = false;
        boolean suppressReload = false;
        if (extras.containsKey(EXTRAS_SERVER)) {
            mServer = extras.getParcelable(EXTRAS_SERVER);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_CERTIFICATE)) {
            mCertificate = extras.getByteArray(EXTRAS_CERTIFICATE);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_CERTIFICATE_PASSWORD)) {
            mCertificatePassword = extras.getString(EXTRAS_CERTIFICATE_PASSWORD);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_DETECTION_THRESHOLD)) {
            mActivityInputMode.setThreshold(extras.getFloat(EXTRAS_DETECTION_THRESHOLD));
            if (mAudioHandler != null) {
                mAudioHandler.setVadThresholds(mActivityInputMode.getVadMax(),
                                               mActivityInputMode.getVadMin());
            }
        }
        if (extras.containsKey(EXTRAS_AMPLITUDE_BOOST)) {
            mAudioBuilder.setAmplitudeBoost(extras.getFloat(EXTRAS_AMPLITUDE_BOOST));
        }
        if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
            mTransmitMode = extras.getInt(EXTRAS_TRANSMIT_MODE);
            IInputMode inputMode;
            switch (mTransmitMode) {
                case Constants.TRANSMIT_PUSH_TO_TALK:
                    inputMode = mToggleInputMode;
                    break;
                case Constants.TRANSMIT_CONTINUOUS:
                    inputMode = mContinuousInputMode;
                    break;
                case Constants.TRANSMIT_VOICE_ACTIVITY:
                    inputMode = mActivityInputMode;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            mAudioBuilder.setInputMode(inputMode);
        }
        if (extras.containsKey(EXTRAS_INPUT_RATE)) {
            mAudioBuilder.setInputSampleRate(extras.getInt(EXTRAS_INPUT_RATE));
        }
        if (extras.containsKey(EXTRAS_INPUT_QUALITY)) {
            mAudioBuilder.setTargetBitrate(extras.getInt(EXTRAS_INPUT_QUALITY));
        }
        if (extras.containsKey(EXTRAS_FORCE_TCP)) {
            mForceTcp = extras.getBoolean(EXTRAS_FORCE_TCP);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_CLIENT_NAME)) {
            mClientName = extras.getString(EXTRAS_CLIENT_NAME);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_ACCESS_TOKENS)) {
            mAccessTokens = extras.getStringArrayList(EXTRAS_ACCESS_TOKENS);
            if (mConnection != null && mConnection.isConnected()) {
                mConnection.sendAccessTokens(mAccessTokens);
            }
        }
        if (extras.containsKey(EXTRAS_AUDIO_SOURCE)) {
            mAudioBuilder.setAudioSource(extras.getInt(EXTRAS_AUDIO_SOURCE));
        }
        if (extras.containsKey(EXTRAS_AUDIO_STREAM)) {
            mAudioBuilder.setAudioStream(extras.getInt(EXTRAS_AUDIO_STREAM));
        }
        if (extras.containsKey(EXTRAS_FRAMES_PER_PACKET)) {
            int fpp = extras.getInt(EXTRAS_FRAMES_PER_PACKET);
            mAudioBuilder.setTargetFramesPerPacket(fpp);
            if (mConnection != null) {
                mConnection.setTargetFramesPerPacket(fpp);
            }
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE)) {
            String trustStore = extras.getString(EXTRAS_TRUST_STORE);
            if (isTrustStorePathAllowed(trustStore)) {
                mTrustStore = trustStore;
                reconnectNeeded = true;
                // Password and format describe the store at this path, so
                // they are only applied together with an accepted path.
                if (extras.containsKey(EXTRAS_TRUST_STORE_PASSWORD)) {
                    mTrustStorePassword = extras.getString(EXTRAS_TRUST_STORE_PASSWORD);
                }
                if (extras.containsKey(EXTRAS_TRUST_STORE_FORMAT)) {
                    mTrustStoreFormat = extras.getString(EXTRAS_TRUST_STORE_FORMAT);
                }
            } else {
                Log.w(TAG, "Rejected trust store path (must be a .bks file under the app files directory); "
                        + "keeping previous trust store settings.");
            }
        } else {
            if (extras.containsKey(EXTRAS_TRUST_STORE_PASSWORD)) {
                mTrustStorePassword = extras.getString(EXTRAS_TRUST_STORE_PASSWORD);
                reconnectNeeded = true;
            }
            if (extras.containsKey(EXTRAS_TRUST_STORE_FORMAT)) {
                mTrustStoreFormat = extras.getString(EXTRAS_TRUST_STORE_FORMAT);
                reconnectNeeded = true;
            }
        }
        if (extras.containsKey(EXTRAS_HALF_DUPLEX)) {
            mHalfDuplex = extras.getBoolean(EXTRAS_HALF_DUPLEX);
        }
        if (extras.containsKey(EXTRAS_HALF_DUPLEX) || extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
            mAudioBuilder.setHalfDuplexEnabled(
                    mTransmitMode == Constants.TRANSMIT_PUSH_TO_TALK && mHalfDuplex);
        }
        if (extras.containsKey(EXTRAS_BLUETOOTH_SCO)) {
            boolean requested = extras.getBoolean(EXTRAS_BLUETOOTH_SCO);
            // A fresh enable skips the end-of-method reload only when the
            // bundle holds nothing else audio-affecting: the confirmed
            // callback then performs the single pipeline recreate on the
            // SCO route. Bundled audio changes reload immediately (and the
            // callback reloads once more on confirm); the connect-time
            // bundle never reloads here since no handler exists yet.
            suppressReload = !bundleHasOtherAudioKeys(extras)
                    && (requested || !mScoRequested);
            mScoRequested = requested;
            if (!requested) {
                // Synchronous teardown with notifications: stop() itself never
                // callbacks, and the generic reload below only refreshes the
                // pipeline, not observers or cue audio.
                stopScoWithNotify();
            }
            updateBluetoothScoRoute();
        }
        if (extras.containsKey(EXTRAS_LOCAL_MUTE_HISTORY)) {
            mLocalMuteHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_MUTE_HISTORY);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_LOCAL_IGNORE_HISTORY)) {
            mLocalIgnoreHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_IGNORE_HISTORY);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_ADAPTIVE_LEVELER)) {
            mAudioBuilder.setAdaptiveLevelerEnabled(extras.getBoolean(EXTRAS_ADAPTIVE_LEVELER));
        }

        // Reload audio subsystem if initialized. Guarded on CONNECTED as well
        // as initialization: a disconnect racing a settings change must not
        // resurrect the pipeline (or trip the DEBUG assertion inside).
        if (!suppressReload && mAudioHandler != null && mAudioHandler.isInitialized()
                && mConnectionState == ConnectionState.CONNECTED) {
            createAudioHandler();
            Log.i(TAG, "Audio subsystem reloaded after settings change.");
        }
        return reconnectNeeded;
    }


    /**
     * Exposes the current connection. The current connection is set once an attempt to connect to
     * a server is made, and remains set until a subsequent connection. It remains available
     * after disconnection to provide information regarding the terminated connection.
     * @return The active {@link HumlaConnection}.
     */
    public HumlaConnection getConnection() {
        return mConnection;
    }

    /**
     * Return the current {@link AudioHandler}. An AudioHandler is instantiated upon connection
     * to a server, and destroyed upon disconnection.
     * @return the active AudioHandler, or null if there is no active connection.
     */
    private AudioHandler getAudioHandler() throws NotSynchronizedException {
        if (!isSynchronized())
            throw new NotSynchronizedException();
        if (mAudioHandler == null && mConnectionState == ConnectionState.CONNECTED)
            throw new RuntimeException("Audio handler should always be instantiated while connected!");
        return mAudioHandler;
    }

    /**
     * Returns the current {@link ModelHandler}, containing the channel tree. A model handler is
     * valid for the lifetime of a connection.
     * @return the active ModelHandler, or null if there is no active connection.
     */
    private ModelHandler getModelHandler() throws NotSynchronizedException {
        if (!isSynchronized())
            throw new NotSynchronizedException();
        if (mModelHandler == null && mConnectionState == ConnectionState.CONNECTED)
            throw new RuntimeException("Model handler should always be instantiated while connected!");
        return mModelHandler;
    }


    @Override
    public HumlaService.ConnectionState getConnectionState() {
        return mConnectionState;
    }

    @Override
    public HumlaException getConnectionError() {
        HumlaConnection connection = getConnection();
        return connection != null ? connection.getError() : null;
    }

    @Override
    public boolean isReconnecting() {
        return mReconnecting;
    }

    @Override
    public int getReconnectAttempts() {
        return mReconnectAttempts;
    }

    @Override
    public void cancelReconnect() {
        mReconnectAttempts = 0;
        setReconnecting(false);
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        releaseWifiLock();
    }

    @Override
    public Server getTargetServer() {
        return mServer;
    }

    @Override
    public IHumlaSession HumlaSession() throws HumlaDisconnectedException {
        if (mConnectionState != ConnectionState.CONNECTED) {
            throw new HumlaDisconnectedException();
        }
        return this;
    }

    @Override
    public long getTCPLatency() {
        try {
            return getConnection().getTCPLatency();
        } catch (NotConnectedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long getUDPLatency() {
        try {
            return getConnection().getUDPLatency();
        } catch (NotConnectedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int getMaxBandwidth() {
        try {
            return getConnection().getMaxBandwidth();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int getCurrentBandwidth() {
        try {
            return getAudioHandler().getCurrentBandwidth();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int getServerVersion() {
        try {
            return getConnection().getServerVersion();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long getServerVersionV2() {
        try {
            return getConnection().getServerVersionV2();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getServerRelease() {
        try {
            return getConnection().getServerRelease();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getServerOSName() {
        try {
            return getConnection().getServerOSName();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getServerOSVersion() {
        try {
            return getConnection().getServerOSVersion();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int getSessionId() {
        try {
            return getConnection().getSession();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public IUser getSessionUser() {
        try {
            return getModelHandler().getUser(getSessionId());
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public IChannel getSessionChannel() {
        IUser user = getSessionUser();
        if (user != null)
            return user.getChannel();
        throw new IllegalStateException("Session user should be set post-synchronization!");
    }

    @Override
    public IUser getUser(int session) {
        try {
            return getModelHandler().getUser(session);
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public IChannel getChannel(int id) {
        try {
            return getModelHandler().getChannel(id);
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public IChannel getRootChannel() {
        return getChannel(0);
    }

    @Override
    public int getPermissions() {
        try {
            return getModelHandler().getPermissions();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int getTransmitMode() {
        return mTransmitMode;
    }

    @Override
    public HumlaUDPMessageType getCodec() {
        try {
            return getConnection().getCodec();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }


    @Override
    public boolean isTalking() {
        return mToggleInputMode.isTalkingOn();
    }

    @Override
    public void setTalkingState(boolean talking) {
        mToggleInputMode.setTalkingOn(talking);
        if (mAudioHandler != null) {
            mAudioHandler.setPttTalking(talking);
        }
    }

    @Override
    public void joinChannel(int channel) {
        moveUserToChannel(getSessionId(), channel);
    }

    @Override
    public void moveUserToChannel(int session, int channel) {
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(session);
        usb.setChannelId(channel);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public void sendAccessTokens(final List<String> tokens) {
        getConnection().sendAccessTokens(tokens);
    }

    @Override
    public void requestPermissions(int channel) {
        Mumble.PermissionQuery.Builder pqb = Mumble.PermissionQuery.newBuilder();
        pqb.setChannelId(channel);
        getConnection().sendTCPMessage(pqb.build(), HumlaTCPMessageType.PermissionQuery);
    }

    @Override
    public void requestComment(int session) {
        Mumble.RequestBlob.Builder rbb = Mumble.RequestBlob.newBuilder();
        rbb.addSessionComment(session);
        getConnection().sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob);
    }

    @Override
    public void requestAvatar(int session) {
        Mumble.RequestBlob.Builder rbb = Mumble.RequestBlob.newBuilder();
        rbb.addSessionTexture(session);
        getConnection().sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob);
    }

    @Override
    public void requestChannelDescription(int channel) {
        Mumble.RequestBlob.Builder rbb = Mumble.RequestBlob.newBuilder();
        rbb.addChannelDescription(channel);
        getConnection().sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob);
    }

    @Override
    public void registerUser(int session) {
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(session);
        usb.setUserId(0);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public void kickBanUser(int session, String reason, boolean ban) {
        Mumble.UserRemove.Builder urb = Mumble.UserRemove.newBuilder();
        urb.setSession(session);
        urb.setReason(reason);
        urb.setBan(ban);
        getConnection().sendTCPMessage(urb.build(), HumlaTCPMessageType.UserRemove);
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        try {
            if (!isSynchronized())
                throw new NotSynchronizedException();

            Mumble.TextMessage.Builder tmb = Mumble.TextMessage.newBuilder();
            tmb.addSession(session);
            tmb.setMessage(message);
            getConnection().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage);

            User self = getModelHandler().getUser(getSessionId());
            User user = getModelHandler().getUser(session);
            List<User> users = new ArrayList<User>(1);
            users.add(user);
            return new Message(getSessionId(), self.getName(), new ArrayList<Channel>(0), new ArrayList<Channel>(0), users, message);
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        try {
            if (!isSynchronized())
                throw new NotSynchronizedException();

            Mumble.TextMessage.Builder tmb = Mumble.TextMessage.newBuilder();
            if (tree) tmb.addTreeId(channel);
            else tmb.addChannelId(channel);
            tmb.setMessage(message);
            getConnection().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage);

            User self = getModelHandler().getUser(getSessionId());
            Channel targetChannel = getModelHandler().getChannel(channel);
            List<Channel> targetChannels = new ArrayList<Channel>();
            targetChannels.add(targetChannel);
            return new Message(getSessionId(), self.getName(), targetChannels, tree ? targetChannels : new ArrayList<Channel>(0), new ArrayList<User>(0), message);
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setUserComment(int session, String comment) {
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(session);
        usb.setComment(comment);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public void setPrioritySpeaker(int session, boolean priority) {
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(session);
        usb.setPrioritySpeaker(priority);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public void setMuteDeafState(int session, boolean mute, boolean deaf) {
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(session);
        usb.setMute(mute);
        usb.setDeaf(deaf);
        if (!mute) usb.setSuppress(false);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public void setSelfMuteDeafState(boolean mute, boolean deaf) {
        // Coerce centrally to mirror murmur (murmur/Messages.cpp) and
        // desktop: deaf implies mute, unmute implies undeafen. The packet
        // and the optimistic mirror below derive from the same pair so a
        // coercion mismatch can never flap for one RTT.
        if (deaf) mute = true;
        if (!mute) deaf = false;
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSelfMute(mute);
        usb.setSelfDeaf(deaf);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
        applyOptimisticSelfMuteDeaf(mute, deaf);
    }

    /**
     * Desktop-parity optimistic self-mute/deafen: apply the requested state
     * to the local model and capture gate immediately, treating the server
     * echo as the confirm. murmur applies self_mute/self_deaf from self
     * unconditionally (no permission check; spoofed writes from others are
     * silently dropped), so no rollback is needed: TCP ordering guarantees
     * echoes arrive in request order and the state converges on the last
     * request. Other users' mute (admin {@code mute}/{@code deaf}) stays
     * pessimistic in {@link #setMuteDeafState}.
     *
     * <p>Mirroring into the {@link User} object (rather than rebinding every
     * UI reader) fans the update out through the existing
     * {@code onUserStateUpdated} path: menu icons, notification, settings
     * persistence, and TTS suppression all follow with no UI edits. The echo
     * then becomes a no-op via the unchanged guard in
     * {@link ModelHandler#messageUserState}.
     *
     * <p>Accepted transients (both convergent via the echo): the capture gate
     * is pushed before the model write, so if the session user is not yet
     * visible the mic follows desired state up to a round trip ahead of the
     * UI; and the chat log line is best-effort (dropped if a disconnect
     * interleaves, since logging re-gates on synchronization while the state
     * change itself does not).
     */
    private void applyOptimisticSelfMuteDeaf(boolean mute, boolean deaf) {
        HumlaConnection connection = mConnection;
        ModelHandler modelHandler = mModelHandler;
        // sendTCPMessage silently drops when disconnected; never show a
        // state the server never saw.
        if (connection == null || modelHandler == null || !connection.isSynchronized())
            return;
        // Push the capture gate before the model: desired state is
        // authoritative even when the model user isn't visible yet (e.g.
        // the reconnect restore racing the initial UserState dump). Snapshot
        // the handler: disconnect nulls the field from another thread.
        AudioHandler audioHandler = mAudioHandler;
        if (audioHandler != null)
            audioHandler.setSelfMutedOptimistic(mute);
        final int session;
        try {
            session = connection.getSession();
        } catch (NotSynchronizedException e) {
            return;
        }
        // Same monitor as ModelHandler.messageUserState, which writes these
        // fields on the TCP reader thread.
        synchronized (modelHandler) {
            User self = modelHandler.getUser(session);
            if (self == null)
                return;
            boolean changed = false;
            if (self.isSelfMuted() != mute) {
                self.setSelfMuted(mute);
                changed = true;
            }
            if (self.isSelfDeafened() != deaf) {
                self.setSelfDeafened(deaf);
                changed = true;
            }
            if (changed) {
                // Log here, at the toggle site like desktop's MainWindow: the
                // echo's log is suppressed as unchanged (see ModelHandler).
                if (mute && deaf)
                    logInfo(getString(R.string.chat_notify_muted_deafened));
                else if (mute)
                    logInfo(getString(R.string.chat_notify_muted));
                else
                    logInfo(getString(R.string.chat_notify_unmuted));
                mCallbacks.onUserStateUpdated(self);
            }
        }
    }

    public void registerObserver(IHumlaObserver observer) {
        mCallbacks.registerObserver(observer);
    }

    public void unregisterObserver(IHumlaObserver observer) {
        mCallbacks.unregisterObserver(observer);
    }

    @Override
    public boolean isConnected() {
        return mConnectionState == ConnectionState.CONNECTED;
    }

    @Override
    public void linkChannels(IChannel channelA, IChannel channelB) {
        Mumble.ChannelState.Builder csb = Mumble.ChannelState.newBuilder();
        csb.setChannelId(channelA.getId());
        csb.addLinksAdd(channelB.getId());
        getConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState);
    }

    @Override
    public void unlinkChannels(IChannel channelA, IChannel channelB) {
        Mumble.ChannelState.Builder csb = Mumble.ChannelState.newBuilder();
        csb.setChannelId(channelA.getId());
        csb.addLinksRemove(channelB.getId());
        getConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState);
    }

    @Override
    public void unlinkAllChannels(IChannel channel) {
        Mumble.ChannelState.Builder csb = Mumble.ChannelState.newBuilder();
        csb.setChannelId(channel.getId());
        for (IChannel linked : channel.getLinks()) {
            csb.addLinksRemove(linked.getId());
        }
        getConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState);
    }

    @Override
    public byte registerWhisperTarget(final WhisperTarget target) {
        byte id = mWhisperTargetList.append(target);
        if (id < 0) {
            return -1;
        }

        Mumble.VoiceTarget.Target voiceTarget = target.createTarget();
        Mumble.VoiceTarget.Builder vtb = Mumble.VoiceTarget.newBuilder();
        vtb.setId(id);
        vtb.addTargets(voiceTarget);
        getConnection().sendTCPMessage(vtb.build(), HumlaTCPMessageType.VoiceTarget);
        return id;
    }

    @Override
    public void unregisterWhisperTarget(byte targetId) {
        mWhisperTargetList.free(targetId);
    }

    @Override
    public void setVoiceTargetId(byte targetId) {
        // Mask, not Byte.toUnsignedInt: the latter needs API 26 and this
        // module is minSdk 21 without core-library desugaring.
        if ((targetId & 0xFF) > 0x1F) {
            throw new IllegalArgumentException(
                    "Target ID must be at most 5 bits: " + (targetId & 0xFF));
        }
        mVoiceTargetId = targetId;
        if (mAudioHandler != null) {
            mAudioHandler.setVoiceTargetId(targetId);
        }
        mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(targetId));
    }

    @Override
    public byte getVoiceTargetId() {
        return mVoiceTargetId;
    }

    @Override
    public VoiceTargetMode getVoiceTargetMode() {
        return VoiceTargetMode.fromId(mVoiceTargetId);
    }

    @Override
    public WhisperTarget getWhisperTarget() {
        if (VoiceTargetMode.fromId(mVoiceTargetId) == VoiceTargetMode.WHISPER) {
            return mWhisperTargetList.get(mVoiceTargetId);
        }
        return null;
    }

    @Override
    public ServerSettings getServerSettings() {
        try {
            return getModelHandler().getServerSettings();
        } catch (NotSynchronizedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setListeningChannel(int channelId, boolean listening) {
        if (!isSynchronized()) return;
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(getSessionId());
        if (listening) {
            usb.addListeningChannelAdd(channelId);
        } else {
            usb.addListeningChannelRemove(channelId);
        }
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public void setListeningVolume(int channelId, float volumeAdjustment) {
        if (!isSynchronized()) return;
        Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
        usb.setSession(getSessionId());
        Mumble.UserState.VolumeAdjustment.Builder vab = Mumble.UserState.VolumeAdjustment.newBuilder();
        vab.setListeningChannel(channelId);
        vab.setVolumeAdjustment(volumeAdjustment);
        usb.addListeningVolumeAdjustment(vab);
        getConnection().sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState);
    }

    @Override
    public boolean isListeningToChannel(int channelId) {
        try {
            Channel ch = getModelHandler().getChannels().get(channelId);
            return ch != null && ch.isListening();
        } catch (NotSynchronizedException e) {
            return false;
        }
    }


    /**
     * The current connection state of the service.
     */
    public enum ConnectionState {
        /**
         * The default state of Humla, before connection to a server and after graceful/expected
         * disconnection from a server.
         */
        DISCONNECTED,
        /**
         * A connection to the server is currently in progress.
         */
        CONNECTING,
        /**
         * Humla has received all data necessary for normal protocol communication with the server.
         */
        CONNECTED,
        /**
         * The connection was lost due to either a kick/ban or socket I/O error.
         * Humla may be reconnecting in this state.
         * @see #isReconnecting()
         * @see #cancelReconnect()
         */
        CONNECTION_LOST
    }

    public static class HumlaBinder extends Binder {
        private final IHumlaService mService;

        private HumlaBinder(IHumlaService service) {
            mService = service;
        }

        public IHumlaService getService() {
            return mService;
        }
    }
}
