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

package se.lublin.mumla.db

import se.lublin.humla.model.Server

/**
 * An interface for persistent storage services (i.e. databases, cloud) to implement.
 * Created by andrew on 13/08/13.
 */
interface MumlaDatabase {
    fun open()
    fun close()

    fun getServers(): List<Server>
    fun addServer(server: Server)
    fun updateServer(server: Server)
    fun removeServer(server: Server)

    fun isCommentSeen(hash: String, commentHash: ByteArray): Boolean
    fun markCommentSeen(hash: String, commentHash: ByteArray)

    fun getPinnedChannels(serverId: Long): List<Int>
    fun addPinnedChannel(serverId: Long, channelId: Int)
    fun removePinnedChannel(serverId: Long, channelId: Int)
    fun isChannelPinned(serverId: Long, channelId: Int): Boolean

    fun getAccessTokens(serverId: Long): List<String>
    fun addAccessToken(serverId: Long, token: String)
    fun removeAccessToken(serverId: Long, token: String)

    fun getLocalMutedUsers(serverId: Long): List<Int>
    fun addLocalMutedUser(serverId: Long, userId: Int)
    fun removeLocalMutedUser(serverId: Long, userId: Int)

    fun getLocalIgnoredUsers(serverId: Long): List<Int>
    fun addLocalIgnoredUser(serverId: Long, userId: Int)
    fun removeLocalIgnoredUser(serverId: Long, userId: Int)

    /**
     * Adds the given certificate binary blob to the database with an optional passphrase.
     * @param name The user-readable certificate name.
     * @param certificate A PKCS12 binary blob.
     * @param password The PKCS12 decryption passphrase, or null if unencrypted.
     * @return A handle for the newly created certificate.
     */
    fun addCertificate(name: String, certificate: ByteArray, password: String?): DatabaseCertificate

    fun addCertificate(name: String, certificate: ByteArray): DatabaseCertificate {
        return addCertificate(name, certificate, null)
    }

    fun getCertificates(): List<DatabaseCertificate>

    /**
     * Obtains the certificate data associated with the given certificate ID.
     * @param id The certificate ID to fetch the data of.
     * @return A binary representation of a PKCS12 certificate.
     */
    fun getCertificateData(id: Long): ByteArray?

    /**
     * Obtains the certificate passphrase associated with the given certificate ID.
     * @param id The certificate ID to fetch the passphrase of.
     * @return The PKCS12 decryption passphrase, or null if unencrypted.
     */
    fun getCertificatePassword(id: Long): String?

    /**
     * Removes the certificate with the given ID.
     * @param id The certificate's identifier.
     */
    fun removeCertificate(id: Long)
}
