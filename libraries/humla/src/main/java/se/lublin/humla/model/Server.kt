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

package se.lublin.humla.model

import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.util.Patterns
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.minidns.hla.ResolverApi
import org.minidns.hla.SrvResolverResult
import org.minidns.record.SRV
import org.minidns.util.SrvUtil
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import se.lublin.humla.Constants

/**
 * Single server model: Room row, parcel, and UI model in one (no separate layers).
 *
 * Room and Parcel bind the raw `name`/`host`/`port` fields (`@JvmField`, so no
 * accessor masks them); `id`/`username`/`password` are plain properties. The
 * user-visible name fallback lives in [getName] only, and the resolved-host
 * invalidation lives in [setHost]/[setPort] only — route every field write
 * through the setters, never a direct `.host`/`.port` assignment, or the
 * invalidation is bypassed. Parcel bytes match the hand-written original
 * exactly (six fields in order; resolved state excluded).
 */
@Entity(tableName = "server")
@Parcelize
// Final by intent (pre-image Java was non-final, but no subclass exists
// in-tree): documents sealing; re-open if extensibility is ever required.
class Server(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    var id: Long,
    @ColumnInfo(name = "name")
    @JvmField
    var name: String?,
    @ColumnInfo(name = "host")
    @JvmField
    var host: String?,
    @ColumnInfo(name = "port")
    @JvmField
    var port: Int,
    @ColumnInfo(name = "username")
    var username: String?,
    @ColumnInfo(name = "password")
    var password: String?
) : Parcelable {
    @Ignore
    @IgnoredOnParcel
    @Volatile
    var resolvedHost: String? = null

    @Ignore
    @IgnoredOnParcel
    @Volatile
    var resolvedPort: Int = 0

    /**
     * Returns a user-defined name for the server, or the host if the user-defined name is not set.
     * @return A user readable name for the server.
     */
    fun getName(): String? {
        return if (!name.isNullOrEmpty()) name else host
    }

    fun setName(name: String?) {
        this.name = name
    }

    fun setHost(host: String?) {
        this.host = host
        this.resolvedHost = null
    }

    fun getHost(): String? {
        return host
    }

    fun setPort(port: Int) {
        this.port = port
        this.resolvedHost = null
    }

    fun getPort(): Int {
        return port
    }

    /**
     * Returns whether or not the server is stored in a database.
     * @return true if the server's ID is in the database.
     */
    fun isSaved(): Boolean {
        return id != -1L
    }

    fun getSrvHost(): String? {
        // Snapshot: sibling synchronized writers replace the field (see HumlaService reconnect).
        val resolved = resolvedHost
        if (resolved != null) {
            return resolved
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "getSrvHost() called on main thread; returning fallback host without caching.")
            return host
        }
        srvResolve()
        return resolvedHost ?: host
    }

    fun getSrvPort(): Int {
        // Snapshot: sibling synchronized writers replace the field (see HumlaService reconnect).
        val resolved = resolvedHost
        if (resolved != null) {
            return resolvedPort
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "getSrvPort() called on main thread; returning fallback port without caching.")
            return if (port != 0) port else Constants.DEFAULT_PORT
        }
        srvResolve()
        return if (resolvedPort != 0) {
            resolvedPort
        } else {
            if (port != 0) port else Constants.DEFAULT_PORT
        }
    }

    @Synchronized
    fun setResolved(host: String?, port: Int) {
        resolvedHost = host
        resolvedPort = port
    }

    @Synchronized
    private fun srvResolve() {
        if (resolvedHost != null) {
            return
        }
        // if we have a port then don't bother with SRV
        if (port != 0) {
            resolvedHost = host
            resolvedPort = port
            return
        }
        // NPE-exact: the original dereferenced mHost unconditionally below.
        val rawHost = host ?: throw NullPointerException("host")
        // skip also IP addresses and Tor Onion Services (a pseudo-TLD)
        if (Patterns.IP_ADDRESS.matcher(rawHost).matches() ||
            rawHost.contains(":") ||
            rawHost.endsWith(".onion")
        ) {
            resolvedHost = rawHost
            resolvedPort = Constants.DEFAULT_PORT
            return
        }
        // Do not block main (UI) thread on synchronous DNS queries
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "srvResolve() called on main thread; skipping synchronous DNS query to prevent ANR.")
            return
        }
        // set to our fallback values in case of no SRV or resolve fail
        var srvHost = rawHost
        var srvPort = Constants.DEFAULT_PORT
        try {
            val lookup = "_mumble._tcp.$srvHost"
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<SrvResolverResult> {
                ResolverApi.INSTANCE.resolveSrv(lookup)
            }
            var res: SrvResolverResult? = null
            try {
                res = future.get(800, TimeUnit.MILLISECONDS)
            } catch (ignored: TimeoutException) {
                future.cancel(true)
                Log.d(TAG, "SRV lookup timed out for $lookup")
            } finally {
                executor.shutdownNow()
            }
            if (res != null && res.wasSuccessful()) {
                val answers: Set<SRV> = res.getAnswersOrEmptySet()
                if (answers.isNotEmpty()) {
                    val srvs = SrvUtil.sortSrvRecords(answers)
                    for (srv in srvs) {
                        Log.d(TAG, "resolved $lookup SRV: $srv")
                        var target = srv.target.toString()
                        if (target.endsWith(".")) {
                            target = target.substring(0, target.length - 1)
                        }
                        srvHost = target
                        srvPort = srv.port
                        break
                    }
                } else {
                    Log.d(TAG, "resolveSrv $lookup: empty answer")
                }
            } else if (res != null) {
                Log.d(TAG, "resolveSrv $lookup: " + res.responseCode)
            }
        } catch (e: Exception) {
            Log.d(TAG, "exception in srvResolve: $e")
        }
        resolvedHost = srvHost
        resolvedPort = srvPort
    }

    companion object {
        private val TAG: String = Server::class.java.name
    }
}
