/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TabHost;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.util.HumlaServiceProvider;

/**
 * Fragment to change your comment using basic WYSIWYG tools.
 * Created by andrew on 10/08/13.
 */
public abstract class AbstractCommentFragment extends DialogFragment {

    private static final String TAG = "AbstractCommentFragment";

    public static final String ARG_COMMENT = "comment";
    public static final String ARG_EDITING = "editing";

    private TabHost mTabHost;
    private WebView mCommentView;
    private EditText mCommentEdit;
    private HumlaServiceProvider mProvider;
    private HumlaObserver mPendingObserver;
    private String mComment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        mComment = args.getString(ARG_COMMENT);
        validateArguments(args);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        try {
            mProvider = (HumlaServiceProvider) context;
        } catch (ClassCastException e) {
            // A wrapped context (theme wrapper, test harness) is not the host activity itself.
            if (getActivity() instanceof HumlaServiceProvider) {
                mProvider = (HumlaServiceProvider) getActivity();
            } else {
                throw new RuntimeException(context.getClass().getName() + " must implement HumlaServiceProvider!", e);
            }
        }
    }

    @Override
    public void onDetach() {
        mProvider = null;
        super.onDetach();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View view = inflater.inflate(R.layout.dialog_comment, null, false);

        mCommentView = (WebView) view.findViewById(R.id.comment_view);
        hardenCommentWebView();
        mCommentEdit = (EditText) view.findViewById(R.id.comment_edit);

        mTabHost = (TabHost) view.findViewById(R.id.comment_tabhost);
        mTabHost.setup();

        if (mComment == null) {
            mCommentView.loadData("Loading...", null, null);
            IHumlaService service = mProvider != null ? mProvider.getService() : null;
            if (service != null) {
                requestComment(service);
            }
        } else {
            loadComment(mComment);
        }

        TabHost.TabSpec viewTab = mTabHost.newTabSpec("View");
        viewTab.setIndicator(getString(R.string.comment_view));
        viewTab.setContent(R.id.comment_tab_view);

        TabHost.TabSpec editTab = mTabHost.newTabSpec("Edit");
        editTab.setIndicator(getString(isEditing() ? R.string.comment_edit_source : R.string.comment_view_source));
        editTab.setContent(R.id.comment_tab_edit);

        mTabHost.addTab(viewTab);
        mTabHost.addTab(editTab);

        mTabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            @Override
            public void onTabChanged(String tabId) {
                // View hierarchy may be torn down; never touch nulled fields.
                if (mCommentView == null || mCommentEdit == null) return;
                if ("View".equals(tabId)) {
                    // When switching back to view tab, update with user's HTML changes.
                    mCommentView.loadData(mCommentEdit.getText().toString(), "text/html", "UTF-8");
                } else if ("Edit".equals(tabId) && "".equals(mCommentEdit.getText().toString())) {
                    // Load edittext content for the first time when the tab is selected, to improve performance with long messages.
                    mCommentEdit.setText(mComment);
                }
            }
        });

        mTabHost.setCurrentTab(isEditing() ? 1 : 0);

        if (isEditing()) {
            return new MaterialAlertDialogBuilder(requireActivity())
                    .setView(view)
                    .setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.save, (dialog, which) -> {
                        IHumlaService service = mProvider != null ? mProvider.getService() : null;
                        if (service != null) {
                            editComment(service, mCommentEdit.getText().toString());
                        }
                    })
                    .create();
        } else {
            return new MaterialAlertDialogBuilder(requireActivity())
                    .setView(view)
                    .setNegativeButton(R.string.close, null)
                    .create();
        }
    }

    @Override
    public void onDestroyView() {
        // Release the WebView's native peer; otherwise the renderer and its
        // host Activity stay reachable via mCommentView after dismissal.
        if (mCommentView != null) {
            ViewGroup parent = (ViewGroup) mCommentView.getParent();
            if (parent != null) {
                parent.removeView(mCommentView);
            }
            mCommentView.destroy();
            mCommentView = null;
        }
        mTabHost = null;
        mCommentEdit = null;
        super.onDestroyView();
    }

    /**
     * Locks down the comment WebView against hostile server-supplied HTML.
     * Both the received comment ({@link #loadComment}) and the edit-preview
     * path in {@link #onCreateDialog} render through {@code mCommentView},
     * so hardening here covers both. Remote images follow the existing
     * external-images setting (default off).
     */
    private void hardenCommentWebView() {
        mCommentView.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleCommentUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Subframe loads (e.g. <iframe>) never leave the WebView, so
                // embedded hostile HTML cannot pop an external browser.
                // Main-frame navigations open externally via handleCommentUrl.
                if (request == null || !request.isForMainFrame()) return true;
                return handleCommentUrl(request.getUrl() == null ? null : request.getUrl().toString());
            }
        });
        mCommentView.getSettings().setJavaScriptEnabled(false);
        mCommentView.getSettings().setAllowFileAccess(false);
        mCommentView.getSettings().setAllowContentAccess(false);
        mCommentView.getSettings().setAllowFileAccessFromFileURLs(false);
        mCommentView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        boolean loadExternalImages = Settings.getInstance(requireContext()).shouldLoadExternalImages();
        mCommentView.getSettings().setBlockNetworkImage(!loadExternalImages);
        mCommentView.getSettings().setBlockNetworkLoads(!loadExternalImages);
    }

    /**
     * Opens http(s) links externally via the system chooser and blocks every
     * other scheme (javascript:, file:, intent:, tel:, ...). Always returns
     * true so the WebView itself never navigates anywhere.
     */
    private boolean handleCommentUrl(String url) {
        // Navigation callbacks can race dialog teardown; Fragment.startActivity
        // throws IllegalStateException when detached, so bail out first.
        if (!isAdded()) return true;
        if (url != null && (url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8))) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.comment_open_link)));
            } catch (ActivityNotFoundException e) {
                Log.d(TAG, "No activity found to open comment link");
            }
        }
        return true;
    }

    protected void loadComment(String comment) {
        if (mCommentView == null) return;
        mCommentView.loadData(comment, "text/html", "UTF-8");
        mComment = comment;
    }

    protected boolean isEditing() {
        return requireArguments().getBoolean(ARG_EDITING);
    }

    /**
     * Rejects bundles missing fragment-specific keys. The base implementation
     * accepts anything; subclasses resolve their id getter so a partial bundle
     * fails here instead of surfacing as a silent {@code 0} at first use.
     */
    protected void validateArguments(@NonNull Bundle args) {
    }

    /**
     * Reads a required int argument, failing fast when the key is absent.
     * {@code requireArguments()} alone only guards a missing bundle; a present
     * bundle with a missing key would silently yield {@code 0}.
     */
    protected static int requireIntArgument(@NonNull Bundle args, String key) {
        if (!args.containsKey(key)) {
            throw new IllegalStateException("Missing required argument \"" + key + "\"");
        }
        return args.getInt(key);
    }

    /**
     * Tracks an observer registered with the service so that dismissing the
     * dialog before the async reply arrives does not leak it (or the fragment
     * it captures). Unregistered in {@link #onDestroy}.
     */
    protected void trackCommentObserver(HumlaObserver observer) {
        mPendingObserver = observer;
    }

    @Override
    public void onDestroy() {
        if (mPendingObserver != null) {
            IHumlaService service = mProvider != null ? mProvider.getService() : null;
            if (service != null) {
                service.unregisterObserver(mPendingObserver);
            }
            mPendingObserver = null;
        }
        super.onDestroy();
    }

    /**
     * Requests a comment from the service. Will not be called if we already have a comment provided.
     * This method is expected to set a callback that will call {@link se.lublin.mumla.channel.comment.AbstractCommentFragment#loadComment(String comment)}.
     * @param service The bound Humla service to use for remote calls.
     */
    public abstract void requestComment(IHumlaService service);

    /**
     * Asks the service to replace the comment.
     * @param service The bound Humla service to use for remote calls.
     * @param comment The comment the user has defined.
     */
    public abstract void editComment(IHumlaService service, String comment);
}
