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

package se.lublin.humla.protocol;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import se.lublin.humla.model.IUser;
import se.lublin.humla.model.User;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.HumlaObserver;

/**
 * Tests verifying that ModelHandler correctly manages user removals, ensuring
 * that disconnected or kicked users are evicted from the user map to prevent
 * memory leaks and stale references (ODD-01).
 */
public class ModelHandlerUserRemoveTest extends TestCase {

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

    private HumlaLogger createTestLogger() {
        return new HumlaLogger() {
            @Override
            public void logInfo(String message) {}
            @Override
            public void logWarning(String message) {}
            @Override
            public void logError(String message) {}
        };
    }

    public void testUserRemovedFromUsersMap() {
        final AtomicReference<IUser> notifiedUser = new AtomicReference<>();
        final AtomicReference<String> notifiedReason = new AtomicReference<>();

        HumlaObserver observer = new HumlaObserver() {
            @Override
            public void onUserRemoved(IUser user, String reason) {
                notifiedUser.set(user);
                notifiedReason.set(reason);
            }
        };

        ModelHandler handler = new ModelHandler(createTestContext(), observer, createTestLogger(), null, null);

        // Add a user via UserState
        Mumble.UserState userState = Mumble.UserState.newBuilder()
                .setSession(42)
                .setName("Alice")
                .build();
        handler.messageUserState(userState);

        User alice = handler.getUser(42);
        assertNotNull("User Alice should exist in model", alice);
        assertEquals("Alice", alice.getName());
        assertEquals(1, handler.getUsers().size());
        assertTrue("getUsers() should contain session 42", handler.getUsers().containsKey(42));
        assertNotNull("Alice should have a default root channel assigned", alice.getChannel());

        // Remove the user via UserRemove
        Mumble.UserRemove removeMsg = Mumble.UserRemove.newBuilder()
                .setSession(42)
                .setReason("Left server")
                .build();
        handler.messageUserRemove(removeMsg);

        // Verify user is evicted from mUsers (ODD-01 fix)
        assertNull("Departed user must be removed from mUsers", handler.getUser(42));
        assertFalse("getUsers() must not contain departed session", handler.getUsers().containsKey(42));
        assertEquals("mUsers size must be 0 after eviction", 0, handler.getUsers().size());

        // Verify observer notification
        assertSame("Observer should be notified with Alice's User instance", alice, notifiedUser.get());
        assertEquals("Left server", notifiedReason.get());

        // Verify user was detached from channel
        assertNull("Alice's channel must be cleared upon removal", alice.getChannel());
    }

    public void testUserRemoveWithKickAndActor() {
        final AtomicReference<IUser> notifiedUser = new AtomicReference<>();
        final AtomicReference<String> notifiedReason = new AtomicReference<>();

        HumlaObserver observer = new HumlaObserver() {
            @Override
            public void onUserRemoved(IUser user, String reason) {
                notifiedUser.set(user);
                notifiedReason.set(reason);
            }
        };

        ModelHandler handler = new ModelHandler(createTestContext(), observer, createTestLogger(), null, null);

        // Add actor and target
        handler.messageUserState(Mumble.UserState.newBuilder().setSession(1).setName("Admin").build());
        handler.messageUserState(Mumble.UserState.newBuilder().setSession(2).setName("Troublemaker").build());
        assertEquals(2, handler.getUsers().size());

        // Kick the troublemaker
        Mumble.UserRemove kickMsg = Mumble.UserRemove.newBuilder()
                .setSession(2)
                .setActor(1)
                .setReason("Violating rules")
                .setBan(false)
                .build();
        handler.messageUserRemove(kickMsg);

        assertNull("Kicked user must be removed from mUsers", handler.getUser(2));
        assertNotNull("Actor must still remain in mUsers", handler.getUser(1));
        assertEquals(1, handler.getUsers().size());
        assertEquals("Violating rules", notifiedReason.get());
    }

    public void testRemoveNonExistentUserIsGraceful() {
        final AtomicBoolean called = new AtomicBoolean(false);
        final AtomicReference<IUser> userRef = new AtomicReference<>();

        HumlaObserver observer = new HumlaObserver() {
            @Override
            public void onUserRemoved(IUser user, String reason) {
                called.set(true);
                userRef.set(user);
            }
        };

        ModelHandler handler = new ModelHandler(createTestContext(), observer, createTestLogger(), null, null);

        // Remove unknown session
        Mumble.UserRemove ghostRemove = Mumble.UserRemove.newBuilder()
                .setSession(999)
                .setReason("Phantom user")
                .build();
        handler.messageUserRemove(ghostRemove);

        assertTrue("Observer should still be invoked on unknown user remove", called.get());
        assertNull("Observer user param should be null for unknown session", userRef.get());
        assertNull("mUsers should remain empty", handler.getUser(999));
        assertEquals(0, handler.getUsers().size());
    }

    public void testConcurrentReadAndRemoveSafety() throws Exception {
        ModelHandler handler = new ModelHandler(createTestContext(), new HumlaObserver(), createTestLogger(), null, null);

        // Populate initial users
        final int userCount = 100;
        for (int i = 0; i < userCount; i++) {
            handler.messageUserState(Mumble.UserState.newBuilder()
                    .setSession(i)
                    .setName("User_" + i)
                    .build());
        }
        assertEquals(userCount, handler.getUsers().size());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean failure = new AtomicBoolean(false);

        // Reader task: continuously reads and iterates mUsers
        Runnable reader = new Runnable() {
            @Override
            public void run() {
                try {
                    latch.await();
                    for (int i = 0; i < 500; i++) {
                        for (User u : handler.getUsers().values()) {
                            if (u != null) {
                                u.getName();
                            }
                        }
                    }
                } catch (Exception e) {
                    failure.set(true);
                }
            }
        };

        // Remover task: removes even-numbered sessions
        Runnable remover = new Runnable() {
            @Override
            public void run() {
                try {
                    latch.await();
                    for (int i = 0; i < userCount; i += 2) {
                        handler.messageUserRemove(Mumble.UserRemove.newBuilder()
                                .setSession(i)
                                .setReason("Departed")
                                .build());
                    }
                } catch (Exception e) {
                    failure.set(true);
                }
            }
        };

        executor.execute(reader);
        executor.execute(reader);
        executor.execute(remover);

        latch.countDown();
        executor.shutdown();
        assertTrue("Executor should terminate cleanly", executor.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse("Concurrent iteration and removal must not throw exceptions", failure.get());

        // Verify remaining users
        assertEquals(userCount / 2, handler.getUsers().size());
        for (int i = 0; i < userCount; i++) {
            if (i % 2 == 0) {
                assertNull("Even sessions must have been evicted", handler.getUser(i));
            } else {
                assertNotNull("Odd sessions must still exist", handler.getUser(i));
            }
        }
    }
}
