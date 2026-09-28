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

import se.lublin.humla.model.Channel;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.HumlaObserver;

/**
 * Unit tests verifying channel topology tracking, forward references, and parenting in {@link ModelHandler}.
 */
public class ModelHandlerTopologyTest extends TestCase {

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
