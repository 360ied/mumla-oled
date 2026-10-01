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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.Bundle;
import android.widget.TabHost;

import java.lang.reflect.Field;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import se.lublin.mumla.R;

/**
 * Verifies the ODD-15 teardown hardening: dismissing the dialog nulls all
 * three view fields, and a tab callback racing teardown returns safely
 * instead of throwing.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} is
 * incompatible with the module's {@code TestCase} convention. Nothing here
 * asserts WebView rendering — the shadow WebView is treated as unemulated.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CommentFragmentTeardownTest {

    private static Bundle pinnedBundle() {
        Bundle args = new Bundle();
        args.putInt(UserCommentFragment.ARG_SESSION, 1);
        args.putString(AbstractCommentFragment.ARG_COMMENT, "<p>x</p>");
        args.putBoolean(AbstractCommentFragment.ARG_EDITING, false);
        return args;
    }

    /** Shows the fragment as a dialog on a themed stub host. */
    private static final class ShownDialog {
        private final CommentDialogStubHost host;
        private final UserCommentFragment fragment;

        ShownDialog() {
            ActivityController<CommentDialogStubHost> controller =
                    Robolectric.buildActivity(CommentDialogStubHost.class);
            host = controller.get();
            host.setTheme(R.style.Theme_Mumla);
            controller.setup();
            fragment = new UserCommentFragment();
            fragment.setArguments(pinnedBundle());
            fragment.show(host.getSupportFragmentManager(), "comment");
            host.getSupportFragmentManager().executePendingTransactions();
        }

        UserCommentFragment fragment() {
            return fragment;
        }

        void dismiss() {
            fragment.dismiss();
            host.getSupportFragmentManager().executePendingTransactions();
        }
    }

    private static Object viewField(Object target, String name) throws ReflectiveOperationException {
        Field field =
                AbstractCommentFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    public void destroyViewNullsAllViewFields() throws ReflectiveOperationException {
        ShownDialog dialog = new ShownDialog();
        assertNotNull(viewField(dialog.fragment(), "mTabHost"));
        assertNotNull(viewField(dialog.fragment(), "mCommentView"));
        assertNotNull(viewField(dialog.fragment(), "mCommentEdit"));
        dialog.dismiss();
        assertNull(viewField(dialog.fragment(), "mTabHost"));
        assertNull(viewField(dialog.fragment(), "mCommentView"));
        assertNull(viewField(dialog.fragment(), "mCommentEdit"));
    }

    @Test
    public void tabCallbackAfterTeardownIsSafe() throws ReflectiveOperationException {
        ShownDialog dialog = new ShownDialog();
        TabHost tabHost = (TabHost) viewField(dialog.fragment(), "mTabHost");
        assertNotNull(tabHost);
        dialog.dismiss();
        tabHost.setCurrentTab(1);
        tabHost.setCurrentTab(0);
    }
}
