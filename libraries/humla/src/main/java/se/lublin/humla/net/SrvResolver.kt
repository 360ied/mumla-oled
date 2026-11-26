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

package se.lublin.humla.net

import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.minidns.hla.ResolverApi
import org.minidns.record.SRV
import org.minidns.util.SrvUtil
import se.lublin.humla.Constants

/** The host and port to connect to after the SRV rule (see [SrvResolver]). */
data class SrvTarget(val host: String, val port: Int)

/**
 * The one `_mumble._tcp` SRV rule, shared by the transport's connect path and
 * the server-list pings (docs/target-architecture/transport.md):
 *
 * - a non-zero port returns the host and port as typed, without a lookup;
 * - an IP literal, anything containing `:` (IPv6), or an onion host returns
 *   the host with the default port, without a lookup;
 * - otherwise `_mumble._tcp.<host>` is looked up, and the first record after
 *   [SrvUtil.sortSrvRecords], minus its trailing dot, wins (a record port of
 *   0 means the default port);
 * - no record, a failed lookup, or a timeout returns the host with the
 *   default port; cancellation propagates.
 *
 * The lookup is blocking and runs inside [runInterruptible] on
 * [Dispatchers.IO], as a `Deferred` in this resolver's own scope, so the
 * caller's 800 ms bound really holds: on timeout the deferred is cancelled,
 * which interrupts the lookup the way cancel-on-future did for the pings,
 * and the caller returns without waiting for it.
 */
class SrvResolver(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** The blocking lookup, injected by tests; the resolver sorts the records. */
    private val lookup: (String) -> List<SRV> = ::resolveSrvBlocking,
) {
    companion object {
        /**
         * The SRV budget both callers use. MiniDNS has its own per-query
         * timeout; this bound is the one the callers contract for.
         */
        const val DEFAULT_TIMEOUT_MS = 800L

        private val logger: Logger = Logger.getLogger(SrvResolver::class.java.name)
    }

    // Lookups run here, detached from the caller: a timeout must return
    // without waiting for a lookup that ignores interruption.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(host: String, port: Int): SrvTarget {
        if (port != 0) return SrvTarget(host, port)
        // contains(":") is subsumed by isIpLiteral (true for IPv6) but kept
        // explicit at the call site for readability.
        if (TlsHostnameVerifier.isIpLiteral(host) || host.contains(":") ||
            TlsHostnameVerifier.isOnionHost(host)
        ) {
            return SrvTarget(host, Constants.DEFAULT_PORT)
        }
        val name = "_mumble._tcp.$host"
        val deferred = scope.async {
            runInterruptible(Dispatchers.IO) { lookup(name) }
        }
        val best = try {
            val records = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (records == null) {
                deferred.cancel()
                logger.fine("SRV lookup timed out for $name")
                return SrvTarget(host, Constants.DEFAULT_PORT)
            }
            SrvUtil.sortSrvRecords(records).firstOrNull()
                ?: return SrvTarget(host, Constants.DEFAULT_PORT)
        } catch (e: CancellationException) {
            // The caller went away (disconnect/new connect): interrupt the
            // lookup and propagate, never connect with a default port.
            deferred.cancel()
            throw e
        } catch (e: Exception) {
            logger.warning("SRV resolution failed for $name: $e")
            return SrvTarget(host, Constants.DEFAULT_PORT)
        }
        logger.fine("resolved $name SRV: $best")
        // A port-0 record keeps its target on the default port, as the
        // transport and Server.getSrvPort did before the rule was shared.
        val port = if (best.port == 0) Constants.DEFAULT_PORT else best.port
        return SrvTarget(best.target.toString().removeSuffix("."), port)
    }
}

/**
 * The production lookup: MiniDNS's HLA resolver. An unsuccessful result is
 * an empty answer; a thrown failure propagates to [SrvResolver.resolve],
 * which maps it to the default port.
 * Kept to one call plus the success check so no untested resolution logic
 * lives outside [SrvResolver].
 */
private fun resolveSrvBlocking(name: String): List<SRV> {
    val result = ResolverApi.INSTANCE.resolveSrv(name)
    return if (result.wasSuccessful()) result.getAnswersOrEmptySet().toList() else emptyList()
}
