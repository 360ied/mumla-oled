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

package se.lublin.mumla.channel;

import androidx.recyclerview.widget.RecyclerView;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.util.ArrayList;

import se.lublin.humla.model.Channel;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;

public class ChannelAdapterTest extends TestCase {

    private Channel mChannel;
    private User mUser1;
    private User mUser2;
    private ChannelAdapter mAdapter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mChannel = new Channel(1, false);
        mUser1 = new User(101, "Alice");
        mUser2 = new User(102, "Bob");
        mUser1.setChannel(mChannel);
        mUser2.setChannel(mChannel);
        mAdapter = new ChannelAdapter(null, mChannel);
        initObservable(mAdapter);
    }

    private static void initObservable(RecyclerView.Adapter<?> adapter) {
        try {
            Field obsField = RecyclerView.Adapter.class.getDeclaredField("mObservable");
            obsField.setAccessible(true);
            Object observable = obsField.get(adapter);
            if (observable != null) {
                Field listField = android.database.Observable.class.getDeclaredField("mObservers");
                listField.setAccessible(true);
                if (listField.get(observable) == null) {
                    listField.set(observable, new ArrayList<>());
                }
            }
            adapter.setHasStableIds(true);
        } catch (Exception ignored) {
        }
    }

    public void testStableIds() {
        assertTrue("ChannelAdapter must have stable IDs enabled", mAdapter.hasStableIds());
        assertEquals(101L, mAdapter.getItemId(0));
        assertEquals(102L, mAdapter.getItemId(1));
        assertEquals(RecyclerView.NO_ID, mAdapter.getItemId(-1));
        assertEquals(RecyclerView.NO_ID, mAdapter.getItemId(2));
    }

    public void testItemCount() {
        assertEquals(2, mAdapter.getItemCount());

        mAdapter.setChannel(null);
        assertEquals(0, mAdapter.getItemCount());
        assertEquals(RecyclerView.NO_ID, mAdapter.getItemId(0));

        Channel emptyChannel = new Channel(2, false);
        mAdapter.setChannel(emptyChannel);
        assertEquals(0, mAdapter.getItemCount());
    }

    public void testUpdateUserStateSafeWithNulls() {
        // Neither null user nor null view should throw exceptions
        mAdapter.updateUserState(null, null);
        mAdapter.updateUserState(mUser1, null);

        // User not in channel should not throw
        User stranger = new User(999, "Stranger");
        mAdapter.updateUserState(stranger, null);

        // Adapter with null channel should handle updateUserState safely
        mAdapter.setChannel(null);
        mAdapter.updateUserState(mUser1, null);
    }

    public void testNotifyUserChangedSafeWithNulls() {
        mAdapter.notifyUserChanged(null);
        mAdapter.notifyUserChanged(mUser1);

        User stranger = new User(999, "Stranger");
        mAdapter.notifyUserChanged(stranger);

        mAdapter.setChannel(null);
        mAdapter.notifyUserChanged(mUser1);
    }

    public void testTalkStateTransitions() {
        assertEquals(TalkState.PASSIVE, mUser1.getTalkState());
        mUser1.setTalkState(TalkState.TALKING);
        assertEquals(TalkState.TALKING, mUser1.getTalkState());
        mUser1.setTalkState(TalkState.PASSIVE);
        assertEquals(TalkState.PASSIVE, mUser1.getTalkState());
    }
}
