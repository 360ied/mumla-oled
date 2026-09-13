/*
 * Copyright (C) 2026 Mumla OLED Contributors
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

package se.lublin.mumla.db;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import se.lublin.humla.model.Server;

public class MumlaDatabaseTest extends TestCase {

    private static class FakeMumlaDatabase implements MumlaDatabase {
        private final Map<Long, byte[]> mData = new HashMap<>();
        private final Map<Long, String> mPasswords = new HashMap<>();
        private final List<DatabaseCertificate> mCerts = new ArrayList<>();
        private long mNextId = 1L;

        @Override public void open() {}
        @Override public void close() {}
        @Override public List<Server> getServers() { return null; }
        @Override public void addServer(Server server) {}
        @Override public void updateServer(Server server) {}
        @Override public void removeServer(Server server) {}
        @Override public boolean isCommentSeen(String hash, byte[] commentHash) { return false; }
        @Override public void markCommentSeen(String hash, byte[] commentHash) {}
        @Override public List<Integer> getPinnedChannels(long serverId) { return null; }
        @Override public void addPinnedChannel(long serverId, int channelId) {}
        @Override public void removePinnedChannel(long serverId, int channelId) {}
        @Override public boolean isChannelPinned(long serverId, int channelId) { return false; }
        @Override public List<String> getAccessTokens(long serverId) { return null; }
        @Override public void addAccessToken(long serverId, String token) {}
        @Override public void removeAccessToken(long serverId, String token) {}
        @Override public List<Integer> getLocalMutedUsers(long serverId) { return null; }
        @Override public void addLocalMutedUser(long serverId, int userId) {}
        @Override public void removeLocalMutedUser(long serverId, int userId) {}
        @Override public List<Integer> getLocalIgnoredUsers(long serverId) { return null; }
        @Override public void addLocalIgnoredUser(long serverId, int userId) {}
        @Override public void removeLocalIgnoredUser(long serverId, int userId) {}

        @Override
        public DatabaseCertificate addCertificate(String name, byte[] certificate, String password) {
            long id = mNextId++;
            mData.put(id, certificate);
            mPasswords.put(id, password);
            DatabaseCertificate cert = new DatabaseCertificate(id, name);
            mCerts.add(cert);
            return cert;
        }

        @Override
        public List<DatabaseCertificate> getCertificates() {
            return new ArrayList<>(mCerts);
        }

        @Override
        public byte[] getCertificateData(long id) {
            return mData.get(id);
        }

        @Override
        public String getCertificatePassword(long id) {
            return mPasswords.get(id);
        }

        @Override
        public void removeCertificate(long id) {
            mData.remove(id);
            mPasswords.remove(id);
            mCerts.removeIf(c -> c.getId() == id);
        }
    }

    public void testAddCertificateWithPassword() {
        MumlaDatabase db = new FakeMumlaDatabase();
        byte[] data = new byte[]{1, 2, 3, 4};
        DatabaseCertificate cert = db.addCertificate("test.p12", data, "secret");

        assertEquals(1L, cert.getId());
        assertEquals("test.p12", cert.getName());
        assertEquals(data, db.getCertificateData(cert.getId()));
        assertEquals("secret", db.getCertificatePassword(cert.getId()));
    }

    public void testAddCertificateWithoutPasswordDefault() {
        MumlaDatabase db = new FakeMumlaDatabase();
        byte[] data = new byte[]{5, 6, 7, 8};
        DatabaseCertificate cert = db.addCertificate("unencrypted.p12", data);

        assertEquals(1L, cert.getId());
        assertEquals("unencrypted.p12", cert.getName());
        assertEquals(data, db.getCertificateData(cert.getId()));
        assertNull(db.getCertificatePassword(cert.getId()));
    }
}
