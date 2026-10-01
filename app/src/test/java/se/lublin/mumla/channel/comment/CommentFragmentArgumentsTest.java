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

package se.lublin.mumla.channel.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import se.lublin.mumla.R;

/**
 * Verifies the ODD-13 argument guards: a missing bundle or a missing key
 * fails fast with {@link IllegalStateException} instead of a bare
 * {@link NullPointerException} or a silent {@code 0}, while the pinned
 * production bundles behave exactly as before.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} is
 * incompatible with the module's {@code TestCase} convention.
 *
 * <p>Driver note: {@code isEditing()} is host-free, but {@code onCreate()}
 * cannot be driven on a bare instance — {@code super.onCreate()} walks the
 * child {@code FragmentManager}, which needs an attached host under
 * androidx.fragment 1.8.9. The {@code onCreate} rows therefore attach the
 * fragment to {@link CommentDialogStubHost} via a synchronous transaction.
 * Attaching a {@code DialogFragment} drives dialog creation, so the host is
 * themed exactly like the teardown test for parity.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CommentFragmentArgumentsTest {

    private static Bundle userBundle(boolean editing) {
        Bundle args = new Bundle();
        args.putInt(UserCommentFragment.ARG_SESSION, 1);
        args.putString(AbstractCommentFragment.ARG_COMMENT, "<p>x</p>");
        args.putBoolean(AbstractCommentFragment.ARG_EDITING, editing);
        return args;
    }

    private static Bundle channelBundle(boolean editing) {
        Bundle args = new Bundle();
        args.putInt(ChannelDescriptionFragment.ARG_CHANNEL, 1);
        args.putString(AbstractCommentFragment.ARG_COMMENT, "<p>x</p>");
        args.putBoolean(AbstractCommentFragment.ARG_EDITING, editing);
        return args;
    }

    /**
     * Attaches {@code fragment} to a themed stub host, driving {@code onCreate()}
     * (and dialog creation) synchronously.
     * No host teardown needed: Robolectric discards the per-test sandbox automatically.
     */
    private static void attach(AbstractCommentFragment fragment) {
        ActivityController<CommentDialogStubHost> controller =
                Robolectric.buildActivity(CommentDialogStubHost.class);
        CommentDialogStubHost host = controller.get();
        host.setTheme(R.style.Theme_Mumla);
        controller.setup();
        host.getSupportFragmentManager().beginTransaction()
                .add(fragment, "comment").commitNow();
    }

    /** Pins the oracle to the argument guard rather than to "some ISE". */
    private static void assertArgumentError(IllegalStateException e, String key) {
        assertTrue(e.getMessage() != null
                && e.getMessage().contains("argument")
                && e.getMessage().contains(key));
    }

    @Test
    public void userOnCreateWithoutArgumentsThrows() {
        assertArgumentError(assertThrows(IllegalStateException.class,
                () -> attach(new UserCommentFragment())), "argument");
    }

    @Test
    public void userIsEditingWithoutArgumentsThrows() {
        assertArgumentError(assertThrows(IllegalStateException.class,
                () -> new UserCommentFragment().isEditing()), "argument");
    }

    @Test
    public void userOnCreateWithBundlePreservesEditing() {
        UserCommentFragment fragment = new UserCommentFragment();
        fragment.setArguments(userBundle(true));
        attach(fragment);
        assertTrue(fragment.isEditing());
    }

    @Test
    public void userOnCreateWithViewBundleClearsEditing() {
        UserCommentFragment fragment = new UserCommentFragment();
        fragment.setArguments(userBundle(false));
        attach(fragment);
        assertFalse(fragment.isEditing());
    }

    @Test
    public void userOnCreateWithBundleMissingSessionThrows() {
        Bundle args = userBundle(false);
        args.remove(UserCommentFragment.ARG_SESSION);
        UserCommentFragment fragment = new UserCommentFragment();
        fragment.setArguments(args);
        assertArgumentError(assertThrows(IllegalStateException.class,
                () -> attach(fragment)), UserCommentFragment.ARG_SESSION);
    }

    @Test
    public void userOnCreateWithBundleMissingEditingThrows() {
        Bundle args = userBundle(false);
        args.remove(AbstractCommentFragment.ARG_EDITING);
        UserCommentFragment fragment = new UserCommentFragment();
        fragment.setArguments(args);
        assertArgumentError(assertThrows(IllegalStateException.class,
                () -> attach(fragment)), AbstractCommentFragment.ARG_EDITING);
    }

    @Test
    public void channelOnCreateWithoutArgumentsThrows() {
        assertArgumentError(assertThrows(IllegalStateException.class,
                () -> attach(new ChannelDescriptionFragment())), "argument");
    }

    @Test
    public void channelOnCreateWithBundleSucceeds() {
        ChannelDescriptionFragment fragment = new ChannelDescriptionFragment();
        fragment.setArguments(channelBundle(false));
        attach(fragment);
        assertFalse(fragment.isEditing());
    }

    @Test
    public void channelOnCreateWithBundleMissingChannelThrows() {
        Bundle args = channelBundle(false);
        args.remove(ChannelDescriptionFragment.ARG_CHANNEL);
        ChannelDescriptionFragment fragment = new ChannelDescriptionFragment();
        fragment.setArguments(args);
        assertArgumentError(assertThrows(IllegalStateException.class,
                () -> attach(fragment)), ChannelDescriptionFragment.ARG_CHANNEL);
    }

    @Test
    public void channelOnCreateWithEditingRejects() {
        ChannelDescriptionFragment fragment = new ChannelDescriptionFragment();
        fragment.setArguments(channelBundle(true));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> attach(fragment));
        assertTrue(e.getMessage() != null && e.getMessage().contains("editing"));
    }

    @Test
    public void userAttachToNonProviderHostThrows() {
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class)
                .setup().get();
        UserCommentFragment fragment = new UserCommentFragment();
        fragment.setArguments(userBundle(false));
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                host.getSupportFragmentManager().beginTransaction()
                        .add(fragment, "comment").commitNow());
        assertEquals(RuntimeException.class, e.getClass());
        assertTrue(e.getMessage() != null && e.getMessage().contains("HumlaServiceProvider"));
        assertTrue(e.getCause() instanceof ClassCastException);
    }
}
