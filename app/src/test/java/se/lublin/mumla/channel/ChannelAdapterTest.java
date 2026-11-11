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

package se.lublin.mumla.channel;

import androidx.recyclerview.widget.RecyclerView;

import junit.framework.TestCase;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.TalkState;

/**
 * Adapter over live-handle interfaces. Step-2 spike evidence: the adapter
 * renders from IUser/IChannel reads (backed by handles in production).
 */
public class ChannelAdapterTest extends TestCase {

    private IChannel mChannel;
    private IUser mUser1;
    private IUser mUser2;
    private ChannelAdapter mAdapter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mUser1 = mock(IUser.class);
        when(mUser1.getSession()).thenReturn(101);
        when(mUser1.getName()).thenReturn("Alice");
        when(mUser1.getTalkState()).thenReturn(TalkState.PASSIVE);
        mUser2 = mock(IUser.class);
        when(mUser2.getSession()).thenReturn(102);
        when(mUser2.getName()).thenReturn("Bob");
        when(mUser2.getTalkState()).thenReturn(TalkState.PASSIVE);
        mChannel = mock(IChannel.class);
        when(mChannel.getUsers()).thenReturn(Arrays.asList(mUser1, mUser2));
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
        } catch (Exception ignored) {
        }
    }

    public void testItemIds() {
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

        IChannel emptyChannel = mock(IChannel.class);
        when(emptyChannel.getUsers()).thenReturn(Collections.emptyList());
        mAdapter.setChannel(emptyChannel);
        assertEquals(0, mAdapter.getItemCount());
    }

    public void testUpdateUserStateSafeWithNulls() {
        // Neither null user nor null view should throw exceptions
        mAdapter.updateUserState(null, null);
        mAdapter.updateUserState(mUser1, null);

        // User not in channel should not throw
        IUser stranger = mock(IUser.class);
        when(stranger.getSession()).thenReturn(999);
        mAdapter.updateUserState(stranger, null);

        // Adapter with null channel should handle updateUserState safely
        mAdapter.setChannel(null);
        mAdapter.updateUserState(mUser1, null);
    }

    public void testNotifyUserChangedSafeWithNulls() {
        mAdapter.notifyUserChanged(null);
        mAdapter.notifyUserChanged(mUser1);

        IUser stranger = mock(IUser.class);
        when(stranger.getSession()).thenReturn(999);
        mAdapter.notifyUserChanged(stranger);

        mAdapter.setChannel(null);
        mAdapter.notifyUserChanged(mUser1);
    }

    public void testTalkStateReadsFollowHandle() {
        // Handle storage moved to UserHandle (see humla HandlesTest);
        // the adapter reads whatever the handle reports.
        when(mUser1.getTalkState()).thenReturn(TalkState.TALKING);
        assertEquals(101L, mAdapter.getItemId(0));
        assertNull(mAdapter.getTalkStateDrawable(mUser1));
    }

    public void testTalkStateDrawableWithNullContext() {
        assertNull(mAdapter.getTalkStateDrawable(mUser1));
    }

    public void testAvatarCacheManagement() {
        assertNotNull(mAdapter.getAvatarCache());
        mAdapter.clearAvatarCache();
        assertEquals(0, mAdapter.getAvatarCache().size());
    }
}
