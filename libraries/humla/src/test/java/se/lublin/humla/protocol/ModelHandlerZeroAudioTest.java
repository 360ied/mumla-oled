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

import java.util.concurrent.atomic.AtomicInteger;

import se.lublin.humla.model.Channel;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.HumlaObserver;

/**
 * Unit tests verifying plausible zero-audio topology tracking in {@link ModelHandler}.
 */
public class ModelHandlerZeroAudioTest extends TestCase {

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

    private ModelHandler createModelHandler() {
        return new ModelHandler(
                createTestContext(),
                new HumlaObserver(),
                createTestLogger(),
                null,
                null
        );
    }

    public void testZeroAudioWhenLocalUserDeafened() {
        ModelHandler handler = createModelHandler();

        // Sync local session 1
        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());

        // Self user in root channel
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .setName("Self")
                .setChannelId(0)
                .setSelfDeaf(true)
                .build());

        // Peer in root channel, unmuted and able to speak
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setName("Peer")
                .setChannelId(0)
                .build());

        // Even with unmuted peer in current channel, self is deafened -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());
    }

    public void testZeroAudioWhenSoleUserOnServer() {
        ModelHandler handler = createModelHandler();

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .setName("Self")
                .setChannelId(0)
                .build());

        // Sole user on server -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());
    }

    public void testZeroAudioWhenPeersInOtherUnrelatedChannels() {
        ModelHandler handler = createModelHandler();

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());

        // Root channel (0) and unrelated channel (10)
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build());
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(10).setName("FarAway").build());

        // Self in channel 0
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .setName("Self")
                .setChannelId(0)
                .build());

        // Peer in channel 10
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setName("Peer")
                .setChannelId(10)
                .build());

        // Monitored channel is 0 (empty of peers) -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());

        // Peer moves into channel 0
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setChannelId(0)
                .build());

        // Peer is in channel 0 and unmuted -> NOT zero audio
        assertFalse(handler.isPlausiblyZeroAudio());
    }

    public void testZeroAudioWhenAllPeersInMonitoredChannelMuted() {
        ModelHandler handler = createModelHandler();

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build());

        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .setName("Self")
                .setChannelId(0)
                .build());

        // Peer in channel 0 who is self-muted
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setName("Peer")
                .setChannelId(0)
                .setSelfMute(true)
                .build());

        // All peers in monitored channel are muted -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());

        // Peer unmutes
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setSelfMute(false)
                .build());

        // Candidate can speak -> NOT zero audio
        assertFalse(handler.isPlausiblyZeroAudio());
    }

    public void testZeroAudioWithTransitiveChannelLinks() {
        ModelHandler handler = createModelHandler();

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());

        // Channels 1, 2, 3
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(1).setName("Ch1").build());
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(2).setName("Ch2").build());
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(3).setName("Ch3").build());

        // Link 1 <-> 2 and 2 <-> 3
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(1).addLinksAdd(2).build());
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(2).addLinksAdd(3).build());

        // Self in channel 1
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .setName("Self")
                .setChannelId(1)
                .build());

        // Peer in channel 3 (transitively linked via 2), unmuted
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setName("Peer")
                .setChannelId(3)
                .build());

        // Channel 3 is in getAllLinks() for channel 1 -> peer is in monitored set -> NOT zero audio
        assertFalse(handler.isPlausiblyZeroAudio());

        // Mute peer
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setMute(true)
                .build());

        // Peer muted -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());
    }

    public void testZeroAudioWithChannelListeners() {
        ModelHandler handler = createModelHandler();

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());

        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(1).setName("Ch1").build());
        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(2).setName("Ch2").build());

        // Self in channel 1
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .setName("Self")
                .setChannelId(1)
                .build());

        // Peer in channel 2, unmuted
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(2)
                .setName("Peer")
                .setChannelId(2)
                .build());

        // Not listening to Ch2 yet -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());

        // Self listens to Ch2
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .addListeningChannelAdd(2)
                .build());

        // Ch2 is now listened to, and Ch2 has unmuted peer -> NOT zero audio
        assertFalse(handler.isPlausiblyZeroAudio());

        // Self removes listener for Ch2
        handler.messageUserState(Mumble.UserState.newBuilder()
                .setSession(1)
                .addListeningChannelRemove(2)
                .build());

        // Ch2 no longer listened to -> zero audio
        assertTrue(handler.isPlausiblyZeroAudio());
    }

    public void testListenerCallbackTriggeredOnStateChanges() {
        ModelHandler handler = createModelHandler();
        final AtomicInteger notifications = new AtomicInteger(0);
        handler.setOnPlausibleZeroAudioListener(new ModelHandler.OnPlausibleZeroAudioListener() {
            @Override
            public void onPlausibleZeroAudioChanged() {
                notifications.incrementAndGet();
            }
        });

        handler.messageServerSync(Mumble.ServerSync.newBuilder().setSession(1).build());
        assertEquals(1, notifications.get());

        handler.messageUserState(Mumble.UserState.newBuilder().setSession(1).setName("Self").build());
        assertEquals(2, notifications.get());

        handler.messageChannelState(Mumble.ChannelState.newBuilder().setChannelId(1).setName("Ch1").build());
        assertEquals(3, notifications.get());

        handler.messageUserRemove(Mumble.UserRemove.newBuilder().setSession(1).build());
        assertEquals(4, notifications.get());
    }

    public void testForwardChannelLinksCreateStubsAndLinkBidirectionally() {
        ModelHandler handler = createModelHandler();

        // Channel 1 arrives linking to Channel 2 (which does not exist yet)
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(1)
                .setName("Ch1")
                .addLinks(2)
                .build());

        assertNotNull(handler.getChannel(1));
        assertNotNull(handler.getChannel(2));
        assertEquals(1, handler.getChannel(1).getLinks().size());
        assertEquals(1, handler.getChannel(2).getLinks().size());
        assertTrue(handler.getChannel(1).getLinks().contains(handler.getChannel(2)));
        assertTrue(handler.getChannel(2).getLinks().contains(handler.getChannel(1)));

        // Channel 2 arrives later with full state linking back to Channel 1
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(2)
                .setName("Ch2")
                .addLinks(1)
                .build());

        assertEquals("Ch2", handler.getChannel(2).getName());
        assertEquals(1, handler.getChannel(1).getLinks().size());
        assertEquals(1, handler.getChannel(2).getLinks().size());
        assertTrue(handler.getChannel(1).getLinks().contains(handler.getChannel(2)));
        assertTrue(handler.getChannel(2).getLinks().contains(handler.getChannel(1)));
    }

    public void testClearResetsSessionAndPermissions() {
        ModelHandler handler = createModelHandler();
        handler.messageServerSync(Mumble.ServerSync.newBuilder()
                .setSession(42)
                .setPermissions(7)
                .build());

        assertEquals(7, handler.getPermissions());

        handler.clear();
        assertEquals(0, handler.getPermissions());
    }

    public void testForwardParentReferenceCreatesStubAndAdoptsChild() {
        ModelHandler handler = createModelHandler();

        // Channel 2 arrives with parent 1 (where Channel 1 has not yet been received)
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(2)
                .setName("Child")
                .setParent(1)
                .build());

        // Stub for parent Channel 1 must exist
        Channel parentStub = handler.getChannel(1);
        Channel child = handler.getChannel(2);
        assertNotNull(parentStub);
        assertNotNull(child);
        assertEquals(parentStub, child.getParent());
        assertEquals(1, parentStub.getSubchannels().size());
        assertTrue(parentStub.getSubchannels().contains(child));

        // Parent arrives later with full state
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(1)
                .setName("Root")
                .build());

        assertEquals("Root", handler.getChannel(1).getName());
        assertEquals(1, handler.getChannel(1).getSubchannels().size());
        assertTrue(handler.getChannel(1).getSubchannels().contains(child));
        assertEquals(handler.getChannel(1), child.getParent());
    }

    public void testReentrantChannelStateDoesNotDuplicateSubchannels() {
        ModelHandler handler = createModelHandler();

        // Root channel
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(0)
                .setName("Root")
                .build());

        // Child channel
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(1)
                .setName("Sub")
                .setParent(0)
                .build());

        assertEquals(1, handler.getChannel(0).getSubchannels().size());

        // Re-entrant ChannelState updates with same parent
        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(1)
                .setName("SubRenamed")
                .setParent(0)
                .build());

        handler.messageChannelState(Mumble.ChannelState.newBuilder()
                .setChannelId(1)
                .setDescription("A description update")
                .setParent(0)
                .build());

        // Must still have exactly 1 subchannel, no duplicate entries
        assertEquals(1, handler.getChannel(0).getSubchannels().size());
        assertEquals("SubRenamed", handler.getChannel(0).getSubchannels().get(0).getName());
    }
}
