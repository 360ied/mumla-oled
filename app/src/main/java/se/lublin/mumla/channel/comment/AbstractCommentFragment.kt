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

package se.lublin.mumla.channel.comment

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TabHost
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * Fragment to change your comment using basic WYSIWYG tools.
 * Created by andrew on 10/08/13.
 */
abstract class AbstractCommentFragment : DialogFragment() {

    private var mTabHost: TabHost? = null
    private var mCommentView: WebView? = null
    private var mCommentEdit: EditText? = null
    private var mProvider: HumlaServiceProvider? = null
    private var mPendingObserver: HumlaObserver? = null
    private var mPendingService: IHumlaService? = null
    private var mComment: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        mComment = args.getString(ARG_COMMENT)
        validateArguments(args)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        try {
            mProvider = context as HumlaServiceProvider
        } catch (e: ClassCastException) {
            // A wrapped context (theme wrapper, test harness) is not the host activity itself.
            if (activity is HumlaServiceProvider) {
                mProvider = activity as HumlaServiceProvider
            } else {
                throw RuntimeException(context.javaClass.name + " must implement HumlaServiceProvider!", e)
            }
        }
    }

    override fun onDetach() {
        mProvider = null
        super.onDetach()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editing = isEditing()
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_comment, null, false)

        val commentView = view.findViewById(R.id.comment_view) as WebView
        mCommentView = commentView
        hardenCommentWebView(commentView)
        val commentEdit = view.findViewById(R.id.comment_edit) as EditText
        mCommentEdit = commentEdit

        val tabHost = view.findViewById(R.id.comment_tabhost) as TabHost
        mTabHost = tabHost
        tabHost.setup()

        val comment = mComment
        if (comment == null) {
            commentView.loadData("Loading...", null, null)
            val service = boundService()
            if (service != null) {
                requestComment(service)
            } else {
                Log.w(TAG, "No bound service; cannot fetch comment")
            }
        } else {
            loadComment(comment)
        }

        val viewTab = tabHost.newTabSpec(TAB_VIEW)
        viewTab.setIndicator(getString(R.string.comment_view))
        viewTab.setContent(R.id.comment_tab_view)

        val editTab = tabHost.newTabSpec(TAB_EDIT)
        editTab.setIndicator(getString(if (editing) R.string.comment_edit_source else R.string.comment_view_source))
        editTab.setContent(R.id.comment_tab_edit)

        tabHost.addTab(viewTab)
        tabHost.addTab(editTab)

        tabHost.setOnTabChangedListener { tabId ->
            // View hierarchy may be torn down; never touch nulled fields.
            val commentView = mCommentView
            val commentEdit = mCommentEdit
            if (commentView == null || commentEdit == null) return@setOnTabChangedListener
            if (TAB_VIEW == tabId) {
                // When switching back to view tab, update with user's HTML changes.
                commentView.loadData(commentEdit.text.toString(), "text/html", "UTF-8")
            } else if (TAB_EDIT == tabId && commentEdit.text.toString() == "") {
                // Load edittext content for the first time when the tab is selected, to improve performance with long messages.
                commentEdit.setText(mComment)
            }
        }

        tabHost.currentTab = if (editing) 1 else 0

        if (editing) {
            return MaterialAlertDialogBuilder(requireActivity())
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.save) { _, _ ->
                    val service = boundService()
                    // NPE-exact: the original dereferenced mCommentEdit unconditionally.
                    val edit = mCommentEdit ?: throw NullPointerException("mCommentEdit")
                    if (service != null) {
                        editComment(service, edit.text.toString())
                    } else {
                        Log.w(TAG, "No bound service; save discarded")
                    }
                }
                .create()
        } else {
            return MaterialAlertDialogBuilder(requireActivity())
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .create()
        }
    }

    override fun onDestroyView() {
        // Release the WebView's native peer; otherwise the renderer and its
        // host Activity stay reachable via mCommentView after dismissal.
        val commentView = mCommentView
        if (commentView != null) {
            val parent = commentView.parent as ViewGroup?
            if (parent != null) {
                parent.removeView(commentView)
            }
            commentView.destroy()
            mCommentView = null
        }
        mTabHost = null
        mCommentEdit = null
        super.onDestroyView()
    }

    /**
     * Locks down the comment WebView against hostile server-supplied HTML.
     * Both the received comment ({@link #loadComment}) and the edit-preview
     * path in {@link #onCreateDialog} render through the comment view,
     * so hardening here covers both. Remote images follow the existing
     * external-images setting (default off).
     */
    private fun hardenCommentWebView(commentView: WebView) {
        commentView.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleCommentUrl(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Subframe loads (e.g. <iframe>) never leave the WebView, so
                // embedded hostile HTML cannot pop an external browser.
                // Main-frame navigations open externally via handleCommentUrl.
                if (request == null || !request.isForMainFrame) return true
                return handleCommentUrl(request.url?.toString())
            }
        }
        val commentSettings = commentView.settings
        commentSettings.javaScriptEnabled = false
        commentSettings.allowFileAccess = false
        commentSettings.allowContentAccess = false
        commentSettings.allowFileAccessFromFileURLs = false
        commentSettings.allowUniversalAccessFromFileURLs = false
        val loadExternalImages = Settings.getInstance(requireContext()).shouldLoadExternalImages
        commentSettings.blockNetworkImage = !loadExternalImages
        commentSettings.blockNetworkLoads = !loadExternalImages
    }

    /**
     * Opens http(s) links externally via the system chooser and blocks every
     * other scheme (javascript:, file:, intent:, tel:, ...). Always returns
     * true so the WebView itself never navigates anywhere.
     */
    private fun handleCommentUrl(url: String?): Boolean {
        // Navigation callbacks can race dialog teardown; Fragment.startActivity
        // throws IllegalStateException when detached, so bail out first.
        if (!isAdded) return true
        if (url != null && (url.regionMatches(0, "http://", 0, 7, ignoreCase = true) ||
                url.regionMatches(0, "https://", 0, 8, ignoreCase = true))
        ) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.comment_open_link)))
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "No activity found to open comment link", e)
            }
        }
        return true
    }

    protected fun loadComment(comment: String?) {
        mComment = comment
        val commentView = mCommentView ?: return
        // NPE-exact: WebView.loadData rejects null data; the original NPE'd inside it.
        commentView.loadData(comment ?: throw NullPointerException("comment"), "text/html", "UTF-8")
    }

    protected fun isEditing(): Boolean {
        return requireArguments().getBoolean(ARG_EDITING)
    }

    /**
     * Rejects bundles missing fragment-specific keys. The base implementation
     * requires the editing flag; subclasses additionally resolve their id
     * getter so a partial bundle fails here instead of surfacing as a silent
     * {@code 0} at first use.
     */
    protected open fun validateArguments(args: Bundle) {
        if (!args.containsKey(ARG_EDITING)) {
            throw IllegalStateException("Missing required argument \"$ARG_EDITING\"")
        }
    }

    /**
     * Reads a required int argument, failing fast when the key is absent.
     * {@code requireArguments()} alone only guards a missing bundle; a present
     * bundle with a missing key would silently yield {@code 0}. A present key
     * holding a non-int value still yields the platform default; callers
     * always write these keys with the matching putter, so that path is
     * unreachable outside deliberately crafted bundles.
     */
    protected fun requireIntArgument(args: Bundle, key: String): Int {
        if (!args.containsKey(key)) {
            throw IllegalStateException("Missing required argument \"$key\"")
        }
        return args.getInt(key)
    }

    /**
     * Tracks an observer registered with the service so that dismissing the
     * dialog before the async reply arrives does not leak it (or the fragment
     * it captures). A previous pending observer is released first, so repeat
     * registrations cannot orphan one. Unregistered in {@link #onDestroy}
     * against the cached service, which stays valid for unregistration even
     * if the provider has since unbound.
     */
    protected fun trackCommentObserver(service: IHumlaService, observer: HumlaObserver) {
        releasePendingObserver()
        mPendingService = service
        mPendingObserver = observer
    }

    private fun releasePendingObserver() {
        val observer = mPendingObserver
        if (observer != null) {
            val service = mPendingService
            if (service != null) {
                service.unregisterObserver(observer)
            }
            mPendingObserver = null
            mPendingService = null
        }
    }

    /** Returns the bound service, or null when the provider is gone or unbound. */
    private fun boundService(): IHumlaService? {
        return mProvider?.getService()
    }

    override fun onDestroy() {
        releasePendingObserver()
        super.onDestroy()
    }

    /**
     * Requests a comment from the service. Will not be called if we already have a comment provided.
     * This method is expected to set a callback that will call {@link se.lublin.mumla.channel.comment.AbstractCommentFragment#loadComment(String comment)}.
     * @param service The bound Humla service to use for remote calls.
     */
    abstract fun requestComment(service: IHumlaService)

    /**
     * Asks the service to replace the comment.
     * @param service The bound Humla service to use for remote calls.
     * @param comment The comment the user has defined.
     */
    abstract fun editComment(service: IHumlaService, comment: String)

    companion object {
        private const val TAG = "AbstractCommentFragment"

        private const val TAB_VIEW = "View"
        private const val TAB_EDIT = "Edit"

        const val ARG_COMMENT = "comment"
        const val ARG_EDITING = "editing"
    }
}
