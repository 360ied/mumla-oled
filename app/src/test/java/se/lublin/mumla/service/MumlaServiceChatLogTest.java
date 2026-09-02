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

package se.lublin.mumla.service;

import junit.framework.TestCase;

import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.Server;

public class MumlaServiceChatLogTest extends TestCase {

    private static class TestMumlaService extends MumlaService {
        private Server mTestServer;

        public void setTestServer(Server server) {
            mTestServer = server;
        }

        @Override
        public Server getTargetServer() {
            return mTestServer != null ? mTestServer : super.getTargetServer();
        }
    }

    public void testGetServerKey_NullServer() {
        assertEquals("", MumlaService.getServerKey(null));
    }

    public void testGetServerKey_SavedServer() {
        Server savedServer = new Server(42, "My Server", "mumble.example.com", 64738, "alice", "secret");
        assertEquals("id:42", MumlaService.getServerKey(savedServer));
    }

    public void testGetServerKey_UnsavedServer() {
        Server unsavedServer = new Server(-1, "AdHoc Server", "voice.example.org", 64739, "bob", "");
        assertEquals("endpoint:voice.example.org:64739:bob", MumlaService.getServerKey(unsavedServer));
    }

    public void testGetServerKey_HostCaseInsensitivity() {
        Server upperServer = new Server(-1, "Upper", "MUMBLE.EXAMPLE.COM", 64738, "alice", "");
        Server lowerServer = new Server(-1, "Lower", "mumble.example.com", 64738, "alice", "");
        assertEquals(MumlaService.getServerKey(lowerServer), MumlaService.getServerKey(upperServer));
    }

    public void testGetServerKey_DefaultPortFallback() {
        Server zeroPortServer = new Server(-1, "Zero Port", "mumble.example.com", 0, "charlie", "");
        assertEquals("endpoint:mumble.example.com:" + Constants.DEFAULT_PORT + ":charlie",
                MumlaService.getServerKey(zeroPortServer));
    }

    public void testConstants() {
        assertEquals(500, MumlaService.MAX_CHAT_LOG_SIZE);
        assertEquals(10, MumlaService.MAX_CACHED_SERVERS);
    }

    public void testMessageLogCapacityEviction() {
        TestMumlaService service = new TestMumlaService();
        Server server = new Server(1, "Server 1", "s1.example.com", 64738, "alice", "");
        service.setTestServer(server);

        for (int i = 0; i < 550; i++) {
            service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("msg-" + i), true));
        }

        List<IChatMessage> log = service.getMessageLog();
        assertEquals(MumlaService.MAX_CHAT_LOG_SIZE, log.size());
        assertEquals("msg-50", log.get(0).getBody());
        assertEquals("msg-549", log.get(log.size() - 1).getBody());
    }

    public void testServerSwitchingAndPersistence() {
        TestMumlaService service = new TestMumlaService();
        Server server1 = new Server(1, "Server 1", "s1.example.com", 64738, "alice", "");
        Server server2 = new Server(2, "Server 2", "s2.example.com", 64738, "alice", "");

        service.setTestServer(server1);
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("s1-msg1"), true));
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("s1-msg2"), true));
        assertEquals(2, service.getMessageLog().size());

        service.setTestServer(server2);
        assertTrue("Newly switched server should start with empty log", service.getMessageLog().isEmpty());
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("s2-msg1"), true));
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("s2-msg2"), true));
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("s2-msg3"), true));
        assertEquals(3, service.getMessageLog().size());

        // Switch back to server 1: previous history must be intact
        service.setTestServer(server1);
        List<IChatMessage> s1Log = service.getMessageLog();
        assertEquals(2, s1Log.size());
        assertEquals("s1-msg1", s1Log.get(0).getBody());
        assertEquals("s1-msg2", s1Log.get(1).getBody());

        // Switch back to server 2: previous history must be intact
        service.setTestServer(server2);
        List<IChatMessage> s2Log = service.getMessageLog();
        assertEquals(3, s2Log.size());
        assertEquals("s2-msg1", s2Log.get(0).getBody());
    }

    public void testClearMessageLogConsistency() {
        TestMumlaService service = new TestMumlaService();
        Server server1 = new Server(1, "Server 1", "s1.example.com", 64738, "alice", "");
        Server server2 = new Server(2, "Server 2", "s2.example.com", 64738, "alice", "");

        service.setTestServer(server1);
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("old-msg-1"), true));
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("old-msg-2"), true));
        assertEquals(2, service.getMessageLog().size());

        service.clearMessageLog();
        assertTrue("Log must be empty immediately after clear", service.getMessageLog().isEmpty());

        // Add a message after clear
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("new-msg-1"), true));
        assertEquals(1, service.getMessageLog().size());
        assertEquals("new-msg-1", service.getMessageLog().get(0).getBody());

        // Switch to server 2 and back to server 1: new-msg-1 must NOT be lost due to orphaned map entry
        service.setTestServer(server2);
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("s2-msg"), true));

        service.setTestServer(server1);
        List<IChatMessage> restoredLog = service.getMessageLog();
        assertEquals(1, restoredLog.size());
        assertEquals("new-msg-1", restoredLog.get(0).getBody());
    }

    public void testLruServerCacheEviction() {
        TestMumlaService service = new TestMumlaService();

        // Populate MAX_CACHED_SERVERS + 1 (11) servers
        for (int i = 1; i <= MumlaService.MAX_CACHED_SERVERS + 1; i++) {
            Server server = new Server(i, "Server " + i, "s" + i + ".example.com", 64738, "alice", "");
            service.setTestServer(server);
            service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("msg-from-server-" + i), true));
        }

        // Server 1 was the least recently used, so its cache should have been evicted
        Server server1 = new Server(1, "Server 1", "s1.example.com", 64738, "alice", "");
        service.setTestServer(server1);
        assertTrue("LRU server 1 chat log should be evicted when capacity exceeded",
                service.getMessageLog().isEmpty());

        // Server 2 should still be cached
        Server server2 = new Server(2, "Server 2", "s2.example.com", 64738, "alice", "");
        service.setTestServer(server2);
        assertEquals(1, service.getMessageLog().size());
        assertEquals("msg-from-server-2", service.getMessageLog().get(0).getBody());
    }

    public void testDefensiveSnapshot() {
        TestMumlaService service = new TestMumlaService();
        Server server = new Server(1, "Server 1", "s1.example.com", 64738, "alice", "");
        service.setTestServer(server);

        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("msg-1"), true));
        List<IChatMessage> snapshot = service.getMessageLog();
        assertEquals(1, snapshot.size());

        try {
            snapshot.add(new IChatMessage.TextMessage(new Message("illegal-add"), true));
            fail("getMessageLog() list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected
        }

        // Adding to service should not mutate the existing snapshot copy
        service.addChatMessageToLog(new IChatMessage.TextMessage(new Message("msg-2"), true));
        assertEquals("Snapshot must remain unchanged after subsequent additions", 1, snapshot.size());
        assertEquals(2, service.getMessageLog().size());
    }
}
