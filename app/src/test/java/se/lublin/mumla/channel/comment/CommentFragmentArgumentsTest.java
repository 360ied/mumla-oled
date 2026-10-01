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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;
import se.lublin.mumla.util.HumlaServiceProvider;

/**
 * Verifies the ODD-13 argument guards: a no-args instantiation fails fast
 * with {@link IllegalStateException} (via {@code requireArguments()})
 * instead of a bare {@link NullPointerException}, while the pinned
 * production bundles behave exactly as before.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} is
 * incompatible with the module's {@code TestCase} convention.
 *
 * <p>Driver note: {@code isEditing()} is host-free, but {@code onCreate()}
 * cannot be driven on a bare instance — {@code super.onCreate()} walks the
 * child {@code FragmentManager}, which needs an attached host under
 * androidx.fragment 1.8.9. The {@code onCreate} rows therefore attach the
 * fragment to {@link CommentDialogStubHost} via a synchronous transaction
 * (no dialog is created, so no theme, service, or WebView is involved).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CommentFragmentArgumentsTest {

    private static Bundle userBundle(boolean editing) {
        Bundle args = new Bundle();
        args.putInt("session", 1);
        args.putString("comment", "<p>x</p>");
        args.putBoolean("editing", editing);
        return args;
    }

    private static Bundle channelBundle() {
        Bundle args = new Bundle();
        args.putInt("channel", 1);
        args.putString("comment", "<p>x</p>");
        args.putBoolean("editing", false);
        return args;
    }

    /**
     * Attaches {@code fragment} to a stub host, driving {@code onCreate()} synchronously.
     * No host teardown needed: Robolectric discards the per-test sandbox automatically.
     */
    private static CommentDialogStubHost attach(AbstractCommentFragment fragment) {
        CommentDialogStubHost host = Robolectric.buildActivity(CommentDialogStubHost.class)
                .setup().get();
        host.getSupportFragmentManager().beginTransaction()
                .add(fragment, "comment").commitNow();
        return host;
    }

    @Test(expected = IllegalStateException.class)
    public void noArgsOnCreateThrowsIllegalState() {
        attach(new UserCommentFragment());
    }

    @Test(expected = IllegalStateException.class)
    public void noArgsIsEditingThrowsIllegalState() {
        new UserCommentFragment().isEditing();
    }

    @Test
    public void suppliedBundlePreservesBehavior() {
        UserCommentFragment fragment = new UserCommentFragment();
        fragment.setArguments(userBundle(true));
        attach(fragment);
        assertTrue(fragment.isEditing());
    }

    @Test(expected = IllegalStateException.class)
    public void channelFragmentNoArgsThrows() {
        attach(new ChannelDescriptionFragment());
    }

    @Test
    public void channelFragmentSuppliedBundleNoThrow() {
        ChannelDescriptionFragment fragment = new ChannelDescriptionFragment();
        fragment.setArguments(channelBundle());
        attach(fragment);
        assertFalse(fragment.isEditing());
    }
}

/**
 * Minimal test-local host satisfying the {@code HumlaServiceProvider} cast
 * in {@code onAttach}. Shared with {@code CommentFragmentTeardownTest}.
 * Returning null from {@code getService()} is safe because every pinned
 * bundle carries a non-null {@code "comment"}, so the provider-dependent
 * {@code requestComment} path is never entered. Production callers can pass a
 * null comment (entering {@code requestComment}); these tests intentionally
 * pin non-null bundles to isolate the argument guards.
 */
class CommentDialogStubHost extends FragmentActivity implements HumlaServiceProvider {
    @Override
    public IMumlaService getService() {
        return null;
    }

    @Override
    public void addServiceFragment(HumlaServiceFragment fragment) {
    }

    @Override
    public void removeServiceFragment(HumlaServiceFragment fragment) {
    }
}
