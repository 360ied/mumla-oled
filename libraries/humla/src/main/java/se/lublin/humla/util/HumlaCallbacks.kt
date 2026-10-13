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

package se.lublin.humla.util

import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import se.lublin.humla.BuildConfig
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.net.HandshakeFailure

/**
 * A composite wrapper around Humla observers to easily broadcast to each observer.
 * Created by andrew on 12/07/14.
 *
 * <p>Fan-out isolates failures: a throwing observer is logged and the rest
 * still run. Only {@code RuntimeException} is caught — an {@code Error} still
 * aborts the broadcast. Registration rejects {@code null} fail-fast while
 * unregistration tolerates it for defensive call sites.
 */
class HumlaCallbacks : IHumlaObserver {
    private val callbacks: MutableSet<IHumlaObserver> =
        Collections.newSetFromMap(ConcurrentHashMap<IHumlaObserver, Boolean>())

    fun registerObserver(observer: IHumlaObserver) {
        java.util.Objects.requireNonNull(observer, "observer")
        callbacks.add(observer)
    }

    fun unregisterObserver(observer: IHumlaObserver?) {
        if (observer == null) return
        callbacks.remove(observer)
    }

    override fun onConnected() {
        broadcast { it.onConnected() }
    }

    override fun onConnecting() {
        broadcast { it.onConnecting() }
    }

    override fun onDisconnected(e: HumlaException?) {
        broadcast { o -> o.onDisconnected(e) }
    }

    override fun onTLSHandshakeFailed(
        chain: Array<X509Certificate>?,
        failure: HandshakeFailure?,
        verifiedHost: String?
    ) {
        broadcast { o -> o.onTLSHandshakeFailed(chain, failure, verifiedHost) }
    }

    override fun onChannelAdded(channel: IChannel?) {
        broadcast { o -> o.onChannelAdded(channel) }
    }

    override fun onChannelStateUpdated(channel: IChannel?) {
        broadcast { o -> o.onChannelStateUpdated(channel) }
    }

    override fun onChannelRemoved(channel: IChannel?) {
        broadcast { o -> o.onChannelRemoved(channel) }
    }

    override fun onChannelPermissionsUpdated(channel: IChannel?) {
        broadcast { o -> o.onChannelPermissionsUpdated(channel) }
    }

    override fun onUserConnected(user: IUser?) {
        broadcast { o -> o.onUserConnected(user) }
    }

    override fun onUserStateUpdated(user: IUser?) {
        broadcast { o -> o.onUserStateUpdated(user) }
    }

    override fun onUserTalkStateUpdated(user: IUser?) {
        broadcast { o -> o.onUserTalkStateUpdated(user) }
    }

    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) {
        broadcast { o -> o.onUserJoinedChannel(user, newChannel, oldChannel) }
    }

    override fun onUserRemoved(user: IUser?, reason: String?) {
        broadcast { o -> o.onUserRemoved(user, reason) }
    }

    override fun onPermissionDenied(reason: String?) {
        broadcast { o -> o.onPermissionDenied(reason) }
    }

    override fun onMessageLogged(message: IMessage?) {
        broadcast { o -> o.onMessageLogged(message) }
    }

    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) {
        broadcast { o -> o.onVoiceTargetChanged(mode) }
    }

    override fun onBluetoothScoChanged(active: Boolean) {
        broadcast { o -> o.onBluetoothScoChanged(active) }
    }

    override fun onAudioRouteChanged() {
        broadcast { it.onAudioRouteChanged() }
    }

    override fun onLogInfo(message: String?) {
        broadcast { o -> o.onLogInfo(message) }
    }

    override fun onLogWarning(message: String?) {
        broadcast { o -> o.onLogWarning(message) }
    }

    override fun onLogError(message: String?) {
        broadcast { o -> o.onLogError(message) }
    }

    /** Fan-out with per-observer isolation: a throwing observer is logged and the rest still run. */
    private fun interface ObserverCall {
        fun call(observer: IHumlaObserver)
    }

    private fun broadcast(call: ObserverCall) {
        for (observer in callbacks) {
            try {
                call.call(observer)
            } catch (observerError: RuntimeException) {
                // Never swallow coroutine cancellation as a mere observer
                // failure (no in-tree observer throws it today; belt and
                // braces for a future coroutine-based observer).
                if (observerError is kotlinx.coroutines.CancellationException) {
                    throw observerError
                }
                // Full traces in debug only: a persistently-throwing observer
                // on a high-frequency callback must not spam logcat.
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("HumlaCallbacks", "Observer threw, continuing fan-out", observerError)
                } else {
                    android.util.Log.w(
                        "HumlaCallbacks",
                        "Observer " + observer.javaClass.name +
                            " threw, continuing fan-out: " + observerError
                    )
                }
            }
        }
    }
}
