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

package se.lublin.mumla.util

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.KeyStoreException

/**
 * Persists the user's pinned (trust-on-first-use) certificates in a private BKS store.
 *
 * Created by andrew on 05/04/14.
 */
object MumlaTrustStore {

    private const val STORE_FILE = "mumla-store.bks"
    private const val STORE_PASS = ""
    private const val STORE_FORMAT = "BKS"

    /**
     * Loads the app's trust store of certificates.
     * @return A loaded KeyStore with the user's trusted certificates.
     */
    @JvmStatic
    @Throws(
        java.security.cert.CertificateException::class,
        java.security.NoSuchAlgorithmException::class,
        java.io.IOException::class,
        KeyStoreException::class
    )
    fun getTrustStore(context: Context): KeyStore {
        val store = KeyStore.getInstance(STORE_FORMAT)
        try {
            context.openFileInput(STORE_FILE).use { fis ->
                store.load(fis, STORE_PASS.toCharArray())
            }
        } catch (e: FileNotFoundException) {
            store.load(null, null)
        }
        return store
    }

    @JvmStatic
    @Throws(
        java.io.IOException::class,
        java.security.cert.CertificateException::class,
        java.security.NoSuchAlgorithmException::class,
        KeyStoreException::class
    )
    fun saveTrustStore(context: Context, store: KeyStore) {
        // Write to a temporary file and rename over the store so a failure
        // mid-write cannot leave the existing trust store truncated.
        val tmp = File(context.filesDir, "$STORE_FILE.tmp")
        try {
            context.openFileOutput(tmp.name, Context.MODE_PRIVATE).use { fos ->
                store.store(fos, STORE_PASS.toCharArray())
            }
            if (!tmp.renameTo(File(context.filesDir, STORE_FILE))) {
                throw java.io.IOException("Could not replace trust store")
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    @JvmStatic
    fun clearTrustStore(context: Context) {
        context.deleteFile(STORE_FILE)
    }

    /**
     * Gets the app's trust store path.
     * @return null if the store has not yet been initialized, or the absolute path if it has.
     */
    @JvmStatic
    fun getTrustStorePath(context: Context): String? {
        val trustPath = File(context.filesDir, STORE_FILE)
        if (trustPath.exists()) return trustPath.absolutePath
        return null
    }

    @JvmStatic
    fun getTrustStoreFormat(): String {
        return STORE_FORMAT
    }

    /**
     * Gets the trust store password. The BKS store is an integrity container for
     * public pinned certificates, not a secret: the empty password only guards
     * against accidental corruption, so there is nothing here to protect.
     */
    @JvmStatic
    fun getTrustStorePassword(): String {
        return STORE_PASS
    }
}
