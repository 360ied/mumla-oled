/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.net.HandshakeFailure

/**
 * Created by andrew on 18/10/15.
 */
interface IHumlaObserver {
    fun onConnected()

    fun onConnecting()

    fun onDisconnected(e: HumlaException?)

    fun onTLSHandshakeFailed(
        chain: Array<X509Certificate>?,
        failure: HandshakeFailure?,
        verifiedHost: String?
    )

    fun onChannelAdded(channel: IChannel?)

    fun onChannelStateUpdated(channel: IChannel?)

    fun onChannelRemoved(channel: IChannel?)

    fun onChannelPermissionsUpdated(channel: IChannel?)

    fun onUserConnected(user: IUser?)

    fun onUserStateUpdated(user: IUser?)

    fun onUserTalkStateUpdated(user: IUser?)

    fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?)

    fun onUserRemoved(user: IUser?, reason: String?)

    fun onPermissionDenied(reason: String?)

    fun onMessageLogged(message: IMessage?)

    fun onVoiceTargetChanged(mode: VoiceTargetMode?)

    /**
     * Called when the Bluetooth SCO link transitions.
     * In-tree route host calls on main; contract is caller-thread, so
     * out-of-tree callers on any thread are legal -- do not assume a looper.
     * @param active True if SCO is up and carrying call audio, false on
     *               teardown or failed bring-up (phone-audio fallback).
     */
    fun onBluetoothScoChanged(active: Boolean)

    /**
     * Called when Bluetooth SCO bring-up fails.
     * Replaces the library-owned Toast: the app layer renders user-visible
     * feedback. Edges stay coalesced by the existing route debounce; no
     * extra throttle here. In-tree route host calls on main; contract is
     * caller-thread (see above). In-tree observers should extend
     * HumlaObserver (no-op default); out-of-tree direct implementors in
     * Java inherit the default (humla compiles the interface with
     * jvm-default=enable, so the implementor's own flags do not matter);
     * no override needed (Kotlin observers inherit the default no-op).
     * @param reason Failure reason (see `BluetoothScoManager` `REASON_*`), or
     *               null when unknown.
     */
    fun onBluetoothScoFailed(reason: String?) {
    }

    /**
     * Called after every confirmed audio-route change
     * (voice triple vs media path, SCO or media-sink driven), after the
     * pipeline reload. In-tree route host calls on main; contract is
     * caller-thread (see above). Use it to follow output routing (e.g. volume
     * stream); SCO-specific UI belongs in onBluetoothScoChanged.
     * In-tree observers should extend HumlaObserver (no-op default);
     * out-of-tree direct implementors in Java inherit the default (humla
     * compiles the interface with jvm-default=enable); no override needed.
     */
    fun onAudioRouteChanged() {
    }

    fun onLogInfo(message: String?)

    fun onLogWarning(message: String?)

    fun onLogError(message: String?)
}
