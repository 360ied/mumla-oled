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

package se.lublin.humla.util;

import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import se.lublin.humla.BuildConfig;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.net.HandshakeFailure;

/**
 * A composite wrapper around Humla observers to easily broadcast to each observer.
 * Created by andrew on 12/07/14.
 *
 * <p>Fan-out isolates failures: a throwing observer is logged and the rest
 * still run. Only {@code RuntimeException} is caught — an {@code Error} still
 * aborts the broadcast. Registration rejects {@code null} fail-fast while
 * unregistration tolerates it for defensive call sites.
 */
public class HumlaCallbacks implements IHumlaObserver {
    private final Set<IHumlaObserver> mCallbacks;

    public HumlaCallbacks() {
        mCallbacks = Collections.newSetFromMap(new ConcurrentHashMap<IHumlaObserver, Boolean>());
    }

    public void registerObserver(IHumlaObserver observer) {
        java.util.Objects.requireNonNull(observer, "observer");
        mCallbacks.add(observer);
    }

    public void unregisterObserver(IHumlaObserver observer) {
        if (observer == null) return;
        mCallbacks.remove(observer);
    }

    @Override
    public void onConnected() {
        broadcast(o -> o.onConnected());
    }

    @Override
    public void onConnecting() {
        broadcast(o -> o.onConnecting());
    }

    @Override
    public void onDisconnected(HumlaException e) {
        broadcast(o -> o.onDisconnected(e));
    }

    @Override
    public void onTLSHandshakeFailed(X509Certificate[] chain, HandshakeFailure failure, String verifiedHost) {
        broadcast(o -> o.onTLSHandshakeFailed(chain, failure, verifiedHost));
    }

    @Override
    public void onChannelAdded(IChannel channel) {
        broadcast(o -> o.onChannelAdded(channel));
    }

    @Override
    public void onChannelStateUpdated(IChannel channel) {
        broadcast(o -> o.onChannelStateUpdated(channel));
    }

    @Override
    public void onChannelRemoved(IChannel channel) {
        broadcast(o -> o.onChannelRemoved(channel));
    }

    @Override
    public void onChannelPermissionsUpdated(IChannel channel) {
        broadcast(o -> o.onChannelPermissionsUpdated(channel));
    }

    @Override
    public void onUserConnected(IUser user) {
        broadcast(o -> o.onUserConnected(user));
    }

    @Override
    public void onUserStateUpdated(IUser user) {
        broadcast(o -> o.onUserStateUpdated(user));
    }

    @Override
    public void onUserTalkStateUpdated(IUser user) {
        broadcast(o -> o.onUserTalkStateUpdated(user));
    }

    @Override
    public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
        broadcast(o -> o.onUserJoinedChannel(user, newChannel, oldChannel));
    }

    @Override
    public void onUserRemoved(IUser user, String reason) {
        broadcast(o -> o.onUserRemoved(user, reason));
    }

    @Override
    public void onPermissionDenied(String reason) {
        broadcast(o -> o.onPermissionDenied(reason));
    }

    @Override
    public void onMessageLogged(IMessage message) {
        broadcast(o -> o.onMessageLogged(message));
    }

    @Override
    public void onVoiceTargetChanged(VoiceTargetMode mode) {
        broadcast(o -> o.onVoiceTargetChanged(mode));
    }

    @Override
    public void onBluetoothScoChanged(boolean active) {
        broadcast(o -> o.onBluetoothScoChanged(active));
    }

    @Override
    public void onAudioRouteChanged() {
        broadcast(o -> o.onAudioRouteChanged());
    }

    @Override
    public void onLogInfo(String message) {
        broadcast(o -> o.onLogInfo(message));
    }

    @Override
    public void onLogWarning(String message) {
        broadcast(o -> o.onLogWarning(message));
    }

    @Override
    public void onLogError(String message) {
        broadcast(o -> o.onLogError(message));
    }

    /** Fan-out with per-observer isolation: a throwing observer is logged and the rest still run. */
    private interface ObserverCall {
        void call(IHumlaObserver observer);
    }

    private void broadcast(ObserverCall call) {
        for (IHumlaObserver observer : mCallbacks) {
            try {
                call.call(observer);
            } catch (RuntimeException observerError) {
                // Full traces in debug only: a persistently-throwing observer
                // on a high-frequency callback must not spam logcat.
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("HumlaCallbacks", "Observer threw, continuing fan-out", observerError);
                } else {
                    android.util.Log.w("HumlaCallbacks", "Observer " + observer.getClass().getName()
                            + " threw, continuing fan-out: " + observerError);
                }
            }
        }
    }
}
