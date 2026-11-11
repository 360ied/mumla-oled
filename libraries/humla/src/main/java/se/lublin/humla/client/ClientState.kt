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

package se.lublin.humla.client

import java.security.cert.X509Certificate
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import se.lublin.humla.net.HandshakeFailure
import se.lublin.humla.state.ServerState

/**
 * Client connection lifecycle. See
 * docs/tech-debt-whales/target-architecture/client.md#clientstate.
 *
 * Android-free and deeply immutable (see StateImmutabilityTest): the
 * Connected branch holds a Session, which is a lifetime object, not a
 * value — the immutability walk stops at Session and never descends into
 * its scope, transport, or flows.
 */
sealed interface ClientState {
    data object Idle : ClientState
    data class Connecting(val target: ConnectTarget, val attempt: Int) : ClientState
    data class Connected(val target: ConnectTarget, val session: Session) : ClientState
    data class Reconnecting(
        val target: ConnectTarget,
        val attempt: Int,
        val retryAtMillis: Long,
        val lastError: ClientError,
        val lastState: ServerState?,
    ) : ClientState
    data class Failed(val target: ConnectTarget, val error: ClientError) : ClientState
}

/**
 * Which errors reconnect vs fail. Only [Connection] reconnects;
 * Rejected/Removed/Trust go to Failed because the user must act.
 */
sealed interface ClientError {
    /** Transport I/O, DNS, or protocol violation: reconnects. */
    data class Connection(val message: String, val cause: Throwable?) : ClientError {
        override fun toString(): String = "Connection(message=$message, cause=$cause)"
    }

    /** Server Reject message: no reconnect. */
    data class Rejected(val type: Int, val reason: String?) : ClientError

    /** Self UserRemove (kick/ban): no reconnect. */
    data class Removed(val actor: Int?, val reason: String?, val ban: Boolean) : ClientError

    /** TLS verification failure: never reconnects; app shows trust dialogs. */
    data class Trust(
        val chain: PersistentList<X509Certificate>,
        val failure: HandshakeFailure,
        val verifiedHost: String?,
    ) : ClientError
}

/** Convenience for tests: empty chain trust error (host defaults to null). */
fun trustError(
    failure: HandshakeFailure = HandshakeFailure.UNTRUSTED_ISSUER,
    verifiedHost: String? = null,
): ClientError.Trust = ClientError.Trust(
    chain = persistentListOf(),
    failure = failure,
    verifiedHost = verifiedHost,
)
