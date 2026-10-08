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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla.protocol;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import junit.framework.TestCase;

import se.lublin.humla.model.IUser;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.HumlaObserver;

/**
 * Verifies the desktop-parity self-mute echo guard in {@link ModelHandler}:
 * the toggle site applies state optimistically and logs, so a server echo
 * that changes nothing must not log a second time (while other users'
 * self-mute transitions still log).
 */
public class ModelHandlerSelfMuteTest extends TestCase {

    private static class CountingLogger implements HumlaLogger {
        int infoCount;
        @Override
        public void logInfo(String message) { infoCount++; }
        @Override
        public void logWarning(String message) {}
        @Override
        public void logError(String message) {}
    }

    private static class CountingObserver extends HumlaObserver {
        int userStateUpdatedCount;
        @Override
        public void onUserStateUpdated(IUser user) { userStateUpdatedCount++; }
        @Override
        public void onUserConnected(IUser user) {}
    }

    private Context createTestContext() {
        final Resources mockResources = new Resources(null, null, null) {
            @Override
            public String getString(int id) {
                return "test_string_" + id;
            }

            @Override
            public String getString(int id, Object... formatArgs) {
                return "test_formatted_string_" + id;
            }
        };

        return new ContextWrapper(null) {
            @Override
            public Resources getResources() {
                return mockResources;
            }
        };
    }

    private Mumble.UserState.Builder selfMuteState(boolean mute, boolean deaf) {
        return Mumble.UserState.newBuilder()
                .setSession(1)
                .setSelfMute(mute)
                .setSelfDeaf(deaf);
    }

    public void testSelfEchoLogsOnlyOnChange() {
        CountingLogger logger = new CountingLogger();
        CountingObserver observer = new CountingObserver();
        ModelHandler handler = new ModelHandler(
                createTestContext(), observer, logger, null, null);

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1).setName("self").build());

        // Reset setup noise (connect log, observer fires for join).
        logger.infoCount = 0;
        observer.userStateUpdatedCount = 0;

        // First transition: logs once, notifies once.
        handler.messageUserState(selfMuteState(true, false).build());
        assertEquals(1, logger.infoCount);
        assertEquals(1, observer.userStateUpdatedCount);

        // Duplicate echo of the optimistic value: no second log line.
        // The observer still fires (idempotent refresh, as on desktop).
        handler.messageUserState(selfMuteState(true, false).build());
        assertEquals(1, logger.infoCount);
        assertEquals(2, observer.userStateUpdatedCount);

        // Genuine transition back: logs again.
        handler.messageUserState(selfMuteState(false, false).build());
        assertEquals(2, logger.infoCount);
        assertEquals(3, observer.userStateUpdatedCount);
    }

    public void testOtherUserSelfMuteStillLogs() {
        CountingLogger logger = new CountingLogger();
        CountingObserver observer = new CountingObserver();
        ModelHandler handler = new ModelHandler(
                createTestContext(), observer, logger, null, null);

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1).setName("self").build());
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2).setName("other").build());

        logger.infoCount = 0;

        // Other user in the same (root) channel muting still logs.
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2).setSelfMute(true).setSelfDeaf(false).build());
        assertEquals(1, logger.infoCount);
    }
}
